(ns bb-mcp.tools.emacs.kanban
  "Kanban board tools.

   This module provides tools for kanban task management."
  (:require [bb-mcp.tools.emacs.core :as core]))

(def ^:private emacs-eval core/emacs-eval)

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

(defn mcp-kanban-status [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-mcp-kanban-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_list_tasks
;; =============================================================================

(def mcp-kanban-list-tasks-spec
  {:name "mcp_kanban_list_tasks"
   :description "List kanban tasks, optionally filtered by status."
   :schema {:type "object"
            :properties {:status {:type "string" :description "Filter by status: todo, inprogress, done"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-kanban-list-tasks [{:keys [status port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.kanban :as k])
                         (k/handle-mcp-kanban-list-tasks {:status %s}))"
                     (if status (pr-str status) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_create_task
;; =============================================================================

(def mcp-kanban-create-task-spec
  {:name "mcp_kanban_create_task"
   :description "Create a new kanban task."
   :schema {:type "object"
            :properties {:title {:type "string" :description "Task title"}
                         :description {:type "string" :description "Task description"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["title"]}})

(defn mcp-kanban-create-task [{:keys [title description port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.kanban :as k])
                         (k/handle-mcp-kanban-create-task {:title %s :description %s}))"
                     (pr-str title) (if description (pr-str description) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_move_task
;; =============================================================================

(def mcp-kanban-move-task-spec
  {:name "mcp_kanban_move_task"
   :description "Move a kanban task to a new status."
   :schema {:type "object"
            :properties {:task_id {:type "string" :description "Task ID"}
                         :new_status {:type "string" :description "New status: todo, inprogress, done"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["task_id" "new_status"]}})

(defn mcp-kanban-move-task [{:keys [task_id new_status port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.kanban :as k])
                         (k/handle-mcp-kanban-move-task {:task_id %s :new_status %s}))"
                     (pr-str task_id) (pr-str new_status))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_update_task
;; =============================================================================

(def mcp-kanban-update-task-spec
  {:name "mcp_kanban_update_task"
   :description "Update a kanban task's status or title."
   :schema {:type "object"
            :properties {:task_id {:type "string" :description "Task ID"}
                         :status {:type "string" :description "New status: todo, inprogress, done"}
                         :title {:type "string" :description "New task title"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["task_id"]}})

(defn mcp-kanban-update-task [{:keys [task_id status title port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.kanban :as k])
                         (k/handle-mcp-kanban-update-task {:task_id %s :status %s :title %s}))"
                     (pr-str task_id)
                     (if status (pr-str status) "nil")
                     (if title (pr-str title) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_roadmap
;; =============================================================================

(def mcp-kanban-roadmap-spec
  {:name "mcp_kanban_roadmap"
   :description "Get the kanban roadmap view."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-kanban-roadmap [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.kanban :as k])
                  (k/handle-mcp-kanban-roadmap {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_my_tasks
;; =============================================================================

(def mcp-kanban-my-tasks-spec
  {:name "mcp_kanban_my_tasks"
   :description "Get tasks assigned to the current user."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-kanban-my-tasks [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.kanban :as k])
                  (k/handle-mcp-kanban-my-tasks {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_kanban_sync
;; =============================================================================

(def mcp-kanban-sync-spec
  {:name "mcp_kanban_sync"
   :description "Sync kanban board with backend."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-kanban-sync [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.kanban :as k])
                  (k/handle-mcp-kanban-sync {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec mcp-kanban-status-spec :handler mcp-kanban-status}
   {:spec mcp-kanban-list-tasks-spec :handler mcp-kanban-list-tasks}
   {:spec mcp-kanban-create-task-spec :handler mcp-kanban-create-task}
   {:spec mcp-kanban-move-task-spec :handler mcp-kanban-move-task}
   {:spec mcp-kanban-update-task-spec :handler mcp-kanban-update-task}
   {:spec mcp-kanban-roadmap-spec :handler mcp-kanban-roadmap}
   {:spec mcp-kanban-my-tasks-spec :handler mcp-kanban-my-tasks}
   {:spec mcp-kanban-sync-spec :handler mcp-kanban-sync}])
