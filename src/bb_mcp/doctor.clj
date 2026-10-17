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
            [clojure.string :as str]))

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

(defn port-free?
  "True when the port check found nothing listening: the guard `bb up` uses so
   it can never start a second server on a port already served."
  [rows]
  (boolean (some (fn [{:keys [step status]}] (and (= "nrepl-port" step) (= :fail status)))
                 rows)))

(defn run-checks
  "Observation -> rows. `obs` is {:port :answers? :procs :config-path :config?}."
  [{:keys [port answers? procs config-path config?]}]
  [(check-port port answers?)
   (check-jvm procs answers?)
   (check-config config-path config?)])

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

(defn observe
  "Read the world through the injectable `sys`
   ({:getenv :home :cwd :probe :procs :config?}); defaults are the real ones."
  [{:keys [getenv home cwd probe procs config?]
    :or {getenv #(System/getenv %)
         home (or (System/getenv "HOME") (System/getProperty "user.home"))
         cwd (str (fs/cwd))
         probe io/port-answers?
         procs processes
         config? fs/regular-file?}}]
  (let [port (m/resolve-port {:env-port (getenv m/port-var)
                              :port-file (io/read-file (str cwd "/.nrepl-port"))})
        path (config-path home)]
    {:port port
     :answers? (boolean (probe port))
     :procs (procs)
     :config-path path
     :config? (boolean (config? path))}))

(defn -main [& _args]
  (let [{:keys [out exit]} (doctor (observe {}))]
    (println out)
    (System/exit exit)))
