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
