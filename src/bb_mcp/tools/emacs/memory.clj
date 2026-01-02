(ns bb-mcp.tools.emacs.memory
  "Memory system tools.

   This module provides tools for project memory operations."
  (:require [bb-mcp.tools.emacs.core :as core]
            [clojure.string :as str]))

(def ^:private emacs-eval core/emacs-eval)
(def ^:private wrap-emacs-call core/wrap-emacs-call)

;; =============================================================================
;; Tool: mcp_memory_query_metadata
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

(defn mcp-memory-query-metadata [{:keys [type limit port]}]
  (let [code (format "(do (require '[emacs-mcp.tools :as tools])
                         (tools/handle-mcp-memory-query-metadata {:type %s :limit %d}))"
                     (pr-str type) (or limit 20))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_get_full
;; =============================================================================

(def mcp-memory-get-full-spec
  {:name "mcp_memory_get_full"
   :description "Get full content of a memory entry by ID.
Use after mcp_memory_query_metadata to fetch specific entries."
   :schema {:type "object"
            :properties {:id {:type "string"
                              :description "ID of the memory entry to retrieve"}
                         :port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required ["id"]}})

(defn mcp-memory-get-full [{:keys [id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools :as tools])
                         (tools/handle-mcp-memory-get-full {:id %s}))"
                     (pr-str id))]
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

(defn mcp-memory-add [{:keys [type content tags port]}]
  (let [tags-elisp (if (seq tags)
                     (str "'(" (str/join " " (map pr-str tags)) ")")
                     "nil")
        elisp-code (format "(emacs-mcp-api-memory-add %s %s %s)"
                           (pr-str type) (pr-str content) tags-elisp)]
    (emacs-eval (wrap-emacs-call elisp-code) :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_search_semantic
;; =============================================================================

(def mcp-memory-search-semantic-spec
  {:name "mcp_memory_search_semantic"
   :description "Semantic search across project memory using embeddings."
   :schema {:type "object"
            :properties {:query {:type "string" :description "Search query"}
                         :limit {:type "integer" :description "Max results (default: 10)"}
                         :type {:type "string" :description "Filter by type"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["query"]}})

(defn mcp-memory-search-semantic [{:keys [query limit type port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-search-semantic {:query %s :limit %s :type %s}))"
                     (pr-str query)
                     (if limit (str limit) "nil")
                     (if type (pr-str type) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: mcp_memory_set_duration
;; =============================================================================

(def mcp-memory-set-duration-spec
  {:name "mcp_memory_set_duration"
   :description "Set duration/expiry for a memory entry."
   :schema {:type "object"
            :properties {:id {:type "string" :description "Memory entry ID"}
                         :duration {:type "string" :description "Duration: ephemeral, short-term, medium-term, long-term, permanent"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["id" "duration"]}})

(defn mcp-memory-set-duration [{:keys [id duration port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-set-duration {:id %s :duration %s}))"
                     (pr-str id) (pr-str duration))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_promote
;; =============================================================================

(def mcp-memory-promote-spec
  {:name "mcp_memory_promote"
   :description "Promote a memory entry to longer duration."
   :schema {:type "object"
            :properties {:id {:type "string" :description "Memory entry ID"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["id"]}})

(defn mcp-memory-promote [{:keys [id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-promote {:id %s}))"
                     (pr-str id))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_demote
;; =============================================================================

(def mcp-memory-demote-spec
  {:name "mcp_memory_demote"
   :description "Demote a memory entry to shorter duration."
   :schema {:type "object"
            :properties {:id {:type "string" :description "Memory entry ID"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["id"]}})

(defn mcp-memory-demote [{:keys [id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-demote {:id %s}))"
                     (pr-str id))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_cleanup_expired
;; =============================================================================

(def mcp-memory-cleanup-expired-spec
  {:name "mcp_memory_cleanup_expired"
   :description "Clean up expired memory entries."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn mcp-memory-cleanup-expired [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.memory :as m])
                  (m/handle-mcp-memory-cleanup-expired {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_expiring_soon
;; =============================================================================

(def mcp-memory-expiring-soon-spec
  {:name "mcp_memory_expiring_soon"
   :description "Get memory entries expiring within the specified number of days."
   :schema {:type "object"
            :properties {:days {:type "integer" :description "Number of days to look ahead"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["days"]}})

(defn mcp-memory-expiring-soon [{:keys [days port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-expiring-soon {:days %d}))"
                     days)]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_log_access
;; =============================================================================

(def mcp-memory-log-access-spec
  {:name "mcp_memory_log_access"
   :description "Log an access event for a memory entry."
   :schema {:type "object"
            :properties {:id {:type "string" :description "Memory entry ID"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["id"]}})

(defn mcp-memory-log-access [{:keys [id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-log-access {:id %s}))"
                     (pr-str id))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_feedback
;; =============================================================================

(def mcp-memory-feedback-spec
  {:name "mcp_memory_feedback"
   :description "Provide feedback on a memory entry."
   :schema {:type "object"
            :properties {:id {:type "string" :description "Memory entry ID"}
                         :feedback {:type "string" :description "Feedback to record"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["id" "feedback"]}})

(defn mcp-memory-feedback [{:keys [id feedback port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-feedback {:id %s :feedback %s}))"
                     (pr-str id) (pr-str feedback))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: mcp_memory_check_duplicate
;; =============================================================================

(def mcp-memory-check-duplicate-spec
  {:name "mcp_memory_check_duplicate"
   :description "Check if a memory entry with similar content already exists."
   :schema {:type "object"
            :properties {:type {:type "string"
                                :description "Memory type: note, snippet, convention, decision"
                                :enum ["note" "snippet" "convention" "decision"]}
                         :content {:type "string" :description "Content to check for duplicates"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["type" "content"]}})

(defn mcp-memory-check-duplicate [{:keys [type content port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.memory :as m])
                         (m/handle-mcp-memory-check-duplicate {:type %s :content %s}))"
                     (pr-str type) (pr-str content))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec mcp-memory-query-metadata-spec :handler mcp-memory-query-metadata}
   {:spec mcp-memory-get-full-spec :handler mcp-memory-get-full}
   {:spec mcp-memory-add-spec :handler mcp-memory-add}
   {:spec mcp-memory-search-semantic-spec :handler mcp-memory-search-semantic}
   {:spec mcp-memory-set-duration-spec :handler mcp-memory-set-duration}
   {:spec mcp-memory-promote-spec :handler mcp-memory-promote}
   {:spec mcp-memory-demote-spec :handler mcp-memory-demote}
   {:spec mcp-memory-cleanup-expired-spec :handler mcp-memory-cleanup-expired}
   {:spec mcp-memory-expiring-soon-spec :handler mcp-memory-expiring-soon}
   {:spec mcp-memory-log-access-spec :handler mcp-memory-log-access}
   {:spec mcp-memory-feedback-spec :handler mcp-memory-feedback}
   {:spec mcp-memory-check-duplicate-spec :handler mcp-memory-check-duplicate}])
