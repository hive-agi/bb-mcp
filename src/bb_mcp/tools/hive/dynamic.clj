(ns bb-mcp.tools.hive.dynamic
  "Dynamic tool loading from hive-mcp.

   This module fetches tool specs from hive-mcp at startup and creates
   forwarding handlers, eliminating the need for manual tool maintenance.

   Flow:
   1. Query hive-mcp for all tool specs via nREPL
   2. Transform specs from hive-mcp format to bb-mcp format
   3. Create forwarding handlers that delegate to hive-mcp
   4. Cache tools in atom for session lifetime"
  (:require [bb-mcp.tools.nrepl :as nrepl]
            [cheshire.core :as json]
            [clojure.edn :as edn]))

(defonce ^:private tool-cache (atom nil))

(def ^:private valid-key-re #"^[a-zA-Z0-9_.-]{1,64}$")

(defn- sanitize-key
  "Replace chars that violate Anthropic's property-key regex with '_'.
   Truncate to 64. Ensures the result matches ^[a-zA-Z0-9_.-]{1,64}$."
  [k]
  (let [s (name k)
        cleaned (clojure.string/replace s #"[^a-zA-Z0-9_.-]" "_")
        truncated (subs cleaned 0 (min 64 (count cleaned)))]
    (if (re-matches valid-key-re truncated) truncated "_")))

(defn- build-key-rename
  "Return {sanitized-string -> original-keyword} for property keys that
   need renaming. Keys already valid are omitted."
  [properties]
  (into {}
        (keep (fn [[k _]]
                (let [n (name k)]
                  (when-not (re-matches valid-key-re n)
                    [(sanitize-key k) (keyword n)]))))
        properties))

(defn- sanitize-schema
  "Rewrite schema so all top-level property keys are API-valid.
   Returns [schema' rename-map] where rename-map is sanitized->original."
  [schema]
  (let [props (:properties schema)
        rename (build-key-rename props)]
    (if (empty? rename)
      [schema {}]
      (let [reverse-rename (into {} (map (fn [[san orig]] [(keyword (name orig)) san]) rename))
            props' (into {} (map (fn [[k v]]
                                   [(keyword (get reverse-rename (keyword (name k)) (name k))) v])
                                 props))
            req' (some->> (:required schema)
                          (mapv #(get reverse-rename (keyword (name %)) (name %))))]
        [(cond-> (assoc schema :properties props')
           req' (assoc :required req'))
         rename]))))

(defn- fetch-hive-tools-raw
  "Query hive-mcp for all tool specs via nREPL.
   Returns raw tool data or nil on failure.

   hive-mcp tools are flat: {:name, :description, :inputSchema, :handler}
   We extract just the spec fields (not handler)."
  [{:keys [port timeout-ms] :or {port 7910 timeout-ms 10000}}]
  (let [code (pr-str
              '(do
                 (require '[hive-mcp.tools.registry :as reg])
                 (require '[hive-mcp.extensions.registry :as ext])
                 (pr-str
                  (mapv (fn [t]
                          {:name (:name t)
                           :description (:description t)
                           :schema (:inputSchema t)})
                        ;; Consolidated tools (supertools) + addon/extension tools
                        (concat (reg/get-consolidated-tools)
                                (ext/get-registered-tools))))))]
    (try
      (let [result (nrepl/eval-code {:port port
                                     :code code
                                     :timeout-ms timeout-ms})]
        (when-not (:error? result)
          ;; Result is double-quoted: nREPL returns pr-str of our pr-str
          ;; First read unwraps the outer quotes, second reads the EDN
          (-> (:result result)
              edn/read-string   ; unwrap outer quotes
              edn/read-string))) ; parse EDN vector
      (catch Exception e
        (binding [*out* *err*]
          (println "[dynamic] Failed to fetch tools:" (ex-message e)))
        nil))))

(def ^:private long-running-tool-patterns
  "Tool name patterns that require extended timeouts.
   Wave dispatch, validated wave, and delegate operations can run for minutes
   as they orchestrate multiple drone executions via OpenRouter."
  #{"dispatch_drone_wave" "dispatch_validated_wave" "delegate"
    "delegate_drone" "swarm_dispatch"})

(defn- tool-timeout-ms
  "Determine appropriate timeout for a tool based on its name.
   Long-running tools (waves, delegation) get 10 minutes.
   Other tools use the caller-specified timeout or the global default.

   CLARITY-I: Inputs are guarded — match timeout to expected execution time."
  [tool-name explicit-timeout-ms]
  (or explicit-timeout-ms
      (when (contains? long-running-tool-patterns tool-name)
        600000)
      600000))

(defn- get-agent-id
  "Get agent ID from env var (set by swarm) or default to 'coordinator'."
  []
  (or (System/getenv "CLAUDE_SWARM_SLAVE_ID") "coordinator"))

(defn- make-forwarding-handler
  "Create a handler that forwards calls to hive-mcp via nREPL.
   Uses the WRAPPED handler from server-context-atom to get piggyback messages.
   Respects existing agent_id from args (injected by core.clj from env var)."
  [tool-name key-rename]
  (fn [args]
    (let [;; Reverse-rename sanitized property keys back to originals hive-mcp expects.
          args (if (empty? key-rename)
                 args
                 (reduce-kv (fn [m san orig-kw]
                              (let [san-kw (keyword san)]
                                (if (contains? m san-kw)
                                  (-> m (dissoc san-kw) (assoc orig-kw (get m san-kw)))
                                  m)))
                            args
                            key-rename))
          ;; nREPL port is always hive-mcp's port, never from tool args.
          ;; Tool args :port belongs to the handler (e.g., CIDER connect target port).
          nrepl-port 7910
          ;; Respect existing agent_id, fallback to env var or 'coordinator' for piggyback cursor
          agent-id (or (:agent_id args) (get-agent-id))
          ;; Inject bb-mcp's own cwd as _caller_cwd so hive-mcp can resolve the
          ;; caller's project scope. bb-mcp runs per-session, so its user.dir IS
          ;; the user's actual cwd. Without this, hive-mcp's shared JVM falls
          ;; back to its own user.dir and scope silently degrades to "global".
          ;; Only injects when caller did not already supply one (e.g. via SDK).
          caller-cwd (or (:_caller_cwd args) (System/getProperty "user.dir"))
          ;; Keep all args intact, only resolve agent_id for per-agent piggyback cursor
          hive-args (-> args
                        (assoc :agent_id agent-id)
                        (assoc :_caller_cwd caller-cwd))
          ;; Call through server's wrapped handler (not raw tools/tools handler)
          ;; This ensures make-tool wrapper runs and piggyback is attached.
          ;;
          ;; Result extraction handles THREE response formats:
          ;; 1. Wrapped: {:content [{:type "text" :text "..."}]} — from make-tool middleware
          ;; 2. Raw map: {:type "text" :text "..."} — from error responses
          ;; 3. Raw vector: [{:type "text" :text "..."} ...] — from handlers without middleware
          ;; All text blocks are concatenated to preserve multi-block responses (e.g. catchup).
          code (pr-str
                `(let [ctx# @(deref (resolve '~'hive-mcp.server.core/server-context-atom))
                       handler# (get-in @(:tools ctx#) [~tool-name :handler])
                       result# (handler# ~hive-args)
                       texts# (cond
                                ;; Wrapped format: {:content [{:type "text" :text "..."}]}
                                (and (map? result#) (:content result#))
                                (mapv :text (:content result#))
                                ;; Raw vector: [{:type "text" :text "..."} ...]
                                (sequential? result#)
                                (mapv :text result#)
                                ;; Single map: {:type "text" :text "..."}
                                (map? result#)
                                [(:text result#)]
                                ;; Fallback
                                :else
                                [(pr-str result#)])]
                   (clojure.string/join "\n\n" (remove nil? texts#))))]
      (let [resp (nrepl/eval-code {:port nrepl-port
                                   :code code
                                   :timeout-ms (tool-timeout-ms tool-name (:timeout_ms args))})
            ;; CLARITY-Y: Don't parse error responses through edn/read-string.
            ;; Error strings like "nREPL connection failed: Read timed out" get
            ;; corrupted by edn parsing into bare symbols like "nREPL".
            parsed (if (:error? resp)
                     (:result resp)
                     (try (edn/read-string (:result resp)) (catch Exception _ (:result resp))))]
        (binding [*out* *err*]
          (println "[dynamic] has-markers:" (clojure.string/includes? (str parsed) "HIVEMIND")))
        {:result parsed
         :error? (:error? resp)}))))

(defn- transform-tool
  "Transform hive-mcp tool spec to bb-mcp format.
   hive-mcp: {:name, :description, :schema}
   bb-mcp: {:spec {:name, :description, :schema}, :handler fn}"
  [{:keys [name description schema]}]
  (let [base-schema (or schema {:type "object" :properties {} :required []})
        [schema' rename] (sanitize-schema base-schema)]
    (when (seq rename)
      (binding [*out* *err*]
        (println "[dynamic] sanitized property keys for" name "->" rename)))
    {:spec {:name name
            :description description
            :schema schema'}
     :handler (make-forwarding-handler name rename)}))

(defn- log-stderr [& args]
  "Log to stderr (stdout reserved for JSON-RPC)."
  (binding [*out* *err*]
    (apply println args)))

(defn load-dynamic-tools!
  "Fetch tools from hive-mcp and cache them.
   Returns true on success, false on failure."
  [& {:keys [port timeout-ms] :or {port 7910 timeout-ms 10000}}]
  (log-stderr "[dynamic] Loading tools from hive-mcp on port" port)
  (if-let [raw-tools (fetch-hive-tools-raw {:port port :timeout-ms timeout-ms})]
    (let [tools (mapv transform-tool raw-tools)]
      (reset! tool-cache tools)
      (log-stderr "[dynamic] Loaded" (count tools) "tools from hive-mcp")
      true)
    (do
      (log-stderr "[dynamic] Failed to load tools, using static fallback")
      false)))

(defn get-tools
  "Get cached dynamic tools. Returns nil if not loaded."
  []
  @tool-cache)

(defn tools-loaded?
  "Check if dynamic tools have been loaded."
  []
  (some? @tool-cache))

(defn clear-cache!
  "Clear the tool cache (for testing/reload)."
  []
  (reset! tool-cache nil))

;; Empty tools vector - actual tools are dynamically loaded
(def tools [])
