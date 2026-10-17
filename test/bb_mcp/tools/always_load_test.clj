(ns bb-mcp.tools.always-load-test
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.always-load :as al]
            [clojure.test :refer [deftest is testing]]))

(deftest names-reads-a-comma-list
  (is (= #{"code" "memory"} (al/names " code, memory ,")))
  (is (= #{} (al/names nil)))
  (is (= #{} (al/names "  "))))

(deftest mark-flags-only-the-named-tools
  (let [specs [{:name "code"} {:name "bash"}]]
    (is (= [{:name "code" :always-load? true} {:name "bash"}] (vec (al/mark specs #{"code"}))))
    (is (identical? specs (al/mark specs #{})) "nothing named leaves the specs untouched")))

(deftest tools-list-carries-the-always-load-meta
  (testing "a marked spec advertises _meta anthropic/alwaysLoad; an unmarked one carries no _meta"
    (let [tools (get-in (proto/tools-list-response
                         1 [{:name "code" :description "d" :schema {} :always-load? true}
                            {:name "bash" :description "d" :schema {}}])
                        [:result :tools])]
      (is (= {"anthropic/alwaysLoad" true} (:_meta (first tools))))
      (is (not (contains? (second tools) :_meta)))
      (is (not (contains? (first tools) :always-load?)) "the internal flag never reaches the wire"))))
