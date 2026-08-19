(ns bb-mcp.tools.bash.remote-test
  "The remote bash executor against a stubbed transport.

   No nREPL and no hive-mcp: `execute`'s 2-arity takes the eval-fn, so every
   shape hive-system.shell.core/exec! can answer with is exercised here — the
   ones a live JVM produces only under a timeout or a bad working directory."
  (:require [bb-mcp.tools.bash.remote :as remote]
            [bb-mcp.tools.bash.spec :as spec]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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
