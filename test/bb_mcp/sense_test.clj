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
                    (if (< (count @asked) turns)
                      (receptor/drain! feed c r)
                      ;; one drain past the script: stop, deliver nothing
                      (do (reset! running false) {:outcome :ok :senses [] :cursor c}))))]
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

(deftest selection-is-opt-in
  (testing "off by default: no env, a plain initialize"
    (is (= {} (receptor/capabilities (receptor/select {} {:method "initialize" :params {}}))))
    (is (= {} (receptor/capabilities (receptor/select {} nil)))))
  (testing "BB_MCP_CHANNELS turns it on"
    (doseq [v ["1" "true" "ON" " yes "]]
      (is (= sense/channel-capability
             (receptor/capabilities (receptor/select {"BB_MCP_CHANNELS" v} {:method "initialize"})))
          v)))
  (testing "anything else leaves it off"
    (doseq [v ["0" "false" "off" "" "maybe"]]
      (is (= {} (receptor/capabilities (receptor/select {"BB_MCP_CHANNELS" v} {:method "initialize"}))) v)))
  (testing "a client whose initialize declares claude/channel is served without configuration"
    (doseq [exp [{:claude/channel {}} {"claude/channel" {}}]]
      (is (= sense/channel-capability
             (receptor/capabilities
              (receptor/select {} {:method "initialize" :params {:capabilities {:experimental exp}}})))))
    (testing "as decoded from the wire"
      (is (sense/client-listens?
           (hp/json-decode "{\"method\":\"initialize\",\"params\":{\"capabilities\":{\"experimental\":{\"claude/channel\":{}}}}}")))))
  (testing "what Claude Code 2.1.x actually sends does not arm it"
    (is (not (sense/client-listens?
              {:method "initialize"
               :params {:capabilities {:roots {:listChanged true} :elicitation {}}}})))))

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
          "armed once the initialize result is out, disarmed at end of input")))
  (testing "select-receptor is handed the initialize request itself"
    (let [seen (atom nil)
          init {:jsonrpc "2.0" :id 1 :method "initialize" :params {:capabilities {:roots {}}}}
          inbox (atom [init])
          t (reify proto/Transport
              (read-msg [_] (when-let [m (first @inbox)] (swap! inbox rest) m))
              (write-msg [_ _]))]
      (core/run-server t {:select-receptor (fn [req] (reset! seen req) (receptor/null-receptor))})
      (is (= init @seen)))))

(deftest a-second-initialize-disarms-the-first-receptor
  (let [events (atom [])
        made (atom 0)
        mk (fn [_]
             (let [n (swap! made inc)]
               (reify receptor/Receptor
                 (capabilities [_] sense/channel-capability)
                 (arm! [this _] (swap! events conj [:arm n]) this)
                 (disarm! [this] (swap! events conj [:disarm n]) this))))
        inbox (atom [{:jsonrpc "2.0" :id 1 :method "initialize"}
                     {:jsonrpc "2.0" :method "notifications/initialized"}
                     {:jsonrpc "2.0" :id 2 :method "initialize"}
                     {:jsonrpc "2.0" :method "notifications/initialized"}])
        outbox (atom [])
        t (reify proto/Transport
            (read-msg [_] (when-let [m (first @inbox)] (swap! inbox rest) m))
            (write-msg [_ m] (swap! outbox conj m)))]
    (core/run-server t {:select-receptor mk})
    (is (= [[:arm 1] [:disarm 1] [:arm 2] [:disarm 2]] @events)
        "the first poller is stopped before the second session's is chosen")
    (is (= [(proto/initialize-response 1 sense/channel-capability)
            (proto/initialize-response 2 sense/channel-capability)]
           @outbox))))

(deftest initialize-has-one-source
  (testing "handle-method answers initialize from the session capabilities it is given"
    (is (= (proto/initialize-response 4)
           (core/handle-method {:method "initialize" :id 4})))
    (is (= (proto/initialize-response 4 sense/channel-capability)
           (core/handle-method {:method "initialize" :id 4
                                :session/capabilities sense/channel-capability})))))

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

(deftest drain-reply-carries-the-epoch
  (is (= {:outcome :ok :senses [] :cursor 3 :epoch "e-1"}
         (sense/read-drain-reply (nrepl-says {:senses [] :cursor 3 :epoch "e-1"}))))
  (is (= {:outcome :ok :senses [] :cursor 3}
         (sense/read-drain-reply (nrepl-says {:senses [] :cursor 3})))
      "an older hive-agent sends no epoch"))

(deftest drain-form-never-loads-code
  (let [code (sense/drain-form 0 {})]
    (is (not (str/includes? code "require")) "no require / requiring-resolve from a poller")
    (is (str/includes? code "find-ns"))
    (is (str/includes? code "ns-resolve"))))

