(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.bash :as bash]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.hive :as hive]
            [bb-mcp.nrepl-spawn :as spawn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bb-mcp.tool :as tool]))

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
                     "ARGS: " (json/generate-string args {:pretty false}) "\n"
                     "RESP: " (or truncated "<nil>") "\n\n")]
      (spit log-file entry :append true))
    (catch Exception _ nil)))

;; Agent context injection - auto-add agent_id from env var for attribution

(def ^:private instance-id
  "Stable ID for this bb-mcp session. Uses parent PID (Claude Code process)
   so the identity survives bb-mcp restarts within the same session.
   Falls back to own PID, then random UUID.

   Why PPID: Each Claude Code window spawns bb-mcp as a child process.
   The parent PID is stable for the session lifetime. When bb-mcp restarts
   (e.g., tool refresh), the PPID stays the same, so cursor positions
   are preserved instead of re-reading from timestamp 0."
  (let [ppid (try
               (let [parent (.parent (java.lang.ProcessHandle/current))]
                 (when (.isPresent parent)
                   (str (.pid (.get parent)))))
               (catch Exception _ nil))
        pid  (try (str (.pid (java.lang.ProcessHandle/current)))
                  (catch Exception _ nil))]
    (or ppid pid (subs (str (java.util.UUID/randomUUID)) 0 8))))

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
(def ^:private native-tools
  [(tool/native-tool bash/tool-spec
                     (fn [args] (bash/format-result (bash/execute args))))
   (tool/native-tool nrepl/tool-spec nrepl/execute)])

(def ^:private tool-sources
  "Ordered tool providers; each a zero-arg fn returning a seq of Tool."
  [(constantly native-tools) hive/get-tools])

(defn get-tools
  "All registered tools from every source, in order."
  ([] (get-tools tool-sources))
  ([sources] (mapcat #(%) sources)))

(defn find-tool [name]
  (first (filter #(= name (tool/tool-name %)) (get-tools))))

(defn- invoke-safely
  "Enrich `arguments`, invoke tool `t`, folding any thrown exception into {:result :error?}."
  [t arguments]
  (try
    (tool/invoke t (inject-agent-context arguments))
    (catch Exception e {:result (str "Error: " (ex-message e)) :error? true})))

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

(defn -main [& _args]
  ;; Ensure hive-mcp nREPL is running (auto-spawn if needed)
  (spawn/ensure-nrepl!)
  ;; Load tools dynamically from hive-mcp (falls back to static on failure)
  (hive/init!)
  (run-server))

;; For REPL development
(comment
  (run-server))