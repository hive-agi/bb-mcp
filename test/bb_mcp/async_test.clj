(ns bb-mcp.async-test
  "Native tools (bash, clojure_eval) queue on async:true instead of holding
   the agent loop, and their results ride out on a later call."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [bb-mcp.async :as async]
            [bb-mcp.core :as core]
            [bb-mcp.guard :as guard]
            [bb-mcp.tool :as tool]))

(use-fixtures :each (fn [t] (async/drain!) (t) (async/drain!)))

(defn- await-drain
  "Drain until a result arrives or the bound passes; a condition wait, not a
   fixed sleep."
  [ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [block (async/drain!)]
        (if (or (seq block) (> (System/currentTimeMillis) deadline))
          block
          (do (Thread/yield) (recur)))))))

(defn- block-results [block]
  (-> block
      (str/split #"---TOOLRESULT---\n")
      second
      (str/split #"\n---/TOOLRESULT---")
      first
      edn/read-string
      :results))

(deftest requested?-reads-the-flag
  (is (true? (async/requested? {:async true})))
  (is (true? (async/requested? {"async" "true"})))
  (is (false? (async/requested? {:async false})))
  (is (false? (async/requested? {})))
  (is (false? (async/requested? "oops")) "non-map arguments are not a request"))

(deftest with-async-property-declares-once
  (let [spec {:name "x" :schema {:type "object" :properties {:a {:type "string"}}}}]
    (is (= "boolean" (get-in (async/with-async-property spec) [:schema :properties :async :type])))
    (let [own (assoc-in spec [:schema :properties :async] {:type "boolean" :description "own"})]
      (is (= own (async/with-async-property own)) "a tool's own async wins"))))

(deftest submit-acks-at-once-and-delivers-once
  (let [release (promise)
        t0      (System/currentTimeMillis)
        ack     (async/submit! "bash" (fn [] (deref release 5000 nil)
                                        {:result "done" :error? false}))]
    (is (< (- (System/currentTimeMillis) t0) 1000) "the ack does not wait for the work")
    (is (:queued (edn/read-string ack)))
    (is (= "" (async/drain!)) "nothing to deliver while the work runs")
    (deliver release true)
    (let [[r] (block-results (await-drain 5000))]
      (is (= "done" (:result r)))
      (is (= :completed (:status r))))
    (is (= "" (async/drain!)) "a result is delivered once")))

(deftest a-throwing-task-still-reports
  (async/submit! "bash" (fn [] (throw (Error. "died"))))
  (let [[r] (block-results (await-drain 5000))]
    (is (= :error (:status r)))
    (is (str/includes? (:result r) "died"))))

(defn- slow-native [release]
  (tool/native-tool (async/with-async-property {:name "slow" :schema {:type "object" :properties {}}})
                    (fn [_] (deref release 5000 nil) {:result "slow-done" :error? false})))

(deftest a-native-call-with-async-returns-an-ack
  (let [release (promise)]
    (with-redefs [core/get-tools          (constantly [(slow-native release)])
                  core/native-tool-names  #{"slow"}
                  guard/decide            (fn [& _] {:guard/verdict :allow})]
      (binding [core/*drain-fn* (constantly "")]
        (let [t0   (System/currentTimeMillis)
              resp (core/handle-method {:method "tools/call" :id 1
                                        :params {:name "slow" :arguments {:async true}}})
              text (get-in resp [:result :content 0 :text])]
          (is (< (- (System/currentTimeMillis) t0) 2000) "the loop is released at once")
          (is (str/includes? text ":queued true"))
          (deliver release true)
          (let [[r] (block-results (await-drain 5000))]
            (is (= "slow-done" (:result r)))))))))

(deftest the-next-call-carries-the-result
  (let [release (promise)]
    (with-redefs [core/get-tools         (constantly [(slow-native release)
                                                      (tool/native-tool {:name "fast"}
                                                                        (fn [_] {:result "f" :error? false}))])
                  core/native-tool-names #{"slow" "fast"}
                  guard/decide           (fn [& _] {:guard/verdict :allow})]
      (binding [core/*drain-fn* (constantly "")]
        (let [ack (get-in (core/handle-method {:method "tools/call" :id 1
                                               :params {:name "slow" :arguments {:async true}}})
                          [:result :content 0 :text])]
          (is (not (str/includes? ack "slow-done")) "held on `release`, so not in the ack"))
        (deliver release true)
        ;; wait on the task finishing, not on a clock
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (while (and (seq (async/running-ids)) (< (System/currentTimeMillis) deadline))
            (Thread/yield)))
        (let [text (get-in (core/handle-method {:method "tools/call" :id 2
                                                :params {:name "fast" :arguments {}}})
                           [:result :content 0 :text])]
          (is (str/starts-with? text "f"))
          (is (str/includes? text "---TOOLRESULT---"))
          (is (str/includes? text "slow-done")))))))

(deftest a-denied-async-call-never-starts
  (let [ran? (atom false)]
    (with-redefs [core/get-tools         (constantly [(tool/native-tool {:name "risky"}
                                                                        (fn [_] (reset! ran? true)
                                                                          {:result "x" :error? false}))])
                  core/native-tool-names #{"risky"}
                  guard/decide           (fn [& _] {:guard/verdict :deny :guard/reason "no"})]
      (binding [core/*drain-fn* (constantly "")]
        (let [resp (core/handle-method {:method "tools/call" :id 1
                                        :params {:name "risky" :arguments {:async true}}})]
          (is (true? (get-in resp [:result :isError])))
          (is (empty? (async/running-ids)))
          (is (false? @ran?)))))))

(deftest a-forwarded-call-keeps-async-for-hive-mcp
  (testing "hive-mcp queues forwarded calls itself; this head must pass the flag through"
    (let [seen (atom nil)]
      (with-redefs [core/get-tools (constantly [(tool/forwarding-tool {:name "project"}
                                                                      (fn [a] (reset! seen a)
                                                                        {:result "ack" :error? false})
                                                                      false)])]
        (core/handle-method {:method "tools/call" :id 1
                             :params {:name "project" :arguments {:async true}}})
        (is (true? (:async @seen)))))))
