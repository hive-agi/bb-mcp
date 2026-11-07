(ns bb-mcp.host.bb
  "Babashka/JVM adapter for bb-mcp.host.port/IByteChannel, over java.net.Socket."
  (:require [bb-mcp.host.port :as hp]
            [cheshire.core :as cheshire]
            [bb-mcp.host.retry :as retry])
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

(def connect-retry-policy
  "Retry a refused connect twice (250 ms, then 1 s) and nothing else. A
   ConnectException is raised by the Socket constructor before any byte is
   written, so a retry can never repeat a request."
  (assoc retry/default-policy
         :retry? #(instance? java.net.ConnectException %)))

(defn- connect!
  "Open one Socket to `host`:`port` with read timeout `timeout-ms`.
   => SocketChannel. Boundary I/O."
  [host port timeout-ms]
  (let [sock (doto (Socket. ^String host ^int port)
               (.setSoTimeout timeout-ms))]
    (->SocketChannel sock
                     (BufferedInputStream. (.getInputStream sock))
                     (BufferedOutputStream. (.getOutputStream sock)))))

(defn open-with
  "Connect to {:host :port :timeout-ms} through `connect` (host port
   timeout-ms -> channel), retried per `policy`, waiting with `sleep!`.
   The seam `open` closes over; tests pass a stub connect and a recording
   sleep."
  [{:keys [connect policy sleep!]} {:keys [host port timeout-ms]}]
  (retry/call-with-retry policy sleep!
                         #(connect (or host "localhost") port (or timeout-ms 600000))))

(defn open
  "Connect to {:host :port :timeout-ms}. => SocketChannel."
  [opts]
  (open-with {:connect connect!
              :policy connect-retry-policy
              :sleep! #(Thread/sleep (long %))}
             opts))

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
