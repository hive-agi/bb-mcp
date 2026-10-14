(ns bb-mcp.tools.hive.compact-trifecta-test
  "JVM trifecta contract for the compact code-tool projection. The babashka
   suite stays portable; this suite exercises hive-test on the JVM."
  (:require [hive-test.trifecta :refer [deftrifecta]]
            [clojure.test.check.generators :as gen]
            [bb-mcp.tools.hive.compact :as compact]))

(def ^:private core
  {:type "object"
   :properties {"command" {:type "string"} "file" {:type "string"}}
   :required ["command"]})

(def ^:private edit-tool
  {:name "code" :description "Code intelligence."
   :schema {:type "object" :required ["command"]
            :properties {"command" {:type "string"}
                         "scope" {:type "string"}
                         "qn" {:type "string"}
                         "new-body" {:type "string"}
                         "other-command-only" {:type "string"}}}})

(deftrifecta code-edit-schema
  compact/compact-tool
  {:apply? true
   :golden-path "test/golden/code-edit-schema.edn"
   :cases {:write-form [edit-tool core]}
   :gen (gen/return [edit-tool core])
   :num-tests 12
   :pred (fn [result]
           (let [properties (get-in result [:schema :properties])]
             (and (every? #(contains? properties %) ["command" "scope" "qn" "new-body"])
                  (not (contains? properties "other-command-only"))
                  (true? (get-in result [:schema :additionalProperties])))))
   :mutations [["drops-edit-params"
                (fn [tool core-schema]
                  (let [projected (compact/compact-tool (assoc tool :name "other") core-schema)]
                    (assoc projected :name "code")))]]})
