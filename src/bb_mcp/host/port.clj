(ns bb-mcp.host.port
  "The host seam. Everything above this namespace is portable Clojure;
   every runtime-specific byte primitive lives in an adapter below it.

   An adapter namespace supplies `(open opts) => IByteChannel`, where opts is
   {:host str :port int :timeout-ms int}.")

(defprotocol IByteChannel
  "A bidirectional byte channel to a remote endpoint."
  (write-bytes! [ch ba]
    "Write all of byte-array `ba`, then flush. Returns nil.")
  (read-bytes! [ch buf]
    "Read available bytes into byte-array `buf`.
     Returns the count read, or -1 at end of stream.")
  (close! [ch]
    "Release the channel. Idempotent."))

(defprotocol IJsonCodec
  "JSON text <-> Clojure data."
  (encode-json [codec v]
    "Render `v` as a JSON string.")
  (decode-json [codec s]
    "Parse JSON string `s`. Map keys become keywords."))

(def adapter-nses
  "Adapter namespaces in resolution order. The first one that loads AND
   reports `supported?` true serves every host primitive. Each entry names a
   namespace, never a runtime: a runtime is recognised by the capability it
   exposes, not by a flag the head has to be told."
  ['bb-mcp.host.cljw 'bb-mcp.host.bb])

(defonce ^:private adapter
  (atom nil))

(defn install!
  "Pin the host adapter to namespace `ns-sym`, bypassing discovery.
   Returns `ns-sym`."
  [ns-sym]
  (reset! adapter ns-sym))

(defn- usable?
  "True when `ns-sym` loads and declares itself able to serve this runtime."
  [ns-sym]
  (try
    (require ns-sym)
    (boolean @(requiring-resolve (symbol (name ns-sym) "supported?")))
    (catch Exception _ false)))

(defn adapter-ns
  "The installed adapter namespace, discovering one on first use.
   Throws when no candidate in `adapter-nses` can serve this runtime."
  []
  (or @adapter
      (install! (or (first (filter usable? adapter-nses))
                    (throw (ex-info "No bb-mcp host adapter can serve this runtime"
                                    {:tried adapter-nses}))))))

(defn- host-var
  "Var `nm` in the installed adapter namespace."
  [nm]
  (requiring-resolve (symbol (name (adapter-ns)) (name nm))))

(defn open
  "Open an IByteChannel to {:host :port :timeout-ms} via the host adapter."
  [opts]
  ((host-var 'open) opts))

(defn json-encode
  "Render `v` as a JSON string via the host adapter's codec."
  [v]
  (encode-json @(host-var 'json) v))

(defn json-decode
  "Parse JSON string `s` via the host adapter's codec. Keys become keywords."
  [s]
  (decode-json @(host-var 'json) s))

(defn session-id
  "Stable id for this bb-mcp session, used to isolate per-session cursors.

   `BB_MCP_SESSION_ID` wins when the launcher sets it — the only answer that
   survives a runtime with no process introspection at all. Otherwise the host
   adapter supplies its best stable id."
  []
  (or (System/getenv "BB_MCP_SESSION_ID")
      ((host-var 'session-id))))
