(ns bb-mcp.nrepl-spawn
  "Connection management for emacs-mcp multiplex pattern.

   bb-mcp is a lightweight babashka wrapper that attaches to the heavyweight
   emacs-mcp JVM via nREPL. This enables using emacs-mcp's tools from any
   project without running a full JVM per project.

   Uses state detection instead of time-based heuristics:
   - :ready       → port listening, connect immediately
   - :starting    → process exists but port not ready, wait
   - :not-running → no process, spawn emacs-mcp

   Architecture (SOLID + CLARITY):
   - Probe Layer: Raw I/O (socket, pgrep)
   - Detection Layer: Pure classification (probes → state)
   - Action Layer: Side effects (spawn, wait)
   - Orchestration Layer: Decision logic"
  (:require [babashka.process :as p]
            [clojure.java.io :as io]))

;;; ============================================================
;;; Configuration
;;; ============================================================

(def default-port 7910)

(def emacs-mcp-dir
  "Directory containing emacs-mcp project."
  (or (System/getenv "EMACS_MCP_DIR")
      (str (System/getenv "HOME") "/dotfiles/gitthings/emacs-mcp")))

;;; ============================================================
;;; Probe Layer - Raw I/O operations
;;; ============================================================

(def lock-file
  "Lock file created by start-mcp.sh to signal 'I'm starting'.
   Format: PID:TIMESTAMP"
  (str (System/getenv "HOME") "/.config/emacs-mcp/starting.lock"))

(defn port-listening?
  "Probe if port is accepting connections.
   Returns {:available? bool :evidence string}"
  [port]
  (try
    (with-open [_sock (java.net.Socket. "localhost" port)]
      {:available? true :evidence "socket connected"})
    (catch Exception _
      {:available? false :evidence "connection refused"})))

(defn lock-file-exists?
  "Check if the lock file exists (start-mcp.sh is running).
   Returns {:starting? bool :evidence string}"
  []
  (let [file (io/file lock-file)]
    (if (.exists file)
      (let [content (slurp file)
            [pid timestamp] (clojure.string/split content #":")
            age-seconds (when timestamp
                          (- (quot (System/currentTimeMillis) 1000)
                             (parse-long (clojure.string/trim timestamp))))]
        ;; Consider stale if older than 60 seconds
        (if (and age-seconds (< age-seconds 60))
          {:starting? true :evidence (str "lock file: pid=" pid " age=" age-seconds "s")}
          {:starting? false :evidence "lock file stale"}))
      {:starting? false :evidence "no lock file"})))

(defn process-running?
  "Probe if emacs-mcp JVM is running.
   Uses pgrep to find Java process with emacs-mcp in classpath.
   Returns {:running? bool :evidence string}"
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true}
                          "pgrep" "-f" "emacs-mcp.*clojure")]
      {:running? (zero? (:exit result))
       :evidence (if (zero? (:exit result))
                   (str "pid=" (clojure.string/trim (:out result)))
                   "no match")})
    (catch Exception e
      ;; pgrep not available - safe fallback (assume not running)
      {:running? false :evidence (str "pgrep failed: " (ex-message e))})))

;;; ============================================================
;;; Detection Layer - Pure state classification
;;; ============================================================

(defn classify-state
  "Classify connection state from probe results.
   Pure function: probe-results → ConnectionState keyword

   Truth table (priority order):
   | port-available? | lock-file? | process? | → state        |
   |-----------------|------------|----------|----------------|
   | true            | any        | any      | :ready         |
   | false           | true       | any      | :starting      |
   | false           | false      | true     | :starting      |
   | false           | false      | false    | :not-running   |"
  [{port-avail :available?} {lock-starting :starting?} {proc-running :running?}]
  (cond
    port-avail              :ready
    lock-starting           :starting  ; Lock file = start-mcp.sh is running
    proc-running            :starting  ; Process exists but port not ready
    :else                   :not-running))

(defn detect-state
  "Detect emacs-mcp connection state.
   Combines probes into classified state with evidence.
   Returns {:status keyword :port int :evidence map}"
  [port]
  (let [port-result (port-listening? port)
        lock-result (lock-file-exists?)
        proc-result (process-running?)]
    {:status   (classify-state port-result lock-result proc-result)
     :port     port
     :evidence {:port port-result :lock lock-result :process proc-result}}))

;;; ============================================================
;;; Action Layer - Side effects based on state
;;; ============================================================

(defn wait-with-backoff
  "Wait for port with exponential backoff.
   Starts at 100ms, doubles each iteration, caps at 2s.
   Returns true if available within max-seconds, false otherwise."
  [port max-seconds]
  (loop [waited 0.0
         delay-ms 100]
    (cond
      (:available? (port-listening? port))
      true

      (>= waited max-seconds)
      false

      :else
      (do
        (Thread/sleep delay-ms)
        (recur (+ waited (/ delay-ms 1000.0))
               (min 2000 (* delay-ms 2)))))))

(def config-dir
  "Configuration directory for emacs-mcp."
  (str (System/getenv "HOME") "/.config/emacs-mcp"))

(defn spawn-emacs-mcp!
  "Spawn emacs-mcp server process.
   Uses start-mcp.sh if available, falls back to clojure -X:mcp.
   Logs to ~/.config/emacs-mcp/server.log"
  []
  (let [script (str emacs-mcp-dir "/start-mcp.sh")
        log-file (io/file (str config-dir "/server.log"))]
    ;; Ensure config dir exists
    (.mkdirs (io/file config-dir))
    ;; Append startup marker to log
    (spit log-file
          (str "=== bb-mcp spawning at " (java.time.Instant/now) " ===\n")
          :append true)
    (if (.exists (io/file script))
      (p/process {:dir emacs-mcp-dir
                  :out :append :out-file log-file
                  :err :append :err-file log-file}
                 script)
      (p/process {:dir emacs-mcp-dir
                  :out :append :out-file log-file
                  :err :append :err-file log-file}
                 "clojure" "-X:mcp"))))

;;; ============================================================
;;; Orchestration Layer - Decision logic
;;; ============================================================

(defn- log-stderr [& args]
  "Log to stderr (stdout reserved for JSON-RPC)."
  (binding [*out* *err*]
    (apply println args)))

(defn ensure-nrepl!
  "Ensure emacs-mcp nREPL is available. Returns true on success.

   State-based decision logic:
   - :ready       → connect immediately (0 latency)
   - :starting    → wait with backoff (process detected starting)
   - :not-running → spawn and wait

   This is the main entry point for bb-mcp startup."
  ([] (ensure-nrepl! default-port))
  ([port]
   (let [{:keys [status evidence]} (detect-state port)]
     ;; Telemetry: log detected state
     (log-stderr "bb-mcp:" (name status) (pr-str evidence))

     (case status
       :ready
       (do
         (log-stderr "bb-mcp: connected to emacs-mcp on port" port)
         true)

       :starting
       (do
         (log-stderr "bb-mcp: emacs-mcp starting, waiting...")
         (if (wait-with-backoff port 30)
           (do (log-stderr "bb-mcp: connected") true)
           (do (log-stderr "bb-mcp: timeout waiting for startup") false)))

       :not-running
       (do
         (log-stderr "bb-mcp: spawning emacs-mcp...")
         (spawn-emacs-mcp!)
         (if (wait-with-backoff port 30)
           (do (log-stderr "bb-mcp: emacs-mcp ready on port" port) true)
           (do (log-stderr "bb-mcp: failed to start emacs-mcp") false)))))))

;; Backwards compatibility alias
(def ensure-connection! ensure-nrepl!)
