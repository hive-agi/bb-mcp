(ns bb-mcp.piggyback-test
  "Piggyback blocks on the tools this head serves itself, against a stubbed
   transport. No nREPL and no hive-mcp: every way the drain can fail is
   exercised, and every one of them must leave the tool's output intact."
  (:require [bb-mcp.core :as core]
            [bb-mcp.guard :as guard]
            [bb-mcp.piggyback :as piggyback]
            [bb-mcp.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private block
  "\n\n---EMACS-ATTENTION---\nEmacs is WAITING FOR INPUT\n---/EMACS-ATTENTION---")

(defn- transport
  "An eval-fn answering `edn` the way nREPL does: pr-str of our pr-str.
   Records the request it was given in `seen`."
  ([edn] (transport (atom nil) edn))
  ([seen edn]
   (fn [req]
     (reset! seen req)
     {:error? false :result (pr-str (pr-str edn))})))

(defn- drain [eval-fn]
  (piggyback/drain "bash" {:_caller_id "coordinator:1" :command "ls"}
                   {:eval-fn eval-fn :port-fn (constantly 7910)}))

;;; ===========================================================================
;;; drain
;;; ===========================================================================

(deftest the-blocks-come-back-as-text
  (is (= block (drain (transport block)))))

(deftest nothing-to-say-is-the-empty-string
  (is (= "" (drain (transport "")))))

(deftest the-drain-is-total
  (testing "a transport error"
    (is (= "" (drain (fn [_] {:error? true :result "Connection refused"})))))
  (testing "a throwing transport"
    (is (= "" (drain (fn [_] (throw (ex-info "socket closed" {})))))))
  (testing "an unreadable reply"
    (is (= "" (drain (fn [_] {:error? false :result "not edn ((("})))))
  (testing "the JVM reporting it could not drain"
    (is (= "" (drain (transport {:piggyback/gap :remote-threw}))))))

(deftest the-request-carries-identity-not-payload
  (let [seen (atom nil)]
    (piggyback/drain "bash"
                     {:_caller_id "coordinator:1" :_caller_cwd "/p" :command "cat secrets.txt"}
                     {:eval-fn (transport seen "") :port-fn (constantly 7910)})
    (let [code (:code @seen)]
      (is (str/includes? code "wrap-handler-piggybacks"))
      (is (str/includes? code "coordinator:1"))
      (is (str/includes? code "/p"))
      (is (not (str/includes? code "secrets.txt"))
          "the command itself never travels to the drain"))
    (is (= 3000 (:timeout-ms @seen)) "a short deadline: output is never held for blocks")))

(deftest append-leaves-a-quiet-response-untouched
  (is (= "out" (piggyback/append "out" "")))
  (is (= (str "out" block) (piggyback/append "out" block))))

;;; ===========================================================================
;;; core: native tools carry the blocks, forwarded tools do not get them twice
;;; ===========================================================================

(defn- call [tool-name verdict]
  (let [drained (atom [])]
    (with-redefs [core/get-tools (constantly
                                  [(tool/native-tool {:name tool-name}
                                                     (fn [_] {:result "stdout" :error? false}))])
                  guard/decide (constantly verdict)]
      (binding [core/*drain-fn* (fn [n args] (swap! drained conj [n args]) block)]
        {:text (get-in (#'core/call-tool 1 tool-name {:command "ls"}) [:result :content 0 :text])
         :drained @drained}))))

(deftest a-native-call-carries-the-blocks
  (let [{:keys [text drained]} (call "bash" #:guard{:verdict :allow :enforcing? true})]
    (is (= (str "stdout" block) text))
    (is (= "bash" (ffirst drained)))
    (is (string? (:_caller_id (second (first drained))))
        "the drain is asked for this session's cursor")))

(deftest a-warned-native-call-keeps-the-warning-and-the-blocks
  (let [{:keys [text]} (call "bash" #:guard{:verdict :warn :reason "prefer carto"
                                            :rule-id :guard/carto-first-shell-search})]
    (is (str/starts-with? text "stdout\n\nGUARD WARNING"))
    (is (str/ends-with? text "---/EMACS-ATTENTION---"))))

(deftest a-refused-native-call-still-carries-the-blocks
  (let [{:keys [text]} (call "bash" #:guard{:verdict :deny :reason "no"})]
    (is (str/starts-with? text "REFUSED by the hive guard"))
    (is (str/includes? text "---EMACS-ATTENTION---"))))

(deftest a-forwarded-tool-is-not-drained-here
  (let [{:keys [text drained]} (call "memory" #:guard{:verdict :allow})]
    (is (= "stdout" text))
    (is (empty? drained) "hive-mcp's own middleware already appends them")))
