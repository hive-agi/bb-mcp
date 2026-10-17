(ns bb-mcp.tools.bash.remote-test
  "The remote bash executor against a stubbed transport.

   No nREPL and no hive-mcp: `execute`'s 2-arity takes the eval-fn, so every
   shape hive-system.shell.core/exec! can answer with is exercised here — the
   ones a live JVM produces only under a timeout or a bad working directory."
  (:require [bb-mcp.tools.bash.remote :as remote]
            [bb-mcp.tools.bash.spec :as spec]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-mcp.tools.nrepl :as nrepl]
            [bb-mcp.host.port :as hp]))

(defn- transport
  "An eval-fn answering `edn` the way nREPL does: pr-str of our pr-str.
   Records the request it was given in `seen`."
  [seen edn]
  (fn [req]
    (reset! seen req)
    {:error? false :result (pr-str (pr-str edn))}))

(defn- run
  ([args edn] (run args edn (atom nil)))
  ([args edn seen]
   (remote/execute args {:eval-fn (transport seen edn) :port-fn (constantly 7910)})))

(deftest ran-to-completion
  (testing "a normal exec! Result becomes the local tool's shape"
    (let [r (run {:command "echo hi"}
                 {:ok {:exit 3 :stdout "hi\n" :stderr "boom\n" :duration-ms 6.7}})]
      (is (= 3 (:exit-code r)))
      (is (= "hi\n" (:stdout r)))
      (is (= "boom\n" (:stderr r)))
      (is (false? (:timed-out r))))))

(deftest killed-at-the-deadline
  (testing "exec!'s :shell/timeout is reported as a timeout, not as an error"
    (let [r (run {:command "sleep 2" :timeout_ms 200}
                 {:ok {:error :shell/timeout :timeout-ms 200}})]
      (is (true? (:timed-out r)))
      (is (= -1 (:exit-code r)))
      (is (nil? (:error r)) "a timeout is a result, not a transport failure"))))

(deftest killed-at-the-deadline-flat-shape
  (testing "hive-system's flattened :shell/timeout is still read as a timeout"
    ;; hive-system stopped wrapping the timeout err in an ok. This head calls
    ;; whichever hive-system the running JVM loaded, so both shapes must fold
    ;; to the same answer -- otherwise a timeout reads as a spawn failure whose
    ;; message is empty, because the flat err carries no :message.
    (let [r (run {:command "sleep 2" :timeout_ms 200}
                 {:error :shell/timeout :cmd "sleep 2" :timeout-ms 200
                  :duration-ms 201.4})]
      (is (true? (:timed-out r)))
      (is (= -1 (:exit-code r)))
      (is (= "Command timed out" (:stderr r)))
      (is (nil? (:error r)) "a timeout is a result, not a transport failure"))))

(deftest never-started
  (testing "a process that could not be spawned surfaces its message"
    (let [r (run {:command "pwd" :working_directory "/nonexistent-xyz"}
                 {:error :shell/exec-failed
                  :message "Cannot run program \"sh\" (in directory \"/nonexistent-xyz\")"})]
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "/nonexistent-xyz"))
      (is (some? (:error r))))))

(deftest transport-down
  (testing "no JVM to answer names the JVM, so the client is not left guessing"
    (let [r (remote/execute {:command "echo hi"}
                            {:eval-fn (fn [_] {:error? true :result "connection refused"})
                             :port-fn (constantly 7910)})]
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "hive-mcp JVM"))
      (is (str/includes? (:stderr r) "connection refused")))))

(deftest transport-throws
  (testing "a thrown transport exception is folded, never propagated"
    (let [r (remote/execute {:command "echo hi"}
                            {:eval-fn (fn [_] (throw (ex-info "socket closed" {})))
                             :port-fn (constantly 7910)})]
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "socket closed")))))

(defn- refusing-open
  "An IByteChannel opener that refuses every port in `down`, the way cljw.net
  does when nothing listens. Records each port it was asked for in `asked`."
  [asked down]
  (fn [{:keys [port]}]
    (swap! asked conj port)
    (when (contains? down port)
      (throw (ex-info (str "cljw.net: could not connect to 'localhost:" port "'")
                      {:port port})))
    (throw (ex-info "unexpected open in this stub" {:port port}))))

