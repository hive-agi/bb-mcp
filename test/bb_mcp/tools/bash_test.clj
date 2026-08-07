(ns bb-mcp.tools.bash-test
  "The bash tool at its real boundary: an OS process.

  The subject here IS the process seam — a double would prove nothing about the
  thing that broke, so these drive `bash` itself and keep to `echo`, `pwd` and
  `sleep`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bb-mcp.tools.bash :as bash]))

(defn- elapsed-ms [f]
  (let [t0 (System/currentTimeMillis)
        result (f)]
    [result (- (System/currentTimeMillis) t0)]))

(deftest captures-output-and-exit-code
  (let [{:keys [exit-code stdout timed-out]} (bash/execute {:command "echo hello"})]
    (is (= 0 exit-code))
    (is (= "hello" (str/trim stdout)))
    (is (false? timed-out))))

(deftest a-non-zero-exit-is-not-an-error
  (let [{:keys [exit-code error]} (bash/execute {:command "exit 3"})]
    (is (= 3 exit-code))
    (is (nil? error))))

(deftest stderr-is-kept-apart-from-stdout
  (let [{:keys [stdout stderr]} (bash/execute {:command "echo out; echo err >&2"})]
    (is (= "out" (str/trim stdout)))
    (is (= "err" (str/trim stderr)))))

(deftest it-runs-where-it-was-told-to
  (let [{:keys [stdout]} (bash/execute {:command "pwd" :working_directory "/tmp"})]
    (is (str/starts-with? (str/trim stdout) "/tmp"))))

(deftest a-backgrounded-child-does-not-hold-the-call
  (testing "a grandchild inheriting stdout must not outlive the caller's patience"
    (let [[{:keys [exit-code stdout detached]} ms]
          (elapsed-ms #(bash/execute {:command "nohup sleep 30 >/dev/null 2>&1 & echo started"
                                      :timeout_ms 5000}))]
      (is (= 0 exit-code))
      (is (= "started" (str/trim stdout)))
      (is (< ms 5000) "returned on the command's own exit, not the grandchild's")
      (is (nil? detached) "a redirected grandchild holds nothing open"))))

(deftest a-backgrounded-child-that-keeps-stdout-still-returns
  (testing "the wedge: the write end stays open, so EOF never comes"
    (let [[{:keys [exit-code stdout detached]} ms]
          (elapsed-ms #(bash/execute {:command "sleep 30 & echo started"
                                      :timeout_ms 5000}))]
      (is (= 0 exit-code))
      (is (= "started" (str/trim stdout)))
      (is (< ms 4000) "the flush grace bounds it; it must not wait on the grandchild")
      (is (true? detached) "and the caller is told the output was cut short"))))

(deftest a-timeout-kills-the-tree-and-says-so
  (let [[{:keys [exit-code timed-out stderr]} ms]
        (elapsed-ms #(bash/execute {:command "sleep 30" :timeout_ms 1500}))]
    (is (true? timed-out))
    (is (= -1 exit-code))
    (is (str/includes? stderr "timed out"))
    (is (< ms 4000))))

(deftest output-written-before-a-timeout-survives-it
  (let [{:keys [stdout timed-out]}
        (bash/execute {:command "echo early; sleep 30" :timeout_ms 1500})]
    (is (true? timed-out))
    (is (= "early" (str/trim stdout)))))

(deftest format-result-warns-when-output-was-detached
  (let [{:keys [result]} (bash/format-result {:exit-code 0 :stdout "started"
                                              :stderr "" :timed-out false
                                              :detached true})]
    (is (str/includes? result "background process"))))
