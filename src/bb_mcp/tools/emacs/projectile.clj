(ns bb-mcp.tools.emacs.projectile
  "Projectile navigation tools.

   This module provides tools for project navigation via Projectile."
  (:require [bb-mcp.tools.emacs.core :as core]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: projectile_info
;; =============================================================================

(def projectile-info-spec
  {:name "projectile_info"
   :description "Get current project info via Projectile."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn projectile-info [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.projectile :as p])
                  (p/handle-projectile-info {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: projectile_files
;; =============================================================================

(def projectile-files-spec
  {:name "projectile_files"
   :description "List project files, optionally filtered by pattern."
   :schema {:type "object"
            :properties {:pattern {:type "string" :description "Glob pattern to filter files"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn projectile-files [{:keys [pattern port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.projectile :as p])
                         (p/handle-projectile-files {:pattern %s}))"
                     (if pattern (pr-str pattern) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: projectile_search
;; =============================================================================

(def projectile-search-spec
  {:name "projectile_search"
   :description "Search for pattern in project files."
   :schema {:type "object"
            :properties {:pattern {:type "string" :description "Search pattern"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["pattern"]}})

(defn projectile-search [{:keys [pattern port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.projectile :as p])
                         (p/handle-projectile-search {:pattern %s}))"
                     (pr-str pattern))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: projectile_find_file
;; =============================================================================

(def projectile-find-file-spec
  {:name "projectile_find_file"
   :description "Find and open a file in the current project."
   :schema {:type "object"
            :properties {:filename {:type "string" :description "Filename to find"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["filename"]}})

(defn projectile-find-file [{:keys [filename port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.projectile :as p])
                         (p/handle-projectile-find-file {:filename %s}))"
                     (pr-str filename))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: projectile_recent
;; =============================================================================

(def projectile-recent-spec
  {:name "projectile_recent"
   :description "Get recently visited files in the current project."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn projectile-recent [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.projectile :as p])
                  (p/handle-projectile-recent {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: projectile_list_projects
;; =============================================================================

(def projectile-list-projects-spec
  {:name "projectile_list_projects"
   :description "List all known Projectile projects."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn projectile-list-projects [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.projectile :as p])
                  (p/handle-projectile-list-projects {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec projectile-info-spec :handler projectile-info}
   {:spec projectile-files-spec :handler projectile-files}
   {:spec projectile-search-spec :handler projectile-search}
   {:spec projectile-find-file-spec :handler projectile-find-file}
   {:spec projectile-recent-spec :handler projectile-recent}
   {:spec projectile-list-projects-spec :handler projectile-list-projects}])
