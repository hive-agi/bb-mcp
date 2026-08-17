(ns bb-mcp.wire.bencode
  "Bencode codec over byte arrays.

   Pure: no streams, no sockets, no host IO types. Lengths are counted in
   BYTES, so UTF-8 round-trips intact.

   `encode`  => byte-array.
   `decode`  => [value next-offset], or ::incomplete when the value is not
                wholly contained in [offset, limit). Callers read more bytes
                and retry from the SAME offset.
   Dict keys are keywordized; dict entries are emitted sorted by key string.")

;; ── encode ───────────────────────────────────────────────────────────────────

(defn- ascii ^bytes [^String s] (.getBytes s "US-ASCII"))
(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- cat-bytes
  ^bytes [arrays]
  (let [total (reduce (fn [n a] (+ n (alength ^bytes a))) 0 arrays)
        out   (byte-array total)]
    (loop [off 0 as (seq arrays)]
      (if as
        (let [^bytes a (first as)
              len (alength a)]
          (System/arraycopy a 0 out off len)
          (recur (+ off len) (next as)))
        out))))

(declare encode)

(defn- enc-string
  ^bytes [^String s]
  (let [b (utf8 s)]
    (cat-bytes [(ascii (str (alength b) ":")) b])))

(defn- enc-int ^bytes [n] (ascii (str "i" n "e")))

(defn- enc-list
  ^bytes [coll]
  (cat-bytes (concat [(ascii "l")] (map encode coll) [(ascii "e")])))

(defn- enc-dict
  ^bytes [m]
  (cat-bytes
   (concat [(ascii "d")]
           (mapcat (fn [[k v]] [(enc-string (name k)) (encode v)])
                   (sort-by (comp str key) m))
           [(ascii "e")])))

(defn encode
  "Bencode `x` to a byte-array."
  ^bytes [x]
  (cond
    (string? x)     (enc-string x)
    (integer? x)    (enc-int x)
    (map? x)        (enc-dict x)
    (sequential? x) (enc-list x)
    :else           (enc-string (str x))))

;; ── decode ───────────────────────────────────────────────────────────────────

(def incomplete
  "Returned by `decode` when [offset, limit) holds only part of a value."
  ::incomplete)

(defn- ub [^bytes ba i] (bit-and (aget ba i) 255))

(defn- read-digits
  "Base-10 digits from `i` up to `term`. => [n next-offset] | ::incomplete."
  [ba i limit term]
  (loop [j i acc 0]
    (cond
      (>= j limit)       incomplete
      (= term (ub ba j)) [acc (inc j)]
      :else              (recur (inc j) (+ (* acc 10) (- (ub ba j) 48))))))

(defn- read-int
  "Bencode integer body (after the leading i). => [n next-offset] | ::incomplete."
  [ba i limit]
  (let [neg?  (and (< i limit) (= 45 (ub ba i)))
        r     (read-digits ba (if neg? (inc i) i) limit 101)]
    (if (= incomplete r)
      incomplete
      [(if neg? (- (first r)) (first r)) (second r)])))

(declare decode)

(defn- decode-string
  [ba i limit]
  (let [r (read-digits ba i limit 58)]
    (if (= incomplete r)
      incomplete
      (let [n     (first r)
            start (second r)
            end   (+ start n)]
        (if (> end limit)
          incomplete
          (let [dst (byte-array n)]
            (System/arraycopy ba start dst 0 n)
            [(String. dst "UTF-8") end]))))))

(defn- decode-list
  [ba i limit]
  (loop [i (inc i) acc []]
    (cond
      (>= i limit)      incomplete
      (= 101 (ub ba i)) [acc (inc i)]
      :else
      (let [x (decode ba i limit)]
        (if (= incomplete x)
          incomplete
          (recur (second x) (conj acc (first x))))))))

(defn- decode-dict
  [ba i limit]
  (loop [i (inc i) acc {}]
    (cond
      (>= i limit)      incomplete
      (= 101 (ub ba i)) [acc (inc i)]
      :else
      (let [k (decode ba i limit)]
        (if (= incomplete k)
          incomplete
          (let [v (decode ba (second k) limit)]
            (if (= incomplete v)
              incomplete
              (recur (second v)
                     (assoc acc (keyword (str (first k))) (first v))))))))))

(defn decode
  "Decode one bencode value from `ba` starting at `i`, bounded by `limit`.
   => [value next-offset] | ::incomplete."
  [ba i limit]
  (if (>= i limit)
    incomplete
    (let [c (ub ba i)]
      (cond
        (= c 100) (decode-dict ba i limit)
        (= c 108) (decode-list ba i limit)
        (= c 105) (read-int ba (inc i) limit)
        :else     (decode-string ba i limit)))))

(defn decode-all
  "Decode every complete value in [0, limit). => [values next-offset]."
  [ba limit]
  (loop [i 0 acc []]
    (let [r (decode ba i limit)]
      (if (= incomplete r)
        [acc i]
        (recur (second r) (conj acc (first r)))))))
