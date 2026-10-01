(ns bb-mcp.update-test
  "The release notice: pure decisions (golden + property + mutation) and the
   boundary against a stub `sys` (no network, no real HOME)."
  (:require [bb-mcp.update :as u]
            [bb-mcp.update.io :as uio]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Golden

(deftest parse-version-golden
  (is (= [1 2 29] (u/parse-version "1.2.29")))
  (is (= [1 2 29] (u/parse-version "v1.2.29")))
  (is (= [1 2 29] (u/parse-version " 1.2.29\n")))
  (testing "pre-release and fork tags are not releases"
    (is (nil? (u/parse-version "1.2.29-hive.1")))
    (is (nil? (u/parse-version "v1.2")))
    (is (nil? (u/parse-version "latest")))
    (is (nil? (u/parse-version nil)))))

(deftest compare-versions-golden
  (is (= -1 (u/compare-versions "1.2.29" "1.2.30")))
  (is (= -1 (u/compare-versions "1.2.9" "1.2.10")) "numeric, not lexical")
  (is (= 1 (u/compare-versions "2.0.0" "1.99.99")))
  (is (= 0 (u/compare-versions "v1.2.3" "1.2.3"))))

(def ls-remote-out
  (str/join "\n" ["aaa\trefs/tags/v1.2.9"
                  "bbb\trefs/tags/v1.2.30"
                  "ccc\trefs/tags/v1.2.10"
                  "ddd\trefs/tags/v1.2.31-hive.1"
                  "eee\trefs/tags/v0.4.0"]))

(deftest newest-tag-golden
  (is (= "1.2.30" (u/newest-tag ls-remote-out)))
  (is (nil? (u/newest-tag "")))
  (is (nil? (u/newest-tag nil)))
  (is (nil? (u/newest-tag "x\trefs/tags/nightly"))))

(deftest cache-golden
  (is (= "/x/hive-mcp/bb-mcp-latest.edn" (u/cache-path {:xdg-cache "/x" :home "/h"})))
  (is (= "/h/.cache/hive-mcp/bb-mcp-latest.edn" (u/cache-path {:xdg-cache nil :home "/h"})))
  (is (= "/h/.cache/hive-mcp/bb-mcp-latest.edn" (u/cache-path {:xdg-cache "" :home "/h"})))
  (let [c (u/cache-entry "1.2.30" 1000)]
    (is (u/cache-fresh? c 1000 u/ttl-ms))
    (is (u/cache-fresh? c (+ 1000 u/ttl-ms) u/ttl-ms))
    (is (not (u/cache-fresh? c (+ 1001 u/ttl-ms) u/ttl-ms)) "older than 24h")
    (is (not (u/cache-fresh? c 999 u/ttl-ms)) "clock went backwards"))
  (is (not (u/cache-fresh? {:latest "garbage" :checked-at 1} 1 u/ttl-ms)))
  (is (not (u/cache-fresh? "not a map" 1 u/ttl-ms))))

(deftest disabled-golden
  (is (u/disabled? "1"))
  (is (u/disabled? "true"))
  (doseq [v [nil "" "0" "false" " FALSE "]]
    (is (not (u/disabled? v)) (pr-str v))))

(deftest decide-golden
  (is (= {:status :behind :local "1.2.29" :latest "1.2.30"}
         (u/decide {:local "1.2.29\n" :latest "1.2.30"})))
  (is (= :current (:status (u/decide {:local "1.2.30" :latest "1.2.30"}))))
  (is (= :ahead (:status (u/decide {:local "1.3.0" :latest "1.2.30"}))))
  (is (= :unknown (:status (u/decide {:local "1.2.29" :latest nil}))) "offline")
  (is (= :unknown (:status (u/decide {:local nil :latest "1.2.30"}))))
  (is (= :disabled (:status (u/decide {:local "1.2.29" :latest "1.2.30" :disabled? true})))))

(deftest notice-line-golden
  (is (= "bb-mcp 1.2.29 -> 1.2.30 available: run hive update (or git pull in /src/bb-mcp)"
         (u/notice-line (u/decide {:local "1.2.29" :latest "1.2.30"}) "/src/bb-mcp")))
  (doseq [d [{:local "1.2.30" :latest "1.2.30"} {:local "1.2.29"} {:disabled? true}]]
    (is (nil? (u/notice-line (u/decide d) "/c")) (pr-str d))))

(deftest check-row-golden
  (is (= {:step "update" :status :warn
          :detail "bb-mcp 1.2.29 -> 1.2.30 available: run hive update (or git pull in /c)"}
         (u/check-row (u/decide {:local "1.2.29" :latest "1.2.30"}) "/c")))
  (testing "nothing but :behind warns, and nothing ever fails"
    (doseq [d [{:local "1.2.30" :latest "1.2.30"} {:local "2.0.0" :latest "1.2.30"}
               {:local "1.2.29"} {:disabled? true}]]
      (is (= :ok (:status (u/check-row (u/decide d) "/c"))) (pr-str d)))))

;; ---------------------------------------------------------------------------
;; Properties

(def gen-version
  "Mostly small components, so equal prefixes (1.2.x vs 1.2.y) are common,
   with some multi-digit ones, so lexical order (9 vs 10) is exercised."
  (gen/vector (gen/frequency [[3 (gen/choose 0 3)] [1 (gen/choose 0 300)]]) 3))
(def gen-tag (gen/fmap (fn [[v prefix?]] (str (when prefix? "v") (u/render-version v)))
                       (gen/tuple gen-version gen/boolean)))

(defn- passes? [p] (:pass? (tc/quick-check 200 p {:seed 20261001})))

(defn- numeric-comparison
  "The comparator `cmp` (over version strings) orders like the component
   vectors do. Parameterised so the mutation test can feed it a mutant."
  [cmp]
  (prop/for-all [a gen-version b gen-version]
    (= (compare a b) (cmp (u/render-version a) (u/render-version b)))))

(deftest version-properties
  (is (passes? (prop/for-all [v gen-version]
                 (= v (u/parse-version (u/render-version v)))))
      "render/parse round-trips")
  (is (passes? (prop/for-all [a gen-tag b gen-tag]
                 (= (u/compare-versions a b) (- (u/compare-versions b a)))))
      "comparison is antisymmetric")
  (is (passes? (numeric-comparison u/compare-versions))
      "comparison is numeric, component by component"))

(deftest newest-tag-properties
  (is (passes? (prop/for-all [tags (gen/not-empty (gen/vector gen-tag))]
                 (let [out (str/join "\n" (map #(str "sha\trefs/tags/" %) tags))
                       newest (u/newest-tag out)]
                   (and (some #(zero? (u/compare-versions newest %)) tags)
                        (every? #(<= (u/compare-versions % newest) 0) tags)))))
      "the newest tag is one of the tags and no tag is newer"))

(deftest decide-properties
  (is (passes? (prop/for-all [a gen-tag b gen-tag]
                 (let [{:keys [status]} (u/decide {:local a :latest b})
                       c (u/compare-versions a b)]
                   (= status (case c -1 :behind 0 :current 1 :ahead)))))
      "the status follows the comparison")
  (is (passes? (prop/for-all [a gen-tag b gen-tag]
                 (= (some? (u/notice-line (u/decide {:local a :latest b}) "/c"))
                    (neg? (u/compare-versions a b)))))
      "a line is printed exactly when behind")
  (is (passes? (prop/for-all [a gen-tag b gen-tag]
                 (nil? (u/notice-line (u/decide {:local a :latest b :disabled? true}) "/c"))))
      "the opt-out always silences the line"))

;; ---------------------------------------------------------------------------
;; Mutation: the properties above must reject a broken comparator

(deftest properties-catch-a-lexical-comparator
  (testing "the numeric property rejects a comparator that compares strings"
    (let [lexical (fn [a b] (compare (str a) (str b)))]
      (is (not (passes? (numeric-comparison lexical))))))
  (testing "and a comparator that ignores the patch component"
    (let [no-patch (fn [a b] (compare (subvec (u/parse-version a) 0 2)
                                      (subvec (u/parse-version b) 0 2)))]
      (is (not (passes? (numeric-comparison no-patch)))))))

;; ---------------------------------------------------------------------------
;; Boundary, through a recording stub sys

(defn- stub-sys
  "A sys over an in-memory filesystem. `remote` is the ls-remote output (nil
   = offline). Records lookups and stderr lines in the returned atom."
  [{:keys [files remote now env] :or {now 5000 env {}}}]
  (let [log (atom {:files (or files {}) :lookups 0 :said []})]
    [{:getenv     #(get env %)
      :home       "/h"
      :now-ms     (constantly now)
      :read-file  #(get-in @log [:files %])
      :write-file (fn [p t] (swap! log assoc-in [:files p] t))
      :ls-remote  (fn [_url _ms] (swap! log update :lookups inc) remote)
      :say        #(swap! log update :said conj %)}
     log]))

(def cache-file "/h/.cache/hive-mcp/bb-mcp-latest.edn")

(deftest notify-prints-one-line-when-behind-and-caches
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29\n"} :remote ls-remote-out})]
    (is (= :behind (:status (uio/notify! sys "/c"))))
    (is (= ["bb-mcp 1.2.29 -> 1.2.30 available: run hive update (or git pull in /c)"]
           (:said @log)))
    (is (= {:latest "1.2.30" :checked-at 5000}
           (read-string (get-in @log [:files cache-file]))))))

(deftest a-fresh-cache-is-not-looked-up-again
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.30"
                                     cache-file (pr-str (u/cache-entry "1.2.30" 4000))}
                             :remote ls-remote-out})]
    (is (= :current (:status (uio/notify! sys "/c"))))
    (is (zero? (:lookups @log)))
    (is (empty? (:said @log)))))

(deftest a-stale-or-corrupt-cache-is-looked-up
  (doseq [cached [(pr-str (u/cache-entry "1.2.20" (- 5000 u/ttl-ms 1))) "{:oops"]]
    (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29" cache-file cached}
                               :remote ls-remote-out})]
      (is (= "1.2.30" (uio/latest! sys)))
      (is (= 1 (:lookups @log))))))

(deftest xdg-cache-home-is-honoured
  (let [[sys log] (stub-sys {:env {"XDG_CACHE_HOME" "/x"} :remote ls-remote-out})]
    (uio/latest! sys)
    (is (contains? (:files @log) "/x/hive-mcp/bb-mcp-latest.edn"))))

(deftest offline-is-silent-and-not-cached
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29"} :remote nil})]
    (is (= :unknown (:status (uio/notify! sys "/c"))))
    (is (empty? (:said @log)))
    (is (not (contains? (:files @log) cache-file)))))