(deftest drain-form-quotes-its-inputs
  (let [code (sense/drain-form 42 {:receptor/parent "coordinator"})]
    (is (str/includes? code "(quote hive-agent.sixth-sense.api)"))
    (is (str/includes? code "(quote drain)"))
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

(deftest an-epoch-change-rereads-even-when-the-new-log-is-longer
  (testing "the hive restarted and its new log grew PAST the old cursor before the next poll"
    (let [{:keys [sent asked logs]}
          (run-script [{:outcome :ok :senses [] :cursor 5 :epoch "A"}
                       {:outcome :ok :senses [ask] :cursor 9 :epoch "B"}
                       {:outcome :ok :senses [ask done] :cursor 9 :epoch "B"}]
                      3)]
      (is (= [0 5 0] asked) "the cursor from log A is never used against log B")
      (is (= [(sense/sense->notification ask) (sense/sense->notification done)] sent)
          "the senses read against the stale cursor are discarded; log B is read whole")
      (is (some #(str/includes? % "restarted") logs)))))

(deftest epoch-rules
  (let [s (assoc (sense/initial-state sense/default-policy) :cursor 5 :epoch "A" :primed? true)]
    (is (false? (sense/log-restarted? s {:cursor 7 :epoch "A"})))
    (is (true? (sense/log-restarted? s {:cursor 7 :epoch "B"})))
    (testing "an older hive sends no epoch: only a backwards cursor tells"
      (is (false? (sense/log-restarted? s {:cursor 7 :epoch nil})))
      (is (true? (sense/log-restarted? s {:cursor 2 :epoch nil}))))
    (testing "the first epoch seen is adopted, not treated as a restart"
      (is (false? (sense/log-restarted? (assoc s :epoch nil) {:cursor 7 :epoch "A"})))
      (is (= "A" (get-in (sense/advance (assoc s :epoch nil) {:outcome :ok :senses [] :cursor 7 :epoch "A"}
                                        sense/default-policy)
                         [:state :epoch]))))))

(deftest a-disarmed-loop-delivers-nothing-more
  (let [running (atom true) outbox (atom [])
        state (receptor/run-loop!
               {:feed (reify receptor/SenseFeed
                        (drain! [_ c _]
                          (reset! running false) ; disarmed while this drain is in flight
                          {:outcome :ok :senses [ask] :cursor (inc c)}))
                :transport (recording-transport outbox)
                :receptor-spec {}
                :policy (assoc sense/default-policy :prime? false)
                :log-fn (fn [_]) :sleep-fn (fn [_]) :running running})]
    (is (empty? @outbox))
    (is (= 1 (:cursor state)))))

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
  (is (= {} (sense/receptor-spec {})) "no session claims to be the coordinator by default")
  (is (= {} (sense/receptor-spec {"BB_MCP_SENSE_PARENT" "  "})))
  (is (= {:receptor/parent "ling-1"} (sense/receptor-spec {"CLAUDE_SWARM_SLAVE_ID" "ling-1"})))
  (is (= {:receptor/parent "coordinator"} (sense/receptor-spec {"BB_MCP_SENSE_PARENT" "coordinator"})))
  (is (= {:receptor/parent "me" :receptor/projects #{"hive" "bb-mcp"}
          :receptor/classes #{:sense/ask :sense/blocked}}
         (sense/receptor-spec {"BB_MCP_SENSE_PARENT" "me"
                               "CLAUDE_SWARM_SLAVE_ID" "ling-1"
                               "BB_MCP_SENSE_PROJECTS" "hive, bb-mcp"
                               "BB_MCP_SENSE_CLASSES" "ask,blocked"}))))

(deftest policy-from-env
  (is (= sense/default-policy (sense/policy-from {})))
  (is (= 250 (:base-ms (sense/policy-from {"BB_MCP_SENSE_POLL_MS" "250"}))))
  (is (false? (:prime? (sense/policy-from {"BB_MCP_SENSE_REPLAY" "1"})))))

;;; ===========================================================================
;;; Default parent: the session's own caller id
;;; ===========================================================================

(deftest caller-id-is-the-spawn-parent-string
  (is (= "coordinator:abc" (core/caller-id nil "abc")))
  (is (= "ling-4:abc" (core/caller-id "ling-4" "abc"))))

(deftest receptor-spec-defaults-to-the-caller-id
  (testing "a coordinator hears ITS swarm, not every swarm on the host"
    (is (= {:receptor/parent "coordinator:abc"} (sense/receptor-spec {} "coordinator:abc"))))
  (testing "a ling hears its children under its caller id, which hive-mcp records as their parent"
    (is (= {:receptor/parent "ling-1:abc"}
           (sense/receptor-spec {"CLAUDE_SWARM_SLAVE_ID" "ling-1"} "ling-1:abc"))))
  (testing "the slave id only stands in when no caller id is known"
    (is (= {:receptor/parent "ling-1"}
           (sense/receptor-spec {"CLAUDE_SWARM_SLAVE_ID" "ling-1"} " "))))
  (testing "an explicit parent wins"
    (is (= {:receptor/parent "boss"}
           (sense/receptor-spec {"BB_MCP_SENSE_PARENT" " boss "
                                 "CLAUDE_SWARM_SLAVE_ID" "ling-1"}
                                "coordinator:abc"))))
  (testing "* or all opts out of the parent filter"
    (doseq [v ["*" "all" " ALL "]]
      (is (= {} (sense/receptor-spec {"BB_MCP_SENSE_PARENT" v
                                      "CLAUDE_SWARM_SLAVE_ID" "ling-1"}
                                     "coordinator:abc"))
          v)))
  (testing "blank caller id: no parent"
    (is (= {} (sense/receptor-spec {} " ")))))

(deftest channel-consumer-is-apart-from-the-caller-id
  (testing "manual drains default to the caller id; the channel never shares that cursor"
    (is (= "coordinator:abc#channel" (sense/channel-consumer "coordinator:abc")))
    (is (not= "coordinator:abc" (sense/channel-consumer "coordinator:abc"))))
  (is (nil? (sense/channel-consumer nil)))
  (is (nil? (sense/channel-consumer "  "))))

(deftest feed-spec-names-the-consumer
  (is (= {:consumer "coordinator:abc#channel" :limit 100 :prime? true}
         (sense/feed-spec "coordinator:abc" sense/default-policy)))
  (is (= {:consumer nil :limit 100 :prime? false}
         (sense/feed-spec nil (sense/policy-from {"BB_MCP_SENSE_REPLAY" "1"})))))

;;; ===========================================================================
;;; drain-form path selection, evaluated against stub hive namespaces
;;; ===========================================================================

(def ^:private api-ns 'hive-agent.sixth-sense.api)
(def ^:private state-ns 'hive-agent.sixth-sense.state)

(defn- with-hive
  "Run `f` with stub hive namespaces holding `api` / `state` vars
   ({sym fn}), removed afterwards so no other test sees them."
  [{:keys [api state]} f]
  (try
    (when api (let [n (create-ns api-ns)] (doseq [[k v] api] (intern n k v))))
    (when state (let [n (create-ns state-ns)] (doseq [[k v] state] (intern n k v))))
    (f)
    (finally
      (remove-ns api-ns)
      (remove-ns state-ns))))

(defn- eval-drain
  "Evaluate `drain-form` as the hive JVM would and read it as the feed does."
  [cursor receptor opts]
  (sense/read-drain-reply
   {:error? false :result (pr-str (load-string (sense/drain-form cursor receptor opts)))}))

(defn- stub-port
  "A sense-port whose :drain! records its opts in `seen` and answers `reply`."
  [seen reply]
  (fn [] {:drain! (fn [opts] (reset! seen opts) reply)}))

(deftest drain-form-selects-the-per-consumer-port
  (let [seen (atom nil) stateless (atom nil)
        hive {:api {'sense-port (stub-port seen {:senses [done] :cursor 12})
                    'drain (fn [o] (reset! stateless o) {:senses [] :cursor 0})}
              :state {'default-sensorium (constantly ::sensorium)
                      'consumer-cursors (constantly {"coordinator:abc" 7})
                      'head (constantly 12)}}]
    (testing "a known consumer drains from its stored cursor; the loop cursor is not sent"
      (with-hive hive
        #(is (= {:outcome :ok :senses [done] :cursor 12 :consumer "coordinator:abc" :primed? true}
                (eval-drain 3 {:receptor/parent "coordinator:abc"}
                            {:consumer "coordinator:abc" :limit 50 :prime? true}))))
      (is (= {:consumer "coordinator:abc" :receptor {:receptor/parent "coordinator:abc"} :limit 50}
             @seen))
      (is (nil? @stateless) "the stateless drain is not called"))
    (testing "a new consumer skips its backlog in the hive: it reads from the head"
      (with-hive (assoc-in hive [:state 'consumer-cursors] (constantly {}))
        #(is (true? (:primed? (eval-drain 0 {} {:consumer "c:new" :prime? true})))))
      (is (= 12 (:cursor @seen))))
    (testing "BB_MCP_SENSE_REPLAY: a new consumer reads its backlog"
      (reset! seen nil)
      (with-hive (assoc-in hive [:state 'consumer-cursors] (constantly {}))
        #(is (false? (:primed? (eval-drain 0 {} {:consumer "c:new" :prime? false})))))
      (is (not (contains? @seen :cursor))))
    (testing "cursors unreadable: the loop is asked to prime itself"
      (with-hive (dissoc hive :state)
        #(is (false? (:primed? (eval-drain 0 {} {:consumer "c:1" :prime? true}))))))))

(deftest drain-form-falls-back-to-the-stateless-drain
  (let [stateless (atom nil)
        drain (fn [o] (reset! stateless o) {:senses [ask] :cursor 4 :epoch "E"})]
    (testing "no sense-port in the hive"
      (with-hive {:api {'drain drain}}
        #(is (= {:outcome :ok :senses [ask] :cursor 4 :epoch "E"}
                (eval-drain 2 {} {:consumer "c:1" :prime? true}))))
      (is (= {:cursor 2 :receptor {}} @stateless)))
    (testing "no consumer named"
      (with-hive {:api {'drain drain 'sense-port (stub-port (atom nil) {:senses [] :cursor 0})}}
        #(is (not (contains? (eval-drain 2 {} {}) :consumer))))))
  (testing "nothing loaded: absent"
    (is (= {:outcome :absent} (eval-drain 0 {} {:consumer "c:1"})))))

;;; ===========================================================================
;;; Detail on the wire and in the notification
;;; ===========================================================================

(deftest drain-form-forwards-a-printable-detail
  (let [s (assoc done :sense/detail {:termination :max-turns :at (java.util.UUID/randomUUID)})
        reply (with-hive {:api {'drain (constantly {:senses [s] :cursor 1})}}
                #(eval-drain 0 {} {}))
        d (-> reply :senses first :sense/detail)]
    (is (= :max-turns (:termination d)))
    (is (string? (:at d)) "an unreadable value is sent as text")))

(deftest detail-rides-in-meta-and-content
  (let [n (sense/sense->notification
           (assoc done :sense/class :sense/truncated :sense/detail {:termination :max-turns}))]
    (is (= "max-turns" (get-in n [:params :meta :termination])))
    (is (= "truncated" (get-in n [:params :meta :sense_class])))
    (is (= "ling-8 completed [termination=max-turns]" (get-in n [:params :content]))))
  (testing "JSON-shaped detail: string keys, error/type"
    (let [n (sense/sense->notification
             (assoc ask :sense/detail {"error/type" "context-overflow" "reason" "no-progress"}))]
      (is (= {:error_type "context-overflow" :reason "no-progress"}
             (select-keys (get-in n [:params :meta]) [:error_type :reason])))
      (is (str/ends-with? (get-in n [:params :content]) "[error_type=context-overflow reason=no-progress]"))))
  (testing "no detail: unchanged"
    (is (= (:sense/text ask) (get-in (sense/sense->notification ask) [:params :content])))))

;;; ===========================================================================
;;; Loop over the per-consumer port
;;; ===========================================================================

(deftest consumer-replies-deliver-when-the-hive-primed
  (let [{:keys [sent]} (run-script [{:outcome :ok :senses [ask] :cursor 5 :consumer "c" :primed? true}
                                    {:outcome :ok :senses [done] :cursor 6 :consumer "c" :primed? true}]
                                   2 :policy sense/default-policy)]
    (is (= [(sense/sense->notification ask) (sense/sense->notification done)] sent))))

(deftest consumer-replies-prime-locally-when-the-hive-could-not
  (let [{:keys [sent]} (run-script [{:outcome :ok :senses [ask] :cursor 5 :consumer "c" :primed? false}
                                    {:outcome :ok :senses [done] :cursor 6 :consumer "c" :primed? false}]
                                   2 :policy sense/default-policy)]
    (is (= [(sense/sense->notification done)] sent))))

(deftest consumer-replies-never-reread
  (testing "the hive owns the cursor: a lower cursor or new epoch is not a local restart"
    (let [{:keys [sent logs]} (run-script [{:outcome :ok :senses [] :cursor 50 :epoch "A" :consumer "c" :primed? true}
                                           {:outcome :ok :senses [ask] :cursor 1 :epoch "B" :consumer "c" :primed? true}]
                                          2)]
      (is (= [(sense/sense->notification ask)] sent))
      (is (not-any? #(str/includes? % "restarted") logs)))))

(deftest drain-reply-carries-the-consumer
  (is (= {:outcome :ok :senses [] :cursor 3 :consumer "c:1" :primed? true}
         (sense/read-drain-reply (nrepl-says {:senses [] :cursor 3 :consumer "c:1" :primed? true})))))
