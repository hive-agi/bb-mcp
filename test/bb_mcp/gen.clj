(ns bb-mcp.gen
  "Deterministic generators for the portable property suites.

   Not test.check and not malli: a property that only runs on the JVM arm
   cannot be a cross-runtime oracle, and neither library exists on cljw. The
   whole file is seed in, [value next-seed] out, so babashka and cljw walking
   the same seeds walk the same corpus — that identity is what makes a
   disagreement between them mean something.")

(def ^:private mask 0x7fffffff)

(defn step
  "Next seed from `seed`. A 31-bit LCG (glibc constants), chosen because it
   needs no host RNG and cannot overflow a 64-bit multiply."
  [seed]
  (bit-and (+ (* seed 1103515245) 12345) mask))

(defn- mix
  "Scramble `seed` before it is reduced modulo a small bound.

   A power-of-two-modulus LCG has near-worthless low bits — `(mod seed 4)`
   cycles with period 4, which produced a first corpus of 150 values that were
   ALL vectors of integers. The reduction has to read mixed bits, not raw ones."
  [seed]
  (let [x (bit-xor seed (bit-shift-right seed 13))
        y (bit-and (* x 1103515245) mask)]
    (bit-xor y (bit-shift-right y 17))))

(defn- pick
  "[n next-seed] with n in [0, bound)."
  [seed bound]
  (let [s (step seed)]
    [(mod (mix s) bound) s]))

(defn seeds
  "`n` seeds from `seed0`, each the LCG successor of the last."
  [seed0 n]
  (take n (iterate step seed0)))

;; ── leaves ───────────────────────────────────────────────────────────────────

(def ^:private alphabet
  "Deliberately mixed-width: a length prefix counted in characters rather than
   bytes survives the ASCII half and dies on the rest."
  ["a" "b" "z" "0" "9" "-" "_" " " "é" "ü" "λ" "日" "→" "🜁"])

(defn gen-int
  "[n next-seed] with n in [-10000, 10000]."
  [seed]
  (let [[n s] (pick seed 20001)]
    [(- n 10000) s]))

(defn gen-string
  "[s next-seed], 0..12 characters drawn from `alphabet`."
  [seed]
  (let [[len s0] (pick seed 13)]
    (loop [i 0 s s0 acc ""]
      (if (= i len)
        [acc s]
        (let [[c s'] (pick s (count alphabet))]
          (recur (inc i) s' (str acc (nth alphabet c))))))))

(defn gen-key
  "[k next-seed] — a keyword, matching what `decode` produces for dict keys."
  [seed]
  (let [[s s'] (gen-string seed)]
    [(keyword (if (= "" s) "k" s)) s']))

;; ── values ───────────────────────────────────────────────────────────────────

(declare gen-value)

(defn- gen-coll
  "[items next-seed] — 0..3 values at `depth`."
  [seed depth]
  (let [[len s0] (pick seed 4)]
    (loop [i 0 s s0 acc []]
      (if (= i len)
        [acc s]
        (let [[v s'] (gen-value s depth)]
          (recur (inc i) s' (conj acc v)))))))

(defn- gen-map
  "[m next-seed] — 0..3 keyword-keyed entries at `depth`."
  [seed depth]
  (let [[len s0] (pick seed 4)]
    (loop [i 0 s s0 acc {}]
      (if (= i len)
        [acc s]
        (let [[k s1] (gen-key s)
              [v s2] (gen-value s1 depth)]
          (recur (inc i) s2 (assoc acc k v)))))))

(defn gen-value
  "[v next-seed] — a bencode-able value. `depth` 0 forces a leaf, so the
   recursion is bounded by construction rather than by luck."
  [seed depth]
  (let [[choice s] (pick seed (if (pos? depth) 4 2))]
    (case choice
      0 (gen-int s)
      1 (gen-string s)
      2 (gen-coll s (dec depth))
      3 (gen-map s (dec depth)))))

(defn gen-message
  "[m next-seed] — an nREPL-shaped dict: keyword keys, nested values."
  [seed]
  (gen-map seed 2))

(defn gen-bytes
  "[byte-array next-seed] — 0..64 arbitrary bytes, biased toward the ASCII
   range so the fuzz corpus lands on plausible-looking frames rather than on
   noise the decoder rejects at the first byte."
  [seed]
  (let [[len s0] (pick seed 65)
        out (byte-array len)]
    (loop [i 0 s s0]
      (if (= i len)
        [out s]
        (let [[b s'] (pick s 128)]
          (aset out i (byte b))
          (recur (inc i) s'))))))

(defn samples
  "`n` values from `gen` (a seed -> [value seed] fn), starting at `seed0`."
  [gen seed0 n]
  (loop [i 0 s seed0 acc []]
    (if (= i n)
      acc
      (let [[v s'] (gen s)]
        (recur (inc i) s' (conj acc v))))))