(deftest the-opt-out-looks-nothing-up
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29"} :remote ls-remote-out
                             :env {u/opt-out-var "1"}})]
    (is (= :disabled (:status (uio/notify! sys "/c"))))
    (is (zero? (:lookups @log)))
    (is (empty? (:said @log)))))

(deftest a-throwing-sys-never-escapes
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29"}})
        sys (assoc sys :ls-remote (fn [& _] (throw (ex-info "boom" {}))))]
    (is (nil? (uio/notify! sys "/c")))
    (is (empty? (:said @log)))))

(deftest start-returns-at-once-on-a-daemon-thread
  (let [[sys log] (stub-sys {:files {"/c/VERSION" "1.2.29"}})
        gate (promise)
        sys (assoc sys :ls-remote (fn [& _] @gate ls-remote-out))
        t0 (System/currentTimeMillis)
        thread (uio/start! sys "/c")]
    (is (< (- (System/currentTimeMillis) t0) 500) "startup does not wait")
    (is (.isDaemon ^Thread thread))
    (deliver gate true)
    (.join ^Thread thread 2000)
    (is (= 1 (count (:said @log))))))

(deftest run-bounded-gives-up-on-a-slow-process
  (testing "a process that answers in time is heard (so the timeout below is real)"
    (is (= "hi\n" (uio/run-bounded ["echo" "hi"] 2000)))
    (is (= "0\n" (uio/run-bounded ["sh" "-c" "echo $GIT_TERMINAL_PROMPT"] 2000))
        "git may never prompt for credentials"))
  (testing "a slow process is abandoned within the bound"
    (let [t0 (System/currentTimeMillis)]
      (is (nil? (uio/run-bounded ["sleep" "5"] 300)))
      (is (< (- (System/currentTimeMillis) t0) 2000))))
  (is (nil? (uio/run-bounded ["false"] 2000)))
  (is (nil? (uio/run-bounded ["no-such-binary-bb-mcp"] 2000))))
