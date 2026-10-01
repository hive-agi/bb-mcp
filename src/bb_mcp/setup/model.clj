(ns bb-mcp.setup.model
  "Pure planning primitives for `bb-mcp setup`.

   Everything here maps observed state (plain data) to steps (plain data).
   No filesystem, no processes, no clock: the boundary collects those into
   the context first (bb-mcp.setup.io) and applies the ops afterwards.

   A step is {:step id :status s :detail str & {:ops [...]}} where the
   planned status is one of
     :ok       nothing to do, already in the desired state
     :change   :ops bring it to the desired state
     :refused  a precondition the tool will not override (reason in :detail)
     :skipped  not applicable here
     :failed   cannot be done here (reason in :detail)
   and an op is a vector [op-keyword & args] interpreted by io/apply-op!.
   Check rows use :ok, :warn and :fail."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The contract's fixed names

(def anchor-rel
  "ANCHOR, relative to $HOME."
  ".local/share/hive-mcp/bb-mcp")

(def launcher-name "start-bb-mcp.sh")

(def server-name
  "The MCP server name every client registers."
  "hive")

(def literal-launcher
  "The launcher as written in JSON configs that expand env vars."
  (str "${HOME}/" anchor-rel "/" launcher-name))

(def port-var "BB_MCP_NREPL_PORT")

(def stale-env-vars
  "Env vars that must never appear in an MCP client config."
  #{"HIVE_MCP_DIR"})

(def default-port 7910)

(defn layout
  "Absolute ANCHOR paths for `home`."
  [home]
  (let [anchor (str home "/" anchor-rel)]
    {:parent   (str home "/.local/share/hive-mcp")
     :anchor   anchor
     :launcher (str anchor "/" launcher-name)}))

(defn step
  "A step map; `more` adds keys such as :ops or :previous."
  [id status detail & {:as more}]
  (merge {:step id :status status :detail detail} more))

;; ---------------------------------------------------------------------------
;; Anchor

(defn plan-anchor
  "Plan ANCHOR -> checkout from the observed anchor
   {:kind :absent|:symlink|:dir|:file :target raw :resolved real-path}."
  [{:keys [checkout layout anchor-obs]}]
  (let [{:keys [kind target resolved]} anchor-obs
        {:keys [parent anchor]} layout
        link [[:mkdirs parent] [:symlink anchor checkout]]]
    (case kind
      :absent  (step "anchor" :change (str anchor " -> " checkout) :ops link)
      :symlink (if (= resolved checkout)
                 (step "anchor" :ok (str anchor " -> " checkout))
                 (step "anchor" :change
                       (str anchor " -> " checkout " (previous: " target ")")
                       :previous target
                       :ops (into [[:delete-link anchor]] link)))
      (step "anchor" :refused
            (str anchor " exists and is not a symlink; it is never deleted."
                 " Move it away and rerun bb-mcp setup.")))))

;; ---------------------------------------------------------------------------
;; Env and entries

(defn desired-env
  "The env a hive entry should carry: the user's own vars kept, the stale
   ones dropped, and BB_MCP_NREPL_PORT present only when a port was given."
  [existing port]
  (cond-> (apply dissoc (into {} existing) port-var stale-env-vars)
    port (assoc port-var (str port))))

(defn env-flags
  "claude mcp add -e flags for `env`, sorted for a stable command line."
  [env]
  (into [] (mapcat (fn [[k v]] ["-e" (str k "=" v)])) (sort env)))

(defn entry-current?
  "True when a client entry {\"command\" \"args\" \"env\"} already launches
   `command` with no args and exactly `env`."
  [entry command env]
  (and (= command (get entry "command"))
       (empty? (get entry "args"))
       (= env (into {} (get entry "env")))))

(defn plan-claude-user
  "Plan the user-scope Claude Code registration from its observed entry."
  [{:keys [layout opts]} entry]
  (let [cmd (:launcher layout)
        env (desired-env (get entry "env") (:port opts))]
    (if (entry-current? entry cmd env)
      (step "claude:user" :ok cmd)
      (step "claude:user" :change (str "register " server-name " -> " cmd)
            :ops [[:exec ["claude" "mcp" "remove" "--scope" "user" server-name]
                   {:ignore-failure? true}]
                  [:exec (-> ["claude" "mcp" "add" "--scope" "user" server-name]
                             (into (env-flags env))
                             (into ["--" cmd]))]]))))

(defn mcp-json-entry
  "The .mcp.json hive entry, keeping the user's own env vars."
  [existing port]
  (let [env (desired-env (get existing "env") port)]
    (cond-> {"type" "stdio" "command" literal-launcher}
      (seq env) (assoc "env" env))))

(defn merge-mcp-json
  "`doc` (a parsed .mcp.json, or nil) with the hive entry upserted and every
   other server and key preserved."
  [doc port]
  (let [doc (if (map? doc) doc {})]
    (assoc-in doc ["mcpServers" server-name]
              (mcp-json-entry (get-in doc ["mcpServers" server-name]) port))))

(defn parse-json
  "Parsed JSON text with string keys; nil for nil; ::invalid when malformed."
  [text]
  (when text
    (try (json/parse-string text)
         (catch Exception _ ::invalid))))

(defn plan-mcp-json
  "Plan the project-scope .mcp.json at `path`. `doc` is the parsed file,
   nil when absent, or ::invalid when it does not parse."
  [path doc port]
  (if (= ::invalid doc)
    (step "claude:project" :refused (str path " is not valid JSON; fix it and rerun"))
    (let [merged (merge-mcp-json doc port)]
      (if (= merged doc)
        (step "claude:project" :ok (str path " -> " literal-launcher))
        (step "claude:project" :change (str "write " path " -> " literal-launcher)
              :ops [[:write-file path (str (json/generate-string merged {:pretty true}) "\n")]])))))

;; ---------------------------------------------------------------------------
;; Checks

(defn- home-root
  "\"/home/u\" or \"/Users/u\" when `path` lives in some user's home."
  [path]
  (re-find #"^/(?:home|Users)/[^/]+" (str path)))

(defn classify-command
  "Why `command` is or is not the ANCHOR launcher: [status reason]."
  [command {:keys [home checkout layout]}]
  (cond
    (nil? command)
    [:fail "no hive entry; run bb-mcp setup"]

    (#{(:launcher layout) literal-launcher} command)
    [:ok command]

    (and checkout (str/starts-with? command (str checkout "/")))
    [:fail (str command " points at the real checkout, not the ANCHOR " (:launcher layout))]

    (and (home-root command) (not= (home-root command) (home-root home)))
    [:fail (str command " is in another user's home; want " (:launcher layout))]

    :else
    [:fail (str command " is not the ANCHOR launcher " (:launcher layout))]))

(defn check-entry
  "Check rows for one client's hive `entry` (string-keyed, may be nil)."
  [label entry ctx]
  (let [[status reason] (classify-command (get entry "command") ctx)
        stale (filter (set (keys (get entry "env"))) (sort stale-env-vars))]
    (cond-> [(step label status reason)]
      (seq stale)
      (conj (step (str label ":env") :fail
                  (str "stale " (str/join ", " stale) " in env; run bb-mcp setup")))
      (seq (get entry "args"))
      (conj (step (str label ":args") :warn
                  (str "args " (pr-str (vec (get entry "args")))
                       " pin a project; the launcher defaults to the client's cwd"))))))

(defn resolve-port
  "The nREPL port the launcher would use: --port, BB_MCP_NREPL_PORT,
   <cwd>/.nrepl-port, else 7910."
  [{:keys [opt-port env-port port-file]}]
  (or opt-port
      (some-> env-port str/trim not-empty parse-long)
      (some-> port-file str/trim not-empty parse-long)
      default-port))

(defn check-anchor
  "Rows for ANCHOR and its launcher."
  [{:keys [layout checkout anchor-obs]}]
  (let [{:keys [kind resolved dir? launcher-exec?]} anchor-obs
        {:keys [anchor launcher]} layout]
    [(cond
       (not dir?)
       (step "anchor" :fail (str anchor " does not resolve to a directory; run bb-mcp setup"))
       (and (= kind :symlink) (not= resolved checkout))
       (step "anchor" :warn (str anchor " -> " resolved " (this checkout is " checkout ")"))
       :else
       (step "anchor" :ok (str anchor (when resolved (str " -> " resolved)))))
     (if launcher-exec?
       (step "launcher" :ok launcher)
       (step "launcher" :fail (str launcher " is missing or not executable")))]))

(defn check-bin
  "Row for a required binary."
  [bin path]
  (if path
    (step bin :ok path)
    (step bin :fail (str bin " not on PATH (babashka is required: https://babashka.org)"))))

(defn check-port
  "The nREPL row: warn only, never a failure."
  [port answers?]
  (if answers?
    (step "nrepl" :ok (str "port " port " answers"))
    (step "nrepl" :warn (str "nothing answers on port " port
                             "; start the hive-mcp backend (nREPL) before using hive tools"))))

;; ---------------------------------------------------------------------------
;; Arguments

(def usage
  (str "Usage: bb-mcp setup [--client claude|codex|all] [--scope user|project]"
       " [--port N] [--dry-run] [--check] [--json]"))

(def ^:private flags
  "Boolean flags: argument -> option key."
  {"--dry-run" :dry-run? "--check" :check? "--json" :json?})

(def ^:private valued
  "Valued options: argument -> [option key, parse fn returning nil on bad input]."
  {"--client" [:client (comp #{:claude :codex :all} keyword)]
   "--scope"  [:scope (comp #{:user :project} keyword)]
   "--port"   [:port #(when-let [p (parse-long %)] (when (< 0 p 65536) p))]})

(defn parse-args
  "argv -> {:opts {...}} or {:error msg} or {:help usage}."
  [argv]
  (loop [opts {:client :all :scope :user} [a v :as args] (seq argv)]
    (cond
      (empty? args) {:opts opts}
      (#{"-h" "--help"} a) {:help usage}
      (flags a) (recur (assoc opts (flags a) true) (next args))
      (valued a) (let [[k parse] (valued a)]
                   (if-let [x (some-> v parse)]
                     (recur (assoc opts k x) (nnext args))
                     {:error (str "bad value for " a ": " (pr-str v) "\n" usage)}))
      :else {:error (str "unknown argument " (pr-str a) "\n" usage)})))

;; ---------------------------------------------------------------------------
;; Report

(def failing-statuses #{:failed :refused :fail})

(defn exit-code [rows]
  (if (some (comp failing-statuses :status) rows) 1 0))

(defn op-line
  "One op as a readable line."
  [[op & args]]
  (case op
    :exec (str/join " " (first args))
    (:symlink :backup) (str (name op) " " (first args) " -> " (second args))
    (str (name op) " " (first args))))

(defn format-table
  "Rows as a short aligned table; planned ops listed under their row."
  [rows]
  (let [label (fn [v] (if (keyword? v) (name v) (str v)))
        width (fn [k] (reduce max 0 (map (comp count label k) rows)))
        ws (width :step)
        wt (width :status)
        pad (fn [s n] (str s (apply str (repeat (- n (count s)) " "))))]
    (str/join "\n"
              (mapcat (fn [{:keys [step status detail ops]}]
                        (cons (str (pad step ws) "  " (pad (name status) wt) "  " detail)
                              (when (= :planned status)
                                (map #(str (pad "" ws) "    + " (op-line %)) ops))))
                      rows))))
