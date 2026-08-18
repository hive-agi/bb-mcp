(ns bb-mcp.host.bb
  "Babashka/JVM adapter for bb-mcp.host.port/IByteChannel, over java.net.Socket."
  (:require [bb-mcp.host.port :as hp]
            [cheshire.core :as cheshire])
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

(defrecord CheshireCodec []
  hp/IJsonCodec
  (encode-json [_ v] (cheshire/generate-string v))
  (decode-json [_ s] (cheshire/parse-string s true)))

(def json
  "The JSON codec for this runtime."
  (->CheshireCodec))

(defn session-id
  "Parent PID — the Claude Code process — so the id survives a bb-mcp restart
   within one session and per-session cursors are preserved. Falls back to own
   PID, then to a random id."
  []
  (let [ppid (try
               (let [parent (.parent (java.lang.ProcessHandle/current))]
                 (when (.isPresent parent)
                   (str (.pid (.get parent)))))
               (catch Exception _ nil))
        pid (try (str (.pid (java.lang.ProcessHandle/current)))
                 (catch Exception _ nil))]
    (or ppid pid (subs (str (java.util.UUID/randomUUID)) 0 8))))

(def supported?
  "This adapter is the JVM/babashka fallback: reaching it means no
   runtime-specific adapter claimed the process, and java.net plus cheshire
   are the assumption of last resort."
  true)
