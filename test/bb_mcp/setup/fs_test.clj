(ns bb-mcp.setup.fs-test
  "bb-mcp setup end to end against a temporary HOME, cwd and checkout.
   Processes are faked: `claude mcp add/remove` edit the temp ~/.claude.json
   the way the real CLI does, so reruns show idempotence. Nothing here
   touches the real HOME."
  (:require [babashka.fs :as fs]
            [bb-mcp.setup :as setup]
            [bb-mcp.setup.model :as m]
            [bb-mcp.setup.toml :as toml]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def codex-config
  (str/join "\n" ["# mine" "[mcp_servers.other]" "command = \"x\"" ""
                  "[mcp_servers.hive]" "command = \"/opt/old/start-bb-mcp.sh\"" ""
                  "[mcp_servers.hive.env]" "HIVE_MCP_DIR = \"/nowhere\"" ""]))

(defn- world
  "A temp root with home/, project/ and checkout/ (holding an executable
   launcher). `:codex? true` creates ~/.codex/config.toml."
  [& {:keys [codex?]}]
  (let [root (str (fs/real-path (fs/create-temp-dir {:prefix "bb-mcp-setup-"})))
        w {:root root
           :home (str (fs/create-dirs (fs/path root "home")))
           :cwd (str (fs/create-dirs (fs/path root "project")))
           :checkout (str (fs/create-dirs (fs/path root "checkout")))}
        launcher (str (:checkout w) "/start-bb-mcp.sh")]
    (spit launcher "#!/bin/sh\n")
    (fs/set-posix-file-permissions launcher "rwxr-xr-x")
    (when codex?
      (fs/create-dirs (fs/path (:home w) ".codex"))
      (spit (str (:home w) "/.codex/config.toml") codex-config))
    w))

(defn- claude-json [home] (str home "/.claude.json"))

(defn- read-claude [home]
  (when (fs/exists? (claude-json home))
    (json/parse-string (slurp (claude-json home)))))

(defn- fake-claude!
  "Mimic `claude mcp remove/add --scope user` on the temp ~/.claude.json."
  [home argv]
  (let [doc (or (read-claude home) {})
        [_ _ verb _ _ server & more] argv]
    (case verb
      "remove" (if (get-in doc ["mcpServers" server])
                 (do (spit (claude-json home) (json/generate-string (update doc "mcpServers" dissoc server)))
                     {:exit 0 :out "" :err ""})
                 {:exit 1 :out "" :err (str "No MCP server found with name: " server)})
      "add" (let [[opts [_ cmd & args]] (split-with #(not= "--" %) more)
                  env (into {} (map #(str/split % #"=" 2)) (take-nth 2 (rest opts)))]
              (spit (claude-json home)
                    (json/generate-string
                     (assoc-in doc ["mcpServers" server]
                               {"type" "stdio" "command" cmd "args" (vec args) "env" env})))
              {:exit 0 :out "" :err ""}))))

(defn- sys
  "A fake system over world `w`. `bins` maps names to paths (absent = not on PATH)."
  [w & {:keys [bins calls answers?] :or {bins {"bb" "/usr/bin/bb" "claude" "/usr/bin/claude"}}}]
  (let [calls (or calls (atom []))]
    (assoc w
           :calls calls
           :getenv (constantly nil)
           :which #(get bins %)
           :exec (fn [argv] (swap! calls conj argv) (fake-claude! (:home w) argv))
           :probe (constantly (boolean answers?))
           :now (constantly "20260930-120000"))))

(defn- statuses [{:keys [rows]}]
  (into {} (map (juxt :step :status)) rows))

(defn- anchor [w] (:anchor (m/layout (:home w))))
(defn- launcher [w] (:launcher (m/layout (:home w))))
(defn- backups [w]
  (map str (fs/glob (fs/path (:home w) ".codex") "config.toml.bak-*")))

(deftest setup-all-clients-then-rerun-is-idempotent
  (let [w (world :codex? true)
        s (sys w)
        first-run (setup/run s ["--client" "all"])]
    (testing "first run changes the anchor and both clients"
      (is (= 0 (:exit first-run)) (:out first-run))
      (is (= {"anchor" :changed "claude:user" :changed "codex" :changed} (statuses first-run))))
    (testing "the anchor is a symlink to the checkout"
      (is (fs/sym-link? (anchor w)))
      (is (= (:checkout w) (str (fs/real-path (anchor w))))))
    (testing "claude: remove (not found, ignored) then add through the anchor"
      (is (= [["claude" "mcp" "remove" "--scope" "user" "hive"]
              ["claude" "mcp" "add" "--scope" "user" "hive" "--" (launcher w)]]
             @(:calls s)))
      (is (= (launcher w) (get-in (read-claude (:home w)) ["mcpServers" "hive" "command"]))))
    (testing "codex: anchor command, stale env gone, other tables kept, one backup"
      (let [text (slurp (str (:home w) "/.codex/config.toml"))]
        (is (= {"command" (launcher w) "env" {}} (toml/hive-entry text)))
        (is (str/includes? text "# mine"))
        (is (str/includes? text "[mcp_servers.other]"))
        (is (= [codex-config] (map slurp (backups w))))))
    (testing "rerun: everything ok, no process, no new backup"
      (reset! (:calls s) [])
      (let [again (setup/run (assoc s :now (constantly "20260930-130000")) ["--client" "all"])]
        (is (= {"anchor" :ok "claude:user" :ok "codex" :ok} (statuses again)))
        (is (= [] @(:calls s)))
        (is (= 1 (count (backups w))))))
    (testing "--check passes; a silent nREPL only warns"
      (let [c (setup/run s ["--check"])]
        (is (= 0 (:exit c)) (:out c))
        (is (= :warn (get (statuses c) "nrepl")))
        (is (= :ok (get (statuses c) "claude:user")))
        (is (= :ok (get (statuses c) "codex")))))))

(deftest dry-run-writes-nothing
  (let [w (world :codex? true)
        s (sys w)
        r (setup/run s ["--dry-run"])]
    (is (= {"anchor" :planned "claude:user" :planned "codex" :planned} (statuses r)))
    (is (not (fs/exists? (anchor w))))
    (is (= codex-config (slurp (str (:home w) "/.codex/config.toml"))))
    (is (empty? (backups w)))
    (is (= [] @(:calls s)))
    (is (str/includes? (:out r) "+ claude mcp add --scope user hive"))))

(deftest anchor-symlink-elsewhere-is-replaced
  (let [w (world)
        old (str (fs/create-dirs (fs/path (:root w) "old-bb-mcp")))
        _ (fs/create-dirs (fs/parent (anchor w)))
        _ (fs/create-sym-link (anchor w) old)
        r (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) [])
        row (first (:rows r))]
    (is (= :changed (:status row)))
    (is (= old (:previous row)))
    (is (= (:checkout w) (str (fs/real-path (anchor w)))))
    (is (fs/directory? old) "the previous target itself is untouched")))

(deftest real-directory-at-anchor-is-refused
  (let [w (world)
        keep (str (anchor w) "/precious")
        _ (fs/create-dirs (anchor w))
        _ (spit keep "data")
        r (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) [])]
    (is (= 1 (:exit r)))
    (is (= :refused (get (statuses r) "anchor")))
    (is (not (fs/sym-link? (anchor w))))
    (is (= "data" (slurp keep)))))

(deftest project-scope-merges-mcp-json
  (let [w (world)
        path (str (:cwd w) "/.mcp.json")
        _ (spit path (json/generate-string {"mcpServers" {"other" {"command" "o"}}}))
        s (sys w :bins {"bb" "/usr/bin/bb"})
        r (setup/run s ["--client" "claude" "--scope" "project" "--port" "7920"])
        doc (json/parse-string (slurp path))]
    (is (= 0 (:exit r)) (:out r))
    (is (= {"command" "o"} (get-in doc ["mcpServers" "other"])))
    (is (= {"type" "stdio"
            "command" "${HOME}/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh"
            "env" {"BB_MCP_NREPL_PORT" "7920"}}
           (get-in doc ["mcpServers" "hive"])))
    (is (= [] @(:calls s)) "project scope needs no claude CLI")
    (is (= :ok (get (statuses (setup/run s ["--client" "claude" "--scope" "project" "--port" "7920"]))
                    "claude:project")))))

(deftest client-targeting
  (let [w (world)]
    (testing "--client all skips clients that are not installed"
      (let [r (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) [])]
        (is (= {"anchor" :changed "claude" :skipped "codex" :skipped} (statuses r)))))
    (testing "--client claude without the CLI fails the step"
      (let [r (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) ["--client" "claude"])]
        (is (= 1 (:exit r)))
        (is (= :failed (get (statuses r) "claude:user")))))
    (testing "--client codex creates the config when ~/.codex is absent"
      (let [r (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) ["--client" "codex"])]
        (is (= :changed (get (statuses r) "codex")))
        (is (= (launcher w) (get (toml/hive-entry (slurp (str (:home w) "/.codex/config.toml"))) "command")))
        (is (empty? (backups w)) "nothing to back up")))))

