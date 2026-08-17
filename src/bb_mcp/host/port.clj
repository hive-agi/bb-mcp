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
