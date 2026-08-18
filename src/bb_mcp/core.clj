(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.hive :as hive]
            [clojure.string :as str]
            [bb-mcp.tool :as tool]
            [bb-mcp.host.port :as hp]))

;; Tool call logging — tail -f /tmp/bb-mcp.log to see MCP traffic
(def ^:private log-file (str "/tmp/bb-mcp-" (System/getProperty "user.name") ".log"))

(defn- log-tool-call
  "Append tool call request + response to log file for debugging."
  [tool-name args response elapsed-ms]
  (try
    (let [response-text (get-in response [:result :content 0 :text])
          is-error? (get-in response [:result :isError])
          truncated (when response-text
                      (if (> (count response-text) 2000)
                        (str (subs response-text 0 2000) "\n... [truncated, " (count response-text) " chars total]")
                        response-text))
          entry (str "─── " (java.time.LocalDateTime/now) " ───\n"
                     "TOOL: " tool-name
                     (when is-error? " [ERROR]")
                     " (" elapsed-ms "ms)\n"
                     "ARGS: " (hp/json-encode args) "\n"
                     "RESP: " (or truncated "<nil>") "\n\n")]
      (spit log-file entry :append true))
    (catch Exception _ nil)))

;; Agent context injection - auto-add agent_id from env var for attribution

(def ^:private instance-id
  "Stable ID for this bb-mcp session, from the host seam.

   Why it must be stable: each Claude Code window spawns bb-mcp as a child
   process. When bb-mcp restarts (e.g. tool refresh) the id must not change,
   or cursor positions are lost and every dynamic tool re-reads from
   timestamp 0."
  (hp/session-id))

(defn- get-agent-id
  "Get agent ID from CLAUDE_SWARM_SLAVE_ID env var, or nil if not set."
  []
  (System/getenv "CLAUDE_SWARM_SLAVE_ID"))

