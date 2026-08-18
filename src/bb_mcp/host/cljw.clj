(ns bb-mcp.host.cljw
  "ClojureWasm adapter for the bb-mcp host seam.

   Bytes move over `cljw.net/connect` — a cljw-original TCP client bound to
   Zig's `std.Io.net`, not a java.net emulation. JSON goes through cljw's
   `clojure.data.json`.

   No cljw namespace is named at read time, so this file also loads on a
   runtime that has none; there it simply reports `supported?` false and the
   seam moves on to the next adapter."
  (:require [bb-mcp.host.port :as hp]))

(defn- host-fn
  "The var `sym` names on this runtime, or nil when it has no such var.
   Tries a plain resolve first: a host namespace the runtime interns itself
   has no file behind it, so `require` would fail on the very thing that is
   present."
  [sym]
  (or (try (resolve sym) (catch Exception _ nil))
      (try (requiring-resolve sym) (catch Exception _ nil))))

(def ^:private connect-fn (host-fn 'cljw.net/connect))
(def ^:private write-str (host-fn 'clojure.data.json/write-str))
(def ^:private read-str (host-fn 'clojure.data.json/read-str))

(def supported?
  "True on a runtime exposing `cljw.net` — the whole discriminator."
  (some? connect-fn))

(defrecord CljwChannel [socket]
  hp/IByteChannel
  (write-bytes! [_ ba]
    (.write socket ba)
    nil)
  (read-bytes! [_ buf]
    (.read socket buf))
  (close! [_]
    (.close socket)))

(defn open
  "Connect to {:host :port}. => CljwChannel.
   `:timeout-ms` is accepted for contract parity and ignored: cljw.net has no
   per-read deadline, so a read blocks until bytes or end of stream."
  [{:keys [host port]}]
  (->CljwChannel (connect-fn (or host "localhost") port)))

(defrecord DataJsonCodec []
  hp/IJsonCodec
  (encode-json [_ v] (write-str v))
  (decode-json [_ s] (read-str s :key-fn keyword)))

(def json
  "The JSON codec for this runtime."
  (->DataJsonCodec))

(defn session-id
  "cljw exposes no process introspection, so a session that wants a stable id
   across restarts must be given one through `BB_MCP_SESSION_ID`. Without it
   this is a fresh id per process, and cursors restart with it."
  []
  (subs (str (java.util.UUID/randomUUID)) 0 8))
