(ns bb-mcp.tools.emacs.dynamic
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

(defn- fetch-emacs-tools-raw
  "Query hive-mcp for all tool specs via nREPL.
   Returns raw tool data or nil on failure.

   hive-mcp tools are flat: {:name, :description, :inputSchema, :handler}
   We extract just the spec fields (not handler)."
  [{:keys [port timeout-ms] :or {port 7910 timeout-ms 10000}}]
  (let [code "(do
                (require '[hive-mcp.tools :as tools])
                (pr-str
                  (mapv (fn [t]
                          {:name (:name t)
                           :description (:description t)
                           :schema (:inputSchema t)})
                        tools/tools)))"]
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

(defn- get-agent-id
  "Get agent ID from env var (set by swarm) or default to 'coordinator'."
  []
  (or (System/getenv "CLAUDE_SWARM_SLAVE_ID") "coordinator"))

(defn- make-forwarding-handler
  "Create a handler that forwards calls to hive-mcp via nREPL.
   Uses the WRAPPED handler from server-context-atom to get piggyback messages.
   Injects agent_id from CLAUDE_SWARM_SLAVE_ID env var for per-agent cursors."
  [tool-name]
  (fn [args]
    (let [port (or (:port args) 7910)
          agent-id (get-agent-id)
          ;; Remove port, inject agent_id for per-agent piggyback cursor
          emacs-args (-> args
                         (dissoc :port)
                         (assoc :agent_id agent-id))
          ;; Call through server's wrapped handler (not raw tools/tools handler)
          ;; This ensures make-tool wrapper runs and piggyback is attached
          code (str "(do
                       (require '[hive-mcp.server :as server])
                       (let [server-ns (find-ns 'hive-mcp.server)
                             atom-var (ns-resolve server-ns 'server-context-atom)
                             context @(deref atom-var)
                             handler (get-in @(:tools context) [\"" tool-name "\" :handler])
                             result (handler " (pr-str emacs-args) ")]
                         ;; Return the content text with any piggyback embedded
                         (get-in result [:content 0 :text])))")]
      (let [resp (nrepl/eval-code {:port port
                                   :code code
                                   :timeout-ms (or (:timeout_ms args) 30000)})
            parsed (try (edn/read-string (:result resp)) (catch Exception _ (:result resp)))]
        (binding [*out* *err*]
          (println "[dynamic] has-markers:" (clojure.string/includes? (str parsed) "HIVEMIND")))
        {:result parsed
         :error? (:error? resp)}))))

(defn- transform-tool
  "Transform hive-mcp tool spec to bb-mcp format.
   hive-mcp: {:name, :description, :schema}
   bb-mcp: {:spec {:name, :description, :schema}, :handler fn}"
  [{:keys [name description schema]}]
  {:spec {:name name
          :description description
          :schema (or schema {:type "object" :properties {} :required []})}
   :handler (make-forwarding-handler name)})

(defn- log-stderr [& args]
  "Log to stderr (stdout reserved for JSON-RPC)."
  (binding [*out* *err*]
    (apply println args)))

(defn load-dynamic-tools!
  "Fetch tools from hive-mcp and cache them.
   Returns true on success, false on failure."
  [& {:keys [port timeout-ms] :or {port 7910 timeout-ms 10000}}]
  (log-stderr "[dynamic] Loading tools from hive-mcp on port" port)
  (if-let [raw-tools (fetch-emacs-tools-raw {:port port :timeout-ms timeout-ms})]
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
