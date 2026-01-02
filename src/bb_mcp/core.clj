(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.bash :as bash]
            [bb-mcp.tools.file :as file]
            [bb-mcp.tools.grep :as grep]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.emacs :as emacs]
            [clojure.string :as str]))

;; Tool registry - native tools + emacs wrappers
(def tools
  (concat
   ;; Native bb-mcp tools (no JVM needed)
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
     :handler nrepl/execute}]
   ;; Emacs tools (delegate to shared nREPL with emacs-mcp)
   emacs/tools))

(defn find-tool [name]
  (first (filter #(= name (get-in % [:spec :name])) tools)))

;; Message handlers
(defmulti handle-method :method)

(defmethod handle-method "initialize" [{:keys [id]}]
  (proto/initialize-response id))

(defmethod handle-method "initialized" [_]
  nil) ;; Notification, no response

(defmethod handle-method "tools/list" [{:keys [id]}]
  (proto/tools-list-response id (map :spec tools)))

(defmethod handle-method "tools/call" [{:keys [id params]}]
  (let [{:keys [name arguments]} params]
    (if-let [tool (find-tool name)]
      (try
        (let [{:keys [result error?]} ((:handler tool) arguments)]
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
  (run-server))

;; For REPL development
(comment
  (run-server))
