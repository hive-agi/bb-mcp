(ns bb-mcp.host.retry-test
  "The bounded connect retry: a stub opener that refuses a given number of
   times, and a recording sleep. Nothing dials a socket or waits."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-test.trifecta :refer [deftrifecta]]
            [clojure.test.check.generators :as gen]
            [bb-mcp.host.retry :as retry]
            [bb-mcp.host.bb :as hb]))

(defn- refusing-connect
  "A connect fn that throws ConnectException for its first `n` calls, then
   answers :channel. Records every call in `calls`."
  [calls n]
  (fn [host port timeout-ms]
    (swap! calls conj [host port timeout-ms])
    (if (<= (count @calls) n)
      (throw (java.net.ConnectException. "Connection refused"))
      :channel)))

(defn- run-open
  "Open through a stub refusing `n` times. => {:result :calls :sleeps}."
  [n]
  (let [calls (atom []) sleeps (atom [])
        result (try (hb/open-with {:connect (refusing-connect calls n)
                                   :policy hb/connect-retry-policy
                                   :sleep! #(swap! sleeps conj %)}
                                  {:port 7910})
                    (catch java.net.ConnectException _ :refused))]
    {:result result :calls (count @calls) :sleeps @sleeps}))

(deftrifecta connect-retry
  run-open
  {:apply? true
   :golden-path "test/golden/connect-retry.edn"
   :cases {:up [0] :refused-once [1] :refused-twice [2] :down [3]}
   :gen (gen/fmap vector (gen/choose 0 6))
   :num-tests 30
   :pred (fn [{:keys [result calls sleeps]}]
           (and (<= calls 3)
                (= (count sleeps) (dec calls))
                (< (reduce + 0 sleeps) 2000)
                (contains? #{:channel :refused} result)))
   :mutations [["no-retry"
                (fn [n] (if (zero? n)
                          {:result :channel :calls 1 :sleeps []}
                          {:result :refused :calls 1 :sleeps []}))]
               ["unbounded"
                (fn [n] {:result :channel :calls (inc n)
                         :sleeps (vec (repeat n 250))})]]})

(deftest only-a-refused-connect-is-retried
  (testing "a non-connect failure is thrown at once, without sleeping"
    (let [calls (atom 0) sleeps (atom [])]
      (is (thrown? java.net.SocketTimeoutException
                   (hb/open-with {:connect (fn [& _] (swap! calls inc)
                                             (throw (java.net.SocketTimeoutException. "slow")))
                                  :policy hb/connect-retry-policy
                                  :sleep! #(swap! sleeps conj %)}
                                 {:port 7910})))
      (is (= 1 @calls))
      (is (= [] @sleeps))))
  (testing "the default policy never retries"
    (is (nil? (retry/next-delay retry/default-policy 1 (java.net.ConnectException.)))))
  (testing "host and timeout defaults reach the connect fn"
    (let [calls (atom [])]
      (hb/open-with {:connect (refusing-connect calls 0)
                     :policy hb/connect-retry-policy
                     :sleep! identity}
                    {:port 7946})
      (is (= [["localhost" 7946 600000]] @calls)))))
