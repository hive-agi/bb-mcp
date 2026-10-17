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
  (testing "BB_MCP_HIVE_NREPL_PORT, trimmed, names its var"
    (is (= {:port 7950 :source :env :env-var "BB_MCP_HIVE_NREPL_PORT"}
           (nrepl/resolve-hive-port {"BB_MCP_HIVE_NREPL_PORT" " 7950\n"}))))
  (testing "a non-number falls back to the default"
    (is (= {:port 7910 :source :default}
           (nrepl/resolve-hive-port {"BB_MCP_HIVE_NREPL_PORT" "  "}))))
  (testing "hive-port is the bare port of the same answer"
    (is (= 7950 (nrepl/hive-port {"BB_MCP_HIVE_NREPL_PORT" "7950"})))))
