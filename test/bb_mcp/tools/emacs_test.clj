(ns bb-mcp.tools.emacs-test
  "TDD tests for emacs tools module.
   These tests verify invariants that must hold through refactoring."
  (:require [clojure.test :refer :all]
            [bb-mcp.tools.emacs :as emacs]))

;; =============================================================================
;; Regression Tests - These MUST pass before and after refactoring
;; =============================================================================

(deftest tool-count-regression-test
  (testing "Total tool count must remain stable"
    (is (= 83 (count emacs/tools))
        "Tool count changed - this breaks backwards compatibility")))

(deftest all-tools-have-required-structure-test
  (testing "All tools must have :spec and :handler keys"
    (doseq [tool emacs/tools]
      (is (map? tool) "Tool must be a map")
      (is (contains? tool :spec) (str "Missing :spec in tool"))
      (is (contains? tool :handler) (str "Missing :handler in tool")))))

(deftest all-specs-have-required-keys-test
  (testing "All specs must have :name, :description, :schema"
    (doseq [{:keys [spec]} emacs/tools]
      (is (string? (:name spec))
          (str "Missing or invalid :name in spec"))
      (is (string? (:description spec))
          (str "Missing or invalid :description for " (:name spec)))
      (is (map? (:schema spec))
          (str "Missing or invalid :schema for " (:name spec))))))

(deftest no-duplicate-tool-names-test
  (testing "Tool names must be unique"
    (let [names (map #(get-in % [:spec :name]) emacs/tools)
          freq (frequencies names)
          duplicates (filter #(> (val %) 1) freq)]
      (is (empty? duplicates)
          (str "Duplicate tool names found: " (keys duplicates))))))

(deftest all-handlers-are-functions-test
  (testing "All handlers must be callable functions"
    (doseq [{:keys [spec handler]} emacs/tools]
      (is (fn? handler)
          (str "Handler for " (:name spec) " is not a function")))))

(deftest all-schemas-are-valid-json-schema-test
  (testing "All schemas must be valid JSON Schema structure"
    (doseq [{:keys [spec]} emacs/tools]
      (let [schema (:schema spec)]
        (is (= "object" (:type schema))
            (str "Schema type must be 'object' for " (:name spec)))
        (is (map? (:properties schema))
            (str "Schema must have :properties for " (:name spec)))
        (is (or (nil? (:required schema))
                (vector? (:required schema)))
            (str "Schema :required must be nil or vector for " (:name spec)))))))

;; =============================================================================
;; Unit Tests - Tool Categories
;; =============================================================================

(def expected-tool-categories
  "Expected tool names by category for parity verification"
  {:buffer #{"eval_elisp" "emacs_status" "list_buffers" "current_buffer"
             "get_buffer_content" "find_file" "mcp_notify" "switch_to_buffer"
             "save_buffer" "goto_line" "insert_text" "recent_files" "buffer_info"
             "project_root" "mcp_capabilities" "mcp_watch_buffer"
             "mcp_list_special_buffers" "mcp_list_workflows" "mcp_run_workflow"}
   :magit #{"magit_status" "magit_branches" "magit_log" "magit_diff"
            "magit_stage" "magit_commit" "magit_push" "magit_pull"
            "magit_fetch" "magit_feature_branches"}
   :memory #{"mcp_memory_query_metadata" "mcp_memory_get_full" "mcp_memory_add"
             "mcp_memory_search_semantic" "mcp_memory_set_duration"
             "mcp_memory_promote" "mcp_memory_demote" "mcp_memory_cleanup_expired"
             "mcp_memory_expiring_soon" "mcp_memory_log_access"
             "mcp_memory_feedback" "mcp_memory_check_duplicate"}
   :kanban #{"mcp_kanban_status" "mcp_kanban_list_tasks" "mcp_kanban_create_task"
             "mcp_kanban_move_task" "mcp_kanban_update_task" "mcp_kanban_roadmap"
             "mcp_kanban_my_tasks" "mcp_kanban_sync"}
   :swarm #{"swarm_status" "swarm_spawn" "swarm_dispatch" "swarm_collect"
            "swarm_list_presets" "swarm_kill" "swarm_broadcast"
            "swarm_pending_prompts" "swarm_respond_prompt"
            "jvm_cleanup" "resource_guard"}
   :projectile #{"projectile_info" "projectile_files" "projectile_search"
                 "projectile_find_file" "projectile_recent" "projectile_list_projects"}
   :org #{"org_clj_parse" "org_clj_write" "org_clj_query" "org_kanban_render"}
   :prompt #{"prompt_capture" "prompt_list" "prompt_search" "prompt_stats"}
   :cider #{"cider_status" "cider_eval_silent" "cider_eval_explicit"
            "cider_spawn_session" "cider_list_sessions" "cider_eval_session"
            "cider_kill_session" "cider_kill_all_sessions"}
   :context #{"mcp_get_context"}})

(deftest all-expected-tools-present-test
  (testing "All expected tools from each category are present"
    (let [actual-names (set (map #(get-in % [:spec :name]) emacs/tools))]
      (doseq [[category expected-names] expected-tool-categories]
        (doseq [tool-name expected-names]
          (is (contains? actual-names tool-name)
              (str "Missing " category " tool: " tool-name)))))))

(deftest category-tool-counts-test
  (testing "Tool counts by category"
    (is (= 19 (count (:buffer expected-tool-categories))) "Buffer tools")
    (is (= 10 (count (:magit expected-tool-categories))) "Magit tools")
    (is (= 12 (count (:memory expected-tool-categories))) "Memory tools")
    (is (= 8 (count (:kanban expected-tool-categories))) "Kanban tools")
    (is (= 11 (count (:swarm expected-tool-categories))) "Swarm tools")
    (is (= 6 (count (:projectile expected-tool-categories))) "Projectile tools")
    (is (= 4 (count (:org expected-tool-categories))) "Org tools")
    (is (= 4 (count (:prompt expected-tool-categories))) "Prompt tools")
    (is (= 8 (count (:cider expected-tool-categories))) "CIDER tools")
    (is (= 1 (count (:context expected-tool-categories))) "Context tools")
    ;; Total: 19+10+12+8+11+6+4+4+8+1 = 83
    (is (= 83 (reduce + (map count (vals expected-tool-categories)))))))

;; =============================================================================
;; Unit Tests - Handler Contract
;; =============================================================================

;; Note: Babashka doesn't expose JVM reflection for function arity
;; Handler signatures are verified structurally during refactor

;; =============================================================================
;; Smoke Tests - Require specific tools exist
;; =============================================================================

(deftest critical-tools-exist-test
  (testing "Critical tools for basic operation exist"
    (let [tool-names (set (map #(get-in % [:spec :name]) emacs/tools))
          critical-tools ["emacs_status" "eval_elisp" "magit_status"
                          "mcp_get_context" "mcp_kanban_status"
                          "mcp_memory_query_metadata" "swarm_status"]]
      (doseq [tool critical-tools]
        (is (contains? tool-names tool)
            (str "Critical tool missing: " tool))))))
