(ns bb-mcp.host.bb
  "Babashka/JVM adapter for bb-mcp.host.port/IByteChannel, over java.net.Socket."
  (:require [bb-mcp.host.port :as hp]
            [cheshire.core :as cheshire]
            [bb-mcp.host.retry :as retry])
  (:import [java.net Socket SocketTimeoutException UnixDomainSocketAddress
            StandardProtocolFamily]
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
   SocketTimeoutException only once the deadline has passed: `select`
   returning 0 early (a `wakeup`, a spurious return) waits again for the
   time that is left. An interrupted thread gets an IOException at once,
   not a timeout and not a busy loop."
  [^Selector sel ^SelectionKey key ops timeout-ms]
  (.interestOps key (int ops))
  (let [deadline (when (pos? timeout-ms) (+ (System/currentTimeMillis) timeout-ms))]
    (loop []
      (let [left (when deadline (- deadline (System/currentTimeMillis)))]
        (when (and left (not (pos? left)))
          (throw (SocketTimeoutException. (str "Unix socket wait exceeded " timeout-ms " ms"))))
        (let [n (if left (.select sel (long left)) (.select sel))
              ready? (or (pos? n) (.contains (.selectedKeys sel) key))]
          (.clear (.selectedKeys sel))
          (cond
            ready? nil
            (.isInterrupted (Thread/currentThread))
            (throw (java.io.IOException. "interrupted waiting on a Unix socket"))
            :else (recur)))))))

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
  "Connect to the Unix domain socket {:path :timeout-ms :connect-timeout-ms}.
   => UnixChannel. NIO's Unix channels have no SO_TIMEOUT, so the channel is
   made non-blocking BEFORE it connects, and every wait (the connect
   included) goes through a Selector: the connect is bounded by
   :connect-timeout-ms (default :timeout-ms), reads and writes by
   :timeout-ms. A listener with a full backlog therefore costs at most the
   connect timeout, never a hang. Throws when nothing listens at :path or
   the connect does not finish in time; on every failure the Selector and
   the channel are both closed. Boundary I/O."
  [{:keys [path timeout-ms connect-timeout-ms]}]
  (let [timeout (long (or timeout-ms 600000))
        connect-timeout (long (or connect-timeout-ms timeout))
        addr (UnixDomainSocketAddress/of ^String path)
        ch (java.nio.channels.SocketChannel/open StandardProtocolFamily/UNIX)
        sel (volatile! nil)
        done (volatile! false)]
    (try
      (.configureBlocking ch false)
      (vreset! sel (Selector/open))
      (let [key (.register ch ^Selector @sel SelectionKey/OP_CONNECT)]
        (when-not (.connect ch addr)
          (await-ready! @sel key SelectionKey/OP_CONNECT connect-timeout)
          (when-not (.finishConnect ch)
            (throw (SocketTimeoutException.
                    (str "Unix socket connect did not finish in " connect-timeout " ms")))))
        (let [uc (->UnixChannel ch @sel key timeout)]
          (vreset! done true)
          uc))
      (finally
        (when-not @done
          (try (some-> ^Selector @sel .close) (catch Exception _ nil))
          (try (.close ch) (catch Exception _ nil)))))))

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
