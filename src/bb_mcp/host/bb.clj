(ns bb-mcp.host.bb
  "Babashka/JVM adapter for bb-mcp.host.port/IByteChannel, over java.net.Socket."
  (:require [bb-mcp.host.port :as hp])
  (:import [java.net Socket]
           [java.io InputStream OutputStream
            BufferedInputStream BufferedOutputStream]))

(defrecord SocketChannel [^Socket socket ^InputStream in ^OutputStream out]
  hp/IByteChannel
  (write-bytes! [_ ba]
    (.write out ^bytes ba)
    (.flush out))
  (read-bytes! [_ buf]
    (.read in ^bytes buf))
  (close! [_]
    (.close socket)))

(defn open
  "Connect to {:host :port :timeout-ms}. => SocketChannel."
  [{:keys [host port timeout-ms]}]
  (let [sock (doto (Socket. ^String (or host "localhost") ^int port)
               (.setSoTimeout (or timeout-ms 600000)))]
    (->SocketChannel sock
                     (BufferedInputStream. (.getInputStream sock))
                     (BufferedOutputStream. (.getOutputStream sock)))))
