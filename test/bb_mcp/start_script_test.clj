(ns bb-mcp.start-script-test
  "start-bb-mcp.sh warns at startup when the nREPL port it took from a
   project's .nrepl-port does not answer. The script is run for real with an
   unknown BB_MCP_RUNTIME, so it stops (exit 2) right after resolving the
   port, before any server starts. Needs a subprocess, so it is a host suite."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private script
  (str (fs/absolutize "start-bb-mcp.sh")))

(defn- run-script
  "Run the start script against PROJECT with EXTRA env; {:exit :err}."
  [project extra]
  (let [r (p/sh {:extra-env (merge {"BB_MCP_RUNTIME" "no-such-runtime"
                                    "BB_MCP_NO_UPDATE_CHECK" "1"}
                                   extra)
                 :continue true}
                "timeout" "20" "bash" script project)]
    {:exit (:exit r) :err (:err r)}))

(defn- free-port
  "A local port nothing listens on: bound once, then released."
  []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(deftest a-dead-project-nrepl-port-is-reported-at-startup
  (let [project (str (fs/create-temp-dir {:prefix "bb-mcp-port"}))
        port    (free-port)]
    (spit (str (fs/path project ".nrepl-port")) (str port "\n"))
    (let [{:keys [exit err]} (run-script project {"BB_MCP_NREPL_PORT" ""})]
      (is (= 2 exit) "the unknown runtime stops the script after port resolution")
      (is (str/includes? err "does not answer"))
      (is (str/includes? err (str port))))))

(deftest a-live-or-explicit-port-is-not-reported
  (testing "a project port something listens on"
    (with-open [s (java.net.ServerSocket. 0)]
      (let [project (str (fs/create-temp-dir {:prefix "bb-mcp-port"}))]
        (spit (str (fs/path project ".nrepl-port")) (str (.getLocalPort s)))
        (is (not (str/includes? (:err (run-script project {"BB_MCP_NREPL_PORT" ""}))
                                "does not answer"))))))
  (testing "a port given by env is the caller's choice, not a stale file"
    (let [project (str (fs/create-temp-dir {:prefix "bb-mcp-port"}))]
      (spit (str (fs/path project ".nrepl-port")) (str (free-port)))
      (is (not (str/includes? (:err (run-script project {"BB_MCP_NREPL_PORT" "7910"}))
                              "does not answer"))))))
