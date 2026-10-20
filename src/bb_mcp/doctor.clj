(ns bb-mcp.doctor
  "`bb doctor`: read-only diagnosis of the hive-mcp backend bb-mcp talks to.

   Three checks, each a row {:step :status :detail} in the shape bb-mcp.setup.model
   already renders: is the nREPL port reachable, is a hive-mcp JVM running, is
   the hive-mcp config file present. The check functions are pure over observed
   values; `observe` and `-main` are the only boundary and they only READ (a TCP
   connect, the process table, a file test). Nothing here starts or kills a process.
   A follow-up `bb up` starts the backend and can gate on `port-free?`."
  (:require [babashka.fs :as fs]
            [bb-mcp.setup.io :as io]
            [bb-mcp.setup.model :as m]
            [clojure.string :as str]
            [babashka.process :as p]))

(def jvm-marker
  "Substring of the command line that identifies the hive-mcp backend JVM."
  "hive-mcp.server.core")

(def config-rel
  "hive-mcp config file, relative to HOME."
  ".config/hive-mcp/config.edn")

(defn config-path
  "Absolute path of the hive-mcp config file under `home`."
  [home]
  (str home "/" config-rel))

(defn check-port
  "Row: does anything answer on the nREPL `port`? Unlike the setup row this is
   a :fail, because doctor is asked precisely whether the backend is up."
  [port answers?]
  (if answers?
    (m/step "nrepl-port" :ok (str "port " port " answers"))
    (m/step "nrepl-port" :fail
            (str "nothing answers on port " port
                 "; start the hive-mcp backend, or export "
                 m/port-var "=<port> if it listens elsewhere"))))

(defn hive-processes
  "The `procs` ({:pid :cmd}) whose command line names the hive-mcp backend."
  [procs]
  (filterv (fn [{:keys [cmd]}] (and (string? cmd) (str/includes? cmd jvm-marker)))
           procs))

(defn check-jvm
  "Row: is a hive-mcp backend JVM running? Absent while the port answers is
   only a warning: the backend may run elsewhere or under another launcher."
  [procs port-answers?]
  (let [found (hive-processes procs)]
    (cond
      (seq found)
      (m/step "jvm" :ok (str "hive-mcp JVM running (pid "
                             (str/join ", " (map :pid found)) ")"))
      port-answers?
      (m/step "jvm" :warn (str "no local process matches " jvm-marker
                               ", but the port answers: backend runs elsewhere?"))
      :else
      (m/step "jvm" :fail (str "no process matches " jvm-marker
                               "; the backend JVM is not running")))))

(defn check-config
  "Row: is the hive-mcp config file present at `path`?"
  [path present?]
  (if present?
    (m/step "config" :ok path)
    (m/step "config" :fail (str path " is missing; create it (hive-mcp reads its providers and ports from there)"))))

(def default-ws-port
  "Port of the hive-mcp WebSocket channel when HIVE_MCP_WS_CHANNEL_PORT is unset."
  9999)

(def ws-port-var
  "Env var that moves the hive-mcp WebSocket channel off `default-ws-port`."
  "HIVE_MCP_WS_CHANNEL_PORT")

(def env-groups
  "Env vars the vector stores and the embedding provider read, as
   [step-id mode [var ...]]. :all means every var matters (host and auth);
   :any means one of them is enough (either embedding provider will do).
   Only the NAMES live here; values are never read into a report."
  [["milvus-env" :all ["MILVUS_HOST" "MILVUS_TOKEN"]]
   ["qdrant-env" :all ["QDRANT_HOST" "QDRANT_API_KEY"]]
   ["embedding-env" :any ["OLLAMA_HOST" "OPENROUTER_API_KEY"]]])

(defn check-ws
  "Row: does the MCP WebSocket channel answer on `port`? Only a warning: the
   channel starts lazily and a client may not need it."
  [port answers?]
  (if answers?
    (m/step "ws-channel" :ok (str "port " port " answers"))
    (m/step "ws-channel" :warn
            (str "nothing answers on port " port
                 "; the MCP WebSocket channel is down, or export "
                 ws-port-var "=<port> if it listens elsewhere"))))

