(ns bb-mcp.guard-test
  "The guard client against a stubbed transport.

   No nREPL and no hive-mcp: `decide`'s 3-arity takes the eval-fn, so every way
   the round-trip can fail is exercised here — the ones a live JVM produces
   only when it is down, mid-reload, or serving a build with no guard at all.

   The property that matters most is TOTALITY: every path answers with a
   decision, and every path that could not judge says so rather than passing
   for permission."
  (:require [bb-mcp.core :as core]
            [bb-mcp.guard :as guard]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- transport
  "An eval-fn answering `edn` the way nREPL does: pr-str of our pr-str.
   Records the request it was given in `seen`."
  [seen edn]
  (fn [req]
    (reset! seen req)
    {:error? false :result (pr-str (pr-str edn))}))

(defn- decide
  ([args edn] (decide args edn (atom nil)))
  ([args edn seen]
   (guard/decide "bash" args {:eval-fn (transport seen edn)
                              :port-fn (constantly 7910)})))

;;; ===========================================================================
;;; Verdicts carried back intact
;;; ===========================================================================

(deftest a-refusal-comes-back-a-refusal
  (testing "the JVM's deny is the decision, reason and citations intact"
    (let [d (decide {:command "git add -A"}
                    #:guard{:verdict :deny
                            :reason "stage explicit paths"
                            :rule-id :guard/no-git-add-all
                            :citations ["20260704183422-5333b2fb"]
                            :enforcing? true})]
      (is (guard/denied? d))
      (is (not (guard/warned? d)))
      (is (= :guard/no-git-add-all (:guard/rule-id d)))
      (is (nil? (:guard/gap d)) "a real verdict is not a gap"))))

(deftest an-advisory-lets-the-call-run
  (testing "a warn is a warn, not a deny"
    (let [d (decide {:command "grep -rn foo src"}
                    #:guard{:verdict :warn
                            :reason "prefer carto"
                            :rule-id :guard/carto-first-shell-search
                            :enforcing? true})]
      (is (guard/warned? d))
      (is (not (guard/denied? d))))))

