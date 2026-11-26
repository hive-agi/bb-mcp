(ns bb-mcp.tools.nrepl-socket-test
  "bb-mcp prefers hive-mcp's nREPL Unix socket and falls back to TCP. The
   server is a fake: a plain java.nio ServerSocketChannel on a Unix path in a
   temp dir, speaking just enough bencode to answer one eval. JVM/babashka
   only (a host suite): the fake needs java.nio."
  (:require [clojure.test :refer [deftest is testing]]
            [bb-mcp.host.bb :as host-bb]
            [bb-mcp.host.port :as hp]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.wire.bencode :as bencode])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer]
           [java.nio.channels ServerSocketChannel]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-socket-path []
  (str (Files/createTempDirectory "bbmcp-uds" (make-array FileAttribute 0))
       "/mcp-nrepl.sock"))

(defn- fake-nrepl!
  "Bind a fake nREPL on Unix path `path` that answers every eval with
   `value`. Returns {:server ch :seen atom-of-request-strings}."
  [path value]
  (let [server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of ^String path)))
        seen (atom [])]
    (future
      (try
        (loop []
          (let [c (.accept server)
                buf (ByteBuffer/allocate 4096)]
            (.read c buf)
            (swap! seen conj (String. (.array buf) 0 (.position buf) "UTF-8"))
            (.write c (ByteBuffer/wrap
                       (bencode/encode {:value value :status ["done"]})))
            (.close c)
            (recur)))
        (catch Exception _ nil)))
    {:server server :seen seen}))

(defn- stop! [{:keys [server]} path]
  (.close server)
  (.delete (java.io.File. ^String path)))

(defn- recording-tcp
  "A TCP open-fn that records the port it was asked for, then refuses."
  [asked]
  (fn [{:keys [port]}]
    (swap! asked conj port)
    (throw (java.net.ConnectException. "Connection refused"))))

