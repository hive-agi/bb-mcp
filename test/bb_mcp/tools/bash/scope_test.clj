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

(deftest strip-marker-and-retry-decision
  (let [m "bb-mcp-scope-exec:test"]
    (is (= ["err\n" true] (scope/strip-marker m (str m "\nerr\n"))))
    (is (= ["" true] (scope/strip-marker m m)))
    (is (= ["boom" false] (scope/strip-marker m "boom")))
    (let [sig {:exit-code 1 :stdout "" :stderr "Failed to start transient scope unit: x"}]
      (is (true? (scope/retry-plain? false sig)))
      (is (false? (scope/retry-plain? true sig)) "marker seen: never retry"))))

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

(def ^:private passing-prefix
  "Stands in for a systemd-run that starts the scope and execs the command."
  ["env" "SCOPED_BY_TEST=1"])

(defn- runs-once-despite-signature
  "A command that ran fully and itself ends like a systemd-run start failure
  (exit 1, empty stdout, the signature on stderr) runs exactly once, its
  stderr comes back without the marker, and the probe stays true."
  [stderr-line]
  (let [probe (scope/expiring-probe (constantly true))
        log (java.io.File/createTempFile "bash-scope-ran" ".log")]
    (try
      (let [r (bash/execute {:command (str "echo x >> " (.getPath log)
                                           "; echo '" stderr-line "' >&2; exit 1")
                             :scope-probe probe
                             :scope-prefix passing-prefix})]
        (is (= 1 (:exit-code r)))
        (is (= "" (:stdout r)))
        (is (= (str stderr-line "\n") (:stderr r)) "marker stripped, nothing else touched")
        (is (= ["x"] (str/split-lines (slurp log))) "ran once")
        (is (true? (probe)) "the probe did not flip"))
      (finally (.delete log)))))

(deftest nested-systemd-run-failure-inside-the-command-runs-once
  ;; echo x >> log; systemd-run --user --scope --unit=build make >/dev/null
  ;; with build.scope already existing.
  (runs-once-despite-signature
   "Failed to start transient scope unit: Unit build.scope was already loaded or has a fragment file."))

(deftest remote-bus-failure-inside-the-command-runs-once
  ;; echo x >> log; ssh host 'systemctl --user restart svc' with no remote user bus.
  (runs-once-despite-signature
   "Failed to connect to bus: No medium found"))

(deftest genuine-scope-start-failure-retries-once-plain
  (let [probe (scope/expiring-probe (constantly true))
        log (java.io.File/createTempFile "bash-scope-ran" ".log")]
    (try
      (let [r (bash/execute {:command (str "echo x >> " (.getPath log) "; echo ran; exit 4")
                             :scope-probe probe
                             :scope-prefix failing-prefix})]
        (is (= 4 (:exit-code r)))
        (is (= "ran" (str/trim (:stdout r))))
        (is (= ["x"] (str/split-lines (slurp log))) "the plain retry ran it, once")
        (is (not (str/includes? (:stderr r) "bb-mcp-scope-exec")))
        (is (false? (probe)) "the cached answer flipped"))
      (finally (.delete log)))))

(deftest marker-never-leaks
  (doseq [cmd ["echo out; echo err >&2; exit 7" "true" "printf 'no newline' >&2"]]
    (let [scoped (bash/execute {:command cmd :scope-probe (constantly true) :scope-prefix passing-prefix})
          plain  (bash/execute {:command cmd :scope-probe (constantly false)})]
      (is (not (str/includes? (str (:stderr scoped) (:stdout scoped)) "bb-mcp-scope-exec")))
      (is (= (select-keys plain [:exit-code :stdout :stderr :timed-out])
             (select-keys scoped [:exit-code :stdout :stderr :timed-out]))
          cmd))))

(deftest timeout-behaves-the-same-scoped
  (let [r (bash/execute {:command "echo started; sleep 5" :timeout_ms 300
                         :scope-probe (constantly true) :scope-prefix passing-prefix})]
    (is (true? (:timed-out r)))
    (is (= -1 (:exit-code r)))
    (is (= "Command timed out" (:stderr r)))))

(deftest quoting-survives-the-trampoline
  (let [cmd "x='a \"b\" $HOME'; printf '%s|%s\\n' \"$x\" '$1 \"q\"'\necho \"line2 $((1+2))\"; echo \"$0\""
        scoped (bash/execute {:command cmd :scope-probe (constantly true) :scope-prefix passing-prefix})
        plain  (bash/execute {:command cmd :scope-probe (constantly false)})]
    (is (= (:stdout plain) (:stdout scoped)))
    (is (str/includes? (:stdout scoped) "a \"b\" "))
    (is (str/includes? (:stdout scoped) "|$1 \"q\""))
    (is (str/includes? (:stdout scoped) "line2 3"))))

(deftest real-scope-hides-the-marker
  (if-not (scope/systemd-probe)
    (is true "no systemd user manager here: plain path only")
    (let [r (bash/execute {:command "echo err >&2; exit 1" :scope-probe (constantly true)})]
      (is (= 1 (:exit-code r)))
      (is (= "err\n" (:stderr r))))))

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
