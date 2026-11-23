(ns bb-mcp.tools.nrepl-test
  "clojure_eval states its port order, and a refused connection names the port,
   where it came from, and that order. The channel and the resolver are
   injected; nothing dials a socket."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-mcp.tools.nrepl :as nrepl]))

(defn- refuse [_] (throw (ex-info "Connection refused" {})))

(deftest the-description-states-the-order-and-the-port-arg
  (let [d (:description nrepl/tool-spec)]
    (doseq [step nrepl/port-steps]
      (is (str/includes? d step) step))
    (is (str/includes? d "NOT your working directory"))
    (is (contains? (get-in nrepl/tool-spec [:schema :properties]) :port))))

(deftest a-refused-connection-names-port-source-and-order
  (testing "a port from the server's project dir"
    (let [r (nrepl/execute {:code "1"}
                           {:open-fn refuse
                            :resolve-port (fn [_] {:port 7946 :source :nrepl-port-file
                                                   :path "/srv/proj/.nrepl-port"})})]
      (is (:error? r))
      (doseq [s ["Connection refused" "port 7946" "/srv/proj/.nrepl-port" "Resolution order" "7910"]]
        (is (str/includes? (:result r) s) s))))
  (testing "an explicit port"
    (let [r (nrepl/execute {:code "1" :port 7999}
                           {:open-fn refuse
                            :resolve-port #(nrepl/resolve-nrepl-port % {:getenv (constantly nil)
                                                                         :read-port-file (constantly nil)})})]
      (is (str/includes? (:result r) "port 7999"))
      (is (str/includes? (:result r) "the explicit port parameter")))))

(deftest the-hive-port-says-where-it-came-from
  (testing "nothing set: hive-mcp's default"
    (is (= {:port 7910 :source :default} (nrepl/resolve-hive-port {}))))
  (testing "a project port is not a hive port"
    (is (= {:port 7910 :source :default}
           (nrepl/resolve-hive-port {"BB_MCP_NREPL_PORT" "7934"}))))
  (testing "HIVE_MCP_NREPL_PORT, trimmed, names its var"
    (is (= {:port 7950 :source :env :env-var "HIVE_MCP_NREPL_PORT"}
           (nrepl/resolve-hive-port {"HIVE_MCP_NREPL_PORT" " 7950\n"}))))
  (testing "a non-number falls back to the default"
    (is (= {:port 7910 :source :default}
           (nrepl/resolve-hive-port {"HIVE_MCP_NREPL_PORT" "  "}))))
  (testing "hive-port is the bare port of the same answer"
    (is (= 7950 (nrepl/hive-port {"HIVE_MCP_NREPL_PORT" "7950"})))))

