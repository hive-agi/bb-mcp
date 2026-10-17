(ns bb-mcp.tools.bash.remote
  "The bash tool for a runtime that cannot spawn a subprocess of its own.

   Same tool-spec and same result shape as `bb-mcp.tools.bash`; the process is
   spawned by the hive-mcp JVM through `hive-system.shell.core/exec!` — the
   IShell implementation over ProcessBuilder declared in hive-system's portable
   `protocols.cljc` — and reached over the nREPL socket this head already holds
   for every forwarded tool. No new MCP surface on the JVM side: the var is
   called directly, exactly as the dynamic loader calls the tool registry.

   Result contract of exec!, matched here key for key:
     {:ok {:exit n :stdout s :stderr s :duration-ms d}}   ran
     {:error :shell/timeout :timeout-ms n}                killed at the deadline
     {:error :shell/exec-failed :message s}               never started

   The deadline shape is version-dependent, and this head does not choose the
   hive-system it talks to: builds before the flattening returned the timeout
   as {:ok {:error :shell/timeout}}. Both are accepted."
  (:require [bb-mcp.tools.bash.spec :as spec]
            [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def tool-spec spec/tool-spec)

(def ^:private nrepl-grace-ms
  "Headroom over the command's own deadline, so the socket read outlives the
  kill the JVM performs at `:timeout-ms` and we report the timeout rather than
  a transport error."
  5000)

(defn- remote-form
  "Code evaluated in the hive-mcp JVM. Returns the exec! Result as EDN text."
  [command opts]
  (pr-str
   `(pr-str
     (into {} ((requiring-resolve 'hive-system.shell.core/exec!) ~command ~opts)))))

(defn- ->result
  "Fold one exec! Result into bb-mcp.tools.bash/execute's shape.

  A timeout is recognised in EITHER position on purpose. hive-system flattened
  :shell/timeout to a top-level err, but this head calls whatever hive-system
  the running JVM happens to have loaded, which may still be the older build
  that returned {:ok {:error :shell/timeout}}. Checking the flat shape first
  also keeps a timeout from being read as a spawn failure with no message."
  [{:keys [ok error message] :as r} timeout]
  (cond
    (or (= :shell/timeout error) (= :shell/timeout (:error ok)))
    {:exit-code -1 :stdout "" :stderr "Command timed out" :timed-out true}

    error {:exit-code -1 :stdout "" :stderr (str message) :error (str message)}

    (:error ok)
    {:exit-code -1 :stdout "" :stderr (str (:error ok)) :error (str (:error ok))}

    ok {:exit-code (:exit ok) :stdout (or (:stdout ok) "")
        :stderr (or (:stderr ok) "") :timed-out false}

    :else {:exit-code -1 :stdout "" :stderr (str "unreadable shell result: " (pr-str r))
           :error (str "unreadable shell result (timeout was " timeout "ms)")}))

(def ^:private hive-build-nrepl-port
  "The port of the `:nrepl` alias in hive/hive-build/deps.edn."
  7932)

(defn unreachable-message
  "Pure. The stderr text for a bash call no nREPL accepted.

  `ports` are the ports tried, in order; `cause` the last transport message;
  `resolved` the first port's {:port :source ...} from
  `nrepl/resolve-hive-port`, when known. Names where that port came from,
  which settings bash reads and which it never reads, the owner of the port,
  how to start it, and why there is no local fallback on this runtime."
  ([ports cause] (unreachable-message ports cause nil))
  ([ports cause resolved]
   (let [origin (nrepl/describe-port-source resolved)]
     (str "bash runs in the hive-mcp JVM on this runtime, and it did not answer: "
          cause "\n"
          "Tried localhost:" (str/join ", localhost:" ports)
          (if origin
            (str "; port " (:port resolved) " came from " origin ".\n")
            ".\n")
          "bash dials the hive-mcp host: " nrepl/hive-port-var ", else "
          nrepl/default-nrepl-port "; never BB_MCP_NREPL_PORT nor a project "
          ".nrepl-port, which name a project REPL for clojure_eval.\n"
          (when (= hive-build-nrepl-port (:port resolved))
            (str hive-build-nrepl-port " is the port of the :nrepl alias in "
                 "hive-build's deps.edn, a build REPL, not hive-mcp.\n"))
          "Owner: the hive-mcp JVM nREPL, started by its own `hive-mcp` launcher, "
          "never by bb-mcp.\n"
          "No local fallback: this bb-mcp runtime has no subprocess surface. "
          "Start hive-mcp, or restart bb-mcp with BB_MCP_RUNTIME=bb to run bash "
          "as a local subprocess."))))

(defn execute
  "Run `command` in the hive-mcp JVM and return `bb-mcp.tools.bash`'s shape.

   Working directory resolution matches the local tool: explicit
   :working_directory, else the session cwd injected as :_caller_cwd.

   The transport is a collaborator, not a dependency: the 2-arity takes the
   `:eval-fn` that carries a request to the JVM, so a test drives this with a
   stub instead of a live nREPL.

   Port: the hive-mcp host's, `nrepl/resolve-hive-port` over `:getenv`
   (default the real env). Never BB_MCP_NREPL_PORT nor a project
   .nrepl-port. `:resolve-fn` (-> {:port :source ...}) or a bare-int
   `:port-fn` replaces that resolution. One port, dialed once: a refusal is
   reported, never retried elsewhere."
  ([args] (execute args {:eval-fn nrepl/eval-code}))
  ([{:keys [command working_directory timeout_ms _caller_cwd]}
    {:keys [eval-fn resolve-fn port-fn getenv]
     :or {getenv #(System/getenv %)}}]
   (let [timeout (or timeout_ms 180000)
         dir (or working_directory _caller_cwd)
         opts (cond-> {:timeout-ms timeout} dir (assoc :dir dir))
         code (remote-form command opts)
         resolved (cond resolve-fn (resolve-fn)
                        port-fn {:port (port-fn)}
                        :else (nrepl/resolve-hive-port getenv))
         res (try
               (eval-fn {:port (:port resolved)
                         :code code
                         :timeout-ms (+ timeout nrepl-grace-ms)})
               (catch Exception e {:error? true :result (ex-message e)}))]
     (cond
       (:connect-failed? res)
       (let [msg (unreachable-message [(:port resolved)] (:result res) resolved)]
         {:exit-code -1 :stdout "" :stderr msg :error (str (:result res))})

       (:error? res)
       {:exit-code -1 :stdout ""
        :stderr (str "bash runs in the hive-mcp JVM on this runtime, and it did "
                     "not answer: " (:result res))
        :error (str (:result res))}

       :else
       (->result (-> (:result res) edn/read-string edn/read-string) timeout)))))
