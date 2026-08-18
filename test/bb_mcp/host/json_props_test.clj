(ns bb-mcp.host.json-props-test
  "Round-trip properties for the host seam's JSON codec.

   The point is that this file is runtime-agnostic: run under babashka it
   holds cheshire to the contract, run under cljw it holds
   `clojure.data.json` to the SAME contract, over the same generated corpus.
   Two adapters, one description — a divergence shows up as a failure rather
   than as a difference nobody looked for."
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.host.port :as hp]
            [bb-mcp.gen :as gen]))

(def ^:private corpus
  (gen/samples gen/gen-message 20260818 120))

(defn roundtrip-failures
  "Values in `vs` that `encode` then `decode` does not return unchanged."
  [encode decode vs]
  (reduce (fn [acc v]
            (let [got (try (decode (encode v)) (catch Exception e {::threw (ex-message e)}))]
              (if (= v got)
                acc
                (conj acc {:value v :got got}))))
          []
          vs))

(deftest the-corpus-is-worth-running
  (testing "the corpus carries nesting and multi-byte keys, not 120 empty maps"
    (is (< 60 (count (distinct corpus))))
    (is (some (fn [m] (some coll? (vals m))) corpus))
    (is (some (fn [m] (some #(not= (count (name %))
                                   (alength (.getBytes ^String (name %) "UTF-8")))
                            (keys m)))
              corpus))))

(deftest json-roundtrips-through-the-host-seam
  (testing (str "adapter " (hp/adapter-ns))
    (is (= [] (roundtrip-failures hp/json-encode hp/json-decode corpus)))))

(deftest the-property-discriminates
  (testing "a codec that renders integers as strings is caught"
    (let [mutant (fn [v] (hp/json-encode (if (integer? v) (str v) v)))]
      (is (seq (roundtrip-failures mutant hp/json-decode
                                   (gen/samples gen/gen-int 7 40)))))))
