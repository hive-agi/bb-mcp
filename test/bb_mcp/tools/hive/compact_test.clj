(ns bb-mcp.tools.hive.compact-test
  "The compact schema mode is a pure projection of two schemas, so it is
   pinned without a hive-mcp: what is kept, what is dropped, what stays open,
   and when the tool is returned untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [bb-mcp.tools.hive.compact :as compact]))

(def ^:private full
  {:name "code"
   :description "Code intelligence."
   :schema {:type "object"
            :properties {"command" {:type "string"}
                         "file"    {:type "string"}
                         "qn"      {:type "string"}
                         "new-body" {:type "string"}}
            :required ["command"]}})

(def ^:private core
  {:type "object"
   :properties {"command" {:type "string"} "file" {:type "string"}}
   :required ["command"]})

(deftest mode-reads-the-environment-value
  (is (= "full" (compact/mode nil)))
  (is (= "full" (compact/mode "")))
  (is (= "full" (compact/mode "sometimes")))
  (is (= "compact" (compact/mode "compact")))
  (is (compact/compact? "compact"))
  (is (not (compact/compact? nil))))

(deftest compact-tool-keeps-core-drops-the-rest-and-stays-open
  (let [t (compact/compact-tool full core)]
    (testing "core properties survive, extension properties go"
      (is (= #{"command" "file"} (set (keys (get-in t [:schema :properties]))))))
    (testing "the schema stays open so the dropped parameters are still accepted"
      (is (true? (get-in t [:schema :additionalProperties]))))
    (testing "required and type are untouched"
      (is (= ["command"] (get-in t [:schema :required])))
      (is (= "object" (get-in t [:schema :type]))))
    (testing "the description says how many were dropped and where to find them"
      (is (re-find #"\(2 more\)" (:description t)))
      (is (re-find #"command='help'" (:description t)))
      (is (.startsWith ^String (:description t) "Code intelligence.")))))

(deftest compact-tool-is-identity-when-nothing-would-change
  (testing "no core schema known"
    (is (= full (compact/compact-tool full nil))))
  (testing "nothing to drop"
    (is (= full (compact/compact-tool full (:schema full)))))
  (testing "a tool with no properties at all"
    (let [bare {:name "bash" :description "b" :schema {:type "object" :properties {}}}]
      (is (= bare (compact/compact-tool bare core))))))
