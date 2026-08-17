(ns bb-mcp.tools.nrepl
  "nREPL client for delegating Clojure eval to the shared hive-mcp JVM.

   Wire format lives in bb-mcp.wire.bencode (pure); bytes move through
   bb-mcp.host.port/IByteChannel. A different runtime swaps only the adapter
   passed as :open-fn."
  (:require [clojure.string :as str]
            [bb-mcp.wire.bencode :as bencode]
            [bb-mcp.host.port :as hp]))

(defonce ^:private adapter (atom nil))

(defn set-adapter!
  "Install the IByteChannel open-fn this namespace uses by default.
   Called by the composition root; each runtime installs its own."
  [open-fn]
  (reset! adapter open-fn))

(defn- resolved-adapter
  "The installed adapter, else the JVM/babashka one resolved softly.
   The soft resolve keeps every entry point working without a load-time
   dependency on a host; a runtime lacking java.net.Socket gets nil here and
   installs its own via `set-adapter!`."
  []
  (or @adapter
      (when-let [f (try (requiring-resolve 'bb-mcp.host.bb/open)
                        (catch Throwable _ nil))]
        (reset! adapter f)
        f)))

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

(defn- append-bytes
  "Byte-array holding all of `a` followed by the first `n` bytes of `b`."
  ^bytes [^bytes a ^bytes b n]
  (let [alen (alength a)
        out  (byte-array (+ alen n))]
    (System/arraycopy a 0 out 0 alen)
    (System/arraycopy b 0 out alen n)
    out))

(defn- drop-prefix
  "Byte-array holding `a` from `off` onward."
  ^bytes [^bytes a off]
  (let [len (- (alength a) off)
        out (byte-array len)]
    (System/arraycopy a off out 0 len)
    out))

;; ── channel boundary (effectful) ─────────────────────────────────────────────

(defn- read-messages!
  "Read bencode messages from `ch` until one carries a done status, or the
   channel ends. Boundary I/O."
  [ch]
  (let [buf (byte-array 65536)]
    (loop [acc [] ^bytes pending (byte-array 0)]
      (let [[msgs off] (bencode/decode-all pending (alength pending))
            acc'       (into acc msgs)
            pending'   (if (pos? off) (drop-prefix pending off) pending)]
        (if (some #(has-status? % "done") msgs)
          acc'
          (let [n (hp/read-bytes! ch buf)]
            (if (neg? n)
              acc'
              (recur acc' (append-bytes pending' buf n)))))))))

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
   `:open-fn` injects the IByteChannel adapter; without it the one installed by
   `set-adapter!` is used."
  [{:keys [host port code open-fn] :as opts}]
  (if-let [open (or open-fn (resolved-adapter))]
    (eval-code* (->BencodeNReplClient (or host "localhost") port open) code opts)
    {:result (str "No IByteChannel adapter installed. The composition root must "
                  "call bb-mcp.tools.nrepl/set-adapter! with its host's open-fn.")
     :error? true}))

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
