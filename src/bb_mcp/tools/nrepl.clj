(ns bb-mcp.tools.nrepl
  "nREPL client for delegating Clojure eval to shared JVM."
  (:require [clojure.string :as str])
  (:import [java.net Socket]
           [java.io File OutputStream InputStream
            BufferedInputStream BufferedOutputStream
            ByteArrayOutputStream PushbackInputStream]))

;; =============================================================================
;; Bencode implementation using byte-based I/O
;;
;; Bencode is a BINARY protocol - lengths are in BYTES, not characters.
;; Previous implementation used character streams which corrupted UTF-8.
;; =============================================================================

(declare bencode-to-bytes)

(defn- bencode-string-bytes
  "Encode string to bencode bytes: <byte_length>:<utf8_bytes>"
  ^bytes [^String s]
  (let [str-bytes (.getBytes s "UTF-8")
        str-len (count str-bytes)
        len-str (str str-len)
        len-bytes (.getBytes ^String len-str "US-ASCII")
        len-len (count len-bytes)
        result (byte-array (+ len-len 1 str-len))]
    (System/arraycopy len-bytes 0 result 0 len-len)
    (aset-byte result len-len (byte (int \:)))
    (System/arraycopy str-bytes 0 result (+ len-len 1) str-len)
    result))

(defn- bencode-int-bytes
  "Encode integer to bencode bytes: i<number>e"
  ^bytes [n]
  (.getBytes (str "i" n "e") "US-ASCII"))

(defn- concat-byte-arrays
  "Concatenate multiple byte arrays."
  ^bytes [arrays]
  (let [total (reduce + 0 (map count arrays))
        result (byte-array total)]
    (loop [arrays arrays
           offset 0]
      (if (seq arrays)
        (let [^bytes arr (first arrays)
              len (count arr)]
          (System/arraycopy arr 0 result offset len)
          (recur (rest arrays) (+ offset len)))
        result))))

(defn- bencode-list-bytes
  "Encode list to bencode bytes: l<items>e"
  ^bytes [coll]
  (let [items (mapv bencode-to-bytes coll)]
    (concat-byte-arrays
     (into [(byte-array [(byte (int \l))])]
           (conj (vec items) (byte-array [(byte (int \e))]))))))

(defn- bencode-dict-bytes
  "Encode map to bencode bytes: d<key1><val1>...e"
  ^bytes [m]
  (let [sorted-entries (sort-by (comp str key) m)
        parts (mapcat (fn [[k v]]
                        [(bencode-string-bytes (name k))
                         (bencode-to-bytes v)])
                      sorted-entries)]
    (concat-byte-arrays
     (into [(byte-array [(byte (int \d))])]
           (conj (vec parts) (byte-array [(byte (int \e))]))))))

(defn bencode-to-bytes
  "Convert Clojure data to bencode bytes."
  ^bytes [x]
  (cond
    (string? x) (bencode-string-bytes x)
    (integer? x) (bencode-int-bytes x)
    (map? x) (bencode-dict-bytes x)
    (sequential? x) (bencode-list-bytes x)
    :else (bencode-string-bytes (str x))))

;; =============================================================================
;; Bdecode - reading bencode from byte stream
;; =============================================================================

(declare bdecode-from-stream)

(defn- read-byte!
  "Read a single byte, returning -1 on EOF."
  [^PushbackInputStream in]
  (.read in))

(defn- unread-byte!
  "Push a byte back to the stream."
  [^PushbackInputStream in b]
  (.unread in (int b)))

(defn- read-bencode-string-bytes
  "Read bencode string given its byte length."
  [^PushbackInputStream in length]
  (let [buf (byte-array length)
        n (.read in buf 0 length)]
    (when (= n length)
      (String. buf "UTF-8"))))

(defn- read-bencode-int-from-stream
  "Read bencode integer until 'e'."
  [^PushbackInputStream in]
  (let [baos (ByteArrayOutputStream.)]
    (loop []
      (let [b (read-byte! in)]
        (if (= b (int \e))
          (parse-long (String. (.toByteArray baos) "US-ASCII"))
          (do
            (.write baos b)
            (recur)))))))

(defn- read-length-prefix
  "Read the length prefix of a bencode string (digits before colon)."
  [^PushbackInputStream in first-byte]
  (let [baos (ByteArrayOutputStream.)]
    (.write baos first-byte)
    (loop []
      (let [b (read-byte! in)]
        (if (= b (int \:))
          (parse-long (String. (.toByteArray baos) "US-ASCII"))
          (do
            (.write baos b)
            (recur)))))))

(defn- read-bencode-list-from-stream
  "Read bencode list until 'e'."
  [^PushbackInputStream in]
  (loop [result []]
    (let [b (read-byte! in)]
      (if (= b (int \e))
        result
        (do
          (unread-byte! in b)
          (recur (conj result (bdecode-from-stream in))))))))

(defn- read-bencode-dict-from-stream
  "Read bencode dict until 'e'."
  [^PushbackInputStream in]
  (loop [result {}]
    (let [b (read-byte! in)]
      (if (= b (int \e))
        result
        (do
          (unread-byte! in b)
          (let [k (bdecode-from-stream in)
                v (bdecode-from-stream in)]
            (recur (assoc result (keyword k) v))))))))

