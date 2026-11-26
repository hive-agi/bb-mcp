(ns bb-mcp.wire.utf8
  "UTF-8 byte arithmetic over characters, with no charset API: every runtime
   that can hand out a char code counts bytes the same way. Pure.")

(defn char-bytes
  "Bytes UTF-8 spends on character code `c` (an int). Either unit a runtime
   hands out counts right: on the JVM a supplementary character is two UTF-16
   surrogates of 2 each; on cljw it is one code point of 4."
  [c]
  (let [c (int c)]
    (cond
      (< c 0x80) 1
      (< c 0x800) 2
      (<= 0xD800 c 0xDFFF) 2
      (< c 0x10000) 3
      :else 4)))

(defn byte-length
  "Bytes string `s` takes in UTF-8."
  [s]
  (reduce + 0 (map #(char-bytes (int %)) s)))
