(ns bb-mcp.resources-test
  (:require [bb-mcp.core :as core]
            [bb-mcp.resources :as resources]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(def ^:private descriptor
  {:uri "workflow://demo" :name "Demo" :mimeType "text/plain"})

(def ^:private stub-source
  (reify resources/ResourceSource
    (list-resources [_] [descriptor])
    (read-resource [_ uri]
      (when (= uri "workflow://demo")
        {:uri uri :mimeType "text/plain" :text "demo AST"}))))

(defn dispatch-resource
  "Subject: one request against an injected resource source, no live JVM."
  [request]
  (binding [resources/*source* stub-source]
    (core/handle-method request)))

(def ^:private list-request {:method "resources/list" :id 1})
(def ^:private read-request {:method "resources/read" :id 2
                             :params {:uri "workflow://demo"}})
(def ^:private missing-request {:method "resources/read" :id 3
                                :params {:uri "workflow://missing"}})
(def ^:private invalid-request {:method "resources/read" :id 4
                                :params {:uri ""}})

(deftrifecta resource-dispatch
  #'dispatch-resource
  {:golden-path "test/golden/resources.edn"
   :cases {:list list-request :read read-request
           :missing missing-request :invalid invalid-request}
   :gen (gen/elements [list-request read-request missing-request invalid-request])
   :pred (fn [response]
           (and (= "2.0" (:jsonrpc response))
                (or (contains? (:result response) :resources)
                    (contains? (:result response) :contents)
                    (integer? (get-in response [:error :code])))))
   :mutations [["empty-list" (fn [_] {:jsonrpc "2.0" :id 1 :result {:resources []}})]]
   :assert (fn []
             (is (= [descriptor]
                    (get-in (dispatch-resource list-request) [:result :resources])))
             (is (= "demo AST"
                    (get-in (dispatch-resource read-request) [:result :contents 0 :text]))))})

(deftest resource-lookup-errors
  (testing "unknown URI is distinct from invalid params and an unavailable source"
    (is (= -32002 (get-in (dispatch-resource missing-request) [:error :code])))
    (is (= -32602 (get-in (dispatch-resource invalid-request) [:error :code])))
    (binding [resources/*source* (reify resources/ResourceSource
                                  (list-resources [_] (throw (ex-info "offline" {})))
                                  (read-resource [_ _] (throw (ex-info "offline" {}))))]
      (is (= -32603 (get-in (core/handle-method list-request) [:error :code])))
      (is (= -32603 (get-in (core/handle-method read-request) [:error :code]))))))
