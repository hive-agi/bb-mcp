(ns bb-mcp.wire.bencode-mutation-test
  "Mutation testing for the bencode properties.

   A property that nothing can fail is decoration. Each mutant below is a
   codec that is wrong in one specific way; the suite demands that at least
   one property notices, and pins the two cases where a single property is the
   ONLY witness — that is what stops a redundant property from being deleted
   and a unique one from being quietly weakened.

   The mutants are BEHAVIOURAL — injected collaborators, not edited source.
   Nothing is redefined, so the same file runs on any runtime that can load
   the codec."
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.wire.bencode-props-test :as props]))

;; ── the mutants ──────────────────────────────────────────────────────────────

(defn- drop-last-byte
  "Encoder whose output is one byte short — a message that looks whole and is
   not, which is exactly what a short read produces."
  [encode]
  (fn [v]
    (let [ba (encode v)
          n (dec (alength ^bytes ba))
          out (byte-array n)]
      (System/arraycopy ba 0 out 0 n)
      out)))

(defn- append-extra-value
  "Encoder that emits one valid value too many. Framing, not fidelity: every
   individual value still decodes."
  [encode]
  (fn [v]
    (props/cat-ba (encode v) (.getBytes "i1e" "US-ASCII"))))

(defn- char-length-strings
  "Encoder that counts a string's length in CHARACTERS. Indistinguishable from
   the real one for ASCII, and wrong for everything else — the mutant the
   corpus's multi-byte alphabet exists to catch."
  [encode]
  (fn [v]
    (if (string? v)
      (.getBytes ^String (str (count v) ":" v) "UTF-8")
      (encode v))))

(defn- lenient-tail
  "Decoder that reports an incomplete tail as fully consumed. Round-tripping a
   whole message never notices; the read loop would lose the head of every
   split message."
  [decode-all]
  (fn [ba limit]
    (let [[values _] (decode-all ba limit)]
      [values limit])))

(def mutants
  [{:name :truncated-encode
    :codec (update props/real :encode drop-last-byte)}
   {:name :extra-value-encode
    :codec (update props/real :encode append-extra-value)}
   {:name :char-counted-length
    :codec (update props/real :encode char-length-strings)}
   {:name :lenient-tail-decode
    :codec (update props/real :decode-all lenient-tail)}])

;; ── the kill matrix ──────────────────────────────────────────────────────────

(defn- distinguishes?
  "True when `check` reports a failure against `codec` — or throws, which
   distinguishes just as decisively and is how a malformed length prefix
   surfaces today."
  [check codec vs]
  (try
    (boolean (seq (check codec vs)))
    (catch Exception _ true)))

(defn killed-by
  "Property names that distinguish `codec` from a correct one."
  [codec]
  (into #{}
        (keep (fn [[prop-name check]]
                (when (distinguishes? check codec props/small-corpus) prop-name)))
        props/properties))

(def matrix
  "Mutant name -> the properties that kill it."
  (into {} (map (juxt :name #(killed-by (:codec %)))) mutants))

(deftest every-mutant-is-killed
  (doseq [{:keys [name]} mutants]
    (testing (str name)
      (is (seq (get matrix name))
          (str "no property distinguishes " name " from the real codec")))))

(deftest the-prefix-property-is-not-redundant
  (testing "a lenient tail survives round-tripping and dies on prefixes"
    (is (contains? (get matrix :lenient-tail-decode) :prefix-incomplete))
    (is (not (contains? (get matrix :lenient-tail-decode) :roundtrip)))))

(deftest the-corpus-carries-multi-byte-weight
  (testing "a character-counted length prefix is caught, which only a
            multi-byte string can do"
    (is (seq (get matrix :char-counted-length)))))
