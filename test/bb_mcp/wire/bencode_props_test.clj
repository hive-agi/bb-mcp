(ns bb-mcp.wire.bencode-props-test
  "Properties of the bencode codec.

   Each property is a FUNCTION of an injected codec, not a body that names
   `bencode/encode` directly. That is what lets the mutation suite run the
   identical assertions against a deliberately broken codec and demand that
   they go red — a property nothing can fail is not evidence.

   Portable by construction: byte arrays, arraycopy and the generators, none
   of which differ between babashka and cljw."
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.wire.bencode :as bencode]
            [bb-mcp.gen :as gen]))

(def real
  "The production codec, as an injected collaborator."
  {:encode bencode/encode
   :decode-all bencode/decode-all})

(def ^:private seed0 20260817)
(def ^:private corpus-size 150)

(def ^:private corpus
  "One fixed corpus, walked identically by every runtime and every mutant."
  (gen/samples #(gen/gen-value % 2) seed0 corpus-size))

(def ^:private message-corpus
  "nREPL-shaped dicts — the values the codec actually carries in production."
  (gen/samples gen/gen-message (gen/step seed0) 60))

(def small-corpus
  "A slice of the corpus for the mutation matrix. The prefix property costs one
   decode per byte, so the full corpus times four mutants is minutes; this
   still carries every shape and multi-byte strings."
  (vec (take 40 corpus)))

(defn cat-ba
  "Byte-array holding `a` followed by `b`."
  [a b]
  (let [alen (alength ^bytes a)
        blen (alength ^bytes b)
        out (byte-array (+ alen blen))]
    (System/arraycopy a 0 out 0 alen)
    (System/arraycopy b 0 out alen blen)
    out))

;; ── the properties ───────────────────────────────────────────────────────────

(defn roundtrip-failures
  "Values in `vs` that do not survive encode -> decode-all under `codec`.
   Every byte written must be consumed, and exactly one value must come back."
  [{:keys [encode decode-all]} vs]
  (reduce (fn [acc v]
            (let [ba (encode v)
                  len (alength ^bytes ba)
                  [values off] (decode-all ba len)]
              (if (and (= [v] values) (= off len))
                acc
                (conj acc {:value v :got values :offset off :len len}))))
          []
          vs))

(defn prefix-failures
  "Values with a strict prefix that decodes to anything at all under `codec`.

   The read loop leans on this: a message that has only partly arrived must
   yield no values AND consume no bytes, or the next read appends to a
   buffer whose head has already been eaten."
  [{:keys [encode decode-all]} vs]
  (reduce (fn [acc v]
            (let [ba (encode v)
                  len (alength ^bytes ba)
                  bad (for [cut (range 0 len)
                            :let [[values off] (decode-all ba cut)]
                            :when (not (and (= [] values) (= 0 off)))]
                        {:cut cut :got values :offset off})]
              (if (seq bad)
                (conj acc {:value v :failures (vec bad)})
                acc)))
          []
          vs))

(defn concat-failures
  "Adjacent pairs of `vs` whose concatenated encodings do not decode back to
   exactly that pair under `codec`. Framing, not just value fidelity."
  [{:keys [encode decode-all]} vs]
  (reduce (fn [acc [a b]]
            (let [ba (cat-ba (encode a) (encode b))
                  len (alength ^bytes ba)
                  [values off] (decode-all ba len)]
              (if (and (= [a b] values) (= off len))
                acc
                (conj acc {:pair [a b] :got values :offset off :len len}))))
          []
          (partition 2 1 vs)))

(def ^:private byte-corpus
  "Arbitrary byte arrays — frames nothing in this codebase produced."
  (gen/samples gen/gen-bytes 4242 300))

(defn arbitrary-bytes-failures
  "Byte arrays on which `decode-all` leaves its contract: it must return
   [values offset] with 0 <= offset <= limit, for ANY bytes, and it must not
   throw.

   Kept out of `properties` on purpose — the mutation matrix feeds values, and
   this one feeds raw bytes. It is the property that found the real defect:
   `read-digits` folded a 26-digit length prefix into a long, which threw
   ArithmeticException on babashka and silently wrapped on cljw."
  [{:keys [decode-all]} bas]
  (reduce (fn [acc ba]
            (let [limit (alength ^bytes ba)
                  outcome (try
                            (let [[values off] (decode-all ba limit)]
                              (when-not (and (vector? values)
                                             (integer? off)
                                             (<= 0 off limit))
                                {:got-offset off :got-type (type values)}))
                            (catch Exception e
                              {:threw (ex-message e)}))]
              (if outcome
                (conj acc (assoc outcome
                                 :len limit
                                 :head (vec (take 24 (map int (seq ba))))))
                acc)))
          []
          bas))

(def properties
  "The property battery, by name. The mutation suite iterates this map, so a
   property added here is automatically required to be discriminating."
  {:roundtrip roundtrip-failures
   :prefix-incomplete prefix-failures
   :concatenation concat-failures})

;; ── the production codec satisfies every one of them ─────────────────────────

(defn- shape
  "The bencode shape family of `v`."
  [v]
  (cond (map? v) :dict
        (vector? v) :list
        (string? v) :string
        :else :int))

(def ^:private tally
  "How many of each shape the corpus holds. Asserted on instead of the corpus
   itself: a failed `some` over 150 nested values prints all of them."
  (frequencies (map shape corpus)))

(def ^:private multibyte-strings
  "Corpus strings whose UTF-8 byte length exceeds their character count — the
   only values that can tell a byte-counted length prefix from a char-counted
   one."
  (count (filter (fn [v] (and (string? v)
                              (not= (count v) (alength (.getBytes ^String v "UTF-8")))))
                 corpus)))

(deftest the-corpus-is-worth-running
  (testing "every bencode shape is represented"
    (is (pos? (get tally :dict 0)) (str "tally " tally))
    (is (pos? (get tally :list 0)) (str "tally " tally))
    (is (pos? (get tally :string 0)) (str "tally " tally))
    (is (pos? (get tally :int 0)) (str "tally " tally)))
  (testing "and multi-byte characters, so byte and character lengths diverge"
    (is (pos? multibyte-strings))))

(deftest encode-decode-roundtrips
  (is (= [] (roundtrip-failures real corpus)))
  (is (= [] (roundtrip-failures real message-corpus))))

(deftest a-strict-prefix-decodes-to-nothing
  (is (= [] (prefix-failures real corpus))))

(deftest concatenated-messages-decode-in-order
  (is (= [] (concat-failures real corpus)))
  (is (= [] (concat-failures real message-corpus))))

(deftest decode-survives-arbitrary-bytes
  (testing "no byte sequence makes the decoder throw or overrun its limit"
    (is (= [] (arbitrary-bytes-failures real byte-corpus)))))
