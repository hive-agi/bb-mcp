(ns bb-mcp.setup.toml-test
  "Codex config.toml upsert: pure text in, text out."
  (:require [bb-mcp.setup.model :as m]
            [bb-mcp.setup.toml :as toml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def cmd "/h/me/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh")

(defn- env-fn [port] #(m/desired-env % port))

(def config
  (str/join "\n"
            ["# top comment"
             "model = \"o3\""
             ""
             "[mcp_servers.other]"
             "command = \"x\"  # keep me"
             ""
             "[mcp_servers.hive]"
             "# hive comment"
             "command = \"/opt/old/bb-mcp/start-bb-mcp.sh\""
             "args = ["
             "  \"/srv/hive-mcp\", # pinned"
             "]"
             "startup_timeout_sec = 30"
             ""
             "[mcp_servers.hive.env]"
             "BB_MCP_NREPL_PORT = \"7910\""
             "BB_MCP_RUNTIME = \"cljw\""
             "HIVE_MCP_DIR = \"/x/hive-mcp\""
             ""
             "[mcp_servers.hive.tools.multi]"
             "approval_mode = \"approve\""
             ""]))

(deftest reads-the-hive-entry
  (is (= {"command" "/opt/old/bb-mcp/start-bb-mcp.sh"
          "args" ["/srv/hive-mcp"]
          "env" {"BB_MCP_NREPL_PORT" "7910" "BB_MCP_RUNTIME" "cljw" "HIVE_MCP_DIR" "/x/hive-mcp"}}
         (toml/hive-entry config)))
  (is (nil? (toml/hive-entry "model = 1\n")))
  (is (nil? (toml/hive-entry nil))))

(deftest upsert-preserves-everything-else
  (let [out (toml/upsert-hive config cmd (env-fn nil))]
    (testing "every other table, key and comment kept verbatim"
      (doseq [line ["# top comment" "model = \"o3\"" "[mcp_servers.other]"
                    "command = \"x\"  # keep me" "# hive comment" "startup_timeout_sec = 30"
                    "BB_MCP_RUNTIME = \"cljw\"" "[mcp_servers.hive.tools.multi]"
                    "approval_mode = \"approve\""]]
        (is (str/includes? out line) line)))
    (testing "command points at the anchor; args, stale and default-port env gone"
      (is (= {"command" cmd "env" {"BB_MCP_RUNTIME" "cljw"}} (toml/hive-entry out)))
      (is (not (str/includes? out "HIVE_MCP_DIR")))
      (is (not (str/includes? out "/srv/hive-mcp"))))
    (testing "idempotent"
      (is (= out (toml/upsert-hive out cmd (env-fn nil)))))
    (testing "only the hive tables changed"
      (is (= (count (re-seq #"\n\[" config)) (count (re-seq #"\n\[" out)))))))

(deftest upsert-sets-the-port-in-the-existing-env-table
  (let [out (toml/upsert-hive config cmd (env-fn 7920))]
    (is (= {"BB_MCP_RUNTIME" "cljw" "BB_MCP_NREPL_PORT" "7920"} (get (toml/hive-entry out) "env")))
    (is (= 1 (count (re-seq #"BB_MCP_NREPL_PORT" out))))))

(deftest upsert-appends-a-new-table
  (testing "into a config without one"
    (is (= "model = 1\n\n[mcp_servers.hive]\ncommand = \"/C\"\n"
           (toml/upsert-hive "model = 1\n" "/C" (env-fn nil))))
    (is (= "model = 1\n\n[mcp_servers.hive]\ncommand = \"/C\"\n"
           (toml/upsert-hive "model = 1" "/C" (env-fn nil)))))
  (testing "into an empty or absent config"
    (is (= "[mcp_servers.hive]\ncommand = \"/C\"\n" (toml/upsert-hive "" "/C" (env-fn nil))))
    (is (= "[mcp_servers.hive]\ncommand = \"/C\"\nenv = { BB_MCP_NREPL_PORT = \"7920\" }\n"
           (toml/upsert-hive nil "/C" (env-fn 7920))))))

(deftest upsert-rewrites-an-inline-env
  (let [in "[mcp_servers.hive]\ncommand=\"/C\"\nenv = { HIVE_MCP_DIR = \"x\", A = \"1\" }\n"]
    (is (= {"command" "/C" "env" {"A" "1" "BB_MCP_NREPL_PORT" "7"}}
           (toml/hive-entry (toml/upsert-hive in "/C" (env-fn 7)))))
    (is (not (str/includes? (toml/upsert-hive "[mcp_servers.hive]\nenv = { HIVE_MCP_DIR = \"x\" }\n" "/C" (env-fn nil))
                            "env =")))))

(deftest quoting
  (is (= "\"a\\\"b\\\\c\"" (toml/quote-str "a\"b\\c")))
  (is (= "a\"b\\c" (get (toml/hive-entry (toml/upsert-hive "" "a\"b\\c" (env-fn nil))) "command"))))
