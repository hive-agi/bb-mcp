(ns bb-mcp.concurrent-test
  "run-server answers each tools/call from its own worker: a slow call no
   longer holds a fast one, writes stay whole, cancellation and the input-end
   drain hold."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [bb-mcp.core :as core]
            [bb-mcp.tool :as tool]
            [bb-mcp.protocol :as proto]
            [bb-mcp.host.port :as hp]
            [bb-mcp.inflight :as inflight]
            [bb-mcp.sense.receptor :as receptor]))

(def ^:private no-receptor {:select-receptor (fn [_] (receptor/null-receptor))})

(defn- call [id name & [args]]
  {:jsonrpc "2.0" :id id :method "tools/call"
   :params {:name name :arguments (or args {})}})

(defn- queue-transport
  "A transport reading `msgs` in order (sleeping `gap-ms` before each read)
   and collecting every write into `outbox`, calling `on-write` too."
  [msgs outbox & {:keys [gap-ms on-write] :or {gap-ms 0 on-write (fn [_])}}]
  (let [inbox (atom msgs)]
    (reify proto/Transport
      (read-msg [_]
        (when (pos? gap-ms) (Thread/sleep gap-ms))
        (when (seq @inbox)
          (let [m (first @inbox)] (swap! inbox rest) m)))
      (write-msg [_ m] (on-write m) (swap! outbox conj m)))))

(def ^:private tools
  [(tool/native-tool {:name "slow"}
                     (fn [_] (Thread/sleep 2000) {:result "slow" :error? false}))
   (tool/native-tool {:name "fast"}
                     (fn [_] {:result "fast" :error? false}))
   (tool/native-tool {:name "big"}
                     (fn [{:keys [n]}] {:result (apply str (repeat 4000 (str n))) :error? false}))])

(defmacro ^:private with-tools [& body]
  `(binding [core/*drain-fn* (constantly "")]
     (with-redefs [core/get-tools (constantly tools)]
       ~@body)))

(deftest a-slow-call-does-not-hold-a-fast-one
  (testing "call A sleeps 2 s, call B is instant: B is answered first"
    (with-tools
      (let [outbox  (atom [])
            t0      (System/currentTimeMillis)
            fast-at (promise)]
        (core/run-server (queue-transport [(call 1 "slow") (call 2 "fast")] outbox
                                          :on-write #(when (= 2 (:id %))
                                                       (deliver fast-at (- (System/currentTimeMillis) t0))))
                         (assoc no-receptor :drain-ms 10000))
        (is (= [2 1] (mapv :id @outbox)) "B before A, both answered")
        (is (< (deref fast-at 0 Long/MAX_VALUE) 1000) "B did not wait for A")
        (is (= "fast" (get-in (first @outbox) [:result :content 0 :text])))
        (is (= "slow" (get-in (second @outbox) [:result :content 0 :text])))))))

(deftest handshake-and-list-stay-inline-and-ordered
  (with-tools
    (let [outbox (atom [])]
      (core/run-server (queue-transport [{:jsonrpc "2.0" :id 1 :method "initialize"}
                                         {:jsonrpc "2.0" :method "notifications/initialized"}
                                         {:jsonrpc "2.0" :id 2 :method "tools/list"}
                                         {:jsonrpc "2.0" :id 3 :method "prompts/list"}]
                                        outbox)
                       no-receptor)
      (is (= [1 2 3] (mapv :id @outbox))))))

(deftest concurrent-writes-are-whole-json-lines
  (testing "many calls answering at once over real stdio framing: every line parses"
    (with-tools
      (let [n   16
            out (java.io.StringWriter.)
            _   (binding [*out* out]
                  (let [inner (proto/stdio-transport)
                        inbox (atom (mapv #(call % "big" {:n (mod % 10)}) (range n)))]
                    (core/run-server
                     (reify proto/Transport
                       (read-msg [_]
                         (when (seq @inbox)
                           (let [m (first @inbox)] (swap! inbox rest) m)))
                       (write-msg [_ m] (proto/write-msg inner m)))
                     (assoc no-receptor :drain-ms 20000))))
            lines  (remove str/blank? (str/split-lines (str out)))
            parsed (mapv hp/json-decode lines)]
        (is (= n (count lines)))
        (is (= (set (range n)) (set (map :id parsed))))
        (is (every? #(= 4000 (count (get-in % [:result :content 0 :text]))) parsed))))))

(deftest a-cancelled-call-is-never-answered
  (with-tools
    (let [outbox (atom [])]
      (core/run-server (queue-transport [(call 1 "slow")
                                         {:jsonrpc "2.0" :method "notifications/cancelled"
                                          :params {:requestId 1 :reason "user"}}
                                         (call 2 "fast")]
                                        outbox :gap-ms 50)
                       (assoc no-receptor :drain-ms 5000))
      (is (= [2] (mapv :id @outbox))))))

(deftest input-end-drains-then-abandons
  (with-tools
    (testing "a call that finishes within the drain window is answered"
      (let [outbox (atom [])]
        (core/run-server (queue-transport [(call 1 "slow")] outbox)
                         (assoc no-receptor :drain-ms 10000))
        (is (= [1] (mapv :id @outbox)))))
    (testing "one that outlives it is abandoned unanswered, and the server returns on time"
      (let [outbox (atom [])
            t0     (System/currentTimeMillis)]
        (binding [*err* (java.io.StringWriter.)]
          (core/run-server (queue-transport [(call 1 "slow")] outbox)
                           (assoc no-receptor :drain-ms 200)))
        (is (< (- (System/currentTimeMillis) t0) 1500))
        (Thread/sleep 2200)
        (is (= [] @outbox) "no late write after abandonment")))))

(deftest workers-zero-runs-inline
  (with-tools
    (let [outbox (atom [])]
      (core/run-server (queue-transport [(call 1 "slow") (call 2 "fast")] outbox)
                       (assoc no-receptor :workers 0))
      (is (= [1 2] (mapv :id @outbox))))))

(deftest a-throwing-worker-still-answers
  (let [p   (inflight/pool 2)
        out (promise)]
    (inflight/submit! p 9 #(throw (ex-info "boom" {}))
                      (fn [e] {:id 9 :error (ex-message e)})
                      #(deliver out %))
    (is (= {:id 9 :error "boom"} (deref out 2000 :timeout)))
    (is (:drained? (inflight/drain! p 2000)))
    (is (empty? (inflight/in-flight p)))))

(deftest cancel-of-unknown-or-finished-is-false
  (let [p   (inflight/pool 1)
        out (promise)]
    (is (false? (inflight/cancel! p 42)))
    (inflight/submit! p 1 (constantly {:id 1}) identity #(deliver out %))
    (deref out 2000 nil)
    (Thread/sleep 50)
    (is (false? (inflight/cancel! p 1)))
    (inflight/drain! p 1000)))
