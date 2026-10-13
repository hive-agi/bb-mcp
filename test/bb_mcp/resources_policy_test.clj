(ns bb-mcp.resources-policy-test
  "The resource adapter's URI policy and its port source: only workflow://
   and verb://, no path traversal, a read answers exactly the URIs a list
   shows, and the nREPL port is the configured one."
  (:require [bb-mcp.core :as core]
            [bb-mcp.resources :as resources]
            [bb-mcp.tools.nrepl :as nrepl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [bb-mcp.tools.hive.dynamic :as dynamic]
            [bb-mcp.tools.hive :as hive]))

;; ── the policy, client side and host side ───────────────────────────────────

(def ^:private host-allowed?
  "The policy as the host runs it: the shipped form, evaluated."
  (eval resources/uri-policy-form))

(defn policy-verdict
  "Subject: what the client predicate and the host form each say of `uri`."
  [uri]
  {:uri uri
   :client (resources/allowed-uri? uri)
   :host (host-allowed? uri)})

(def ^:private policy-cases
  {:workflow "workflow://demo"
   :verb "verb://demo/scan"
   :verb-punctuation "verb://hive.core/ready?"
   :dots-in-a-name "workflow://v1..2"
   :dots-in-a-verb "verb://hive.core/a..b"
   :bare-workflow "workflow://"
   :bare-verb "verb://"
   :blank ""
   :file "file:///etc/passwd"
   :http "http://example.com/x"
   :unknown-scheme "unknown://demo"
   :one-slash "workflow:/demo"
   :upper-scheme "WORKFLOW://demo"
   :leading-space " workflow://demo"
   :prefixed "xworkflow://demo"
   :nested-scheme "file://workflow://demo"
   :parent-segment "workflow://a/../demo"
   :only-parent "workflow://.."
   :trailing-parent "workflow://a/.."
   :current-segment "workflow://a/./b"
   :dots-segment "workflow://a/.../b"
   :absolute-path "workflow:///etc/passwd"
   :empty-segment "workflow://a//b"
   :trailing-slash "workflow://a/"
   :encoded-dots "verb://a/%2e%2e/b"
   :encoded-dots-upper "verb://a/%2E%2E/b"
   :double-encoded "workflow://a%252e%252e%252fb"
   :encoded-slash "verb://a%2Fb"
   :encoded-tab "workflow://a%09b"
   :backslash "workflow://a\\..\\b"
   :encoded-backslash "workflow://a%5cb"
   :newline "workflow://a\nb"
   :trailing-newline "workflow://a\n"
   :space "workflow://a b"
   :delete "workflow://a\u007Fb"
   :no-break-space "workflow://a b"
   :line-separator "workflow://a b"
   :bidi-override "workflow://a‮b"
   :next-line "workflow://\u0085x"
   :non-ascii-letter "workflow://café"
   :encoded-nul "workflow://a%00"
   :not-a-string 42})

(defn- policy-oracle
  "The policy written out by hand, with no regex: a served scheme, then
   slash-separated segments of visible ASCII free of percent and backslash,
   none empty and none made of dots alone."
  [uri]
  (boolean
   (when (string? uri)
     (when-some [tail (some #(when (str/starts-with? uri %) (subs uri (count %)))
                            ["workflow://" "verb://"])]
       (and (every? #(and (< 32 (int %) 127) (not (#{\% \\} %))) tail)
            (every? (fn [seg] (and (seq seg) (not-every? #{\.} seg)))
                    (str/split tail #"/" -1)))))))

(def ^:private gen-uri
  (gen/one-of
   [(gen/elements (vec (vals policy-cases)))
    (gen/fmap (fn [[scheme tail]] (str scheme tail))
              (gen/tuple (gen/elements ["workflow://" "verb://" "file://" "WORKFLOW://" ""])
                         gen/string))
    (gen/elements [nil :workflow ["workflow://demo"]])]))

(deftrifecta uri-policy
  #'policy-verdict
  {:golden-path "test/golden/resource-uri-policy.edn"
   :cases policy-cases
   :gen gen-uri
   :pred (fn [{:keys [uri client host]}]
           (and (boolean? client)
                (= client host)
                (= client (policy-oracle uri))))
   :mutations [["allow-everything" (fn [uri] {:uri uri :client true :host true})]
               ["refuse-everything" (fn [uri] {:uri uri :client false :host false})]
               ["scheme-only" (fn [uri]
                                (let [ok (boolean (and (string? uri)
                                                       (or (str/starts-with? uri "workflow://")
                                                           (str/starts-with? uri "verb://"))))]
                                  {:uri uri :client ok :host ok}))]
               ["fragment-blacklist"
                (fn [uri]
                  (let [ok (boolean
                            (and (string? uri)
                                 (some #(and (str/starts-with? uri %) (< (count %) (count uri)))
                                       ["workflow://" "verb://"])
                                 (not-any? #(str/includes? (str/lower-case uri) %)
                                           [".." "\\" "%2e" "%2f" "%5c" "%00"])
                                 (not-any? #(or (<= (int %) 32) (= 127 (int %))) uri)))]
                    {:uri uri :client ok :host ok}))]]
   :assert (fn []
             (doseq [uri ["workflow://demo" "verb://demo/scan" "workflow://v1..2"
                          "verb://hive.core/a..b"]]
               (is (= {:uri uri :client true :host true} (policy-verdict uri)) uri))
             (doseq [uri ["file:///etc/passwd" "unknown://demo" "workflow://a/../demo"
                          "verb://a/%2e%2e/b" "workflow://a\\..\\b" "workflow://"
                          "workflow:///etc/passwd" "workflow://a%252e%252e%252fb"
                          "workflow://a%09b" "workflow://a b" "workflow://a b"
                          "workflow://a‮b" "workflow://\u0085x"]]
               (is (= {:uri uri :client false :host false} (policy-verdict uri)) uri)))})

;; ── a read answers exactly what a list shows ────────────────────────────────

(def ^:private host-ns 'hive-mcp.addons.core)
(def ^:private contract-ns 'hive-addon.resources)

(def ^:private stub-addons
  "A host registry whose addons declare more than the policy serves, behind
   a reader that would answer any URI at all."
  {"contributor" {:entry {:name "contributor" :state :active :capabilities #{:resources}}
                  :declared [{:uri "workflow://demo" :name "Demo" :mimeType "text/plain"}
                             {:uri "verb://demo/scan" :name "Scan"}
                             {:uri "file:///etc/passwd" :name "Passwd"}
                             {:uri "workflow://a/../demo" :name "Traversal"}
                             {:uri "workflow://nameless"}]}
   "dormant" {:entry {:name "dormant" :state :inactive :capabilities #{:resources}}
              :declared [{:uri "workflow://dormant" :name "Dormant"}]}
   "silent" {:entry {:name "silent" :state :active :capabilities #{:tools}}
             :declared [{:uri "workflow://silent" :name "Silent"}]}})

(def ^:private served ["workflow://demo" "verb://demo/scan"])

(defn- with-stub-host
  "Run `(f reads)` with the stub registry and contract interned; `reads`
   collects every URI the host reader is asked for."
  [f]
  (let [reads (atom [])]
    (try
      (create-ns host-ns)
      (intern host-ns 'list-addons (fn [] (mapv :entry (vals stub-addons))))
      (intern host-ns 'get-addon identity)
      (create-ns contract-ns)
      (intern contract-ns 'resources (fn [addon] (get-in stub-addons [addon :declared])))
      (intern contract-ns 'read-resource
              (fn [_ uri] (swap! reads conj uri) (str "body of " uri)))
      (f reads)
      (finally
        (remove-ns contract-ns)
        (remove-ns host-ns)))))

(defn read-through-host
  "Subject: resources/list then resources/read of `uri`, through the real
   adapter forms executed against the stub host."
  [uri]
  (with-stub-host
    (fn [reads]
      (binding [resources/*source* (resources/->HiveResourceSource eval)]
        (let [listing (core/handle-method {:method "resources/list" :id 1})
              response (core/handle-method {:method "resources/read" :id 2
                                            :params {:uri uri}})]
          {:uri uri
           :listed (mapv :uri (get-in listing [:result :resources]))
           :answer (or (get-in response [:result :contents 0 :text])
                       (get-in response [:error :code]))
           :content-uri (get-in response [:result :contents 0 :uri])
           :host-reads @reads})))))

(def ^:private gen-read-uri
  (gen/one-of
   [(gen/elements (into served (mapcat :declared) (vals stub-addons)))
    (gen/elements (filterv string? (vals policy-cases)))
    (gen/fmap (fn [[scheme tail]] (str scheme tail))
              (gen/tuple (gen/elements ["workflow://" "verb://" "file://"])
                         gen/string-alphanumeric))]))

(deftrifecta read-answers-only-listed
  #'read-through-host
  {:golden-path "test/golden/resource-listed-reads.edn"
   :cases {:listed-workflow "workflow://demo"
           :listed-verb "verb://demo/scan"
           :declared-file "file:///etc/passwd"
           :declared-traversal "workflow://a/../demo"
           :declared-nameless "workflow://nameless"
           :dormant-addon "workflow://dormant"
           :silent-addon "workflow://silent"
           :undeclared "workflow://missing"
           :blank ""}
   :gen (gen/fmap #(if (map? %) (:uri %) %) gen-read-uri)
   :pred (fn [{:keys [uri listed answer content-uri host-reads]}]
           (and (= served listed)
                (if (some #{uri} served)
                  (and (= (str "body of " uri) answer)
                       (= uri content-uri)
                       (= [uri] host-reads))
                  (and (contains? #{-32002 -32602} answer)
                       (= [] host-reads)))))
   :mutations [["answers-any-uri" (fn [uri] {:uri uri :listed served
                                             :answer (str "body of " uri)
                                             :content-uri uri :host-reads [uri]})]
               ["lists-all-declared" (fn [uri]
                                       {:uri uri
                                        :listed (into [] (comp (mapcat :declared) (map :uri))
                                                      (vals stub-addons))
                                        :answer -32002 :content-uri nil :host-reads []})]]
   :assert (fn []
             (is (= served (:listed (read-through-host "workflow://demo"))))
             (is (= "body of verb://demo/scan" (:answer (read-through-host "verb://demo/scan"))))
             (doseq [uri ["file:///etc/passwd" "workflow://a/../demo" "workflow://nameless"
                          "workflow://dormant" "workflow://silent" "workflow://missing"]]
               (let [{:keys [answer host-reads]} (read-through-host uri)]
                 (is (= -32002 answer) uri)
                 (is (= [] host-reads) uri))))})

(deftest refused-uris-never-reach-the-host
  (let [forms (atom [])
        source (resources/->HiveResourceSource
                (fn [form] (swap! forms conj form) {:uri "workflow://demo" :text "x"}))]
    (doseq [uri ["file:///etc/passwd" "unknown://demo" "workflow://a/../demo"
                 "verb://a/%2e%2e/b" "workflow://" nil]]
      (is (nil? (resources/read-resource source uri)) (pr-str uri)))
    (is (= [] @forms) "a refused URI is answered without one evaluation")
    (testing "content for another URI than the one asked is not an answer"
      (is (nil? (resources/read-resource source "workflow://other")))
      (is (= {:uri "workflow://demo" :text "x"}
             (resources/read-resource source "workflow://demo"))))))

(deftest a-host-listing-is-filtered-again-on-arrival
  (let [source (resources/->HiveResourceSource
                (fn [_] [{:uri "workflow://demo" :name "Demo"}
                         {:uri "file:///etc/passwd" :name "Passwd"}
                         {:uri "verb://a/../b" :name "Traversal"}
                         {:uri "verb://nameless"}
                         "workflow://not-a-map"]))]
    (is (= [{:uri "workflow://demo" :name "Demo"}] (resources/list-resources source)))))

;; ── the port ────────────────────────────────────────────────────────────────

(defn- in-process-host
  "An nREPL stand-in: evaluates the code it is sent and answers the printed
   value, recording the options of each call in `seen`."
  [seen]
  (fn [{:keys [code] :as opts}]
    (swap! seen conj (dissoc opts :code))
    {:result (pr-str (eval (read-string code))) :error? false}))

(deftest the-adapter-port-is-the-configured-one
  (let [seen (atom [])
        host (in-process-host seen)]
    (testing "an injected port source is the port dialled"
      (is (= [1 2] (#'resources/hive-eval '(vector 1 2)
                                          {:eval-fn host :port-fn (constantly 7999)})))
      (is (= 7999 (:port (last @seen)))))
    (testing "the default source is the hive host port"
      (with-redefs [nrepl/hive-port (constantly 4242)
                    nrepl/eval-code host]
        (is (= [] (resources/list-resources resources/*source*)))
        (is (nil? (resources/read-resource resources/*source* "workflow://demo"))))
      (is (= [4242 4242] (mapv :port (rest @seen)))))
    (testing "an unreachable host is an error, not an empty answer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Resource source unavailable"
                            (#'resources/hive-eval '(vector)
                                                   {:eval-fn (fn [_] {:result "refused" :error? true})
                                                    :port-fn (constantly 1)}))))))

(deftest the-hive-port-ignores-the-project-repl
  (testing "the override variable, else 7910"
    (is (= 7910 (nrepl/hive-port {})))
    (is (= 7931 (nrepl/hive-port {nrepl/hive-port-var " 7931 "})))
    (is (= 7910 (nrepl/hive-port {nrepl/hive-port-var " "})))
    (is (= 7910 (nrepl/hive-port {nrepl/hive-port-var "not-a-port"}))))
  (testing "neither BB_MCP_NREPL_PORT nor a project's .nrepl-port moves it"
    (is (= 7910 (nrepl/hive-port {"BB_MCP_NREPL_PORT" "45555"
                                  "BB_MCP_PROJECT_DIR" "/somewhere/with/a/repl"})))))

(deftest resources-and-forwarded-tools-dial-one-host
  (let [dialled (atom [])
        stub (fn [{:keys [port]}]
               (swap! dialled conj port)
               {:result (pr-str (pr-str [])) :error? false})]
    (try
      (with-redefs [nrepl/get-nrepl-port (constantly 45555)
                    nrepl/hive-port (constantly 7931)
                    nrepl/eval-code stub]
        (resources/list-resources resources/*source*)
        (resources/read-resource resources/*source* "workflow://demo")
        ((#'dynamic/make-forwarding-handler "some_tool" {}) {})
        (dynamic/load-dynamic-tools!)
        (hive/init! :force true))
      (finally (dynamic/clear-cache!)))
    (is (= [7931 7931 7931 7931 7931] @dialled)
        "a project REPL on 45555 is never sent a hive form, and a list, a read,
         a forwarded tool and both tool loads reach the same process")))
