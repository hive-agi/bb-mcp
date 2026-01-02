(ns bb-mcp.tools.emacs.cider
  "CIDER REPL tools.

   This module provides tools for CIDER REPL operations."
  (:require [bb-mcp.tools.emacs.core :as core]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: cider_status
;; =============================================================================

(def cider-status-spec
  {:name "cider_status"
   :description "Get CIDER connection status including connected state, REPL buffer name, current namespace, and REPL type."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn cider-status [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.cider :as c])
                  (c/handle-cider-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: cider_eval_silent
;; =============================================================================

(def cider-eval-silent-spec
  {:name "cider_eval_silent"
   :description "Evaluate Clojure code via CIDER silently. Fast evaluation without REPL buffer output. Use for routine/automated evals."
   :schema {:type "object"
            :properties {:code {:type "string" :description "Clojure code to evaluate"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["code"]}})

(defn cider-eval-silent [{:keys [code port]}]
  (let [eval-code (format "(do (require '[emacs-mcp.tools.cider :as c])
                              (c/handle-cider-eval-silent {:code %s}))"
                          (pr-str code))]
    (emacs-eval eval-code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: cider_eval_explicit
;; =============================================================================

(def cider-eval-explicit-spec
  {:name "cider_eval_explicit"
   :description "Evaluate Clojure code via CIDER interactively. Shows output in REPL buffer for collaborative debugging. Use when stuck or want user to see output."
   :schema {:type "object"
            :properties {:code {:type "string" :description "Clojure code to evaluate"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["code"]}})

(defn cider-eval-explicit [{:keys [code port]}]
  (let [eval-code (format "(do (require '[emacs-mcp.tools.cider :as c])
                              (c/handle-cider-eval-explicit {:code %s}))"
                          (pr-str code))]
    (emacs-eval eval-code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: cider_spawn_session
;; =============================================================================

(def cider-spawn-session-spec
  {:name "cider_spawn_session"
   :description "Spawn a new named CIDER session with its own nREPL server. Useful for parallel agent work where each agent needs an isolated REPL."
   :schema {:type "object"
            :properties {:name {:type "string" :description "Session identifier (e.g., 'agent-1', 'task-render')"}
                         :project_dir {:type "string" :description "Directory to start nREPL in (optional)"}
                         :agent_id {:type "string" :description "Optional swarm agent ID to link this session to"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["name"]}})

(defn cider-spawn-session [{:keys [name project_dir agent_id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.cider :as c])
                         (c/handle-cider-spawn-session {:name %s :project_dir %s :agent_id %s}))"
                     (pr-str name)
                     (if project_dir (pr-str project_dir) "nil")
                     (if agent_id (pr-str agent_id) "nil"))]
    (emacs-eval code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: cider_list_sessions
;; =============================================================================

(def cider-list-sessions-spec
  {:name "cider_list_sessions"
   :description "List all active CIDER sessions with their status, ports, and linked agents."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn cider-list-sessions [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.cider :as c])
                  (c/handle-cider-list-sessions {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: cider_eval_session
;; =============================================================================

(def cider-eval-session-spec
  {:name "cider_eval_session"
   :description "Evaluate Clojure code in a specific named CIDER session. Use for isolated evaluation in multi-agent scenarios."
   :schema {:type "object"
            :properties {:session_name {:type "string" :description "Name of the session to evaluate in"}
                         :code {:type "string" :description "Clojure code to evaluate"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["session_name" "code"]}})

(defn cider-eval-session [{:keys [session_name code port]}]
  (let [eval-code (format "(do (require '[emacs-mcp.tools.cider :as c])
                              (c/handle-cider-eval-session {:session_name %s :code %s}))"
                          (pr-str session_name) (pr-str code))]
    (emacs-eval eval-code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: cider_kill_session
;; =============================================================================

(def cider-kill-session-spec
  {:name "cider_kill_session"
   :description "Kill a specific named CIDER session and its nREPL server."
   :schema {:type "object"
            :properties {:session_name {:type "string" :description "Name of the session to kill"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["session_name"]}})

(defn cider-kill-session [{:keys [session_name port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.cider :as c])
                         (c/handle-cider-kill-session {:session_name %s}))"
                     (pr-str session_name))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: cider_kill_all_sessions
;; =============================================================================

(def cider-kill-all-sessions-spec
  {:name "cider_kill_all_sessions"
   :description "Kill all CIDER sessions. Useful for cleanup after parallel agent work."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn cider-kill-all-sessions [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.cider :as c])
                  (c/handle-cider-kill-all-sessions {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec cider-status-spec :handler cider-status}
   {:spec cider-eval-silent-spec :handler cider-eval-silent}
   {:spec cider-eval-explicit-spec :handler cider-eval-explicit}
   {:spec cider-spawn-session-spec :handler cider-spawn-session}
   {:spec cider-list-sessions-spec :handler cider-list-sessions}
   {:spec cider-eval-session-spec :handler cider-eval-session}
   {:spec cider-kill-session-spec :handler cider-kill-session}
   {:spec cider-kill-all-sessions-spec :handler cider-kill-all-sessions}])
