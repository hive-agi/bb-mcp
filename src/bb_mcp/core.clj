(ns bb-mcp.core
  "Main entry point for bb-mcp - lightweight MCP server in babashka."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.tools.bash.remote :as remote-bash]
            [bb-mcp.tools.bash.spec :as bash-spec]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.tools.hive :as hive]
            [clojure.string :as str]
            [bb-mcp.tool :as tool]
            [bb-mcp.host.port :as hp]
            [bb-mcp.guard :as guard]
            [bb-mcp.piggyback :as piggyback]
            [bb-mcp.sense.receptor :as receptor]
            [bb-mcp.async :as async]
            [bb-mcp.resources :as resources]))

;; Tool call logging — tail -f /tmp/bb-mcp.log to see MCP traffic
(def ^:private log-file (str "/tmp/bb-mcp-" (System/getProperty "user.name") ".log"))

(defn- log-tool-call
  "Append tool call request + response to log file for debugging."
  [tool-name args response elapsed-ms]
  (try
    (let [response-text (get-in response [:result :content 0 :text])
          is-error? (get-in response [:result :isError])
          truncated (when response-text
                      (if (> (count response-text) 2000)
                        (str (subs response-text 0 2000) "\n... [truncated, " (count response-text) " chars total]")
                        response-text))
          entry (str "─── " (java.time.LocalDateTime/now) " ───\n"
                     "TOOL: " tool-name
                     (when is-error? " [ERROR]")
                     " (" elapsed-ms "ms)\n"
                     "ARGS: " (hp/json-encode args) "\n"
                     "RESP: " (or truncated "<nil>") "\n\n")]
      (spit log-file entry :append true))
    (catch Exception _ nil)))

;; Agent context injection - auto-add agent_id from env var for attribution

(def ^:private instance-id
  "Stable ID for this bb-mcp session, from the host seam.

   Why it must be stable: each Claude Code window spawns bb-mcp as a child
   process. When bb-mcp restarts (e.g. tool refresh) the id must not change,
   or cursor positions are lost and every dynamic tool re-reads from
   timestamp 0."
  (hp/session-id))

(defn- getenv [k]
  (System/getenv k))

(defn- get-agent-id
  "Get agent ID from BB_MCP_AGENT_ID/BB_MCP_CLIENT_ID, CLAUDE_SWARM_SLAVE_ID, or nil if not set."
  []
  (or (getenv "BB_MCP_AGENT_ID")
      (getenv "BB_MCP_CLIENT_ID")
      (getenv "CLAUDE_SWARM_SLAVE_ID")))

(defn caller-id
  "The id this session calls the hive as: `<agent-id>:<instance>`, or
   `coordinator:<instance>` for a session that is not a ling. Pure. It is
   stamped on every request as `_caller_id`, hive-mcp records it as the
   parent of every ling this session spawns, and the sixth-sense receptor
   uses it as its default parent and its drain consumer id."
  [agent-id instance]
  (str (or agent-id "coordinator") ":" instance))

(defn with-caller-credential
  "`args` carrying `credential` as `_caller_credential`. Pure.

   The credential is the signed spawn credential the hive minted for this
   process; it is opaque here, never parsed, logged or printed. Whatever the
   model put under `_caller_credential` is dropped: the environment is the
   only source. A nil or blank credential leaves the key absent."
  [args credential]
  (if (str/blank? credential)
    (dissoc args :_caller_credential)
    (assoc args :_caller_credential credential)))

