(ns bb-mcp.tools.emacs
  "Emacs tools that delegate to emacs-mcp via shared nREPL.

   These tools provide a lightweight way to interact with Emacs
   through bb-mcp while keeping the heavy JVM work on a shared nREPL."
  (:require [bb-mcp.tools.nrepl :as nrepl]))

;; Helper to evaluate emacs-mcp code via nREPL
(defn- emacs-eval
  "Evaluate code that uses emacs-mcp functions via shared nREPL."
  [code & {:keys [port timeout_ms]}]
  (nrepl/execute {:code code
                  :port port
                  :timeout_ms (or timeout_ms 30000)}))

(defn- wrap-emacs-call
  "Wrap an emacsclient call with require and error handling."
  [elisp-code]
  (str "(do (require '[emacs-mcp.emacsclient :as ec])"
       "    (ec/eval-elisp! " (pr-str elisp-code) "))"))

;; =============================================================================
;; Tool: eval_elisp
;; =============================================================================

(def eval-elisp-spec
  {:name "eval_elisp"
   :description "Execute arbitrary Emacs Lisp code via emacsclient.

Requires a shared nREPL with emacs-mcp loaded (port 7910 by default).

Examples:
- eval_elisp(code: \"(buffer-name)\")
- eval_elisp(code: \"(+ 1 2 3)\")
- eval_elisp(code: \"(message \\\"Hello from bb-mcp!\\\")\")"
   :schema {:type "object"
            :properties {:code {:type "string"
                                :description "Emacs Lisp code to evaluate"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["code"]}})

(defn eval-elisp
  "Evaluate Emacs Lisp code via emacs-mcp's emacsclient."
  [{:keys [code port]}]
  (emacs-eval (wrap-emacs-call code) :port port))

;; =============================================================================
;; Tool: emacs_status
;; =============================================================================

(def emacs-status-spec
  {:name "emacs_status"
   :description "Check if Emacs server is running and get basic status."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn emacs-status
  "Check Emacs server status."
  [{:keys [port]}]
  (let [result (emacs-eval (wrap-emacs-call "(emacs-version)") :port port)]
    (if (:error? result)
      {:result (str "Emacs server not reachable: " (:result result))
       :error? true}
      {:result (str "Emacs server running: " (:result result))
       :error? false})))

;; =============================================================================
;; Tool: list_buffers
;; =============================================================================

(def list-buffers-spec
  {:name "list_buffers"
   :description "List all open buffers in Emacs."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}
                         :limit {:type "integer"
                                 :description "Max buffers to return (default: 50)"}}
            :required []}})

(defn list-buffers
  "List all open Emacs buffers."
  [{:keys [port limit]}]
  (let [limit (or limit 50)
        code (format "(mapcar #'buffer-name (seq-take (buffer-list) %d))" limit)]
    (emacs-eval (wrap-emacs-call code) :port port)))

;; =============================================================================
;; Tool: current_buffer
;; =============================================================================

(def current-buffer-spec
  {:name "current_buffer"
   :description "Get current buffer name and associated file path."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn current-buffer
  "Get current buffer info."
  [{:keys [port]}]
  (let [code "(list (buffer-name) (buffer-file-name))"]
    (emacs-eval (wrap-emacs-call code) :port port)))

;; =============================================================================
;; Tool: get_buffer_content
;; =============================================================================

(def get-buffer-content-spec
  {:name "get_buffer_content"
   :description "Get the content of a specific buffer."
   :schema {:type "object"
            :properties {:buffer_name {:type "string"
                                       :description "Name of the buffer"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["buffer_name"]}})

(defn get-buffer-content
  "Get content of a buffer."
  [{:keys [buffer_name port]}]
  (let [code (format "(with-current-buffer %s (buffer-substring-no-properties (point-min) (min (point-max) 100000)))"
                     (pr-str buffer_name))]
    (emacs-eval (wrap-emacs-call code) :port port)))

;; =============================================================================
;; Tool: find_file
;; =============================================================================

(def find-file-spec
  {:name "find_file"
   :description "Open a file in Emacs."
   :schema {:type "object"
            :properties {:file_path {:type "string"
                                     :description "Path to the file to open"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["file_path"]}})

(defn find-file
  "Open a file in Emacs."
  [{:keys [file_path port]}]
  (let [code (format "(find-file %s)" (pr-str file_path))]
    (emacs-eval (wrap-emacs-call code) :port port)))

;; =============================================================================
;; Tool: mcp_notify
;; =============================================================================

(def mcp-notify-spec
  {:name "mcp_notify"
   :description "Show a notification message in Emacs."
   :schema {:type "object"
            :properties {:message {:type "string"
                                   :description "Message to display"}
                         :type {:type "string"
                                :description "Type: info, warning, or error"
                                :enum ["info" "warning" "error"]}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["message"]}})

(defn mcp-notify
  "Show notification in Emacs."
  [{:keys [message type port]}]
  (let [msg-type (or type "info")
        code (format "(message \"[%s] %s\")" msg-type message)]
    (emacs-eval (wrap-emacs-call code) :port port)))

;; =============================================================================
;; Tool: magit_status
;; =============================================================================

(def magit-status-spec
  {:name "magit_status"
   :description "Get comprehensive git status via Magit.

Returns branch, staged/unstaged/untracked files, ahead/behind counts."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-status
  "Get git status via emacs-mcp's magit integration."
  [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-magit-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_get_context
;; =============================================================================

(def mcp-get-context-spec
  {:name "mcp_get_context"
   :description "Get full context from Emacs including buffer, project, git, and memory."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-get-context
  "Get full Emacs context."
  [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-mcp-get-context {}))"]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: mcp_kanban_status
;; =============================================================================

(def mcp-kanban-status-spec
  {:name "mcp_kanban_status"
   :description "Get kanban board status including tasks by status, progress, and backend info."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-kanban-status
  "Get kanban status via emacs-mcp."
  [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-mcp-kanban-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_query
;; =============================================================================

(def mcp-memory-query-metadata-spec
  {:name "mcp_memory_query_metadata"
   :description "Query project memory metadata by type (note, snippet, convention, decision).
Returns id, type, preview, tags, created - 10x fewer tokens than full query."
   :schema {:type "object"
            :properties {:type {:type "string"
                                :description "Memory type: note, snippet, convention, decision"
                                :enum ["note" "snippet" "convention" "decision"]}
                         :limit {:type "integer"
                                 :description "Max results (default: 20)"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["type"]}})

(defn mcp-memory-query-metadata
  "Query memory metadata via emacs-mcp."
  [{:keys [type limit port]}]
  (let [code (format "(do (require '[emacs-mcp.tools :as tools])
                         (tools/handle-mcp-memory-query-metadata {:type %s :limit %d}))"
                     (pr-str type) (or limit 20))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_add
;; =============================================================================

(def mcp-memory-add-spec
  {:name "mcp_memory_add"
   :description "Add an entry to project memory."
   :schema {:type "object"
            :properties {:type {:type "string"
                                :description "Memory type: note, snippet, convention, decision"
                                :enum ["note" "snippet" "convention" "decision"]}
                         :content {:type "string"
                                   :description "Content to store"}
                         :tags {:type "array"
                                :items {:type "string"}
                                :description "Tags for categorization"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["type" "content"]}})

(defn mcp-memory-add
  "Add memory entry via emacs-mcp."
  [{:keys [type content tags port]}]
  (let [;; Convert tags to elisp list format
        tags-elisp (if (seq tags)
                     (str "'(" (clojure.string/join " " (map pr-str tags)) ")")
                     "nil")
        ;; Call elisp API: (emacs-mcp-api-memory-add type content &optional tags)
        elisp-code (format "(emacs-mcp-api-memory-add %s %s %s)"
                           (pr-str type) (pr-str content) tags-elisp)]
    (emacs-eval (wrap-emacs-call elisp-code) :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: swarm_status
;; =============================================================================

(def swarm-status-spec
  {:name "swarm_status"
   :description "Get swarm status including all active slaves, their states, and task counts."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn swarm-status
  "Get swarm status via emacs-mcp."
  [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-swarm-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; All tool specs and handlers
;; =============================================================================

(def tools
  [;; Basic Emacs tools
   {:spec eval-elisp-spec :handler eval-elisp}
   {:spec emacs-status-spec :handler emacs-status}
   {:spec list-buffers-spec :handler list-buffers}
   {:spec current-buffer-spec :handler current-buffer}
   {:spec get-buffer-content-spec :handler get-buffer-content}
   {:spec find-file-spec :handler find-file}
   {:spec mcp-notify-spec :handler mcp-notify}
   ;; Magit integration
   {:spec magit-status-spec :handler magit-status}
   ;; Context & Memory
   {:spec mcp-get-context-spec :handler mcp-get-context}
   {:spec mcp-memory-query-metadata-spec :handler mcp-memory-query-metadata}
   {:spec mcp-memory-add-spec :handler mcp-memory-add}
   ;; Kanban
   {:spec mcp-kanban-status-spec :handler mcp-kanban-status}
   ;; Swarm
   {:spec swarm-status-spec :handler swarm-status}])
