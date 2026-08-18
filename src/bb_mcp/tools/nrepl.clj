(ns bb-mcp.tools.nrepl
  "nREPL client for delegating Clojure eval to the shared hive-mcp JVM.

   Wire format lives in bb-mcp.wire.bencode (pure); bytes move through
   bb-mcp.host.port/IByteChannel. A different runtime swaps only the adapter
   passed as :open-fn."
  (:require [clojure.string :as str]
            [bb-mcp.wire.bencode :as bencode]
            [bb-mcp.host.port :as hp]))

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

;; ── framing (pure byte bookkeeping) ──────────────────────────────────────────

(defn- ensure-room
  "A buffer holding the first `used` bytes of `buf` with room for `extra` more.
   Returns `buf` itself when it already fits.

   Capacity doubles, so filling a response costs O(size) copying in total. The
   previous shape — a fresh exact-size array per read — was O(size^2 / chunk),
   which is invisible at 4 KB and is the whole cost at a megabyte."
  ^bytes [^bytes buf used extra]
  (let [cap (alength buf)
        need (+ used extra)]
    (if (<= need cap)
      buf
      (let [cap' (loop [c (max cap 65536)] (if (>= c need) c (recur (* 2 c))))
            out (byte-array cap')]
        (System/arraycopy buf 0 out 0 used)
        out))))

;; ── channel boundary (effectful) ─────────────────────────────────────────────

(defn- read-messages!
  "Read bencode messages from `ch` until one carries a done status, or the
   channel ends. Boundary I/O.

   One buffer grows across the whole response and `scanned` remembers where
   the last decode pass stopped, so neither the consumed head nor the
   undecoded tail is ever copied again."
  [ch]
  (let [chunk (byte-array 65536)]
    (loop [acc [] buf (byte-array 65536) used 0 scanned 0]
      (let [[msgs off] (bencode/decode-all buf scanned used)
            acc' (into acc msgs)]
        (if (some #(has-status? % "done") msgs)
          acc'
          (let [n (hp/read-bytes! ch chunk)]
            (if (neg? n)
              acc'
              (let [buf' (ensure-room buf used n)]
                (System/arraycopy chunk 0 buf' used n)
                (recur acc' buf' (+ used n) off)))))))))

;; ── Client ───────────────────────────────────────────────────────────────────

(defprotocol NReplClient
  "Evaluate Clojure code on a remote nREPL endpoint."
  (eval-code* [client code opts]
    "Eval `code` on `client`; returns {:result str :error? bool}."))

(defrecord BencodeNReplClient [host port open-fn]
  NReplClient
  (eval-code* [_ code {:keys [timeout-ms]}]
    (let [ch (volatile! nil)]
      (try
        (vreset! ch (open-fn {:host (or host "localhost")
                              :port port
                              :timeout-ms (or timeout-ms 600000)}))
        (hp/write-bytes! @ch (bencode/encode {:op "eval" :code code}))
        (messages->result (read-messages! @ch))
        (catch Exception e
          {:result (str "nREPL connection failed: " (ex-message e))
           :error? true})
        (finally
          (when-let [c @ch]
            (try (hp/close! c) (catch Exception _ nil))))))))

(defn eval-code
  "Evaluate Clojure code on a remote nREPL server.
   `:open-fn` injects the IByteChannel adapter (default: the host seam's)."
  [{:keys [host port code open-fn] :as opts}]
  (eval-code* (->BencodeNReplClient (or host "localhost") port
                                    (or open-fn hp/open))
              code opts))

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
