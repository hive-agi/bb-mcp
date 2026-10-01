(ns bb-mcp.setup.clients
  "MCP client registry for `bb-mcp setup`.

   A client is ONE registration: a method each of `observes`, `detected?`,
   `plan` and `check`, keyed by the client keyword. Adding a client never
   edits a conditional; the registered set is read from `plan`'s methods.
   Every method is pure: `observes` only names what the boundary must read,
   and the others work on the context that reading produced."
  (:require [bb-mcp.setup.model :as m]
            [bb-mcp.setup.toml :as toml]))

(defmulti observes
  "{:files [path ...] :dirs [path ...] :bins [name ...]} this client reads."
  (fn [client _ctx] client))

(defmulti detected?
  "True when the client is installed for this user."
  (fn [client _ctx] client))

(defmulti plan
  "Setup steps registering hive with the client."
  (fn [client _ctx] client))

(defmulti check
  "Check rows for the client's hive registration."
  (fn [client _ctx] client))

(defn registered
  "Every registered client keyword, sorted."
  []
  (sort (remove #{:default} (keys (methods plan)))))

(defn- file [ctx path] (get-in ctx [:obs :files path]))
(defn- bin [ctx b] (get-in ctx [:obs :bins b]))
(defn- dir? [ctx path] (get-in ctx [:obs :dirs path]))

(defn targeted?
  "Named by --client, or detected under --client all."
  [client {:keys [opts] :as ctx}]
  (or (= client (:client opts))
      (and (= :all (:client opts)) (boolean (detected? client ctx)))))

;; ---------------------------------------------------------------------------
;; Claude Code

(defn- claude-json [{:keys [home]}] (str home "/.claude.json"))
(defn- project-json [{:keys [cwd]}] (str cwd "/.mcp.json"))

(defn- claude-doc [ctx]
  (let [doc (m/parse-json (file ctx (claude-json ctx)))]
    (when (map? doc) doc)))

(defn- project-doc [ctx]
  (m/parse-json (file ctx (project-json ctx))))

(defmethod observes :claude [_ ctx]
  {:files [(claude-json ctx) (project-json ctx)] :bins ["claude"]})

(defmethod detected? :claude [_ ctx]
  (bin ctx "claude"))

(defmethod plan :claude [_ {:keys [opts] :as ctx}]
  (case (:scope opts)
    :project [(m/plan-mcp-json (project-json ctx) (project-doc ctx) (:port opts))]
    :user    [(if (bin ctx "claude")
                (m/plan-claude-user ctx (get-in (claude-doc ctx) ["mcpServers" m/server-name]))
                (m/step "claude:user" :failed
                        "claude CLI not on PATH; install Claude Code or use --scope project"))]))

(defmethod check :claude [_ {:keys [opts cwd] :as ctx}]
  (let [doc (claude-doc ctx)
        pdoc (project-doc ctx)
        project (when (map? pdoc) (get-in pdoc ["mcpServers" m/server-name]))
        local (get-in doc ["projects" cwd "mcpServers" m/server-name])]
    (cond-> []
      (= :user (:scope opts))
      (into (m/check-entry "claude:user" (get-in doc ["mcpServers" m/server-name]) ctx))
      (or (= :project (:scope opts)) project)
      (into (m/check-entry "claude:project" project ctx))
      local
      (into (m/check-entry "claude:local" local ctx)))))

;; ---------------------------------------------------------------------------
;; Codex

(defn- codex-dir [{:keys [home]}] (str home "/.codex"))
(defn- codex-toml [ctx] (str (codex-dir ctx) "/config.toml"))

(defmethod observes :codex [_ ctx]
  {:files [(codex-toml ctx)] :dirs [(codex-dir ctx)]})

(defmethod detected? :codex [_ ctx]
  (dir? ctx (codex-dir ctx)))

(defmethod plan :codex [_ {:keys [opts layout stamp] :as ctx}]
  (let [path (codex-toml ctx)
        text (file ctx path)
        cmd (:launcher layout)
        env-fn #(m/desired-env % (:port opts))
        entry (toml/hive-entry text)
        note (when (= :project (:scope opts)) " (user config: codex has no project scope)")]
    [(if (m/entry-current? entry cmd (env-fn (get entry "env")))
       (m/step "codex" :ok (str path " -> " cmd note))
       (m/step "codex" :change (str "upsert [mcp_servers.hive] in " path " -> " cmd note)
               :ops (cond-> [[:mkdirs (codex-dir ctx)]]
                      text (conj [:backup path (str path ".bak-" stamp)])
                      true (conj [:write-file path (toml/upsert-hive text cmd env-fn)]))))]))

(defmethod check :codex [_ ctx]
  (m/check-entry "codex" (toml/hive-entry (file ctx (codex-toml ctx))) ctx))

;; ---------------------------------------------------------------------------
;; Aggregates

(defn observation-spec
  "What the boundary reads for every registered client, plus babashka."
  [ctx]
  (reduce (fn [acc c] (merge-with into acc (observes c ctx)))
          {:files [] :dirs [] :bins ["bb"]}
          (registered)))

(defn- not-targeted [client]
  (m/step (name client) :skipped
          (str (name client) " not detected; pass --client " (name client) " to set it up anyway")))

(defn- per-client [f ctx]
  (mapcat (fn [c] (if (targeted? c ctx) (f c ctx) [(not-targeted c)])) (registered)))

(defn setup-steps
  "Every setup step for `ctx`: the anchor, then each client."
  [ctx]
  (into [(m/plan-anchor ctx)] (per-client plan ctx)))

(defn check-rows
  "Every check row for `ctx`."
  [{:keys [nrepl] :as ctx}]
  (-> (m/check-anchor ctx)
      (conj (m/check-bin "bb" (bin ctx "bb")))
      (into (per-client check ctx))
      (conj (m/check-port (:port nrepl) (:answers? nrepl)))))
