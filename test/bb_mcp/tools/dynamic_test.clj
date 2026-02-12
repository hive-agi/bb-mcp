(ns bb-mcp.tools.dynamic-test
  "Tests for dynamic tool loading from hive-mcp."
  (:require [clojure.test :refer :all]
            [bb-mcp.tools.hive :as hive]
            [bb-mcp.tools.hive.dynamic :as dynamic]))

;; =============================================================================
;; Unit Tests - Transform Functions (no hive-mcp required)
;; =============================================================================

(deftest transform-tool-test
  (testing "Transform hive-mcp spec to bb-mcp format"
    (let [hive-spec {:name "test_tool"
                     :description "A test tool"
                     :schema {:type "object"
                              :properties {:foo {:type "string"}}
                              :required ["foo"]}}
          result (#'dynamic/transform-tool hive-spec)]
      (is (map? result) "Result should be a map")
      (is (contains? result :spec) "Result should have :spec")
      (is (contains? result :handler) "Result should have :handler")
      (is (fn? (:handler result)) "Handler should be a function")
      (is (= "test_tool" (get-in result [:spec :name])))
      (is (= "A test tool" (get-in result [:spec :description])))
      (is (= {:type "object"
              :properties {:foo {:type "string"}}
              :required ["foo"]}
             (get-in result [:spec :schema]))))))

(deftest transform-tool-missing-schema-test
  (testing "Transform handles missing schema"
    (let [hive-spec {:name "minimal_tool"
                     :description "Minimal tool"
                     :schema nil}
          result (#'dynamic/transform-tool hive-spec)]
      (is (= {:type "object" :properties {} :required []}
             (get-in result [:spec :schema]))
          "Missing schema should get default empty schema"))))

;; =============================================================================
;; Unit Tests - Cache Functions (no hive-mcp required)
;; =============================================================================

(deftest cache-lifecycle-test
  (testing "Cache lifecycle"
    ;; Start clean
    (dynamic/clear-cache!)
    (is (nil? (dynamic/get-tools)) "Cache should be nil initially")
    (is (false? (dynamic/tools-loaded?)) "tools-loaded? should be false")

    ;; After clearing, still nil
    (dynamic/clear-cache!)
    (is (nil? (dynamic/get-tools)) "Cache should still be nil after clear")))

;; =============================================================================
;; Unit Tests - Forwarding Handler (no hive-mcp required)
;; =============================================================================

(deftest make-forwarding-handler-test
  (testing "Forwarding handler is created correctly"
    (let [handler (#'dynamic/make-forwarding-handler "test_tool")]
      (is (fn? handler) "Should return a function"))))

;; =============================================================================
;; Integration Tests - Dynamic Loading (requires hive-mcp on port 7910)
;; =============================================================================

(defn hive-mcp-available?
  "Check if hive-mcp nREPL is available."
  []
  (try
    (dynamic/clear-cache!)
    (hive/init!)
    (dynamic/tools-loaded?)
    (catch Exception _ false)))

(deftest dynamic-loading-integration-test
  (when (hive-mcp-available?)
    (testing "Dynamic tool loading from hive-mcp"
      (let [tools (hive/get-tools)]
        (is (> (count tools) 10)
            "Should load tools from hive-mcp")

        (testing "All tools have required structure"
          (doseq [tool tools]
            (is (map? tool) "Tool must be a map")
            (is (contains? tool :spec) "Missing :spec")
            (is (contains? tool :handler) "Missing :handler")
            (is (fn? (:handler tool)) "Handler must be function")))

        (testing "All specs have required keys"
          (doseq [{:keys [spec]} tools]
            (is (string? (:name spec)) "Missing :name")
            (is (string? (:description spec)) "Missing :description")
            (is (map? (:schema spec)) "Missing :schema")))

        (testing "No duplicate tool names"
          (let [names (map #(get-in % [:spec :name]) tools)
                freq (frequencies names)
                dups (filter #(> (val %) 1) freq)]
            (is (empty? dups)
                (str "Duplicate tools: " (keys dups)))))))))

(deftest empty-tools-when-not-initialized-test
  (testing "get-tools returns empty when not initialized"
    (dynamic/clear-cache!)
    (is (= [] (hive/get-tools))
        "Should return empty vector when cache is nil")))