(deftest check-before-setup-fails
  (let [w (world :codex? true)
        r (setup/run (sys w :answers? true) ["--check" "--json"])
        rows (json/parse-string (:out r) true)]
    (is (= 1 (:exit r)))
    (is (= "fail" (:status (first (filter #(= "anchor" (:step %)) rows)))))
    (is (= "fail" (:status (first (filter #(= "codex:env" (:step %)) rows)))))
    (is (= "ok" (:status (first (filter #(= "nrepl" (:step %)) rows)))))))

(deftest check-reports-a-newer-release-as-a-warning
  (let [w (world)
        _ (setup/run (sys w :bins {"bb" "/usr/bin/bb"}) [])
        asked (atom [])
        status (fn [d] (fn [checkout] (swap! asked conj checkout) d))
        check (fn [d] (setup/run (assoc (sys w :bins {"bb" "/usr/bin/bb"} :answers? true)
                                        :update-status (status d))
                                 ["--check"]))]
    (testing "behind: a warn row naming both versions; the check still passes"
      (let [r (check {:status :behind :local "1.2.29" :latest "1.2.30"})
            row (first (filter #(= "update" (:step %)) (:rows r)))]
        (is (= 0 (:exit r)) (:out r))
        (is (= :warn (:status row)))
        (is (str/includes? (:detail row) "1.2.29 -> 1.2.30"))
        (is (= [(:checkout w)] @asked) "asked about this checkout")))
    (testing "current or offline: ok"
      (is (= :ok (get (statuses (check {:status :current :local "1.2.30" :latest "1.2.30"})) "update")))
      (is (= :ok (get (statuses (check {:status :unknown :local "1.2.30"})) "update"))))
    (testing "setup itself (no --check) never asks"
      (reset! asked [])
      (setup/run (assoc (sys w :bins {"bb" "/usr/bin/bb"}) :update-status (status nil)) [])
      (is (= [] @asked)))))

(deftest bad-arguments-exit-2
  (is (= 2 (:exit (setup/run (sys (world)) ["--client" "vim"])))))