(defn check-env-group
  "Row for one `env-groups` entry given `present`, the set of var NAMES that are
   set. A missing var is a :warn naming it exactly (hive-mcp's config.edn can
   supply the value instead), never a :fail, and never prints a value."
  [[id mode vars] present]
  (let [missing (vec (remove present vars))
        fix (fn [vs] (str/join ", " (map #(str "export " % "=<value>") vs)))]
    (case mode
      :all (if (empty? missing)
             (m/step id :ok (str (str/join ", " vars) " set"))
             (m/step id :warn (str "unset " (str/join ", " missing)
                                   "; " (fix missing)
                                   " (or set it in the hive-mcp config.edn)")))
      :any (if (< (count missing) (count vars))
             (m/step id :ok (str (str/join ", " (filter present vars)) " set"))
             (m/step id :warn (str "none of " (str/join ", " vars)
                                   " is set; " (fix [(first vars)])
                                   " (or set it in the hive-mcp config.edn)"))))))

(defn port-free?
  "True when the port check found nothing listening: the guard `bb up` uses so
   it can never start a second server on a port already served."
  [rows]
  (boolean (some (fn [{:keys [step status]}] (and (= "nrepl-port" step) (= :fail status)))
                 rows)))

(defn run-checks
  "Observation -> rows. `obs` is {:port :answers? :procs :config-path :config?}.
   The extra rows run only for what the observation carries: :ws-port and
   :ws-answers? add the WebSocket channel row, :env-present (the set of var
   names that are set) adds one row per `env-groups` entry."
  [{:keys [port answers? procs config-path config? ws-port ws-answers? env-present]}]
  (cond-> [(check-port port answers?)
           (check-jvm procs answers?)
           (check-config config-path config?)]
    ws-port (conj (check-ws ws-port ws-answers?))
    env-present (into (map #(check-env-group % env-present)) env-groups)))

(defn report
  "Rows -> {:rows :exit :out}: the table, and exit 1 when any row failed."
  [rows]
  {:rows rows
   :exit (m/exit-code rows)
   :out  (str "bb doctor\n" (m/format-table rows))})

(defn doctor
  "Observation -> report. The whole pure pipeline."
  [obs]
  (report (run-checks obs)))

;; ---------------------------------------------------------------------------
;; bb up: start the backend only when the doctor proves the port is free

(def up-usage
  "Usage: bb up [--dir <hive-mcp checkout>] [--dry-run]\n  --dir defaults to $HIVE_MCP_DIR")

(def up-argv
  "The command that serves the backend: one JVM that binds the nREPL port and
   the MCP WebSocket channel (hive-mcp's `bb serve` task runs the same alias)."
  ["clojure" "-M:nrepl"])

(def nrepl-port-env
  "Env var the backend reads to choose its nREPL port."
  "HIVE_MCP_NREPL_PORT")

(defn parse-up-args
  "argv -> {:opts {:dir :dry-run?}} or {:error msg} or {:help usage}."
  [argv]
  (loop [opts {} [a v :as args] (seq argv)]
    (cond
      (empty? args) {:opts opts}
      (#{"-h" "--help"} a) {:help up-usage}
      (= "--dry-run" a) (recur (assoc opts :dry-run? true) (next args))
      (= "--dir" a) (if (and v (not (str/blank? v)))
                      (recur (assoc opts :dir v) (nnext args))
                      {:error (str "--dir needs a path\n" up-usage)})
      :else {:error (str "unknown argument " (pr-str a) "\n" up-usage)})))

(defn- row-status
  "Status of the row for `step-id` in `rows`, else nil."
  [rows step-id]
  (some (fn [{:keys [step status]}] (when (= step-id step) status)) rows))

(defn up-plan
  "Doctor `rows` + `opts` ({:dir}) + `obs` ({:port :home}) -> the decision:
     {:action :noop   :reason s}  the backend already answers; nothing to do
     {:action :refuse :reason s}  starting would be wrong or impossible
     {:action :start  :argv v :dir d :env m :log path :port n}
   A start is planned only when the nrepl-port row is a :fail (`port-free?`)
   and no hive-mcp JVM is already running, so `up` can never start a second
   server on a port that is served or about to be served."
  [rows {:keys [dir]} {:keys [port home]}]
  (cond
    (not (port-free? rows))
    {:action :noop :reason (str "port " port " already answers; the backend is up")}

    (= :ok (row-status rows "jvm"))
    {:action :refuse
     :reason (str "a hive-mcp JVM is running but port " port " does not answer yet; "
                  "it may still be booting. Wait and run bb doctor, or stop it first")}

    (str/blank? dir)
    {:action :refuse
     :reason (str "no hive-mcp checkout: pass --dir <path> or export HIVE_MCP_DIR=<path>")}

    :else
    {:action :start
     :argv up-argv
     :dir dir
     :env {nrepl-port-env (str port)}
     :log (str home "/.local/state/hive-mcp/up.log")
     :port port}))

(defn await-port
  "True once `(probe port)` is truthy within `attempts` tries, calling
   `(sleep! ms)` between tries. Both are injected so the loop is testable."
  [probe sleep! port attempts interval-ms]
  (loop [n attempts]
    (cond
      (probe port) true
      (<= n 1) false
      :else (do (sleep! interval-ms) (recur (dec n))))))

(defn up-report
  "A plan and what happened -> {:exit :out}. `started` is nil for a plan that
   was not started, else {:up? bool}; `dry-run?` reports without starting."
  [plan started dry-run?]
  (case (:action plan)
    :noop   {:exit 0 :out (str "bb up: " (:reason plan))}
    :refuse {:exit 1 :out (str "bb up: refused: " (:reason plan))}
    :start  (let [cmd (str/join " " (:argv plan))
                  where (str "in " (:dir plan) " with " nrepl-port-env "=" (:port plan)
                             ", log " (:log plan))]
              (cond
                dry-run?        {:exit 0 :out (str "bb up: would start `" cmd "` " where)}
                (:up? started)  {:exit 0 :out (str "bb up: started `" cmd "` " where
                                                   "; port " (:port plan) " answers")}
                :else           {:exit 1 :out (str "bb up: started `" cmd "` " where
                                                   ", but port " (:port plan)
                                                   " did not answer in time; see the log")}))))

(defn up
  "The whole bb up pipeline over injected effects. `sys` is
   {:observe :start! :probe :sleep!}; `start!` takes a :start plan and launches
   the process (it is never called for :noop, :refuse or a dry run).
   -> {:exit :out :plan}."
  [{:keys [observe start! probe sleep!]} {:keys [dry-run?] :as opts}]
  (let [obs (observe)
        plan (up-plan (run-checks obs) opts obs)
        started (when (and (= :start (:action plan)) (not dry-run?))
                  (start! plan)
                  {:up? (await-port probe sleep! (:port plan) 120 1000)})]
    (assoc (up-report plan started dry-run?) :plan plan)))

;; ---------------------------------------------------------------------------
;; Boundary: read-only observation

(defn- full-cmdline
  "The untruncated command line of `pid` from /proc (Linux), else nil. The JDK
   cuts ProcessHandle command lines at 4096 characters, which loses the main
   class of a JVM with a long classpath."
  [pid]
  (let [f (java.io.File. (str "/proc/" pid "/cmdline"))]
    (when (.isFile f)
      (try (str/replace (slurp f) \u0000 \space)
           ;; unreadable or vanished between listing and reading: fall back
           (catch Exception _ nil)))))

(defn processes
  "The process table as [{:pid :cmd}]; only command lines are read."
  []
  (into []
        (keep (fn [^java.lang.ProcessHandle h]
                (when-let [cmd (.orElse (.commandLine (.info h)) nil)]
                  {:pid (.pid h)
                   :cmd (if (str/includes? cmd "java")
                          (or (full-cmdline (.pid h)) cmd)
                          cmd)})))
        (iterator-seq (.iterator (java.lang.ProcessHandle/allProcesses)))))

(defn env-present
  "The set of `env-groups` var NAMES that `getenv` reports set and non-blank.
   Names only: a value is tested for presence and dropped, never kept."
  [getenv]
  (into #{}
        (comp (mapcat (fn [[_ _ vars]] vars))
              (filter #(not (str/blank? (getenv %)))))
        env-groups))

(defn observe
  "Read the world through the injectable `sys`
   ({:getenv :home :cwd :probe :procs :config?}); defaults are the real ones.
   Besides the nREPL facts it carries the WebSocket channel port and whether it
   answers, and the set of provider env var names that are set."
  [{:keys [getenv home cwd probe procs config?]
    :or {getenv #(System/getenv %)
         home (or (System/getenv "HOME") (System/getProperty "user.home"))
         cwd (str (fs/cwd))
         probe io/port-answers?
         procs processes
         config? fs/regular-file?}}]
  (let [port (m/resolve-port {:env-port (getenv m/port-var)
                              :port-file (io/read-file (str cwd "/.nrepl-port"))})
        ws-port (or (some-> (getenv ws-port-var) str/trim not-empty parse-long)
                    default-ws-port)
        path (config-path home)]
    {:port port
     :answers? (boolean (probe port))
     :procs (procs)
     :config-path path
     :config? (boolean (config? path))
     :ws-port ws-port
     :ws-answers? (boolean (probe ws-port))
     :env-present (env-present getenv)}))

(defn -main [& _args]
  (let [{:keys [out exit]} (doctor (observe {}))]
    (println out)
    (System/exit exit)))

;; ---------------------------------------------------------------------------
;; Boundary: bb up. The only place that starts a process, and only for a
;; :start plan that `up-plan` derived from a doctor report.

(defn start!
  "Launch the backend described by a :start `plan` detached from this process:
   stdin from /dev/null, stdout and stderr appended to the plan's log. Returns
   the process; the caller waits on the port, not on the process."
  [{:keys [argv dir env log]}]
  (fs/create-dirs (fs/parent log))
  (p/process argv {:dir dir
                   :extra-env env
                   :in (java.io.File. "/dev/null")
                   :out :append :out-file (java.io.File. ^String log)
                   :err :append :err-file (java.io.File. ^String log)}))

(defn up-main
  "`bb up`: run the doctor, and start the backend only when its nREPL port is
   free. --dir (or $HIVE_MCP_DIR) names the hive-mcp checkout; --dry-run reports
   the plan without starting anything."
  [& args]
  (let [{:keys [opts error help]} (parse-up-args args)]
    (cond
      help  (println help)
      error (do (println error) (System/exit 2))
      :else
      (let [opts (cond-> opts
                   (not (:dir opts)) (assoc :dir (System/getenv "HIVE_MCP_DIR")))
            {:keys [exit out]} (up {:observe #(observe {})
                                    :start! start!
                                    :probe io/port-answers?
                                    :sleep! #(Thread/sleep (long %))}
                                   opts)]
        (println out)
        (System/exit exit)))))
