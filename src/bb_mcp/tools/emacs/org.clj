(ns bb-mcp.tools.emacs.org
  "Org-mode tools.

   This module provides tools for Org-mode file operations."
  (:require [bb-mcp.tools.emacs.core :as core]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: org_clj_parse
;; =============================================================================

(def org-clj-parse-spec
  {:name "org_clj_parse"
   :description "Parse an Org file into a Clojure data structure."
   :schema {:type "object"
            :properties {:file_path {:type "string" :description "Path to the Org file"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["file_path"]}})

(defn org-clj-parse [{:keys [file_path port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.org :as org])
                         (org/handle-org-clj-parse {:file_path %s}))"
                     (pr-str file_path))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: org_clj_write
;; =============================================================================

(def org-clj-write-spec
  {:name "org_clj_write"
   :description "Write a Clojure data structure as an Org file."
   :schema {:type "object"
            :properties {:file_path {:type "string" :description "Path to write the Org file"}
                         :document {:type "object" :description "Org document as Clojure data"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["file_path" "document"]}})

(defn org-clj-write [{:keys [file_path document port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.org :as org])
                         (org/handle-org-clj-write {:file_path %s :document %s}))"
                     (pr-str file_path) (pr-str document))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: org_clj_query
;; =============================================================================

(def org-clj-query-spec
  {:name "org_clj_query"
   :description "Query an Org file for specific content."
   :schema {:type "object"
            :properties {:file_path {:type "string" :description "Path to the Org file"}
                         :query_type {:type "string" :description "Type of query (e.g., headline, property, tag)"}
                         :query_value {:type "string" :description "Value to query for"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["file_path" "query_type" "query_value"]}})

(defn org-clj-query [{:keys [file_path query_type query_value port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.org :as org])
                         (org/handle-org-clj-query {:file_path %s :query_type %s :query_value %s}))"
                     (pr-str file_path) (pr-str query_type) (pr-str query_value))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: org_kanban_render
;; =============================================================================

(def org-kanban-render-spec
  {:name "org_kanban_render"
   :description "Render an Org file as a kanban board."
   :schema {:type "object"
            :properties {:file_path {:type "string" :description "Path to the Org file"}
                         :format {:type "string" :description "Output format (e.g., text, json)"}
                         :column_width {:type "integer" :description "Width of each column"}
                         :max_cards {:type "integer" :description "Maximum cards per column"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["file_path"]}})

(defn org-kanban-render [{:keys [file_path format column_width max_cards port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.org :as org])
                         (org/handle-org-kanban-render {:file_path %s :format %s :column_width %s :max_cards %s}))"
                     (pr-str file_path)
                     (if format (pr-str format) "nil")
                     (if column_width (str column_width) "nil")
                     (if max_cards (str max_cards) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec org-clj-parse-spec :handler org-clj-parse}
   {:spec org-clj-write-spec :handler org-clj-write}
   {:spec org-clj-query-spec :handler org-clj-query}
   {:spec org-kanban-render-spec :handler org-kanban-render}])