(def ^:private caller-cwd
  "Working directory of the Claude Code session (the project being worked on).
   Prefers BB_MCP_CALLER_CWD (the invocation pwd captured by start-bb-mcp.sh
   before any cd — the user's actual session cwd) over BB_MCP_PROJECT_DIR
   (which a registration arg may pin to a fixed path, e.g. hive-mcp) over
   user.dir (always bb-mcp's own script directory after 'cd $SCRIPT_DIR')."
  (or (System/getenv "BB_MCP_CALLER_CWD")
      (System/getenv "BB_MCP_PROJECT_DIR")
      (System/getProperty "user.dir")))

(defn- inject-agent-context
  "Inject agent context from CLAUDE_SWARM_SLAVE_ID env var.

   Injects FOUR fields:
   - _caller_id: ALWAYS injected — identifies the MCP session/caller.
     Uses instance-id (PPID-based) for per-session cursor isolation.
     Never conflicts with user-specified agent_id (dispatch target).
   - _caller_credential: the HIVE_AGENT_CREDENTIAL the hive minted for
     this process, when set and non-blank; absent otherwise. Never taken
     from args (see with-caller-credential).
   - _caller_cwd: ALWAYS injected — bb-mcp's working directory.
     Ensures hive-mcp resolves project-id from the caller's cwd,
     not from the JVM's user.dir (which differs in multiplexer setup).
   - agent_id: only injected when args lack it (backward compat).
     For dispatch-type tools, user sets agent_id to the target,
     so bb-mcp must NOT overwrite it."
  [args]
  (let [agent-id (get-agent-id)
        caller (caller-id agent-id instance-id)]
    (cond-> (-> args
                (assoc :_caller_id caller)
                (with-caller-credential (getenv "HIVE_AGENT_CREDENTIAL")))
      ;; Inject cwd when args don't already have a directory
      (not (:directory args))
      (assoc :_caller_cwd caller-cwd)
      ;; Inject agent_id from env var when not already set
      (and agent-id (not (:agent_id args)))
      (assoc :agent_id agent-id))))

;; Native bb-mcp tools (bootstrapping essentials only)
;; File tools (read_file, file_write, glob_files, grep) are now loaded
;; dynamically from basic-tools-mcp IAddon via hive-mcp.
(defn- local-bash
  "`bb-mcp.tools.bash/execute`, or nil when this runtime cannot spawn.

   Resolved rather than required: that namespace names babashka.process at load
   time, so a hard require would take the whole server down on a runtime that
   has no process surface at all."
  []
  (try (requiring-resolve 'bb-mcp.tools.bash/execute)
       (catch Exception _ nil)))

(defn- bash-tool
  "The bash tool, wired to whichever executor this runtime can offer.

   A runtime without a subprocess still SERVES it: `bb-mcp.tools.bash.remote`
   hands the command to the hive-mcp JVM over the nREPL socket this head
   already holds, so `tools/list` is the same on every runtime and a client
   never has to ask which head it reached."
  []
  (let [execute (or (local-bash) remote-bash/execute)]
    (tool/native-tool bash-spec/tool-spec
                      (fn [args] (bash-spec/format-result (execute args))))))

(def ^:private native-tools
  (into [] (comp (remove nil?)
                 ;; Every native tool may run in the background, so every one
                 ;; declares `async`: an undeclared param never arrives.
                 (map #(tool/native-tool (async/with-async-property (tool/tool-spec %))
                                         (:handler %))))
        [(bash-tool)
         (tool/native-tool nrepl/tool-spec nrepl/execute)]))

(def ^:private native-tool-names
  "Names of the tools this head serves in its OWN process.

   These never reach hive-mcp's tool-dispatch gate, so this process is the only
   place they can be judged."
  (into #{} (map tool/tool-name) native-tools))

(def ^:private tool-sources
  "Ordered tool providers; each a zero-arg fn returning a seq of Tool."
  [(constantly native-tools) hive/get-tools])

(defn get-tools
  "All registered tools from every source, in order."
  ([] (get-tools tool-sources))
  ([sources] (mapcat #(%) sources)))

(defn find-tool [name]
  (first (filter #(= name (tool/tool-name %)) (get-tools))))

(def ^:dynamic *tool-ceiling-ms*
  "Hard ceiling on a single tool call. `BB_MCP_TOOL_TIMEOUT_MS` overrides it."
  (or (some-> (System/getenv "BB_MCP_TOOL_TIMEOUT_MS") parse-long) 900000))

(defn- invoke-safely
  "Enrich `arguments`, invoke tool `t`, folding any thrown exception into
  {:result :error?}.

  A call that outlives `*tool-ceiling-ms*` is ABANDONED rather than waited on:
  the run-server loop is single-threaded, so one tool that never returns would
  otherwise take every later request with it. The abandoned work carries on in
  its own thread; only this server's attention is reclaimed."
  [t arguments]
  (let [work (future
               (try
                 (tool/invoke t (inject-agent-context arguments))
                 (catch Exception e {:result (str "Error: " (ex-message e)) :error? true})))
        outcome (deref work *tool-ceiling-ms* ::ceiling)]
    (if (= ::ceiling outcome)
      {:result (str "Error: the tool did not return within " *tool-ceiling-ms*
                    "ms and was abandoned so the server stays responsive. "
                    "It may still be running; check for side effects before retrying.")
       :error? true}
      outcome)))

(def ^:dynamic *drain-fn*
  "(fn [tool-name args] -> text) giving the piggyback blocks a native tool's
   response carries. Rebound by tests; `BB_MCP_NATIVE_PIGGYBACK=0` turns it
   off."
  (if (= "0" (System/getenv "BB_MCP_NATIVE_PIGGYBACK"))
    (constantly "")
    piggyback/drain))

(defn- judged-invoke
  "The guard's verdict applied to one native call."
  [t name arguments]
  (let [decision (guard/decide name (inject-agent-context arguments))]
    (cond
      (guard/denied? decision)
      {:result (guard/refusal-text name decision) :error? true}

      (guard/warned? decision)
      (let [{:keys [result error?]} (invoke-safely t arguments)]
        {:result (str result "\n\n" (guard/warning-text decision))
         :error? error?})

      :else (invoke-safely t arguments))))

(defn- async-invoke
  "The guard's verdict applied to one native call, then the call QUEUED.

   The guard runs first, on the caller's thread, so a denied call is refused
   in-band and never started. An allowed call returns its ack at once; the
   result arrives through `async/drain!` on a later response."
  [t name arguments]
  (let [decision (guard/decide name (inject-agent-context arguments))]
    (if (guard/denied? decision)
      {:result (guard/refusal-text name decision) :error? true}
      {:result (cond-> (async/submit! name #(invoke-safely t arguments))
                 (guard/warned? decision) (str "\n\n" (guard/warning-text decision)))
       :error? false})))

(defn- guarded-invoke
  "Invoke `t`, gated and piggybacked when this head serves the tool itself.

   A forwarded tool is judged by hive-mcp's own dispatch gate and gets its
   piggyback blocks from hive-mcp's middleware; doing either here as well
   would double-count it. Only the native tools, which reach neither, are
   judged and drained here. The same holds for `async`: hive-mcp queues a
   forwarded call itself, so only a native call is queued here.

     :deny: short-circuits; the tool never runs.
     :warn: the tool runs and the advisory is appended.
     else:  proceeds, gap or not.

   Blocks are appended on every outcome, a refusal included, as hive-mcp does."
  [t name arguments]
  (if-not (contains? native-tool-names name)
    (invoke-safely t arguments)
    (let [background? (async/requested? arguments)
          arguments   (async/strip arguments)
          outcome     (if background?
                        (async-invoke t name arguments)
                        (judged-invoke t name arguments))]
      (update outcome :result
              #(piggyback/append (str %) (*drain-fn* name (inject-agent-context arguments)))))))

(defn- call-tool
  "Resolve, invoke, log, and build the tools/call response for `name`.

   Results of native calls queued with async:true ride out on whatever call
   comes next, forwarded or native, so the caller need not know which head
   ran the work."
  [id name arguments]
  (let [t0 (System/currentTimeMillis)]
    (if-let [t (find-tool name)]
      (let [{:keys [result error?]} (guarded-invoke t name arguments)
            result   (piggyback/append (str result) (async/drain!))
            response (proto/tool-call-response id result error?)]
        (log-tool-call name arguments response (- (System/currentTimeMillis) t0))
        response)
      (proto/json-rpc-error id -32601 (str "Unknown tool: " name)))))

;; Message handlers
(defmulti handle-method :method)

(defmethod handle-method "initialize"
  ;; THE initialize response. `:session/capabilities` is what this session's
  ;; receptor adds (run-server attaches it); absent, the base set alone.
  [{:keys [id] :session/keys [capabilities]}]
  (proto/initialize-response id (or capabilities {})))

(defmethod handle-method "initialized" [_]
  nil) ;; Notification, no response

(defmethod handle-method "tools/list" [{:keys [id]}]
  (proto/tools-list-response
   id (->> (get-tools) (remove tool/deprecated?) (map tool/tool-spec))))

(defmethod handle-method "tools/call" [{:keys [id params]}]
  (call-tool id (:name params) (:arguments params)))

(defmethod handle-method "resources/list" [{:keys [id]}]
  (try
    (proto/resources-list-response id (resources/list-resources resources/*source*))
    (catch Exception e
      (proto/json-rpc-error id -32603 (str "Resource source unavailable: " (ex-message e))))))

(defmethod handle-method "resources/read" [{:keys [id params]}]
  (let [uri (:uri params)]
    (if-not (and (string? uri) (not (str/blank? uri)))
      (proto/json-rpc-error id -32602 "Resource URI must be a non-blank string")
      (try
        (if-let [content (resources/read-resource resources/*source* uri)]
          (proto/resources-read-response id content)
          (proto/json-rpc-error id -32002 (str "Resource not found: " uri)))
        (catch Exception e
          (proto/json-rpc-error id -32603 (str "Resource read failed: " (ex-message e))))))))

(defmethod handle-method "prompts/list" [{:keys [id]}]
  (proto/json-rpc-response id {:prompts []}))

(defmethod handle-method :default [{:keys [id method]}]
  (if id
    (proto/json-rpc-error id -32601 (str "Method not found: " method))
    nil)) ;; Ignore unknown notifications

;; Main loop

(defn- env-receptor
  "The receptor for the session `init-request` opens, from that request and
   this process's environment and caller id (channels are opt-in: see
   receptor/select)."
  [init-request]
  (receptor/select (into {} (System/getenv)) init-request
                   (caller-id (get-agent-id) instance-id)))

(defn- in-session
  "`msg` as the session with receptor `r` sees it: carrying the capabilities
   the receptor adds, for the methods whose answer depends on the session."
  [r msg]
  (assoc msg :session/capabilities (receptor/capabilities r)))

(defn- open-session
  "The receptor for a new session opened by `init-request`. The previous
   session's receptor `r` is disarmed FIRST, so a repeated initialize never
   leaves two pollers running."
  [r select-receptor init-request]
  (receptor/disarm! r)
  (select-receptor init-request))

(defn run-server
  "Read, dispatch, and write MCP messages over `transport` until input ends.

   The session's Receptor is chosen when `initialize` arrives (via
   `:select-receptor`, a fn of the initialize request; default: from that
   request and the environment) after disarming the one before, and armed on
   the first message AFTER it, i.e. once the handshake is done, so no channel
   event can precede the initialize result. It is disarmed when input ends.
   Every write, response or notification, goes through one serialised
   transport."
  ([] (run-server (proto/stdio-transport)))
  ([transport] (run-server transport {}))
  ([transport {:keys [select-receptor] :or {select-receptor env-receptor}}]
   (let [t (proto/serialized transport)]
     (loop [r (receptor/null-receptor)]
       (if-let [msg (proto/read-msg t)]
         (let [initialize? (= "initialize" (:method msg))
               r (if initialize? (open-session r select-receptor msg) r)]
           (when-let [response (handle-method (in-session r msg))]
             (proto/write-msg t response))
           (when-not initialize? (receptor/arm! r t))
           (recur r))
         (receptor/disarm! r))))))

(defn- warn-unless-nrepl-reachable!
  "Print a startup hint to stderr when no nREPL answers on the resolved port.
  The hive-mcp JVM is started by its own launcher, never by this process."
  []
  (let [port (nrepl/get-nrepl-port)]
    (when-not (try
                (hp/close! (hp/open {:port port :timeout-ms 1000}))
                true
                (catch Exception _ false))
      (binding [*out* *err*]
        (println (str "bb-mcp: no hive-mcp nREPL on port " port
                      " — start it with the `hive-mcp` launcher."
                      " Until then only native tools are available."))))))

(defn- warn-where-bash-runs!
  "State the executor when bash is NOT this process's own subprocess, so a
  command that lands in another process is a stated fact rather than a
  surprise in a stack trace."
  []
  (when-not (local-bash)
    (binding [*out* *err*]
      (println (str "bb-mcp: no subprocess surface on this runtime ("
                    (hp/adapter-ns) ") — bash runs in the hive-mcp JVM, "
                    "over the nREPL socket on port " (nrepl/get-nrepl-port) ".")))))

(defn- start-release-notice!
  "Tell the user, on stderr, when a newer bb-mcp release exists.

  Everything, loading the namespaces included, happens on a daemon thread,
  so the MCP handshake never waits for it. It is resolved rather than
  required: it needs babashka.process, which a runtime without a subprocess
  surface (cljw) lacks. For that runtime start-bb-mcp.sh runs `bb-mcp notice`
  beside it and sets BB_MCP_RELEASE_NOTICE=launcher, so it is said once.
  BB_MCP_NO_UPDATE_CHECK=1 turns it off."
  []
  (when-not (System/getenv "BB_MCP_RELEASE_NOTICE")
    (try
      (doto (Thread. ^Runnable
                     (fn []
                       (try
                         ((requiring-resolve 'bb-mcp.update.io/notify!)
                          ((requiring-resolve 'bb-mcp.update.io/system))
                          ((requiring-resolve 'bb-mcp.setup/checkout-dir)))
                         ;; A release notice must never break the server.
                         (catch Exception _no-notice nil))))
        (.setDaemon true)
        (.start))
      ;; No threads on this runtime: no notice, the server runs as before.
      (catch Exception _no-threads nil))))

(defn -main [& _args]
  (warn-unless-nrepl-reachable!)
  (warn-where-bash-runs!)
  (start-release-notice!)
  (hive/init!)
  (run-server))

;; For REPL development
(comment
  (run-server))