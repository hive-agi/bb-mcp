(ns bb-mcp.host.conformance-test
  "Contract suite for bb-mcp.host.port/IByteChannel.

   Every adapter runs the SAME assertions. The loopback arm keeps the suite
   meaningful with no hive-mcp present; the socket arm runs the identical
   contract against the real JVM when one is listening."
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.host.port :as hp]
            [bb-mcp.host.bb :as host-bb]
            [bb-mcp.tools.nrepl :as nrepl]))

;; Real bytes captured from the live hive-mcp nREPL answering (+ 1 2):
;; a value message followed by a done message.
(def ^:private captured-response
  (str "d2:id5:cap-12:ns4:user7:session36:dcf34e20-2070-41fb-9dc8-cab3ec7bc265"
       "5:value1:3e"
       "d2:id5:cap-17:session36:dcf34e20-2070-41fb-9dc8-cab3ec7bc265"
       "6:statusl4:doneee"))

(defrecord LoopbackChannel [chunks written closed?]
  hp/IByteChannel
  (write-bytes! [_ ba]
    (swap! written conj (String. ^bytes ba "UTF-8"))
    nil)
  (read-bytes! [_ buf]
    (if-let [chunk (first @chunks)]
      (let [^bytes b (.getBytes ^String chunk "UTF-8")
            n (min (alength b) (alength ^bytes buf))]
        (swap! chunks rest)
        (System/arraycopy b 0 buf 0 n)
        n)
      -1))
  (close! [_] (reset! closed? true) nil))

(defn- loopback
  "An open-fn serving `chunks` and recording what was written."
  [chunks state]
  (fn [_opts]
    (let [ch (->LoopbackChannel (atom chunks) (:written state) (:closed? state))]
      (reset! (:opened? state) true)
      ch)))

(defn- fresh-state []
  {:written (atom []) :closed? (atom false) :opened? (atom false)})

;; ── the contract, run against any adapter ────────────────────────────────────

(defn- eval-through
  "Drive a full eval through `open-fn`. => {:result :error?}"
  [open-fn]
  (nrepl/eval-code {:host "localhost" :port 7910 :code "(+ 1 2)"
                    :timeout-ms 10000 :open-fn open-fn}))

(deftest loopback-arm-satisfies-the-contract
  (let [state (fresh-state)
        open  (loopback [captured-response] state)
        {:keys [result error?]} (eval-through open)]

    (testing "a real captured response folds to its value"
      (is (= "3" result))
      (is (false? error?)))

    (testing "the adapter received the bencoded request"
      (is (= ["d4:code7:(+ 1 2)2:op4:evale"] @(:written state))))

    (testing "the channel is closed even on the happy path"
      (is (true? @(:closed? state))))))

(deftest loopback-arm-handles-chunk-splits
  (testing "a response split across reads decodes the same"
    (doseq [cut [1 20 64 (dec (count captured-response))]]
      (let [state (fresh-state)
            open  (loopback [(subs captured-response 0 cut)
                             (subs captured-response cut)]
                            state)]
        (is (= "3" (:result (eval-through open)))
            (str "split at " cut))))))

(deftest a-stream-that-ends-before-done-is-an-error
  (let [state (fresh-state)
        open  (loopback ["d2:id5:cap-15:value1:3e"] state)
        {:keys [result error?]} (eval-through open)]
    (is (true? error?))
    (is (= "No response from nREPL" result))))

(deftest a-refused-connection-is-reported-not-thrown
  (let [open (fn [_] (throw (ex-info "connection refused" {})))
        {:keys [result error?]} (eval-through open)]
    (is (true? error?))
    (is (re-find #"nREPL connection failed" result))))

;; ── the same contract against the real socket adapter ────────────────────────

(defn- nrepl-listening? []
  (try
    (hp/close! (host-bb/open {:port 7910 :timeout-ms 1000}))
    true
    (catch Exception _ false)))

(deftest socket-arm-satisfies-the-contract
  (if-not (nrepl-listening?)
    (println "SKIP socket-arm-satisfies-the-contract — no nREPL on 7910")
    (testing "the babashka socket adapter answers the same as the loopback"
      (let [{:keys [result error?]} (eval-through host-bb/open)]
        (is (= "3" result))
        (is (false? error?))))))
