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
