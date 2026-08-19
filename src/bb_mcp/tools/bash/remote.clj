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
     {:ok {:error :shell/timeout :timeout-ms n}}          killed at the deadline
     {:error :shell/exec-failed :message s}               never started"
  (:require [bb-mcp.tools.bash.spec :as spec]
            [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]))

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
  "Fold one exec! Result into bb-mcp.tools.bash/execute's shape."
  [{:keys [ok error message] :as r} timeout]
  (cond
    error {:exit-code -1 :stdout "" :stderr (str message) :error (str message)}

    (= :shell/timeout (:error ok))
    {:exit-code -1 :stdout "" :stderr "Command timed out" :timed-out true}

    (:error ok)
    {:exit-code -1 :stdout "" :stderr (str (:error ok)) :error (str (:error ok))}

    ok {:exit-code (:exit ok) :stdout (or (:stdout ok) "")
        :stderr (or (:stderr ok) "") :timed-out false}

    :else {:exit-code -1 :stdout "" :stderr (str "unreadable shell result: " (pr-str r))
           :error (str "unreadable shell result (timeout was " timeout "ms)")}))

(defn execute
  "Run `command` in the hive-mcp JVM and return `bb-mcp.tools.bash`'s shape.

   Working directory resolution matches the local tool: explicit
   :working_directory, else the session cwd injected as :_caller_cwd.

   The transport is a collaborator, not a dependency: the 2-arity takes the
   `:eval-fn` that carries a request to the JVM, so a test drives this with a
   stub instead of a live nREPL."
  ([args] (execute args {:eval-fn nrepl/eval-code
                         :port-fn nrepl/get-nrepl-port}))
  ([{:keys [command working_directory timeout_ms _caller_cwd]}
    {:keys [eval-fn port-fn]}]
   (let [timeout (or timeout_ms 180000)
        dir (or working_directory _caller_cwd)
        opts (cond-> {:timeout-ms timeout} dir (assoc :dir dir))
        res (try
              (eval-fn {:port (port-fn)
                        :code (remote-form command opts)
                        :timeout-ms (+ timeout nrepl-grace-ms)})
              (catch Exception e {:error? true :result (ex-message e)}))]
     (if (:error? res)
       {:exit-code -1 :stdout ""
        :stderr (str "bash runs in the hive-mcp JVM on this runtime, and it did "
                     "not answer: " (:result res))
        :error (str (:result res))}
       (->result (-> (:result res) edn/read-string edn/read-string) timeout)))))