(def ^:private caller-cwd
  "Working directory of the Claude Code session (the project being worked on).
   Prefers BB_MCP_CALLER_CWD (the invocation pwd captured by start-bb-mcp.sh
   before any cd — the user's actual session cwd) over BB_MCP_PROJECT_DIR
   (which a registration arg may pin to a fixed path, e.g. hive-mcp) over
   user.dir (always bb-mcp's own script directory after 'cd $SCRIPT_DIR')."
  (or (System/getenv "BB_MCP_CALLER_CWD")
      (System/getenv "BB_MCP_PROJECT_DIR")
      (System/getProperty "user.dir")))

(defn- inject-agent-context
  "Inject agent context from CLAUDE_SWARM_SLAVE_ID env var.

   Injects THREE fields:
   - _caller_id: ALWAYS injected — identifies the MCP session/caller.
     Uses instance-id (PPID-based) for per-session cursor isolation.
     Never conflicts with user-specified agent_id (dispatch target).
   - _caller_cwd: ALWAYS injected — bb-mcp's working directory.
     Ensures hive-mcp resolves project-id from the caller's cwd,
     not from the JVM's user.dir (which differs in multiplexer setup).
   - agent_id: only injected when args lack it (backward compat).
     For dispatch-type tools, user sets agent_id to the target,
     so bb-mcp must NOT overwrite it."
  [args]
  (let [agent-id (get-agent-id)
        caller-id (str (or agent-id "coordinator") ":" instance-id)]
    (cond-> (assoc args :_caller_id caller-id)
      ;; Inject cwd when args don't already have a directory
      (not (:directory args))
      (assoc :_caller_cwd caller-cwd)
      ;; Inject agent_id from env var when not already set
      (and agent-id (not (:agent_id args)))
      (assoc :agent_id agent-id))))

;; Native bb-mcp tools (bootstrapping essentials only)
;; File tools (read_file, file_write, glob_files, grep) are now loaded
;; dynamically from basic-tools-mcp IAddon via hive-mcp.
(defn- bash-tool
  "The bash tool, or nil on a runtime that cannot spawn a subprocess.

   Resolved rather than required: `bb-mcp.tools.bash` names babashka.process
   at load time, so a hard require would take the whole server down on a
   runtime that has no process surface at all."
  []
  (let [rslv (fn [nm] (try (requiring-resolve (symbol "bb-mcp.tools.bash" nm))
                           (catch Exception _ nil)))]
    (when-let [execute (rslv "execute")]
      (let [spec @(rslv "tool-spec")
            fmt (rslv "format-result")]
        (tool/native-tool spec (fn [args] (fmt (execute args))))))))

(def ^:private native-tools
  (into [] (remove nil?)
        [(bash-tool)
         (tool/native-tool nrepl/tool-spec nrepl/execute)]))

(def ^:private tool-sources
  "Ordered tool providers; each a zero-arg fn returning a seq of Tool."
  [(constantly native-tools) hive/get-tools])

(defn get-tools
  "All registered tools from every source, in order."
  ([] (get-tools tool-sources))
  ([sources] (mapcat #(%) sources)))

(defn find-tool [name]
  (first (filter #(= name (tool/tool-name %)) (get-tools))))

(def ^:dynamic *tool-ceiling-ms*
  "Hard ceiling on a single tool call. `BB_MCP_TOOL_TIMEOUT_MS` overrides it."
  (or (some-> (System/getenv "BB_MCP_TOOL_TIMEOUT_MS") parse-long) 900000))

(defn- invoke-safely
  "Enrich `arguments`, invoke tool `t`, folding any thrown exception into
  {:result :error?}.

  A call that outlives `*tool-ceiling-ms*` is ABANDONED rather than waited on:
  the run-server loop is single-threaded, so one tool that never returns would
  otherwise take every later request with it. The abandoned work carries on in
  its own thread; only this server's attention is reclaimed."
  [t arguments]
  (let [work (future
               (try
                 (tool/invoke t (inject-agent-context arguments))
                 (catch Exception e {:result (str "Error: " (ex-message e)) :error? true})))
        outcome (deref work *tool-ceiling-ms* ::ceiling)]
    (if (= ::ceiling outcome)
      {:result (str "Error: the tool did not return within " *tool-ceiling-ms*
                    "ms and was abandoned so the server stays responsive. "
                    "It may still be running; check for side effects before retrying.")
       :error? true}
      outcome)))

(defn- call-tool
  "Resolve, invoke, log, and build the tools/call response for `name`."
  [id name arguments]
  (let [t0 (System/currentTimeMillis)]
    (if-let [t (find-tool name)]
      (let [{:keys [result error?]} (invoke-safely t arguments)
            response (proto/tool-call-response id result error?)]
        (log-tool-call name arguments response (- (System/currentTimeMillis) t0))
        response)
      (proto/json-rpc-error id -32601 (str "Unknown tool: " name)))))

;; Message handlers
(defmulti handle-method :method)

(defmethod handle-method "initialize" [{:keys [id]}]
  (proto/initialize-response id))

(defmethod handle-method "initialized" [_]
  nil) ;; Notification, no response

(defmethod handle-method "tools/list" [{:keys [id]}]
  (proto/tools-list-response
   id (->> (get-tools) (remove tool/deprecated?) (map tool/tool-spec))))

(defmethod handle-method "tools/call" [{:keys [id params]}]
  (call-tool id (:name params) (:arguments params)))

(defmethod handle-method "resources/list" [{:keys [id]}]
  (proto/resources-list-response id []))

(defmethod handle-method "prompts/list" [{:keys [id]}]
  (proto/json-rpc-response id {:prompts []}))

(defmethod handle-method :default [{:keys [id method]}]
  (if id
    (proto/json-rpc-error id -32601 (str "Method not found: " method))
    nil)) ;; Ignore unknown notifications

;; Main loop
(defn run-server
  "Read, dispatch, and write MCP messages over `transport` until input ends."
  ([] (run-server (proto/stdio-transport)))
  ([transport]
   (loop []
     (when-let [msg (proto/read-msg transport)]
       (when-let [response (handle-method msg)]
         (proto/write-msg transport response))
       (recur)))))

(defn- warn-unless-nrepl-reachable!
  "Print a startup hint to stderr when no nREPL answers on the resolved port.
  The hive-mcp JVM is started by its own launcher, never by this process."
  []
  (let [port (nrepl/get-nrepl-port)]
    (when-not (try
                (hp/close! (hp/open {:port port :timeout-ms 1000}))
                true
                (catch Exception _ false))
      (binding [*out* *err*]
        (println (str "bb-mcp: no hive-mcp nREPL on port " port
                      " — start it with the `hive-mcp` launcher."
                      " Until then only native tools are available."))))))

(defn- warn-unless-bash-available!
  "Print a startup line to stderr when this runtime cannot spawn a subprocess,
  so a shorter tools/list reads as a stated limit rather than a silent one."
  []
  (when-not (some #(= "bash" (tool/tool-name %)) native-tools)
    (binding [*out* *err*]
      (println (str "bb-mcp: no subprocess surface on this runtime ("
                    (hp/adapter-ns) ") — the bash tool is not served.")))))

(defn -main [& _args]
  (warn-unless-nrepl-reachable!)
  (warn-unless-bash-available!)
  (hive/init!)
  (run-server))

;; For REPL development
(comment
  (run-server))