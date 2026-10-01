(ns bb-mcp.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [bb-mcp.core :as core]
            [bb-mcp.tool :as tool]
            [bb-mcp.protocol :as proto]
            [bb-mcp.sense.receptor :as receptor]))

(def ^:private t1 (tool/native-tool {:name "t1"} (fn [_] {:result "1" :error? false})))
(def ^:private t2 (tool/native-tool {:name "t2"} (fn [_] {:result "2" :error? false})))

(deftest get-agent-id-prefers-generic-client-env
  (testing "Dirge can identify its bb-mcp session without pretending to be a Claude swarm slave"
    (with-redefs [core/getenv (fn [k]
                                (case k
                                  "BB_MCP_CLIENT_ID" "dirge"
                                  "CLAUDE_SWARM_SLAVE_ID" "claude-slave"
                                  nil))]
      (is (= "dirge" (#'core/get-agent-id))))))

;; ── caller credential: carried from HIVE_AGENT_CREDENTIAL, never from args ───

(deftest with-caller-credential-carries-the-credential
  (testing "a set credential is added unchanged"
    (is (= {:command "ls" :_caller_credential "opaque.cred-1"}
           (core/with-caller-credential {:command "ls"} "opaque.cred-1"))))
  (testing "a model-supplied credential is overwritten by the environment's"
    (is (= "opaque.cred-1"
           (:_caller_credential
            (core/with-caller-credential {:_caller_credential "forged"} "opaque.cred-1"))))))

(deftest with-caller-credential-absent-when-unset-or-blank
  (doseq [credential [nil "" "   "]]
    (testing (str "credential " (pr-str credential) " leaves the key absent")
      (is (= {:command "ls"}
             (core/with-caller-credential {:command "ls"} credential)))
      (testing "and drops a model-supplied one"
        (is (not (contains? (core/with-caller-credential
                              {:command "ls" :_caller_credential "forged"}
                              credential)
                            :_caller_credential)))))))

(deftest inject-agent-context-reads-the-credential-from-the-environment
  (let [env-with (fn [credential]
                   (fn [k] (when (= k "HIVE_AGENT_CREDENTIAL") credential)))]
    (testing "set: stamped beside _caller_id"
      (with-redefs [core/getenv (env-with "opaque.cred-1")]
        (let [out (#'core/inject-agent-context {:_caller_credential "forged"})]
          (is (= "opaque.cred-1" (:_caller_credential out)))
          (is (string? (:_caller_id out))))))
    (testing "unset: no key, the rest as before"
      (with-redefs [core/getenv (env-with nil)]
        (is (not (contains? (#'core/inject-agent-context {:_caller_credential "forged"})
                            :_caller_credential)))))))

(deftest inject-agent-context-overwrites-a-model-supplied-caller-id
  (testing "the stamped _caller_id wins over one the model put in args"
    (with-redefs [core/getenv (constantly nil)]
      (let [out (#'core/inject-agent-context {:_caller_id "coordinator:forged"})]
        (is (not= "coordinator:forged" (:_caller_id out)))
        (is (str/starts-with? (:_caller_id out) "coordinator:"))))))

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

(deftest invoke-safely-abandons-a-tool-that-never-returns
  (testing "the loop is single-threaded, so one stuck tool must not hold it"
    (let [t0 (System/currentTimeMillis)
          {:keys [result error?]}
          (binding [core/*tool-ceiling-ms* 250]
            (#'core/invoke-safely
             (tool/native-tool {:name "stuck"}
                               (fn [_] (Thread/sleep 30000) {:result "never" :error? false}))
             {}))
          elapsed (- (System/currentTimeMillis) t0)]
      (is (true? error?))
      (is (str/includes? result "abandoned"))
      (is (< elapsed 5000) "returned on the ceiling, not on the tool"))))

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
      (core/run-server t {:select-receptor (fn [_init] (receptor/null-receptor))})
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

(deftest malformed-arguments-test
  (testing "an unknown tool with non-map arguments yields a clean -32601, no crash"
    (with-redefs [core/get-tools (constantly [])]
      (is (= (proto/json-rpc-error 1 -32601 "Unknown tool: nope")
             (core/handle-method {:method "tools/call" :id 1
                                  :params {:name "nope" :arguments "oops"}})))))
  (testing "a found tool with non-map arguments folds the assoc failure into an isError response"
    (with-redefs [core/get-tools
                  (constantly [(tool/native-tool {:name "echo"}
                                                 (fn [a] {:result (:x a) :error? false}))])]
      (is (true? (get-in (core/handle-method {:method "tools/call" :id 2
                                              :params {:name "echo" :arguments "oops"}})
                         [:result :isError]))))))