(deftest refused-connection-is-tagged-by-the-client
  (testing "nrepl/eval-code marks a refused open as :connect-failed?"
    (let [asked (atom [])
          r (nrepl/eval-code {:port 7932 :code "(+ 1 2)"
                              :open-fn (refusing-open asked #{7932})})]
      (is (true? (:error? r)))
      (is (true? (:connect-failed? r)))
      (is (str/includes? (:result r) "localhost:7932"))
      (is (= [7932] @asked)))))

(deftest refused-everywhere-names-owner-and-recovery
  (testing "the port refuses: the error names the owner, how to start it, and why there is no local exec"
    (let [asked (atom [])
          eval-fn (fn [req] (nrepl/eval-code (assoc req :open-fn (refusing-open asked #{7932 7910}))))
          r (remote/execute {:command "git status"}
                            {:eval-fn eval-fn :port-fn (constantly 7932)})]
      (is (= [7932] @asked) "one port, dialed once")
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "Tried localhost:7932."))
      (is (str/includes? (:stderr r) "hive-mcp JVM nREPL"))
      (is (str/includes? (:stderr r) "`hive-mcp` launcher"))
      (is (str/includes? (:stderr r) "BB_MCP_HIVE_NREPL_PORT"))
      (is (str/includes? (:stderr r) "No local fallback"))
      (is (str/includes? (:stderr r) "BB_MCP_RUNTIME=bb")))))

(deftest resolve-nrepl-port-names-its-source
  (let [no-env (constantly nil)
        no-file (constantly nil)]
    (testing "an explicit port wins over everything"
      (is (= {:port 1234 :source :explicit-param}
             (nrepl/resolve-nrepl-port 1234 {:getenv (constantly "7000")
                                             :read-port-file (constantly 7932)}))))
    (testing "BB_MCP_NREPL_PORT beats the .nrepl-port file"
      (is (= {:port 7000 :source :env :env-var "BB_MCP_NREPL_PORT"}
             (nrepl/resolve-nrepl-port nil {:getenv {"BB_MCP_NREPL_PORT" " 7000\n"}
                                            :read-port-file (constantly 7932)}))))
    (testing "the .nrepl-port file carries its path"
      (let [asked (atom nil)]
        (is (= {:port 7932 :source :nrepl-port-file :path "/proj/.nrepl-port"}
               (nrepl/resolve-nrepl-port nil {:getenv no-env :project-dir "/proj"
                                              :read-port-file (fn [p] (reset! asked p) 7932)})))
        (is (= "/proj/.nrepl-port" @asked))))
    (testing "nothing configured: hive-mcp's default"
      (is (= {:port 7910 :source :default}
             (nrepl/resolve-nrepl-port nil {:getenv no-env :project-dir "/proj"
                                            :read-port-file no-file}))))
    (testing "the default source reads without a redundant fallback clause"
      (let [msg (remote/unreachable-message [7910] "refused" {:port 7910 :source :default})]
        (is (str/includes? msg "port 7910 came from the built-in default"))
        (is (not (str/includes? msg "then hive-mcp's default")))))))

(deftest refused-error-says-where-the-port-came-from
  (let [refuse (fn [{:keys [port]}]
                 {:error? true :connect-failed? true
                  :result (str "cljw.net: could not connect to 'localhost:" port "'")})]
    (testing "an injected .nrepl-port source is named by path, with the hive-build hint for 7932"
      (let [r (remote/execute {:command "git status"}
                              {:eval-fn refuse
                               :resolve-fn (constantly {:port 7932 :source :nrepl-port-file
                                                        :path "/proj/.nrepl-port"})})]
        (is (str/includes? (:stderr r) "port 7932 came from /proj/.nrepl-port"))
        (is (str/includes? (:stderr r) "hive-build's deps.edn"))))
    (testing "an env port is named by its variable, and no hive-build hint"
      (let [r (remote/execute {:command "git status"}
                              {:eval-fn refuse
                               :resolve-fn (constantly {:port 7000 :source :env
                                                        :env-var "BB_MCP_HIVE_NREPL_PORT"})})]
        (is (str/includes? (:stderr r) "port 7000 came from the BB_MCP_HIVE_NREPL_PORT env var"))
        (is (not (str/includes? (:stderr r) "hive-build")))))
    (testing "a bare-int :port-fn carries no source and claims none"
      (let [r (remote/execute {:command "git status"}
                              {:eval-fn refuse :port-fn (constantly 7000)})]
        (is (str/includes? (:stderr r) "Tried localhost:7000."))
        (is (not (str/includes? (:stderr r) "came from")))))))

(defn- recording-ok
  "An eval-fn that records each dialed port in `seen` and answers exit 0."
  [seen]
  (fn [{:keys [port] :as req}]
    (swap! seen conj port)
    ((transport (atom nil) {:ok {:exit 0 :stdout "ok\n" :stderr ""}}) req)))

(defn- recording-refuse
  "An eval-fn that records each dialed port in `seen` and refuses it the way
  cljw.net does."
  [seen]
  (fn [{:keys [port]}]
    (swap! seen conj port)
    {:error? true :connect-failed? true
     :result (str "cljw.net: could not connect to 'localhost:" port "'")}))

(deftest bash-dials-the-hive-host-never-the-project-repl
  (testing "BB_MCP_NREPL_PORT (the launcher's copy of <project>/.nrepl-port) is not dialed"
    (let [seen (atom [])
          r (remote/execute {:command "true"}
                            {:eval-fn (recording-ok seen)
                             :getenv {"BB_MCP_NREPL_PORT" "7934"}})]
      (is (= [7910] @seen))
      (is (= 0 (:exit-code r)))))
  (testing "BB_MCP_HIVE_NREPL_PORT moves the hive host, and wins over BB_MCP_NREPL_PORT"
    (let [seen (atom [])]
      (remote/execute {:command "true"}
                      {:eval-fn (recording-ok seen)
                       :getenv {"BB_MCP_HIVE_NREPL_PORT" "7950"
                                "BB_MCP_NREPL_PORT" "7934"}})
      (is (= [7950] @seen)))))

(deftest a-refused-hive-port-is-dialed-once-and-named
  (testing "an env-set hive port refuses: one dial, the var is named, no project port is offered"
    (let [seen (atom [])
          r (remote/execute {:command "git status"}
                            {:eval-fn (recording-refuse seen)
                             :getenv {"BB_MCP_HIVE_NREPL_PORT" "7950"
                                      "BB_MCP_NREPL_PORT" "7934"}})]
      (is (= [7950] @seen) "one hive host, so nothing to fall back to")
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "port 7950 came from the BB_MCP_HIVE_NREPL_PORT env var"))
      (is (str/includes? (:stderr r) "never BB_MCP_NREPL_PORT nor a project .nrepl-port"))
      (is (not (str/includes? (:stderr r) "7934")))))
  (testing "the default hive port refuses: named as the default"
    (let [seen (atom [])
          r (remote/execute {:command "git status"}
                            {:eval-fn (recording-refuse seen) :getenv {}})]
      (is (= [7910] @seen))
      (is (str/includes? (:stderr r) "port 7910 came from the built-in default")))))

(deftest failure-after-connect-is-never-retried
  (testing "an error once connected is not a refusal: the command is sent once"
    (let [seen (atom [])
          r (remote/execute {:command "touch x"}
                            {:eval-fn (fn [{:keys [port]}]
                                        (swap! seen conj port)
                                        {:error? true :result "Read timed out"})
                             :port-fn (constantly 7932)})]
      (is (= [7932] @seen))
      (is (str/includes? (:stderr r) "Read timed out")))))

(defn- broken-channel-open
  "An opener whose channel OPENS, then throws on `fail-at` (:write or :read).
  Records each opened port in `opened` and each write in `writes`."
  [opened writes fail-at]
  (fn [{:keys [port]}]
    (swap! opened conj port)
    (reify hp/IByteChannel
      (write-bytes! [_ _]
        (swap! writes inc)
        (when (= :write fail-at) (throw (ex-info "Broken pipe" {:port port})))
        nil)
      (read-bytes! [_ _]
        (when (= :read fail-at) (throw (ex-info "Read timed out" {:port port})))
        -1)
      (close! [_] nil))))

(deftest failure-after-open-is-not-a-refusal
  (doseq [fail-at [:write :read]]
    (testing (str "a channel that opened and then failed on " (name fail-at)
                  " is an error, never :connect-failed?")
      (let [opened (atom []) writes (atom 0)
            r (nrepl/eval-code {:port 7932 :code "(+ 1 2)"
                                :open-fn (broken-channel-open opened writes fail-at)})]
        (is (true? (:error? r)))
        (is (not (contains? r :connect-failed?)))
        (is (= [7932] @opened))
        (is (= 1 @writes))))))

(deftest eval-after-open-failure-is-sent-once
  (doseq [fail-at [:write :read]]
    (testing (str "remote bash over a client whose channel fails on " (name fail-at)
                  " sends the eval exactly once, to the resolved port only")
      (let [opened (atom []) writes (atom 0) evals (atom [])
            client (fn [req]
                     (nrepl/eval-code
                      (assoc req :open-fn (broken-channel-open opened writes fail-at))))
            recording (fn [req] (swap! evals conj (:port req)) (client req))
            r (remote/execute {:command "touch x"}
                              {:eval-fn recording :port-fn (constantly 7932)})]
        (is (= [7932] @evals))
        (is (= [7932] @opened))
        (is (= 1 @writes))
        (is (= -1 (:exit-code r)))
        (is (not (str/includes? (:stderr r) "No local fallback")))))))

(deftest request-carries-the-guarded-inputs
  (testing "working directory and timeout reach exec!, and the deadline has headroom"
    (let [seen (atom nil)]
      (run {:command "pwd" :_caller_cwd "/tmp" :timeout_ms 1000}
           {:ok {:exit 0 :stdout "/tmp\n" :stderr ""}} seen)
      (is (str/includes? (:code @seen) ":dir \"/tmp\"")
          "the session cwd is passed as exec!'s :dir")
      (is (str/includes? (:code @seen) ":timeout-ms 1000"))
      (is (> (:timeout-ms @seen) 1000)
          "the socket read must outlive the kill the JVM performs")))
  (testing "an explicit working_directory wins over the injected cwd"
    (let [seen (atom nil)]
      (run {:command "pwd" :_caller_cwd "/tmp" :working_directory "/var"}
           {:ok {:exit 0 :stdout "" :stderr ""}} seen)
      (is (str/includes? (:code @seen) ":dir \"/var\"")))))

(deftest one-spec-two-executors
  (testing "the remote tool advertises the same spec the local one does"
    (is (identical? spec/tool-spec remote/tool-spec))
    (is (= "bash" (:name remote/tool-spec)))))
