(ns bb-mcp.tools.route-map-test
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.route-map :as rm]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private cloak
  {:name "cloak"
   :description (str "Sealing for hive memory: encrypt entries, hide KG edges. "
                     "Start with `cloak status`.")})

(deftest lead-sentence-stops-at-the-first-sentence
  (is (= "Sealing for hive memory: encrypt entries, hide KG edges."
         (rm/lead-sentence (:description cloak))))
  (is (= "One line" (rm/lead-sentence "One line\nsecond line")))
  (is (= "No period" (rm/lead-sentence "  No period  ")))
  (is (nil? (rm/lead-sentence nil)))
  (is (nil? (rm/lead-sentence "   "))))

(deftest lead-sentence-is-clipped-at-a-word
  (let [long-text (str/join " " (repeat 80 "word"))
        lead      (rm/lead-sentence long-text)]
    (is (<= (count lead) (inc rm/max-lead-chars)))
    (is (str/ends-with? lead "word…"))))

(deftest one-line-per-tool-in-order
  (is (= ["- cloak: Sealing for hive memory: encrypt entries, hide KG edges."
          "- bash"
          "- memory: Consolidated memory operations."]
         (vec (rm/route-lines [cloak
                               {:name "bash" :description ""}
                               {:name "" :description "nameless is skipped"}
                               {:name "memory" :description "Consolidated memory operations. Commands: add."}])))))

(deftest instructions-lead-with-the-header
  (let [text (rm/instructions [cloak])]
    (is (str/starts-with? text rm/header))
    (is (str/includes? text "\n- cloak: Sealing for hive memory")))
  (is (nil? (rm/instructions [])) "no tools, no instructions"))

(deftest only-off-turns-them-off
  (is (rm/enabled? nil))
  (is (rm/enabled? "on"))
  (is (not (rm/enabled? " OFF "))))

(deftest initialize-carries-instructions-only-when-given
  (testing "given text reaches the wire as :instructions"
    (is (= "route" (get-in (proto/initialize-response 1 {} "route") [:result :instructions]))))
  (testing "nil leaves the key out"
    (is (not (contains? (:result (proto/initialize-response 1 {} nil)) :instructions)))
    (is (not (contains? (:result (proto/initialize-response 1)) :instructions)))))
