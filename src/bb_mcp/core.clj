(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.bash :as bash]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.hive :as hive]
            [bb-mcp.nrepl-spawn :as spawn]
            [clojure.string :as str]))

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
  "Working directory of the bb-mcp process (Claude Code session cwd).
   Injected into args so hive-mcp resolves the correct project-id
   instead of falling back to its own JVM's user.dir."
  (System/getProperty "user.dir"))

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
  [{:spec bash/tool-spec
    :handler (fn [args] (bash/format-result (bash/execute args)))}
   {:spec nrepl/tool-spec
    :handler nrepl/execute}])

(defn get-tools
  "Get all registered tools: native + hive-mcp (dynamic)."
  []
  (concat native-tools (hive/get-tools)))

(defn find-tool [name]
  (first (filter #(= name (get-in % [:spec :name])) (get-tools))))

;; Message handlers
(defmulti handle-method :method)

(defmethod handle-method "initialize" [{:keys [id]}]
  (proto/initialize-response id))

(defmethod handle-method "initialized" [_]
  nil) ;; Notification, no response

(defmethod handle-method "tools/list" [{:keys [id]}]
  (proto/tools-list-response id (map :spec (get-tools))))

(defmethod handle-method "tools/call" [{:keys [id params]}]
  (let [{:keys [name arguments]} params
        ;; Auto-inject agent_id from CLAUDE_SWARM_SLAVE_ID env var
        enriched-args (inject-agent-context arguments)]
    (if-let [tool (find-tool name)]
      (try
        (let [{:keys [result error?]} ((:handler tool) enriched-args)]
          (proto/tool-call-response id result error?))
        (catch Exception e
          (proto/tool-call-response id (str "Error: " (ex-message e)) true)))
      (proto/json-rpc-error id -32601 (str "Unknown tool: " name)))))

(defmethod handle-method "resources/list" [{:keys [id]}]
  (proto/resources-list-response id []))

(defmethod handle-method "prompts/list" [{:keys [id]}]
  (proto/json-rpc-response id {:prompts []}))

(defmethod handle-method :default [{:keys [id method]}]
  (if id
    (proto/json-rpc-error id -32601 (str "Method not found: " method))
    nil)) ;; Ignore unknown notifications

;; Main loop
(defn run-server []
  (loop []
    (when-let [msg (proto/read-message)]
      (when-let [response (handle-method msg)]
        (proto/write-message response))
      (recur))))

(defn -main [& _args]
  ;; Ensure hive-mcp nREPL is running (auto-spawn if needed)
  (spawn/ensure-nrepl!)
  ;; Load tools dynamically from hive-mcp (falls back to static on failure)
  (hive/init!)
  (run-server))

;; For REPL development
(comment
  (run-server))
