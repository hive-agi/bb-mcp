(ns bb-mcp.tools.emacs.context
  "Context tools.

   This module provides tools for getting Emacs context."
  (:require [bb-mcp.tools.emacs.core :as core]))

(def ^:private emacs-eval core/emacs-eval)

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

(defn mcp-get-context [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-mcp-get-context {}))"]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec mcp-get-context-spec :handler mcp-get-context}])
