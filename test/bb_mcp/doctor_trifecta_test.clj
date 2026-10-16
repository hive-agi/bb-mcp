(ns bb-mcp.doctor-trifecta-test
  "JVM/hive-test trifecta contract for the pure bb doctor pipeline. Kept apart
   from the portable suite like the compact trifecta."
  (:require [hive-test.trifecta :refer [deftrifecta]]
            [clojure.test.check.generators :as gen]
            [bb-mcp.doctor :as d]))

(def ^:private healthy
  {:port 7910 :answers? true :procs [{:pid 1 :cmd "java -m hive-mcp.server.core"}]
   :config-path "/h/c.edn" :config? true})

(def ^:private down
  {:port 7910 :answers? false :procs [] :config-path "/h/c.edn" :config? false})

(deftrifecta doctor-report
  d/doctor
  {:apply? true
   :golden-path "test/golden/doctor-report.edn"
   :cases {:healthy [healthy] :down [down]}
   :gen (gen/elements [[healthy] [down]])
   :num-tests 20
   :pred (fn [{:keys [rows exit]}]
           (and (= 3 (count rows))
                (= exit (if (some #(= :fail (:status %)) rows) 1 0))))
   :mutations [["always-green"
                (fn [obs] (assoc (d/doctor obs) :exit 0))]]})
