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
  (let [o (d/observe {:getenv {"BB_MCP_NREPL_PORT" "7920" "QDRANT_HOST" "h"}
                      :home "/h" :cwd "/nonexistent-cwd"
                      :probe #(= 7920 %) :procs (constantly [hive-proc])
                      :config? #(= % "/h/.config/hive-mcp/config.edn")})]
    (is (= {:port 7920 :answers? true :procs [hive-proc]
            :config-path "/h/.config/hive-mcp/config.edn" :config? true
            :ws-port 9999 :ws-answers? false :env-present #{"QDRANT_HOST"}}
           o))))

(defn- plan-for [o opts]
  (d/up-plan (d/run-checks o) opts o))

(def down-obs
  {:port 7910 :answers? false :procs [] :config-path "/h/c.edn" :config? true :home "/h"})

(defn- detail-of [rows step]
  (some #(when (= step (:step %)) (:detail %)) rows))

(deftest ws-and-env-rows-are-added-when-observed
  (let [rows (d/run-checks (obs :ws-port 9999 :ws-answers? false
                                :env-present #{"MILVUS_HOST" "OLLAMA_HOST"}))]
    (is (= 3 (count (d/run-checks (obs)))) "no extra rows without the keys")
    (is (= :warn (status-of rows "ws-channel")))
    (is (str/includes? (detail-of rows "ws-channel") "HIVE_MCP_WS_CHANNEL_PORT"))
    (is (= :warn (status-of rows "milvus-env")))
    (is (str/includes? (detail-of rows "milvus-env") "unset MILVUS_TOKEN"))
    (is (= :warn (status-of rows "qdrant-env")))
    (is (= :ok (status-of rows "embedding-env")))
    (is (= 0 (:exit (d/report rows))) "env and ws gaps never fail the doctor")))

(deftest env-present-keeps-names-never-values
  (let [env {"MILVUS_HOST" "secret-host" "QDRANT_API_KEY" "" "OLLAMA_HOST" "x"}
        present (d/env-present env)]
    (is (= #{"MILVUS_HOST" "OLLAMA_HOST"} present))
    (is (not (str/includes? (pr-str present) "secret-host")))))

(deftest up-plan-starts-only-on-a-free-port
  (is (= :start (:action (plan-for down-obs {:dir "/hive"}))))
  (is (= {"HIVE_MCP_NREPL_PORT" "7910"} (:env (plan-for down-obs {:dir "/hive"}))))
  (is (= :noop (:action (plan-for (assoc down-obs :answers? true) {:dir "/hive"}))))
  (is (= :refuse (:action (plan-for down-obs {}))) "no checkout, no start")
  (is (= :refuse (:action (plan-for (assoc down-obs :procs [hive-proc]) {:dir "/hive"})))
      "a booting JVM blocks a second start"))

(deftest up-never-starts-on-noop-refuse-or-dry-run
  (let [started (atom 0)
        sys (fn [o] {:observe (constantly o) :start! (fn [_] (swap! started inc))
                     :probe (constantly true) :sleep! (constantly nil)})]
    (is (= 0 (:exit (d/up (sys (assoc down-obs :answers? true)) {:dir "/hive"}))))
    (is (= 1 (:exit (d/up (sys down-obs) {}))))
    (let [r (d/up (sys down-obs) {:dir "/hive" :dry-run? true})]
      (is (= 0 (:exit r)))
      (is (str/includes? (:out r) "would start")))
    (is (= 0 @started))
    (is (= 0 (:exit (d/up (sys down-obs) {:dir "/hive"}))))
    (is (= 1 @started))))

(deftest up-fails-when-the-port-never-answers
  (let [r (d/up {:observe (constantly down-obs) :start! (constantly nil)
                 :probe (constantly false) :sleep! (constantly nil)}
                {:dir "/hive"})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:out r) "did not answer"))))

(deftest await-port-retries-then-gives-up
  (let [calls (atom 0)]
    (is (d/await-port (fn [_] (swap! calls inc) (= 3 @calls)) (constantly nil) 1 5 1))
    (is (not (d/await-port (constantly false) (constantly nil) 1 3 1)))))

(deftest parse-up-args-reads-dir-and-dry-run
  (is (= {:opts {:dir "/x" :dry-run? true}} (d/parse-up-args ["--dir" "/x" "--dry-run"])))
  (is (:error (d/parse-up-args ["--dir"])))
  (is (:error (d/parse-up-args ["--bogus"])))
  (is (:help (d/parse-up-args ["-h"]))))