(deftest an-allow-is-an-allow
  (testing "a judged allow keeps :enforcing? true and carries no gap"
    (let [d (decide {:command "ls"} #:guard{:verdict :allow :enforcing? true})]
      (is (= :allow (:guard/verdict d)))
      (is (true? (:guard/enforcing? d)))
      (is (nil? (:guard/gap d))))))

;;; ===========================================================================
;;; Every failure fails OPEN, and says so
;;; ===========================================================================

(defn- gap-of
  "The decision produced when the transport behaves as `f`."
  [f]
  (guard/decide "bash" {:command "ls"}
                {:eval-fn f :port-fn (constantly 7910)}))

(deftest a-throwing-transport-allows-and-announces
  (testing "an unreachable JVM does not stop the tool, and is not permission"
    (let [d (gap-of (fn [_] (throw (ex-info "connection refused" {}))))]
      (is (= :allow (:guard/verdict d)))
      (is (false? (:guard/enforcing? d)))
      (is (= :guard-unreachable (:guard/gap d)))
      (is (str/includes? (str (:guard/gap-detail d)) "connection refused")))))

(deftest a-transport-error-allows-and-announces
  (testing "nREPL's own {:error? true} is a gap, not a verdict"
    (let [d (gap-of (constantly {:error? true :result "No response from nREPL"}))]
      (is (= :allow (:guard/verdict d)))
      (is (false? (:guard/enforcing? d)))
      (is (= :guard-unreachable (:guard/gap d))))))

(deftest an-unreadable-reply-allows-and-announces
  (testing "a value that is not a decision is a gap"
    (doseq [junk ["" "not-edn(((" (pr-str (pr-str [1 2 3])) (pr-str (pr-str nil))]]
      (let [d (gap-of (constantly {:error? false :result junk}))]
        (is (= :allow (:guard/verdict d)) (str "junk: " (pr-str junk)))
        (is (false? (:guard/enforcing? d)))
        (is (= :unreadable-reply (:guard/gap d)))))))

(deftest an-absent-seam-allows-and-announces
  (testing "a JVM with no guard registered is a gap, not a silent pass"
    (let [d (decide {:command "ls"} {:guard/gap :seam-absent})]
      (is (= :allow (:guard/verdict d)))
      (is (false? (:guard/enforcing? d)))
      (is (= :seam-absent (:guard/gap d))))))

(deftest a-throwing-seam-allows-and-announces
  (testing "the remote catch is carried back as a gap with its detail"
    (let [d (decide {:command "ls"}
                    {:guard/gap :remote-threw :guard/gap-detail "boom"})]
      (is (= :allow (:guard/verdict d)))
      (is (= :remote-threw (:guard/gap d)))
      (is (= "boom" (:guard/gap-detail d))))))

(deftest decide-is-total
  (testing "no input shape produces nil or a throw"
    (doseq [args [{} {:command nil} {:command "ls"} {:weird ["a" 1 true nil]}]]
      (is (map? (decide args #:guard{:verdict :allow :enforcing? true}))
          (str "args: " (pr-str args))))))

;;; ===========================================================================
;;; What crosses the wire
;;; ===========================================================================

(deftest the-request-carries-the-moment
  (testing "tool name, input and cwd reach the JVM"
    (let [seen (atom nil)]
      (decide {:command "git add -A" :_caller_cwd "/home/leibniz/PP/hive"}
              #:guard{:verdict :allow :enforcing? true}
              seen)
      (let [code (:code @seen)]
        (is (str/includes? code "bash"))
        (is (str/includes? code "git add -A"))
        (is (str/includes? code "/home/leibniz/PP/hive"))
        (is (str/includes? code ":guard/decide")
            "it must go through the published seam, not a named namespace"))))
  (testing "the payload is quoted so the JVM never evaluates wire data"
    (let [seen (atom nil)]
      (decide {:command "(println :pwned)"} #:guard{:verdict :allow} seen)
      (is (str/includes? (:code @seen) "quote")))))

(deftest the-deadline-is-short
  (testing "a decision does not inherit a tool's long timeout"
    (let [seen (atom nil)]
      (decide {:command "ls"} #:guard{:verdict :allow} seen)
      (is (<= (:timeout-ms @seen) 10000)
          "a guard that cannot answer promptly must fail open, not hold the call"))))

;;; ===========================================================================
;;; Coverage — which tools this gate is responsible for
;;; ===========================================================================

(deftest the-native-tools-are-the-gated-ones
  (testing "every tool this head serves itself is judged here"
    ;; These reach no other gate: hive-mcp's dispatch chain never sees them.
    ;; A new native tool added without a thought for the guard fails here.
    (is (= #{"bash" "clojure_eval"} @#'core/native-tool-names))))

;;; ===========================================================================
;;; Rendering
;;; ===========================================================================

(deftest a-refusal-states-the-rule-and-its-citation
  (let [text (guard/refusal-text
              "bash"
              #:guard{:verdict :deny
                      :reason "stage explicit paths, never git add -A"
                      :rule-id :guard/no-git-add-all
                      :citations ["20260704183422-5333b2fb" "20260101000000-deadbeef"]})]
    (is (str/includes? text "REFUSED"))
    (is (str/includes? text "bash"))
    (is (str/includes? text "stage explicit paths"))
    (is (str/includes? text "20260704183422-5333b2fb"))
    (is (str/includes? text "20260101000000-deadbeef"))
    (is (str/includes? text "no-git-add-all")))
  (testing "a refusal with no citations still renders"
    (is (string? (guard/refusal-text "bash" #:guard{:verdict :deny :reason "no"})))))

(deftest a-warning-states-the-rule
  (let [text (guard/warning-text #:guard{:reason "prefer carto"
                                         :rule-id :guard/carto-first-shell-search})]
    (is (str/includes? text "GUARD WARNING"))
    (is (str/includes? text "prefer carto"))
    (is (str/includes? text "carto-first-shell-search"))))