(defn bdecode-from-stream
  "Decode bencode from PushbackInputStream."
  [^PushbackInputStream in]
  (let [b (read-byte! in)]
    (when (>= b 0)
      (cond
        (= b (int \i)) (read-bencode-int-from-stream in)
        (= b (int \l)) (read-bencode-list-from-stream in)
        (= b (int \d)) (read-bencode-dict-from-stream in)
        (Character/isDigit (char b))
        (let [length (read-length-prefix in b)]
          (read-bencode-string-bytes in length))
        :else nil))))

;; nREPL client
;; ── nREPL message vocabulary (pure) ──────────────────────────────────────────

(defn- has-status?
  "True when nREPL response `msg` carries status `s`."
  [msg s]
  (some #(= % s) (get msg :status [])))

(defn- reduce-messages
  "Fold nREPL response messages into {:value :out :err :ex}. Pure."
  [messages]
  (reduce (fn [acc msg]
            (cond-> acc
              (:value msg) (update :value str (:value msg))
              (:out msg)   (update :out str (:out msg))
              (:err msg)   (update :err str (:err msg))
              (:ex msg)    (assoc :ex (:ex msg))
              (has-status? msg "eval-error") (update :ex #(or % "eval-error"))))
          {:value "" :out "" :err "" :ex nil}
          messages))

(defn- messages->result
  "Promote nREPL response messages into {:result str :error? bool}. Pure.
   A stream that closed before a done message is reported as an error."
  [messages]
  (if-not (some #(has-status? % "done") messages)
    {:result "No response from nREPL" :error? true}
    (let [{:keys [value out err ex]} (reduce-messages messages)]
      (if ex
        {:result (str "Error: " ex
                      (when (seq err) (str "\n" err))
                      (when (seq value) (str "\n" value)))
         :error? true}
        {:result (if (seq value) value (or out "nil"))
         :error? false}))))

;; ── nREPL socket boundary (effectful) ────────────────────────────────────────

(defn- send-eval-request!
  "Write and flush an nREPL eval request for `code`. Boundary I/O."
  [^OutputStream out code]
  (.write out ^bytes (bencode-to-bytes {:op "eval" :code code}))
  (.flush out))

(defn- read-response-messages
  "Read bencode response maps from `in` until (and including) the done message,
   or until the stream closes. Boundary I/O."
  [^PushbackInputStream in]
  (loop [acc []]
    (if-let [msg (bdecode-from-stream in)]
      (let [acc' (conj acc msg)]
        (if (has-status? msg "done") acc' (recur acc')))
      acc)))

;; ── Client ───────────────────────────────────────────────────────────────────

(defprotocol NReplClient
  "Evaluate Clojure code on a remote nREPL endpoint."
  (eval-code* [client code opts]
    "Eval `code` on `client`; returns {:result str :error? bool}."))

(defrecord BencodeNReplClient [host port]
  NReplClient
  (eval-code* [_ code {:keys [timeout-ms]}]
    (try
      (with-open [socket (doto (Socket. ^String (or host "localhost") ^int port)
                           (.setSoTimeout (or timeout-ms 600000)))
                  out (BufferedOutputStream. (.getOutputStream socket))
                  in  (PushbackInputStream. (BufferedInputStream. (.getInputStream socket)))]
        (send-eval-request! out code)
        (messages->result (read-response-messages in)))
      (catch Exception e
        {:result (str "nREPL connection failed: " (ex-message e))
         :error? true}))))

(defn eval-code
  "Evaluate Clojure code on a remote nREPL server via a BencodeNReplClient."
  [{:keys [host port code] :as opts}]
  (eval-code* (->BencodeNReplClient (or host "localhost") port) code opts))

(def tool-spec
  {:name "clojure_eval"
   :description "Evaluate Clojure code on a shared nREPL server.

Port resolution order:
1. Explicit port parameter
2. BB_MCP_NREPL_PORT env var
3. .nrepl-port file in BB_MCP_PROJECT_DIR
4. Default: 7910 (hive-mcp nREPL)

Examples:
- clojure_eval(code: \"(+ 1 2)\")
- clojure_eval(code: \"(require '[my.ns])\", port: 7910)"
   :schema {:type "object"
            :properties {:code {:type "string"
                                :description "Clojure code to evaluate"}
                         :port {:type "integer"
                                :description "nREPL port (auto-discovered if not specified)"}
                         :timeout_ms {:type "integer"
                                      :description "Timeout in ms (default: 600000 / 10 min). Long-running tools like wave dispatch may take several minutes."}}
            :required ["code"]}})

(defn find-nrepl-port
  "Find nREPL port from .nrepl-port file in given directory."
  [dir]
  (let [port-file (str dir "/.nrepl-port")]
    (when (.exists (java.io.File. port-file))
      (parse-long (str/trim (slurp port-file))))))

(defn get-project-dir
  "Get the target project directory from env or default to cwd."
  []
  (or (System/getenv "BB_MCP_PROJECT_DIR") "."))

(defn get-nrepl-port
  "Get nREPL port from env, .nrepl-port file, or default.
   Default is 7910 for hive-mcp nREPL."
  []
  (or (when-let [env-port (System/getenv "BB_MCP_NREPL_PORT")]
        (parse-long env-port))
      (find-nrepl-port (get-project-dir))
      7910))

(defn execute
  "Execute Clojure code via nREPL."
  [{:keys [code port timeout_ms]}]
  (let [port (or port (get-nrepl-port))]
    (eval-code {:port port
                :code code
                :timeout-ms (or timeout_ms 600000)})))
