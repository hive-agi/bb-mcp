(ns bb-mcp.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [bb-mcp.core :as core]
            [bb-mcp.tool :as tool]
            [bb-mcp.protocol :as proto]))

(def ^:private t1 (tool/native-tool {:name "t1"} (fn [_] {:result "1" :error? false})))
(def ^:private t2 (tool/native-tool {:name "t2"} (fn [_] {:result "2" :error? false})))

;; ── toolsource: get-tools aggregates over an ordered source list ──────────────

(deftest get-tools-composition-test
  (testing "tools are aggregated across sources in order (native-first preserved)"
    (is (= [t1 t2]
           (core/get-tools [(constantly [t1]) (constantly [t2])])))))

(deftest get-tools-empty-source-test
  (testing "a source yielding no tools contributes nothing"
    (is (= [t1]
           (core/get-tools [(constantly []) (constantly [t1])])))))

;; ── handle-method: destratified tools/call (invoke-safely + call-tool) ────────

(deftest invoke-safely-test
  (testing "a well-behaved handler passes through unchanged"
    (is (= {:result "ok" :error? false}
           (#'core/invoke-safely
            (tool/native-tool {:name "x"} (fn [_] {:result "ok" :error? false}))
            {}))))
  (testing "a thrown exception folds into the {:result :error?} channel"
    (is (= {:result "Error: boom" :error? true}
           (#'core/invoke-safely
            (tool/native-tool {:name "x"} (fn [_] (throw (ex-info "boom" {}))))
            {})))))

(deftest call-tool-happy-test
  (testing "resolve -> invoke -> build response for a found tool"
    (with-redefs [core/get-tools
                  (constantly [(tool/native-tool {:name "echo"}
                                                 (fn [a] {:result (:x a) :error? false}))])]
      (is (= {:jsonrpc "2.0"
              :id 7
              :result {:content [{:type "text" :text "1"}] :isError false}}
             (#'core/call-tool 7 "echo" {:x 1}))))))

(deftest call-tool-throwing-test
  (testing "a throwing handler yields an isError response starting with Error:"
    (with-redefs [core/get-tools
                  (constantly [(tool/native-tool {:name "boom"}
                                                 (fn [_] (throw (ex-info "boom" {}))))])]
      (let [resp (#'core/call-tool 5 "boom" {})]
        (is (true? (get-in resp [:result :isError])))
        (is (str/starts-with? (get-in resp [:result :content 0 :text]) "Error: "))))))

(deftest unknown-tool-test
  (testing "an unresolved tool routes through the multimethod to a -32601 error"
    (with-redefs [core/get-tools (constantly [])]
      (is (= (proto/json-rpc-error 9 -32601 "Unknown tool: nope")
             (core/handle-method {:method "tools/call" :id 9 :params {:name "nope"}}))))))

(deftest dispatch-default-test
  (testing "known method dispatches; an id-less unknown notification is ignored"
    (is (= (proto/json-rpc-response 3 {:prompts []})
           (core/handle-method {:method "prompts/list" :id 3})))
    (is (nil? (core/handle-method {:method "whatever"})))))

;; ── transport: run-server drives an injected Transport (no stdio, no hive) ─────

(deftest run-server-loop-test
  (testing "read -> dispatch -> write loop over an in-memory transport, ignoring id-less notifications"
    (let [inbox  (atom [{:jsonrpc "2.0" :id 1 :method "initialize"}
                        {:jsonrpc "2.0" :id 2 :method "prompts/list"}
                        {:jsonrpc "2.0" :method "notifications/x"}])
          outbox (atom [])
          t      (reify proto/Transport
                   (read-msg [_]
                     (when (seq @inbox)
                       (let [m (first @inbox)] (swap! inbox rest) m)))
                   (write-msg [_ m] (swap! outbox conj m)))]
      (core/run-server t)
      (is (= [(proto/initialize-response 1)
              (proto/json-rpc-response 2 {:prompts []})]
             @outbox)))))

(deftest stdio-transport-roundtrip-test
  (testing "StdioTransport reads a newline-delimited message and writes JSON + newline"
    (is (= {:method "initialized"}
           (with-in-str "{\"method\":\"initialized\"}\n"
             (proto/read-msg (proto/stdio-transport)))))
    (is (= "{\"a\":1}"
           (str/trim (with-out-str
                       (proto/write-msg (proto/stdio-transport) {:a 1})))))))
