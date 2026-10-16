(ns bb-mcp.doctor-test
  "bb doctor: pure checks over stubbed observations. No process is started or
   killed and no port is opened; every effect is an injected stub."
  (:require [bb-mcp.doctor :as d]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def hive-proc {:pid 42 :cmd "/usr/bin/java -m hive-mcp.server.core"})
(def other-proc {:pid 7 :cmd "/usr/bin/python serve.py"})

(defn- obs [& {:as more}]
  (merge {:port 7910 :answers? true :procs [hive-proc other-proc]
          :config-path "/h/.config/hive-mcp/config.edn" :config? true}
         more))

(defn- status-of [rows step]
  (some #(when (= step (:step %)) (:status %)) rows))

(deftest healthy-backend-is-all-ok
  (let [{:keys [rows exit out]} (d/doctor (obs))]
    (is (= 0 exit))
    (is (= [:ok :ok :ok] (mapv :status rows)))
    (is (str/includes? out "pid 42"))
    (is (not (d/port-free? rows)))))

(deftest dead-port-fails-and-names-the-env-var
  (let [{:keys [rows exit]} (d/doctor (obs :answers? false :procs []))]
    (is (= 1 exit))
    (is (= :fail (status-of rows "nrepl-port")))
    (is (= :fail (status-of rows "jvm")))
    (is (str/includes? (:detail (first rows)) "BB_MCP_NREPL_PORT"))
    (is (d/port-free? rows))))

(deftest jvm-absent-but-port-answers-is-only-a-warning
  (let [{:keys [rows exit]} (d/doctor (obs :procs [other-proc]))]
    (is (= :warn (status-of rows "jvm")))
    (is (= 0 exit))))

(deftest missing-config-fails
  (let [{:keys [rows exit]} (d/doctor (obs :config? false))]
    (is (= :fail (status-of rows "config")))
    (is (= 1 exit))
    (is (str/includes? (:detail (last rows)) "/h/.config/hive-mcp/config.edn"))))

(deftest hive-processes-ignores-nil-and-foreign-commands
  (is (= [hive-proc] (d/hive-processes [hive-proc other-proc {:pid 1 :cmd nil}]))))

(deftest observe-reads-through-injected-ports
  (let [o (d/observe {:getenv {"BB_MCP_NREPL_PORT" "7920"}
                      :home "/h" :cwd "/nonexistent-cwd"
                      :probe #(= 7920 %) :procs (constantly [hive-proc])
                      :config? #(= % "/h/.config/hive-mcp/config.edn")})]
    (is (= {:port 7920 :answers? true :procs [hive-proc]
            :config-path "/h/.config/hive-mcp/config.edn" :config? true}
           o))))
