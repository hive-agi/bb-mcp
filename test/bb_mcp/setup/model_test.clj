(ns bb-mcp.setup.model-test
  "Pure planning for bb-mcp setup: no filesystem, no processes."
  (:require [bb-mcp.setup.model :as m]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(def home "/h/me")
(def checkout "/src/bb-mcp")
(def lay (m/layout home))
(def launcher (:launcher lay))

(defn- ctx [& {:as more}]
  (merge {:home home :checkout checkout :layout lay :opts {:client :all :scope :user}} more))

(deftest layout-is-the-contract-anchor
  (is (= "/h/me/.local/share/hive-mcp/bb-mcp" (:anchor lay)))
  (is (= "/h/me/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh" launcher))
  (is (= "${HOME}/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh" m/literal-launcher)))

(deftest plan-anchor-states
  (testing "absent: create the parent and the link"
    (let [s (m/plan-anchor (ctx :anchor-obs {:kind :absent}))]
      (is (= :change (:status s)))
      (is (= [[:mkdirs (:parent lay)] [:symlink (:anchor lay) checkout]] (:ops s)))))
  (testing "symlink to this checkout: nothing to do"
    (let [s (m/plan-anchor (ctx :anchor-obs {:kind :symlink :target checkout :resolved checkout}))]
      (is (= :ok (:status s)))
      (is (nil? (:ops s)))))
  (testing "symlink elsewhere: replaced, previous target reported"
    (let [s (m/plan-anchor (ctx :anchor-obs {:kind :symlink :target "/old/bb-mcp" :resolved "/old/bb-mcp"}))]
      (is (= :change (:status s)))
      (is (= "/old/bb-mcp" (:previous s)))
      (is (= [:delete-link (:anchor lay)] (first (:ops s))))
      (is (= [:symlink (:anchor lay) checkout] (last (:ops s))))))
  (testing "dangling symlink: replaced"
    (is (= :change (:status (m/plan-anchor (ctx :anchor-obs {:kind :symlink :target "/gone"}))))))
  (testing "a real directory or file is refused, never deleted"
    (doseq [kind [:dir :file]]
      (let [s (m/plan-anchor (ctx :anchor-obs {:kind kind}))]
        (is (= :refused (:status s)))
        (is (nil? (:ops s)))))))

(deftest desired-env-keeps-user-vars-drops-stale
  (is (= {} (m/desired-env nil nil)))
  (is (= {"BB_MCP_RUNTIME" "cljw"}
         (m/desired-env {"BB_MCP_RUNTIME" "cljw" "HIVE_MCP_DIR" "x" "BB_MCP_NREPL_PORT" "7910"} nil)))
  (is (= {"BB_MCP_NREPL_PORT" "7920"} (m/desired-env {"HIVE_MCP_DIR" "x"} 7920))))

(deftest plan-claude-user-entry
  (testing "current entry: ok"
    (is (= :ok (:status (m/plan-claude-user (ctx) {"type" "stdio" "command" launcher "args" [] "env" {}})))))
  (testing "stale entry: remove (ignoring not-found) then add through the anchor"
    (let [s (m/plan-claude-user (ctx) {"command" "/old/start-bb-mcp.sh" "env" {"HIVE_MCP_DIR" "x"}})
          [[_ rm rm-opts] [_ add]] (:ops s)]
      (is (= :change (:status s)))
      (is (= ["claude" "mcp" "remove" "--scope" "user" "hive"] rm))
      (is (:ignore-failure? rm-opts))
      (is (= ["claude" "mcp" "add" "--scope" "user" "hive" "--" launcher] add))))
  (testing "--port becomes an -e flag before the -- separator"
    (let [[_ [_ add]] (:ops (m/plan-claude-user (ctx :opts {:port 7920}) nil))]
      (is (= ["claude" "mcp" "add" "--scope" "user" "hive" "-e" "BB_MCP_NREPL_PORT=7920" "--" launcher] add))))
  (testing "args pinning a project make the entry stale"
    (is (= :change (:status (m/plan-claude-user (ctx) {"command" launcher "args" ["/p"]}))))))

(deftest mcp-json-merge
  (let [doc {"mcpServers" {"other" {"command" "x" "args" ["y"]}
                           "hive" {"command" "/old" "env" {"HIVE_MCP_DIR" "z" "KEEP" "1"}}}
             "extra" true}
        merged (m/merge-mcp-json doc nil)]
    (testing "other servers and keys preserved"
      (is (= {"command" "x" "args" ["y"]} (get-in merged ["mcpServers" "other"])))
      (is (true? (get merged "extra"))))
    (testing "hive uses the literal ${HOME} launcher, user env kept, stale env dropped"
      (is (= {"type" "stdio" "command" m/literal-launcher "env" {"KEEP" "1"}}
             (get-in merged ["mcpServers" "hive"]))))
    (testing "idempotent"
      (is (= merged (m/merge-mcp-json merged nil)))))
  (testing "absent file"
    (is (= {"mcpServers" {"hive" {"type" "stdio" "command" m/literal-launcher}}}
           (m/merge-mcp-json nil nil)))))

(deftest plan-mcp-json-steps
  (let [path "/p/.mcp.json"]
    (is (= :refused (:status (m/plan-mcp-json path (m/parse-json "{nope") nil))))
    (is (= :ok (:status (m/plan-mcp-json path (m/merge-mcp-json {} nil) nil))))
    (let [s (m/plan-mcp-json path {"mcpServers" {"a" {"command" "b"}}} 7920)
          [[op p text]] (:ops s)]
      (is (= [:write-file path] [op p]))
      (is (= {"type" "stdio" "command" m/literal-launcher "env" {"BB_MCP_NREPL_PORT" "7920"}}
             (get-in (json/parse-string text) ["mcpServers" "hive"]))))))

(deftest classify-commands
  (let [c (ctx)
        other-home (str "/" "home" "/someone-else/bb-mcp/start-bb-mcp.sh")]
    (is (= :ok (first (m/classify-command launcher c))))
    (is (= :ok (first (m/classify-command m/literal-launcher c))))
    (is (re-find #"real checkout" (second (m/classify-command (str checkout "/start-bb-mcp.sh") c))))
    (is (re-find #"another user's home"
                 (second (m/classify-command other-home (ctx :home (str "/" "home" "/me"))))))
    (is (re-find #"no hive entry" (second (m/classify-command nil c))))
    (is (= :fail (first (m/classify-command "/opt/x/start-bb-mcp.sh" c))))))

(deftest check-entry-flags-stale-env-and-args
  (let [rows (m/check-entry "codex" {"command" launcher "args" ["/p"] "env" {"HIVE_MCP_DIR" "x"}} (ctx))]
    (is (= [["codex" :ok] ["codex:env" :fail] ["codex:args" :warn]]
           (map (juxt :step :status) rows)))))

(deftest check-anchor-rows
  (is (= [:ok :ok] (map :status (m/check-anchor (ctx :anchor-obs {:kind :symlink :resolved checkout
                                                                   :dir? true :launcher-exec? true})))))
  (is (= [:warn :ok] (map :status (m/check-anchor (ctx :anchor-obs {:kind :symlink :resolved "/else"
                                                                     :dir? true :launcher-exec? true})))))
  (is (= [:fail :fail] (map :status (m/check-anchor (ctx :anchor-obs {:kind :absent}))))))

(deftest port-resolution
  (is (= 7920 (m/resolve-port {:opt-port 7920 :env-port "1" :port-file "2"})))
  (is (= 1 (m/resolve-port {:env-port " 1 " :port-file "2"})))
  (is (= 2 (m/resolve-port {:env-port "" :port-file "2\n"})))
  (is (= 7910 (m/resolve-port {})))
  (is (= :warn (:status (m/check-port 7910 false)))))

(deftest argument-parsing
  (is (= {:opts {:client :all :scope :user}} (m/parse-args [])))
  (is (= {:opts {:client :codex :scope :project :port 7920 :dry-run? true :check? true :json? true}}
         (m/parse-args ["--client" "codex" "--scope" "project" "--port" "7920"
                        "--dry-run" "--check" "--json"])))
  (is (:error (m/parse-args ["--client" "vim"])))
  (is (:error (m/parse-args ["--port"])))
  (is (:error (m/parse-args ["--port" "70000"])))
  (is (:error (m/parse-args ["--bogus"])))
  (is (:help (m/parse-args ["--help"]))))

(deftest exit-codes
  (is (zero? (m/exit-code [{:status :ok} {:status :warn} {:status :skipped} {:status :planned}])))
  (doseq [bad [:failed :refused :fail]]
    (is (= 1 (m/exit-code [{:status :ok} {:status bad}])))))
