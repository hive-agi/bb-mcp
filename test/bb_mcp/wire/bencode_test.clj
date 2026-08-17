(ns bb-mcp.wire.bencode-test
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.wire.bencode :as bencode]))

(defn- ->str [^bytes ba] (String. ba "UTF-8"))
(defn- ->bytes ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- decode1
  "Decode one value from the whole of `s`. => value"
  [s]
  (let [ba (->bytes s)]
    (first (bencode/decode ba 0 (alength ba)))))

(deftest encode-golden
  (testing "an nREPL eval request encodes to the exact bytes the server accepts"
    ;; Keys sort by key-string: :code before :op.
    (is (= "d4:code7:(+ 1 2)2:op4:evale"
           (->str (bencode/encode {:op "eval" :code "(+ 1 2)"})))))

  (testing "lengths count BYTES, not characters"
    (let [encoded (->str (bencode/encode "não"))]
      (is (= "4:não" encoded))))

  (testing "scalars"
    (is (= "i42e" (->str (bencode/encode 42))))
    (is (= "i-7e" (->str (bencode/encode -7))))
    (is (= "3:abc" (->str (bencode/encode "abc"))))
    (is (= "l1:a1:be" (->str (bencode/encode ["a" "b"]))))))

(deftest decode-basics
  (testing "scalars and containers"
    (is (= "abc" (decode1 "3:abc")))
    (is (= 42 (decode1 "i42e")))
    (is (= -7 (decode1 "i-7e")))
    (is (= ["a" "b"] (decode1 "l1:a1:be"))))

  (testing "dict keys are keywordized"
    (is (= {:op "eval"} (decode1 "d2:op4:evale"))))

  (testing "multibyte survives the round trip"
    (is (= "não" (decode1 "4:não")))
    (doseq [v ["ação" "日本語" "a" "" "λx.x"]]
      (let [ba (bencode/encode v)]
        (is (= v (first (bencode/decode ba 0 (alength ba)))))))))

(deftest round-trip
  (testing "nested structures survive encode -> decode"
    (doseq [v [{:op "eval" :code "(+ 1 2)"}
               {:status ["done"] :id "7"}
               {:a {:b ["c" 1]}}
               {}
               []]]
      (let [ba (bencode/encode v)
            [decoded off] (bencode/decode ba 0 (alength ba))]
        (is (= v decoded) (str "round trip of " (pr-str v)))
        (is (= (alength ba) off) "consumes exactly the encoded bytes")))))

(deftest incompleteness-is-reported-not-guessed
  (testing "every proper prefix of a message decodes as ::incomplete"
    (let [ba  (bencode/encode {:op "eval" :code "(+ 1 2)"})
          len (alength ba)]
      (doseq [cut (range 1 len)]
        (is (= bencode/incomplete (bencode/decode ba 0 cut))
            (str "prefix of length " cut " must be incomplete")))
      (is (not= bencode/incomplete (bencode/decode ba 0 len))
          "the full message decodes")))

  (testing "a string whose body is truncated is incomplete, not short"
    (let [ba (->bytes "5:abc")]
      (is (= bencode/incomplete (bencode/decode ba 0 (alength ba)))))))

(deftest decode-all-stops-at-the-partial-tail
  (testing "complete messages are returned; the partial tail offset is reported"
    (let [whole   (str "d2:op4:evale" "d2:id1:7e" "d2:op")
          ba      (->bytes whole)
          [vs off] (bencode/decode-all ba (alength ba))]
      (is (= [{:op "eval"} {:id "7"}] vs))
      (is (= 21 off) "offset points at the start of the incomplete tail")
      (is (= "d2:op" (subs whole off))))))
