(ns bb-mcp.nrepl-spawn-test
  (:require [clojure.test :refer [deftest testing is]]
            [bb-mcp.nrepl-spawn :as spawn]))

(deftest classify-state-truth-table-test
  (testing "port availability wins over lock and process"
    (is (= :ready
           (spawn/classify-state {:available? true} {:starting? false} {:running? false}))))
  (testing "lock file marks a starting server when port not yet up"
    (is (= :starting
           (spawn/classify-state {:available? false} {:starting? true} {:running? false}))))
  (testing "a live process marks starting when port down and no lock"
    (is (= :starting
           (spawn/classify-state {:available? false} {:starting? false} {:running? true}))))
  (testing "all probes negative means not-running"
    (is (= :not-running
           (spawn/classify-state {:available? false} {:starting? false} {:running? false})))))

(deftest detect-state-wiring-test
  (testing "injected probes assemble into status + evidence in the right positions"
    (is (= {:status :ready
            :port 7910
            :evidence {:port {:available? true}
                       :lock {:starting? false}
                       :process {:running? false}}}
           (spawn/detect-state 7910
                               {:port-fn (constantly {:available? true})
                                :lock-fn (constantly {:starting? false})
                                :proc-fn (constantly {:running? false})})))))