(deftest the-deprecated-hive-port-name-still-works
  (testing "BB_MCP_HIVE_NREPL_PORT is read, marked deprecated, warned once"
    (let [err (java.io.StringWriter.)
          r (binding [*err* err]
              [(nrepl/resolve-hive-port {"BB_MCP_HIVE_NREPL_PORT" "7951"})
               (nrepl/resolve-hive-port {"BB_MCP_HIVE_NREPL_PORT" "7951"})])]
      (is (= [{:port 7951 :source :env :env-var "BB_MCP_HIVE_NREPL_PORT" :deprecated? true}]
             (distinct r)))
      (is (<= (count (re-seq #"BB_MCP_HIVE_NREPL_PORT is deprecated" (str err))) 1)
            "at most one warning per process")))
  (testing "the new name wins over the old"
    (is (= {:port 7952 :source :env :env-var "HIVE_MCP_NREPL_PORT"}
           (nrepl/resolve-hive-port {"HIVE_MCP_NREPL_PORT" "7952"
                                     "BB_MCP_HIVE_NREPL_PORT" "7951"}))))
  (testing "BB_MCP_NREPL_PORT still picks clojure_eval's port"
    (is (= {:port 7000 :source :env :env-var "BB_MCP_NREPL_PORT"}
           (nrepl/resolve-nrepl-port nil {:getenv {"BB_MCP_NREPL_PORT" "7000"}
                                          :read-port-file (constantly nil)})))))

(deftest the-standard-socket-path
  (testing "XDG_RUNTIME_DIR wins"
    (is (= "/run/user/1000/hive/mcp-nrepl.sock"
           (nrepl/socket-path "/run/user/1000/" "/tmp" 1000 "mcp-nrepl"))))
  (testing "else <tmpdir>/hive-<uid>"
    (is (= "/tmp/hive-1000/mcp-nrepl.sock" (nrepl/socket-path nil "/tmp" 1000 "mcp-nrepl")))
    (is (= "/tmp/hive-7/mcp-nrepl.sock" (nrepl/socket-path "  " "/tmp/" 7 "mcp-nrepl"))))
  (testing "no base known: no path"
    (is (nil? (nrepl/socket-path nil "/tmp" nil "mcp-nrepl")))))

(deftest the-hive-socket-says-where-it-came-from
  (let [base {:tmpdir "/tmp" :uid 1000 :read-socket-file (constantly nil)
              :exists? (constantly false)}]
    (testing "nothing: no socket, so TCP"
      (is (nil? (nrepl/resolve-hive-socket (assoc base :getenv {})))))
    (testing "HIVE_MCP_NREPL_SOCKET first"
      (is (= {:path "/s.sock" :source :env :env-var "HIVE_MCP_NREPL_SOCKET"}
             (nrepl/resolve-hive-socket
              (assoc base :getenv {"HIVE_MCP_NREPL_SOCKET" " /s.sock "
                                   "HIVE_MCP_DIR" "/h"}
                     :read-socket-file (constantly "/f.sock"))))))
    (testing "then <HIVE_MCP_DIR>/.nrepl-socket"
      (is (= {:path "/f.sock" :source :nrepl-socket-file :file "/h/.nrepl-socket"}
             (nrepl/resolve-hive-socket
              (assoc base :getenv {"HIVE_MCP_DIR" "/h"}
                     :read-socket-file {"/h/.nrepl-socket" "/f.sock"})))))
    (testing "then the T2 path, only when it exists"
      (is (= {:path "/run/u/hive/mcp-nrepl.sock" :source :standard-path}
             (nrepl/resolve-hive-socket
              (assoc base :getenv {"XDG_RUNTIME_DIR" "/run/u"}
                     :exists? #{"/run/u/hive/mcp-nrepl.sock"}))))
      (is (nil? (nrepl/resolve-hive-socket
                 (assoc base :getenv {"XDG_RUNTIME_DIR" "/run/u"})))))))

(deftest an-injected-tcp-stub-is-never-bypassed
  (testing "open-fn alone means TCP only, whatever socket discovery would say"
    (let [asked (atom [])]
      (with-redefs [nrepl/resolve-hive-socket (constantly {:path "/live.sock"})]
        (nrepl/eval-code {:port (nrepl/hive-port) :code "1"
                          :open-fn (fn [{:keys [port]}] (swap! asked conj port) (refuse nil))}))
      (is (= [(nrepl/hive-port)] @asked)))))

(deftest a-port-override-turns-socket-discovery-off
  (let [base {:tmpdir "/tmp" :uid 1000
              :read-socket-file {"/h/.nrepl-socket" "/f.sock"}
              :exists? #{"/run/u/hive/mcp-nrepl.sock"}}
        disk {"HIVE_MCP_DIR" "/h" "XDG_RUNTIME_DIR" "/run/u"}]
    (testing "default port: .nrepl-socket, then the T2 path"
      (is (= "/f.sock" (:path (nrepl/resolve-hive-socket (assoc base :getenv disk)))))
      (is (= "/run/u/hive/mcp-nrepl.sock"
             (:path (nrepl/resolve-hive-socket
                     (assoc base :getenv {"XDG_RUNTIME_DIR" "/run/u"}))))))
    (testing "HIVE_MCP_NREPL_PORT set: no discovered socket, TCP only"
      (is (nil? (nrepl/resolve-hive-socket
                 (assoc base :getenv (assoc disk "HIVE_MCP_NREPL_PORT" "7999"))))))
    (testing "the deprecated alias is an override too"
      (is (nil? (binding [*err* (java.io.StringWriter.)]
                  (nrepl/resolve-hive-socket
                   (assoc base :getenv (assoc disk "BB_MCP_HIVE_NREPL_PORT" "7999")))))))
    (testing "an explicit HIVE_MCP_NREPL_SOCKET still wins over a port override"
      (is (= {:path "/s.sock" :source :env :env-var "HIVE_MCP_NREPL_SOCKET"}
             (nrepl/resolve-hive-socket
              (assoc base :getenv (assoc disk "HIVE_MCP_NREPL_PORT" "7999"
                                         "HIVE_MCP_NREPL_SOCKET" "/s.sock"))))))
    (testing "a blank override is no override"
      (is (= "/f.sock" (:path (nrepl/resolve-hive-socket
                               (assoc base :getenv (assoc disk "HIVE_MCP_NREPL_PORT" " ")))))))))

(deftest a-failed-socket-connect-falls-back-inside-the-timeout
  (testing "TCP gets what is left of the caller's timeout, and the socket connect is bounded"
    (let [unix-asked (atom nil) tcp-asked (atom nil)]
      (nrepl/eval-code {:port 7910 :code "1" :timeout-ms 1000 :socket "/gone.sock"
                        :open-unix-fn (fn [o] (reset! unix-asked o) (Thread/sleep 300)
                                        (throw (java.net.SocketTimeoutException. "connect")))
                        :open-fn (fn [o] (reset! tcp-asked o) (refuse nil))})
      (is (<= (:connect-timeout-ms @unix-asked 1000) 500) "the connect leaves TCP at least half")
      (is (<= (:timeout-ms @tcp-asked) 720) "TCP gets the time left, not a fresh timeout")
      (is (pos? (:timeout-ms @tcp-asked)))))
  (testing "no socket: TCP gets the whole timeout, as before"
    (let [tcp-asked (atom nil)]
      (nrepl/eval-code {:port 7910 :code "1" :timeout-ms 1000 :socket nil
                        :open-fn (fn [o] (reset! tcp-asked o) (refuse nil))})
      (is (= 1000 (:timeout-ms @tcp-asked))))))
