(ns bb-mcp.host.bb
  "Babashka/JVM adapter for bb-mcp.host.port/IByteChannel, over java.net.Socket."
  (:require [bb-mcp.host.port :as hp]
            [cheshire.core :as cheshire]
            [bb-mcp.host.retry :as retry])
  (:import [java.net Socket SocketTimeoutException UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels Selector SelectionKey]
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

;; ── Unix domain socket (JDK NIO, T1) ───────────────────────────────────

(defn- await-ready!
  "Wait until `key`'s channel is ready for `ops`, at most `timeout-ms`
   (0 waits forever, as Socket's SO_TIMEOUT does). Throws
   SocketTimeoutException when the wait runs out."
  [^Selector sel ^SelectionKey key ops timeout-ms]
  (.interestOps key (int ops))
  (let [n (if (pos? timeout-ms) (.select sel (long timeout-ms)) (.select sel))]
    (.clear (.selectedKeys sel))
    (when (zero? n)
      (throw (SocketTimeoutException. (str "Unix socket wait exceeded " timeout-ms " ms"))))))

(defrecord UnixChannel [^java.nio.channels.SocketChannel ch ^Selector sel ^SelectionKey key timeout-ms]
  hp/IByteChannel
  (write-bytes! [_ ba]
    (let [bb (ByteBuffer/wrap ^bytes ba)]
      (while (.hasRemaining bb)
        (when (zero? (.write ch bb))
          (await-ready! sel key SelectionKey/OP_WRITE timeout-ms)))
      nil))
  (read-bytes! [_ buf]
    (let [bb (ByteBuffer/wrap ^bytes buf)]
      (loop []
        (let [n (.read ch bb)]
          (if (zero? n)
            (do (await-ready! sel key SelectionKey/OP_READ timeout-ms) (recur))
            n)))))
  (close! [_]
    (try (.close sel) (finally (.close ch)))))

(defn open-unix
  "Connect to the Unix domain socket {:path :timeout-ms}. => UnixChannel.
   NIO's Unix channels have no SO_TIMEOUT, so the channel is non-blocking
   and every wait goes through a Selector bounded by :timeout-ms. Throws
   when nothing listens at :path. Boundary I/O."
  [{:keys [path timeout-ms]}]
  (let [ch (java.nio.channels.SocketChannel/open
            (UnixDomainSocketAddress/of ^String path))]
    (try
      (.configureBlocking ch false)
      (let [sel (Selector/open)
            key (.register ch sel SelectionKey/OP_READ)]
        (->UnixChannel ch sel key (long (or timeout-ms 600000))))
      (catch Exception e
        (.close ch)
        (throw e)))))

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
