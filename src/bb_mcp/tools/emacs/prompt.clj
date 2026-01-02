(ns bb-mcp.tools.emacs.prompt
  "Prompt capture tools.

   This module provides tools for capturing and managing prompts."
  (:require [bb-mcp.tools.emacs.core :as core]
            [clojure.string :as str]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: prompt_capture
;; =============================================================================

(def prompt-capture-spec
  {:name "prompt_capture"
   :description "Capture a prompt for later reference."
   :schema {:type "object"
            :properties {:prompt {:type "string" :description "The prompt text"}
                         :accomplishes {:type "string" :description "What the prompt accomplishes"}
                         :category {:type "string" :description "Category for organization"}
                         :tags {:type "array" :items {:type "string"} :description "Tags for the prompt"}
                         :quality {:type "string" :description "Quality rating"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["prompt" "accomplishes"]}})

(defn prompt-capture [{:keys [prompt accomplishes category tags quality port]}]
  (let [tags-str (if (seq tags)
                   (str "[" (str/join " " (map pr-str tags)) "]")
                   "nil")
        code (format "(do (require '[emacs-mcp.tools.prompt :as pr])
                         (pr/handle-prompt-capture {:prompt %s :accomplishes %s :category %s :tags %s :quality %s}))"
                     (pr-str prompt)
                     (pr-str accomplishes)
                     (if category (pr-str category) "nil")
                     tags-str
                     (if quality (pr-str quality) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: prompt_list
;; =============================================================================

(def prompt-list-spec
  {:name "prompt_list"
   :description "List captured prompts, optionally filtered."
   :schema {:type "object"
            :properties {:category {:type "string" :description "Filter by category"}
                         :quality {:type "string" :description "Filter by quality"}
                         :limit {:type "integer" :description "Maximum prompts to return"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn prompt-list [{:keys [category quality limit port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.prompt :as pr])
                         (pr/handle-prompt-list {:category %s :quality %s :limit %s}))"
                     (if category (pr-str category) "nil")
                     (if quality (pr-str quality) "nil")
                     (if limit (str limit) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: prompt_search
;; =============================================================================

(def prompt-search-spec
  {:name "prompt_search"
   :description "Search captured prompts by query."
   :schema {:type "object"
            :properties {:query {:type "string" :description "Search query"}
                         :limit {:type "integer" :description "Maximum results"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["query"]}})

(defn prompt-search [{:keys [query limit port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.prompt :as pr])
                         (pr/handle-prompt-search {:query %s :limit %s}))"
                     (pr-str query)
                     (if limit (str limit) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: prompt_stats
;; =============================================================================

(def prompt-stats-spec
  {:name "prompt_stats"
   :description "Get statistics about captured prompts."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn prompt-stats [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.prompt :as pr])
                  (pr/handle-prompt-stats {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec prompt-capture-spec :handler prompt-capture}
   {:spec prompt-list-spec :handler prompt-list}
   {:spec prompt-search-spec :handler prompt-search}
   {:spec prompt-stats-spec :handler prompt-stats}])
