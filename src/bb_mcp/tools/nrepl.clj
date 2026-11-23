(ns bb-mcp.tools.nrepl
  "nREPL client for delegating Clojure eval to the shared hive-mcp JVM.

   Wire format lives in bb-mcp.wire.bencode (pure); bytes move through
   bb-mcp.host.port/IByteChannel. A different runtime swaps only the adapter
   passed as :open-fn."
  (:require [clojure.string :as str]
            [bb-mcp.wire.bencode :as bencode]
            [bb-mcp.host.port :as hp]))

;; ── nREPL message vocabulary (pure) ──────────────────────────────────────────

(defn- has-status?
  "True when nREPL response `msg` carries status `s`."
  [msg s]
  (some #(= % s) (get msg :status [])))

(defn- reduce-messages
  "Fold nREPL response messages into {:value :out :err :ex}. Pure."
  [messages]
  (reduce (fn [acc msg]
            (cond-> acc
              (:value msg) (update :value str (:value msg))
              (:out msg)   (update :out str (:out msg))
              (:err msg)   (update :err str (:err msg))
              (:ex msg)    (assoc :ex (:ex msg))
              (has-status? msg "eval-error") (update :ex #(or % "eval-error"))))
          {:value "" :out "" :err "" :ex nil}
          messages))

(defn- non-empty?
  "True when string `s` has content.

   Never `(seq s)`: on a string that is a fold over its characters, and cljw
   builds that fold in O(n^2) — 90 seconds for the 147 KB tool-list response,
   spent entirely on deciding whether it was empty."
  [s]
  (pos? (count s)))

(defn- messages->result
  "Promote nREPL response messages into {:result str :error? bool}. Pure.
   A stream that closed before a done message is reported as an error."
  [messages]
  (if-not (some #(has-status? % "done") messages)
    {:result "No response from nREPL" :error? true}
    (let [{:keys [value out err ex]} (reduce-messages messages)]
      (if ex
        {:result (str "Error: " ex
                      (when (non-empty? err) (str "\n" err))
                      (when (non-empty? value) (str "\n" value)))
         :error? true}
        {:result (if (non-empty? value) value (or out "nil"))
         :error? false}))))

;; ── framing (pure byte bookkeeping) ──────────────────────────────────────────

(defn- ensure-room
  "A buffer holding the first `used` bytes of `buf` with room for `extra` more.
   Returns `buf` itself when it already fits.

   Capacity doubles, so filling a response costs O(size) copying in total. The
   previous shape — a fresh exact-size array per read — was O(size^2 / chunk),
   which is invisible at 4 KB and is the whole cost at a megabyte."
  ^bytes [^bytes buf used extra]
  (let [cap (alength buf)
        need (+ used extra)]
    (if (<= need cap)
      buf
      (let [cap' (loop [c (max cap 65536)] (if (>= c need) c (recur (* 2 c))))
            out (byte-array cap')]
        (System/arraycopy buf 0 out 0 used)
        out))))

;; ── channel boundary (effectful) ─────────────────────────────────────────────

(defn- read-messages!
  "Read bencode messages from `ch` until one carries a done status, or the
   channel ends. Boundary I/O.

   One buffer grows across the whole response and `scanned` remembers where
   the last decode pass stopped, so neither the consumed head nor the
   undecoded tail is ever copied again."
  [ch]
  (let [chunk (byte-array 65536)]
    (loop [acc [] buf (byte-array 65536) used 0 scanned 0]
      (let [[msgs off] (bencode/decode-all buf scanned used)
            acc' (into acc msgs)]
        (if (some #(has-status? % "done") msgs)
          acc'
          (let [n (hp/read-bytes! ch chunk)]
            (if (neg? n)
              acc'
              (let [buf' (ensure-room buf used n)]
                (System/arraycopy chunk 0 buf' used n)
                (recur acc' buf' (+ used n) off)))))))))

;; ── Client ───────────────────────────────────────────────────────────────────

(defprotocol NReplClient
  "Evaluate Clojure code on a remote nREPL endpoint."
  (eval-code* [client code opts]
    "Eval `code` on `client`; returns {:result str :error? bool}."))

(defn- open-channel
  "Open the channel for one eval: the Unix socket at `socket` through
   `open-unix-fn` when one is given and answers, else TCP through `open-fn`
   exactly as without a socket. A socket that refuses, or is gone, costs one
   failed connect and never fails the call."
  [{:keys [host port socket open-fn open-unix-fn timeout-ms]}]
  (or (when socket
        (try (open-unix-fn {:path socket :timeout-ms timeout-ms})
             (catch Exception _ nil)))
      (open-fn {:host host :port port :timeout-ms timeout-ms})))

(defrecord BencodeNReplClient [host port open-fn socket open-unix-fn]
  NReplClient
  (eval-code* [_ code {:keys [timeout-ms]}]
    (let [opened (try {:ch (open-channel {:host (or host "localhost")
                                          :port port
                                          :socket socket
                                          :open-fn open-fn
                                          :open-unix-fn open-unix-fn
                                          :timeout-ms (or timeout-ms 600000)})}
                      (catch Exception e {:failure e}))]
      (if-let [e (:failure opened)]
        {:result (str "nREPL connection failed: " (ex-message e))
         :error? true
         :connect-failed? true}
        (let [ch (:ch opened)]
          (try
            (hp/write-bytes! ch (bencode/encode {:op "eval" :code code}))
            (messages->result (read-messages! ch))
            (catch Exception e
              {:result (str "nREPL connection failed: " (ex-message e))
               :error? true})
            (finally
              (try (hp/close! ch) (catch Exception _ nil)))))))))

(declare hive-port resolve-hive-socket)

(defn- default-socket
  "hive-mcp's Unix socket path when `port` is the hive-mcp host's port on
   localhost, else nil: the socket stands for that one nREPL and no other."
  [host port]
  (when (and (contains? #{nil "localhost" "127.0.0.1"} host)
             (= port (hive-port)))
    (:path (resolve-hive-socket))))

(defn eval-code
  "Evaluate Clojure code on a remote nREPL server.

   Transport: hive-mcp's nREPL Unix socket first, then TCP `:port` as
   before. `:socket` names the socket path (nil: TCP only); omitted, it is
   `resolve-hive-socket`'s path when `:port` is `hive-port` on localhost.
   `:open-fn` injects the TCP IByteChannel adapter and `:open-unix-fn` the
   Unix one (defaults: the host seam's). A caller that injects only
   `:open-fn` gets TCP only, so a stub is never bypassed by a live socket."
  [{:keys [host port code open-fn open-unix-fn] :as opts}]
  (let [socket (cond
                 (contains? opts :socket) (:socket opts)
                 (and open-fn (not open-unix-fn)) nil
                 :else (try (default-socket host port) (catch Exception _ nil)))]
    (eval-code* (->BencodeNReplClient (or host "localhost") port
                                      (or open-fn hp/open)
                                      socket
                                      (or open-unix-fn hp/open-unix))
                code opts)))

(def port-steps
  "How clojure_eval picks its nREPL, in order. Stated in the tool description
   and in every connection failure."
  ["1. the port parameter"
   "2. the BB_MCP_NREPL_PORT env var of this bb-mcp server"
   "3. <BB_MCP_PROJECT_DIR>/.nrepl-port (the server's project dir, NOT your working directory)"
   "4. 7910, hive-mcp's own live JVM (over its nREPL Unix socket when one answers)"])

(def port-order
  "`port-steps` on one line."
  (str/join "; " port-steps))

(def tool-spec
  {:name "clojure_eval"
   :description (str "Evaluate Clojure code on a shared nREPL server.

Port resolution order, per call:
" (str/join "\n" port-steps) "

Steps 2-4 belong to the bb-mcp server, not to your worktree: to evaluate in a
worktree's own nREPL, pass the port from that worktree's .nrepl-port. Step 4
is the running coordinator; never run tests or :reload there.

Examples:
- clojure_eval(code: \"(+ 1 2)\", port: 7946)
- clojure_eval(code: \"(require '[my.ns])\", port: 7946)")
   :schema {:type "object"
            :properties {:code {:type "string"
                                :description "Clojure code to evaluate"}
                         :port {:type "integer"
                                :description "nREPL port for this call. Omitted: BB_MCP_NREPL_PORT, then <BB_MCP_PROJECT_DIR>/.nrepl-port, then 7910 (hive-mcp)."}
                         :timeout_ms {:type "integer"
                                      :description "Timeout in ms (default: 600000 / 10 min). Long-running tools like wave dispatch may take several minutes."}}
            :required ["code"]}})

(defn find-nrepl-port
  "Find nREPL port from .nrepl-port file in given directory."
  [dir]
  (let [port-file (str dir "/.nrepl-port")]
    (when (.exists (java.io.File. port-file))
      (parse-long (str/trim (slurp port-file))))))

(defn get-project-dir
  "Get the target project directory from env or default to cwd."
  []
  (or (System/getenv "BB_MCP_PROJECT_DIR") "."))

(def default-nrepl-port
  "hive-mcp's own nREPL port."
  7910)

(def nrepl-port-env "BB_MCP_NREPL_PORT")

(defn- slurp-port-file
  "The port in the .nrepl-port file at `path`, or nil when there is none."
  [path]
  (let [f (java.io.File. ^String path)]
    (when (.exists f)
      (parse-long (str/trim (slurp f))))))

(defn resolve-nrepl-port
  "The nREPL port and where it came from: {:port n :source s ...}.

   Order and :source:
     explicit port param            :explicit-param
     BB_MCP_NREPL_PORT env var      :env             (+ :env-var)
     <project-dir>/.nrepl-port      :nrepl-port-file (+ :path)
     hive-mcp's port 7910           :default

   The 0-arity reads the real env and filesystem. The 2-arity takes them as
   collaborators: :getenv (name -> string), :project-dir, :read-port-file
   (path -> long or nil)."
  ([] (resolve-nrepl-port nil {}))
  ([explicit {:keys [getenv project-dir read-port-file]
              :or {getenv #(System/getenv %)
                   read-port-file slurp-port-file}}]
   (let [dir (or project-dir (get-project-dir))
         path (str dir "/.nrepl-port")]
     (or (when explicit {:port explicit :source :explicit-param})
         (when-let [p (some-> (getenv nrepl-port-env) str/trim parse-long)]
           {:port p :source :env :env-var nrepl-port-env})
         (when-let [p (read-port-file path)]
           {:port p :source :nrepl-port-file :path path})
         {:port default-nrepl-port :source :default}))))

(defn describe-port-source
  "Pure. Where a resolved port came from, as a phrase: \"/x/.nrepl-port\",
   \"the BB_MCP_NREPL_PORT env var\", ... nil when the source is unknown."
  [{:keys [source env-var path]}]
  (case source
    :explicit-param "the explicit port parameter"
    :env (str "the " env-var " env var")
    :nrepl-port-file path
    :default "the built-in default (hive-mcp's nREPL)"
    nil))

(defn get-nrepl-port
  "Get nREPL port from env, .nrepl-port file, or default, as a bare int.
   Default is 7910 for hive-mcp nREPL. `resolve-nrepl-port` also says where
   the port came from."
  []
  (:port (resolve-nrepl-port)))

(def hive-port-var
  "Env var naming the hive-mcp host's own nREPL port: the one name."
  "HIVE_MCP_NREPL_PORT")

(def deprecated-hive-port-vars
  "Older names of `hive-port-var`, read after it, in order. Each still works
   and costs one stderr warning per process."
  ["BB_MCP_HIVE_NREPL_PORT"])

(defonce ^:private warned-vars (atom #{}))

(defn- warn-deprecated!
  "Print one stderr line for deprecated env var `v`, once per process."
  [v]
  (when-not (contains? @warned-vars v)
    (swap! warned-vars conj v)
    (binding [*out* *err*]
      (println (str "bb-mcp: warning: env var " v " is deprecated; set "
                    hive-port-var " instead.")))))

(defn- env-port
  "The port env var `v` holds, trimmed, or nil when it holds no number."
  [getenv v]
  (some-> (getenv v) str/trim not-empty parse-long))

(defn resolve-hive-port
  "The nREPL port of the hive-mcp host and where it came from:
   {:port n :source :env :env-var v} for the first of `hive-port-var` and
   `deprecated-hive-port-vars` that holds a number (a deprecated one adds
   :deprecated? true and warns once on stderr), else
   {:port 7910 :source :default}. Never reads a project's .nrepl-port nor
   BB_MCP_NREPL_PORT. `getenv` (name -> string) is injectable; the 0-arity
   reads the real env."
  ([] (resolve-hive-port #(System/getenv %)))
  ([getenv]
   (or (when-let [p (env-port getenv hive-port-var)]
         {:port p :source :env :env-var hive-port-var})
       (some (fn [v]
               (when-let [p (env-port getenv v)]
                 (warn-deprecated! v)
                 {:port p :source :env :env-var v :deprecated? true}))
             deprecated-hive-port-vars)
       {:port default-nrepl-port :source :default})))

(defn hive-port
  "The nREPL port of the hive-mcp host, as a bare int: the :port of
   `resolve-hive-port`. Every call that must land in the hive host
   (forwarded tools, tool loading, resources, bash) dials this one port,
   over hive-mcp's Unix socket when `resolve-hive-socket` finds one."
  ([] (hive-port #(System/getenv %)))
  ([getenv] (:port (resolve-hive-port getenv))))

;; ── hive-mcp's nREPL Unix socket (STD-04 T1/T2) ────────────────────────

(def hive-socket-var
  "Env var naming hive-mcp's nREPL Unix socket path."
  "HIVE_MCP_NREPL_SOCKET")

(def hive-socket-service
  "The T2 service name of hive-mcp's nREPL socket."
  "mcp-nrepl")

(defn socket-path
  "Pure. The STD-04 T2 path of `service`'s socket:
   $XDG_RUNTIME_DIR/hive/<service>.sock, else
   <tmpdir>/hive-<uid>/<service>.sock. nil when neither base is known
   (no XDG_RUNTIME_DIR and no uid). `xdg` is the XDG_RUNTIME_DIR value."
  [xdg tmpdir uid service]
  (cond
    (some-> xdg str/trim not-empty)
    (str (str/replace (str/trim xdg) #"/+$" "") "/hive/" service ".sock")

    (and (some? uid) (some-> tmpdir not-empty))
    (str (str/replace tmpdir #"/+$" "") "/hive-" uid "/" service ".sock")))

(defn- process-uid
  "This process's real uid from /proc/self/status, or nil where there is no
   /proc. Read as text so every runtime (bb, cljw) answers the same way."
  []
  (try
    (some->> (slurp "/proc/self/status")
             (re-find #"(?m)^Uid:\s+(\d+)")
             second
             parse-long)
    (catch Exception _ nil)))

(defn- file-exists?
  [path]
  (.exists (java.io.File. ^String path)))

(defn- slurp-socket-file
  "The socket path written in the .nrepl-socket file at `path`, or nil."
  [path]
  (when (file-exists? path)
    (some-> (slurp path) str/trim not-empty)))

(defn resolve-hive-socket
  "hive-mcp's nREPL Unix socket and where it came from, or nil:
     HIVE_MCP_NREPL_SOCKET env var          :env
     <HIVE_MCP_DIR>/.nrepl-socket           :nrepl-socket-file (+ :file)
     the T2 path for mcp-nrepl, if present  :standard-path
   A caller dials it first and falls back to TCP when it does not answer.
   The 1-arity takes collaborators: :getenv, :tmpdir, :uid, :exists?
   (path -> bool), :read-socket-file (path -> string or nil)."
  ([] (resolve-hive-socket {}))
  ([{:keys [getenv tmpdir uid exists? read-socket-file]
     :or {getenv #(System/getenv %)
          exists? file-exists?
          read-socket-file slurp-socket-file}
     :as opts}]
   (or (when-let [p (some-> (getenv hive-socket-var) str/trim not-empty)]
         {:path p :source :env :env-var hive-socket-var})
       (when-let [dir (some-> (getenv "HIVE_MCP_DIR") str/trim not-empty)]
         (let [f (str dir "/.nrepl-socket")]
           (when-let [p (read-socket-file f)]
             {:path p :source :nrepl-socket-file :file f})))
       (let [uid (if (contains? opts :uid) uid (process-uid))
             tmp (if (contains? opts :tmpdir) tmpdir (System/getProperty "java.io.tmpdir"))]
         (when-let [p (socket-path (getenv "XDG_RUNTIME_DIR") tmp uid hive-socket-service)]
           (when (exists? p)
             {:path p :source :standard-path}))))))

(defn execute
  "Execute Clojure code via nREPL. A connection failure names the port, where
   it came from, and `port-order`. `opts` injects :resolve-port (explicit ->
   the `resolve-nrepl-port` map) and :open-fn for tests."
  ([params] (execute params {}))
  ([{:keys [code port timeout_ms]} {:keys [resolve-port open-fn]
                                    :or {resolve-port #(resolve-nrepl-port % {})}}]
   (let [resolved (resolve-port port)
         r (eval-code {:port (:port resolved)
                       :code code
                       :open-fn open-fn
                       :timeout-ms (or timeout_ms 600000)})]
     (if (:connect-failed? r)
       (update r :result str " (port " (:port resolved) ", from "
               (describe-port-source resolved) "). Resolution order: " port-order)
       r))))
