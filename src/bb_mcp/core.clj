(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.bash :as bash]
            [bb-mcp.tools.file :as file]
            [bb-mcp.tools.grep :as grep]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.hive :as hive]
            [bb-mcp.nrepl-spawn :as spawn]
            [clojure.string :as str]))

;; Agent context injection - auto-add agent_id from env var for attribution
(defn- get-agent-id
  "Get agent ID from CLAUDE_SWARM_SLAVE_ID env var, or nil if not set."
  []
  (System/getenv "CLAUDE_SWARM_SLAVE_ID"))

(defn- inject-agent-context
  "Inject agent context from CLAUDE_SWARM_SLAVE_ID env var.

   Injects TWO fields:
   - _caller_id: ALWAYS injected — identifies the MCP session/caller.
     Used by piggyback middleware for per-caller cursor isolation.
     Never conflicts with user-specified agent_id (dispatch target).
   - agent_id: only injected when args lack it (backward compat).
     For dispatch-type tools, user sets agent_id to the target,
     so bb-mcp must NOT overwrite it."
  [args]
  (let [agent-id (get-agent-id)]
    (if agent-id
      (cond-> (assoc args :_caller_id agent-id)
        (not (:agent_id args)) (assoc :agent_id agent-id))
      args)))

;; Native bb-mcp tools (no JVM needed)
(def ^:private native-tools
  [{:spec bash/tool-spec
    :handler (fn [args] (bash/format-result (bash/execute args)))}
   {:spec file/read-file-spec
    :handler file/read-file}
   {:spec file/write-file-spec
    :handler file/write-file}
   {:spec file/glob-spec
    :handler file/glob-files}
   {:spec grep/tool-spec
    :handler grep/execute}
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
