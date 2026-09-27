(ns bb-mcp.sense-test
  "The sixth-sense receptor against a stub transport and a stub sense feed.
   No nREPL, no hive JVM, no stdout: every effect is a collaborator."
  (:require [bb-mcp.core :as core]
            [bb-mcp.host.port :as hp]
            [bb-mcp.protocol :as proto]
            [bb-mcp.sense.core :as sense]
            [bb-mcp.sense.receptor :as receptor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;;; ===========================================================================
;;; Fixtures
;;; ===========================================================================

(def ^:private ask
  {:sense/id "s-1" :sense/class :sense/ask :sense/agent "ling-7"
   :sense/project "hive" :sense/parent "coordinator"
   :sense/text "ling-7 asks: may I touch core.clj?" :sense/at 1790000000000})

(def ^:private done
  {:sense/id "s-2" :sense/class :sense/completed :sense/agent "ling-8"
   :sense/project "hive-agent" :sense/text "ling-8 completed" :sense/at "2026-09-26T23:00:00Z"})

(defn- recording-transport
  "A Transport that records every written message in `outbox`."
  [outbox]
  (reify proto/Transport
    (read-msg [_] nil)
    (write-msg [_ m] (swap! outbox conj m))))

(defn- scripted-feed
  "A SenseFeed answering each drain with the next outcome of `script`, then
   an empty :ok at the last cursor forever. Records the cursors it was asked."
  [script asked]
  (let [q (atom script)]
    (reify receptor/SenseFeed
      (drain! [_ cursor _]
        (swap! asked conj cursor)
        (if-let [o (first @q)]
          (do (swap! q rest) o)
          {:outcome :ok :senses [] :cursor cursor})))))

(defn- run-script
  "Run the receptor loop over `script` synchronously, stopping after `turns`
   drains. Returns {:sent [...] :asked [...] :logs [...] :naps [...]}."
  [script turns & {:keys [policy]}]
  (let [outbox (atom []) asked (atom []) logs (atom []) naps (atom [])
        running (atom true)
        feed (scripted-feed script asked)
        counted (reify receptor/SenseFeed
                  (drain! [_ c r]
                    (when (>= (inc (count @asked)) turns) (reset! running false))
                    (receptor/drain! feed c r)))]
    (receptor/run-loop! {:feed counted
                         :transport (recording-transport outbox)
                         :receptor-spec {:receptor/parent "coordinator"}
                         :policy (or policy (assoc sense/default-policy :prime? false))
                         :log-fn #(swap! logs conj %)
                         :sleep-fn #(swap! naps conj %)
                         :running running})
    {:sent @outbox :asked @asked :logs @logs :naps @naps}))

;;; ===========================================================================
;;; Capability in initialize
;;; ===========================================================================

(deftest channel-receptor-declares-the-capability
  (let [res (proto/initialize-response 1 (receptor/capabilities (receptor/channel-receptor)))]
    (testing "claude/channel is declared under experimental"
      (is (= {} (get-in res [:result :capabilities :experimental "claude/channel"]))))
    (testing "the base capabilities survive the merge"
      (is (= {:listChanged false} (get-in res [:result :capabilities :tools]))))
    (testing "it survives the JSON codec with its slash intact"
      (let [back (hp/json-decode (hp/json-encode res))]
        (is (contains? (get-in back [:result :capabilities :experimental]) :claude/channel))))))

(deftest null-receptor-declares-nothing
  (is (= (proto/initialize-response 1)
         (proto/initialize-response 1 (receptor/capabilities (receptor/null-receptor))))))

(deftest selection-follows-BB_MCP_CHANNELS
  (testing "on by default"
    (is (= sense/channel-capability (receptor/capabilities (receptor/select {})))))
  (testing "explicitly off"
    (doseq [v ["0" "false" "off" "NO"]]
      (is (= {} (receptor/capabilities (receptor/select {"BB_MCP_CHANNELS" v}))) v))))

(deftest run-server-advertises-the-chosen-receptor
  (testing "the initialize result carries the receptor's capability; the receptor is armed only after the handshake"
    (let [inbox (atom [{:jsonrpc "2.0" :id 1 :method "initialize"}
                       {:jsonrpc "2.0" :method "notifications/initialized"}])
          outbox (atom [])
          events (atom [])
          r (reify receptor/Receptor
              (capabilities [_] sense/channel-capability)
              (arm! [this _] (swap! events conj [:arm (count @outbox)]) this)
              (disarm! [this] (swap! events conj :disarm) this))
          t (reify proto/Transport
              (read-msg [_] (when-let [m (first @inbox)] (swap! inbox rest) m))
              (write-msg [_ m] (swap! outbox conj m)))]
      (core/run-server t {:select-receptor (constantly r)})
      (is (= [(proto/initialize-response 1 sense/channel-capability)] @outbox))
      (is (= [[:arm 1] :disarm] @events)
          "armed once the initialize result is out, disarmed at end of input"))))

;;; ===========================================================================
;;; Pure: sense -> notification
;;; ===========================================================================

(deftest one-sense-one-notification-shape
  (let [n (sense/sense->notification ask)]
    (is (= "2.0" (:jsonrpc n)))
    (is (= "notifications/claude/channel" (:method n)))
    (is (not (contains? n :id)) "a notification carries no id")
    (is (= "ling-7 asks: may I touch core.clj?" (get-in n [:params :content])))
    (is (= {:agent "ling-7" :sense_class "ask" :project "hive"
            :parent "coordinator" :sense_id "s-1"}
           (get-in n [:params :meta])))))

(deftest meta-keys-are-letters-digits-underscore
  (doseq [s [ask done {} {:sense/class :sense/error :sense/agent nil :sense/project ""}]]
    (let [m (get-in (sense/sense->notification s) [:params :meta])]
      (is (every? sense/meta-key? (keys m)) (pr-str m))
      (is (every? #(re-matches #"[A-Za-z0-9_]+" (name %)) (keys m)))
      (is (every? string? (vals m)) "meta values are strings")
      (is (not-any? str/blank? (vals m)) "blank values are dropped"))))

(deftest meta-key-predicate
  (is (sense/meta-key? :sense_class))
  (is (sense/meta-key? "agent1"))
  (is (not (sense/meta-key? :sense-class)))
  (is (not (sense/meta-key? :sense/class)))
  (is (not (sense/meta-key? "a b"))))

(deftest a-textless-sense-still-says-something
  (is (= "blocked from ling-3"
         (get-in (sense/sense->notification {:sense/class :sense/blocked :sense/agent "ling-3"})
                 [:params :content]))))

;;; ===========================================================================
;;; Pure: drain reply
;;; ===========================================================================

(defn- nrepl-says [v] {:error? false :result (pr-str (pr-str v))})

(deftest drain-reply-reading
  (is (= {:outcome :ok :senses [ask] :cursor 4}
         (sense/read-drain-reply (nrepl-says {:senses [ask] :cursor 4}))))
  (is (= {:outcome :absent}
         (sense/read-drain-reply (nrepl-says {:sense-feed/absent true}))))
  (is (= :failed (:outcome (sense/read-drain-reply (nrepl-says {:sense-feed/error "boom"})))))
  (is (= :failed (:outcome (sense/read-drain-reply {:error? true :result "Connection refused"}))))
  (is (= :failed (:outcome (sense/read-drain-reply {:error? false :result "{:unbalanced"}))))
  (is (= :failed (:outcome (sense/read-drain-reply (nrepl-says [:not :a :map]))))))

(deftest drain-form-quotes-its-inputs
  (let [code (sense/drain-form 42 {:receptor/parent "coordinator"})]
    (is (str/includes? code "hive-agent.sixth-sense.api/drain"))
    (is (str/includes? code "(quote 42)"))
    (is (str/includes? code "(quote #:receptor{:parent \"coordinator\"})")
        "receptor is data in the JVM, never evaluated")))

(deftest nrepl-feed-drives-the-eval-fn
  (let [seen (atom nil)
        feed (receptor/nrepl-feed {:eval-fn (fn [req] (reset! seen req) (nrepl-says {:senses [] :cursor 9}))
                                   :port-fn (constantly 7910)})]
    (is (= {:outcome :ok :senses [] :cursor 9} (receptor/drain! feed 3 {:receptor/parent "c"})))
    (is (= 7910 (:port @seen)))
    (is (str/includes? (:code @seen) "(quote 3)")))
  (testing "a thrown transport folds into :failed"
    (is (= :failed (:outcome (receptor/drain! (receptor/nrepl-feed {:eval-fn (fn [_] (throw (ex-info "down" {})))
                                                                    :port-fn (constantly 1)})
                                              0 {}))))))

;;; ===========================================================================
;;; Loop: one notification per sense, cursor, priming, restart
;;; ===========================================================================

(deftest loop-writes-one-notification-per-sense
  (let [{:keys [sent asked]} (run-script [{:outcome :ok :senses [ask done] :cursor 2}
                                          {:outcome :ok :senses [] :cursor 2}
                                          {:outcome :ok :senses [ask] :cursor 3}]
                                         3)]
    (is (= [(sense/sense->notification ask)
            (sense/sense->notification done)
            (sense/sense->notification ask)]
           sent))
    (is (= [0 2 2] asked) "each drain resumes from the cursor the last one returned")))

(deftest priming-skips-the-backlog
  (let [{:keys [sent asked]} (run-script [{:outcome :ok :senses [ask done] :cursor 2}
                                          {:outcome :ok :senses [ask] :cursor 3}]
                                         2 :policy sense/default-policy)]
    (is (= [(sense/sense->notification ask)] sent)
        "senses from before the session are not replayed")
    (is (= [0 2] asked))))

(deftest a-hive-restart-rereads-from-zero
  (let [{:keys [sent asked logs]} (run-script [{:outcome :ok :senses [] :cursor 50}
                                               {:outcome :ok :senses [] :cursor 1}
                                               {:outcome :ok :senses [done] :cursor 1}]
                                              3)]
    (is (= [0 50 0] asked) "a cursor that went backwards means a new sense log")
    (is (= [(sense/sense->notification done)] sent))
    (is (some #(str/includes? % "restarted") logs))))

;;; ===========================================================================
;;; Loop: absent drain fn, unreachable hive, backoff
;;; ===========================================================================

(deftest absent-drain-backs-off-and-logs-once
  (let [{:keys [sent logs naps]}
        (run-script (concat (repeat 6 {:outcome :absent})
                            [{:outcome :ok :senses [] :cursor 0}])
                    7)
        slept (fn [n] (reduce + (take n naps)))]
    (is (empty? sent))
    (is (= 1 (count (filter #(str/includes? % "not loaded") logs))) "logged once, not every poll")
    (is (some #(str/includes? % "answering again") logs) "recovery is logged")
    (testing "delays double from the base rate and cap at :max-ms"
      (let [{:keys [state sleep-ms]}
            (reduce (fn [{:keys [state]} _] (sense/advance state {:outcome :absent} sense/default-policy))
                    {:state (sense/initial-state sense/default-policy)}
                    (range 10))]
        (is (= 30000 sleep-ms))
        (is (= 30000 (:delay-ms state)))))
    (is (pos? (slept 1)))))

(deftest backoff-sequence
  (let [p sense/default-policy
        delays (->> (iterate (fn [[s _]] (let [{:keys [state sleep-ms]} (sense/advance s {:outcome :failed :detail "x"} p)]
                                           [state sleep-ms]))
                             [(sense/initial-state p) nil])
                    rest (map second) (take 7))]
    (is (= [2000 4000 8000 16000 30000 30000 30000] delays)))
  (testing "one good poll returns to the base rate"
    (let [s (assoc (sense/initial-state sense/default-policy) :delay-ms 30000 :noted :failed :primed? true)]
      (is (= 1000 (:sleep-ms (sense/advance s {:outcome :ok :senses [] :cursor 0} sense/default-policy)))))))

(deftest a-closed-transport-stops-the-loop
  (let [running (atom true) logs (atom [])
        state (receptor/run-loop!
               {:feed (reify receptor/SenseFeed
                        (drain! [_ c _] {:outcome :ok :senses [ask] :cursor (inc c)}))
                :transport (reify proto/Transport
                             (read-msg [_] nil)
                             (write-msg [_ _] (throw (ex-info "stdout closed" {}))))
                :receptor-spec {}
                :policy (assoc sense/default-policy :prime? false)
                :log-fn #(swap! logs conj %)
                :sleep-fn (fn [_])
                :running running})]
    (is (false? @running))
    (is (= 1 (:cursor state)))
    (is (some #(str/includes? % "transport closed") @logs))))

;;; ===========================================================================
;;; Concurrency: responses and notifications never interleave on the stream
;;; ===========================================================================

(defn- char-by-char-transport
  "A Transport whose write is deliberately NOT atomic: it appends the encoded
   line one character at a time, yielding between characters. Unserialised,
   two writers interleave; serialised, every line comes out whole."
  [sb]
  (reify proto/Transport
    (read-msg [_] nil)
    (write-msg [_ m]
      (doseq [c (str (hp/json-encode m) "\n")]
        (locking sb (.append ^StringBuilder sb c))
        (Thread/yield)))))

(defn- hammer
  "Write `n` responses and `n` notifications from two threads at once."
  [t n]
  (let [a (future (dotimes [i n] (proto/write-msg t (proto/json-rpc-response i {:ok i}))))
        b (future (dotimes [i n] (proto/write-msg t (sense/sense->notification (assoc ask :sense/id (str "s" i))))))]
    @a @b))

(deftest serialized-writes-never-interleave
  (let [sb (StringBuilder.)
        n 60]
    (hammer (proto/serialized (char-by-char-transport sb)) n)
    (let [lines (str/split-lines (str sb))
          parsed (map hp/json-decode lines)]
      (is (= (* 2 n) (count lines)))
      (is (every? map? parsed) "every line is one whole JSON message")
      (is (= n (count (filter :id parsed))))
      (is (= n (count (filter #(= sense/channel-method (:method %)) parsed)))))))

(deftest armed-receptor-and-server-share-one-stream
  (testing "a live channel receptor writing on its own thread while the server answers requests"
    (let [sb (StringBuilder.)
          t (proto/serialized (char-by-char-transport sb))
          senses (mapv #(assoc ask :sense/id (str "s" %)) (range 30))
          fed (atom false)
          r (receptor/channel-receptor
             {:feed (reify receptor/SenseFeed
                      (drain! [_ c _]
                        (if (compare-and-set! fed false true)
                          {:outcome :ok :senses senses :cursor 30}
                          {:outcome :ok :senses [] :cursor c})))
              :policy (assoc sense/default-policy :prime? false :base-ms 5)
              :log-fn (fn [_])})]
      (receptor/arm! r t)
      (dotimes [i 30] (proto/write-msg t (proto/json-rpc-response i {:ok i})))
      (loop [k 0]
        (when (and (< k 200)
                   (< (count (filter #(str/includes? % "claude/channel") (str/split-lines (str sb)))) 30))
          (Thread/sleep 10) (recur (inc k))))
      (receptor/disarm! r)
      (let [parsed (map hp/json-decode (str/split-lines (str sb)))]
        (is (every? map? parsed))
        (is (= 30 (count (filter :id parsed))))
        (is (= 30 (count (filter #(= sense/channel-method (:method %)) parsed))))))))

;;; ===========================================================================
;;; Environment
;;; ===========================================================================

(deftest receptor-spec-from-env
  (is (= {:receptor/parent "coordinator"} (sense/receptor-spec {})))
  (is (= {:receptor/parent "ling-1"} (sense/receptor-spec {"CLAUDE_SWARM_SLAVE_ID" "ling-1"})))
  (is (= {:receptor/parent "me" :receptor/projects #{"hive" "bb-mcp"}
          :receptor/classes #{:sense/ask :sense/blocked}}
         (sense/receptor-spec {"BB_MCP_SENSE_PARENT" "me"
                               "BB_MCP_SENSE_PROJECTS" "hive, bb-mcp"
                               "BB_MCP_SENSE_CLASSES" "ask,blocked"}))))

(deftest policy-from-env
  (is (= sense/default-policy (sense/policy-from {})))
  (is (= 250 (:base-ms (sense/policy-from {"BB_MCP_SENSE_POLL_MS" "250"}))))
  (is (false? (:prime? (sense/policy-from {"BB_MCP_SENSE_REPLAY" "1"})))))
