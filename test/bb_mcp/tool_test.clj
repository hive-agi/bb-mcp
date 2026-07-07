(ns bb-mcp.tool-test
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.tool :as tool]))

(deftest native-tool-test
  (testing "NativeTool exposes its spec, is never deprecated, and invokes its handler"
    (let [spec {:name "echo" :description "d" :schema {:type "object"}}
          t    (tool/native-tool spec (fn [args] {:result (:x args) :error? false}))]
      (is (= "echo" (tool/tool-name t)))
      (is (= spec (tool/tool-spec t)))
      (is (false? (tool/deprecated? t)))
      (is (= {:result 42 :error? false} (tool/invoke t {:x 42}))))))

(deftest forwarding-tool-test
  (testing "ForwardingTool honors its deprecated flag and delegates invoke"
    (let [spec {:name "fwd" :description "d" :schema {:type "object"}}
          t    (tool/forwarding-tool spec (fn [_] {:result "ok" :error? false}) true)]
      (is (= "fwd" (tool/tool-name t)))
      (is (= spec (tool/tool-spec t)))
      (is (true? (tool/deprecated? t)))
      (is (= {:result "ok" :error? false} (tool/invoke t {}))))))

(deftest deprecated-coercion-test
  (testing "forwarding-tool coerces a nil deprecated flag to false"
    (is (false? (tool/deprecated? (tool/forwarding-tool {:name "x"} identity nil))))))
