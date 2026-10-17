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
  (testing "both ports refuse: the error names the owner, how to start it, and why there is no local exec"
    (let [asked (atom [])
          eval-fn (fn [req] (nrepl/eval-code (assoc req :open-fn (refusing-open asked #{7932 7910}))))
          r (remote/execute {:command "git status"}
                            {:eval-fn eval-fn :port-fn (constantly 7932)})]
      (is (= [7932 7910] @asked) "the resolved port first, then hive-mcp's own")
      (is (= -1 (:exit-code r)))
      (is (str/includes? (:stderr r) "localhost:7932, localhost:7910"))
      (is (str/includes? (:stderr r) "hive-mcp JVM nREPL"))
      (is (str/includes? (:stderr r) "`hive-mcp` launcher"))
      (is (str/includes? (:stderr r) ".nrepl-port"))
      (is (str/includes? (:stderr r) "No local fallback"))
      (is (str/includes? (:stderr r) "BB_MCP_RUNTIME=bb")))))

(deftest refused-port-falls-back-to-hive-mcp-port
  (testing "a stale resolved port refuses; hive-mcp's port answers and the command runs"
    (let [seen (atom [])
          eval-fn (fn [{:keys [port] :as req}]
                    (swap! seen conj port)
                    (if (= 7932 port)
                      {:error? true :connect-failed? true
                       :result "cljw.net: could not connect to 'localhost:7932'"}
                      ((transport (atom nil) {:ok {:exit 0 :stdout "clean\n" :stderr ""}}) req)))
          r (remote/execute {:command "git status"}
                            {:eval-fn eval-fn :port-fn (constantly 7932)})]
      (is (= [7932 7910] @seen))
      (is (= 0 (:exit-code r)))
      (is (= "clean\n" (:stdout r))))))

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
