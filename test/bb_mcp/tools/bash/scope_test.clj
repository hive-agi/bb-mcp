(ns bb-mcp.tools.bash.scope-test
  "OOM scope policy for bash tool commands: argv building through an
  injected probe, and one real command proving it leaves the caller's cgroup."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bb-mcp.tools.bash :as bash]
            [bb-mcp.tools.bash.scope :as scope]))

(deftest scoped-argv-follows-the-probe-answer
  (is (= ["systemd-run" "--user" "--scope" "--collect" "--quiet"
          "-p" "ManagedOOMPreference=none" "bash" "-c" "ls"]
         (scope/scoped-argv true ["bash" "-c" "ls"])))
  (is (= ["bash" "-c" "ls"] (scope/scoped-argv false ["bash" "-c" "ls"])))
  (is (= ["bash" "-c" "ls"] (scope/scoped-argv nil ["bash" "-c" "ls"])))
  (is (= ["bash" "-c" "ls"] (scope/scoped-argv :yes ["bash" "-c" "ls"])))
  (is (= [] (scope/scoped-argv true []))))

(deftest argv-for-applies-the-injected-probe
  (is (= (into scope/scope-prefix ["x"]) (scope/argv-for (constantly true) ["x"])))
  (is (= ["x"] (scope/argv-for (constantly false) ["x"])))
  (is (= ["x"] (scope/argv-for #(throw (ex-info "no bus" {})) ["x"]))))

(deftest caching-probe-runs-once
  (let [calls (atom 0)
        probe (scope/caching-probe #(do (swap! calls inc) true))]
    (is (true? (probe)))
    (is (true? (probe)))
    (is (= 1 @calls))))

(deftest execute-keeps-working-on-the-plain-path
  (let [r (bash/execute {:command "echo hi; exit 3" :scope-probe (constantly false)})]
    (is (= 3 (:exit-code r)))
    (is (= "hi" (str/trim (:stdout r))))))

(deftest plan-argv-scopes-only-what-resolves
  (let [path (System/getenv "PATH")]
    (is (= (into scope/scope-prefix ["bash" "-c" "ls"]) (scope/plan-argv true ["bash" "-c" "ls"] {:path path})))
    (is (= ["hive-no-such-exe-x"] (scope/plan-argv true ["hive-no-such-exe-x"] {:path path})))
    (is (= ["bash"] (scope/plan-argv true ["bash"] {:path "/nonexistent-dir"}))
        "the child's PATH decides")
    (is (= ["bash"] (scope/plan-argv false ["bash"] {:path path})))))

(deftest expiring-probe-reprobes-and-records
  (let [now (atom 0)
        calls (atom 0)
        probe (scope/expiring-probe #(do (swap! calls inc) true) {:ttl-ms 1000 :clock #(deref now)})]
    (is (true? (probe)))
    (is (true? (probe)))
    (is (= 1 @calls))
    (scope/record-answer! probe false)
    (is (false? (probe)))
    (reset! now 2000)
    (is (true? (probe)))
    (is (= 2 @calls))))

(deftest scope-start-failure-signature
  (is (scope/scope-start-failure? {:exit-code 1 :stdout "" :stderr "Failed to start transient scope unit: Connection timed out\n"}))
  (is (scope/scope-start-failure? {:exit-code 1 :stdout "" :stderr "Failed to connect to bus: No such file or directory"}))
  (is (not (scope/scope-start-failure? {:exit-code 1 :stdout "x" :stderr "Failed to start transient scope unit"})))
  (is (not (scope/scope-start-failure? {:exit-code 2 :stdout "" :stderr "Failed to start transient scope unit"})))
  (is (not (scope/scope-start-failure? {:exit-code 1 :stdout "" :stderr "boom"}))))

(def ^:private failing-prefix
  "Behaves like systemd-run when the user manager refuses the scope."
  ["sh" "-c" "echo 'Failed to start transient scope unit: Connection timed out' >&2; exit 1" "sh"])

(deftest execute-routes-through-the-prefix
  (let [r (bash/execute {:command "echo mark=${SCOPED_BY_TEST:-none}"
                         :scope-probe (constantly true)
                         :scope-prefix ["env" "SCOPED_BY_TEST=1"]})]
    (is (= "mark=1" (str/trim (:stdout r))))))

(deftest execute-retries-plain-when-the-scope-fails-to-start
  (let [probe (scope/expiring-probe (constantly true))
        r (bash/execute {:command "echo ran; exit 4" :scope-probe probe :scope-prefix failing-prefix})]
    (is (= 4 (:exit-code r)))
    (is (= "ran" (str/trim (:stdout r))))
    (is (false? (probe)) "the cached answer flipped")))

(deftest execute-never-retries-a-command-that-ran
  (let [probe (scope/expiring-probe (constantly true))
        marker (java.io.File/createTempFile "bash-scope-ran" ".log")]
    (try
      (let [r (bash/execute {:command (str "echo ran >> " (.getPath marker)
                                           "; echo 'Failed to start transient scope unit' >&2; echo out; exit 1")
                             :scope-probe probe
                             :scope-prefix ["env" "SCOPED_BY_TEST=1"]})]
        (is (= 1 (:exit-code r)))
        (is (= 1 (count (str/split-lines (slurp marker)))))
        (is (true? (probe))))
      (finally (.delete marker)))))

(defn- own-cgroup []
  (some #(when (str/starts-with? % "0::") (subs % 3))
        (str/split-lines (:out (babashka.process/sh "cat" "/proc/self/cgroup")))))

(deftest execute-lands-in-a-sibling-scope
  (if-not (scope/systemd-probe)
    (is true "no systemd user manager here: plain path only")
    (testing "the command's cgroup differs from the caller's; exit code and stdout pass through"
      (let [r (bash/execute {:command "grep '^0::' /proc/self/cgroup; exit 5"
                             :scope-probe (constantly true)})
            child (some-> (:stdout r) str/trim (subs 3))]
        (is (= 5 (:exit-code r)))
        (is (str/ends-with? (str child) ".scope"))
        (is (not= (own-cgroup) child))))))