(deftest the-socket-is-used-when-present
  (let [path (temp-socket-path)
        fake (fake-nrepl! path "3")
        asked (atom [])]
    (try
      (let [r (nrepl/eval-code {:port 7910 :code "(+ 1 2)" :timeout-ms 5000
                                :socket path
                                :open-fn (recording-tcp asked)
                                :open-unix-fn host-bb/open-unix})]
        (is (= {:result "3" :error? false} r))
        (is (= [] @asked) "TCP was never dialled")
        (is (re-find #"\(\+ 1 2\)" (first @(:seen fake))) "the eval reached the socket"))
      (testing "through the default host seam and socket discovery"
        ;; hp/open refuses, so a missed socket can never reach a live 7910.
        (with-redefs [nrepl/resolve-hive-socket (constantly {:path path :source :env})
                      hp/open (fn [_] (throw (ex-info "TCP must not be dialled" {})))]
          (is (= {:result "3" :error? false}
                 (nrepl/eval-code {:port (nrepl/hive-port) :code "1" :timeout-ms 5000})))))
      (finally (stop! fake path)))))

(deftest tcp-is-used-when-no-socket-exists
  (testing "a socket path nothing listens on falls back to TCP, same port"
    (let [asked (atom [])
          r (nrepl/eval-code {:port 7910 :code "1" :timeout-ms 2000
                              :socket (temp-socket-path)
                              :open-fn (recording-tcp asked)
                              :open-unix-fn host-bb/open-unix})]
      (is (= [7910] @asked))
      (is (:connect-failed? r))
      (is (re-find #"Connection refused" (:result r)))))
  (testing "discovery finds no socket: TCP exactly as before"
    (let [asked (atom [])]
      (with-redefs [nrepl/resolve-hive-socket (constantly nil)]
        (nrepl/eval-code {:port (nrepl/hive-port) :code "1"
                          :open-fn (recording-tcp asked)
                          :open-unix-fn (fn [_] (throw (ex-info "must not dial" {})))}))
      (is (= [(nrepl/hive-port)] @asked))))
  (testing "a port that is not the hive host's never takes the hive socket"
    (let [asked (atom []) unix (atom 0)]
      (with-redefs [nrepl/resolve-hive-socket (constantly {:path "/x.sock"})]
        (nrepl/eval-code {:port 1 :code "1"
                          :open-fn (recording-tcp asked)
                          :open-unix-fn (fn [_] (swap! unix inc) nil)}))
      (is (= [1] @asked))
      (is (zero? @unix)))))

(deftest a-unix-read-honours-the-timeout
  (let [path (temp-socket-path)
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of ^String path)))]
    (try
      (let [t0 (System/currentTimeMillis)
            r (nrepl/eval-code {:port 7910 :code "1" :timeout-ms 300
                                :socket path
                                :open-fn (fn [_] (throw (ex-info "no tcp" {})))
                                :open-unix-fn host-bb/open-unix})]
        (is (:error? r))
        (is (< (- (System/currentTimeMillis) t0) 5000) "a silent server does not hang the call"))
      (finally
        (.close server)
        (.delete (java.io.File. ^String path))))))

(defn- full-backlog-server!
  "A Unix listener with backlog 1 that never accepts, its queue filled by
   non-blocking connects until the kernel refuses more. Returns
   {:server ch :fillers [ch...]}."
  [path]
  (let [addr (UnixDomainSocketAddress/of ^String path)
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind addr (int 1)))
        fillers (loop [i 0 acc []]
                  (let [c (doto (java.nio.channels.SocketChannel/open StandardProtocolFamily/UNIX)
                            (.configureBlocking false))
                        ok? (try (.connect c addr) true (catch Exception _ (.close c) false))]
                    (if (and ok? (< i 16)) (recur (inc i) (conj acc c)) acc)))]
    {:server server :fillers fillers}))

(deftest a-unix-connect-is-bounded-on-a-full-backlog
  (let [path (temp-socket-path)
        {:keys [server fillers]} (full-backlog-server! path)]
    (try
      (testing "open-unix returns or throws within its connect timeout, never hangs"
        (let [t0 (System/currentTimeMillis)
              f (future (try (host-bb/open-unix {:path path :timeout-ms 60000
                                                 :connect-timeout-ms 300})
                             (catch Exception e e)))
              r (deref f 3000 ::hung)]
          (is (not= ::hung r) "the connect did not block past its timeout")
          (is (instance? Exception r) "a full backlog is a failed connect")
          (is (< (- (System/currentTimeMillis) t0) 3000))))
      (testing "eval-code then falls back to TCP"
        (let [asked (atom [])
              f (future (nrepl/eval-code {:port 7910 :code "1" :timeout-ms 4000
                                          :socket path
                                          :open-fn (recording-tcp asked)
                                          :open-unix-fn host-bb/open-unix}))
              r (deref f 5000 ::hung)]
          (is (not= ::hung r))
          (is (= [7910] @asked) "TCP was tried after the socket could not connect")))
      (finally
        (doseq [c fillers] (.close ^java.nio.channels.SocketChannel c))
        (.close server)
        (.delete (java.io.File. ^String path))))))

(deftest a-selector-wakeup-is-not-a-timeout
  (let [path (temp-socket-path)
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of ^String path)))
        answer (future
                 (let [c (.accept server)]
                   (Thread/sleep 600)
                   (.write c (ByteBuffer/wrap (.getBytes "hi" "UTF-8")))
                   c))
        ch (host-bb/open-unix {:path path :timeout-ms 5000})
        stop (atom false)
        waker (future (while (not @stop)
                        (Thread/sleep 50)
                        (.wakeup ^java.nio.channels.Selector (:sel ch))))]
    (try
      (let [buf (byte-array 16)
            n (try (hp/read-bytes! ch buf) (catch Exception e e))]
        (is (= 2 n) "the read waited through the wakeups for the answer"))
      (finally
        (reset! stop true)
        @waker
        (hp/close! ch)
        (.close ^java.nio.channels.SocketChannel (deref answer 2000 nil))
        (.close server)
        (.delete (java.io.File. ^String path))))))

(deftest a-sent-request-is-never-retried-over-tcp
  (let [path (temp-socket-path)
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of ^String path)))
        seen (atom 0)
        hangup (future
                 (let [c (.accept server)]
                   (.read c (ByteBuffer/allocate 4096))
                   (swap! seen inc)
                   (.close c)))]
    (try
      (let [asked (atom [])
            r (nrepl/eval-code {:port 7910 :code "(launch!)" :timeout-ms 3000
                                :socket path
                                :open-fn (recording-tcp asked)
                                :open-unix-fn host-bb/open-unix})]
        (deref hangup 3000 nil)
        (is (:error? r))
        (is (= 1 @seen) "the request reached the socket once")
        (is (= [] @asked) "and was not sent again over TCP"))
      (finally
        (.close server)
        (.delete (java.io.File. ^String path))))))
