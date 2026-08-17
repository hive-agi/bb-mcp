(ns bb-mcp.host.cljw
  "ClojureWasm adapter for bb-mcp.host.port/IByteChannel, over the `cljw.net`
   thin binding to Zig's std.Io.net.

   Loadable only under cljw — `cljw.net` does not exist on any other host."
  (:require [bb-mcp.host.port :as hp]))

(defrecord CljwChannel [sock]
  hp/IByteChannel
  (write-bytes! [_ ba]
    (.write sock ba)
    nil)
  (read-bytes! [_ buf]
    (.read sock buf))
  (close! [_]
    (.close sock)
    nil))

(defn open
  "Connect to {:host :port}. => CljwChannel.
   `:timeout-ms` is accepted and ignored: cljw.net has no per-read deadline yet."
  [{:keys [host port]}]
  (->CljwChannel (cljw.net/connect (or host "localhost") port)))
