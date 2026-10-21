(ns bb-mcp.hive-port-test
  "guard, piggyback and the sense receptor all talk to the hive-mcp host, so
   they dial `nrepl/hive-port` (BB_MCP_HIVE_NREPL_PORT, else 7910), never a
   project's .nrepl-port. Each is called with a recording eval-fn and no
   port-fn, and the port it dialled is compared with hive-port. Nothing is
   redefined."
  (:require [clojure.test :refer [deftest is]]
            [bb-mcp.guard :as guard]
            [bb-mcp.piggyback :as piggyback]
            [bb-mcp.sense.receptor :as receptor]
            [bb-mcp.tools.nrepl :as nrepl]))

(defn- recorder
  "An eval-fn that records the port it was asked to dial and answers nothing."
  [seen]
  (fn [{:keys [port]}]
    (swap! seen conj port)
    {:error? true :result "recorded"}))

(deftest guard-dials-the-hive-port
  (let [seen (atom [])]
    (guard/decide "bash" {:command "true"} {:eval-fn (recorder seen)})
    (is (= [(nrepl/hive-port)] @seen))))

(deftest piggyback-dials-the-hive-port
  (let [seen (atom [])]
    (piggyback/drain "bash" {} {:eval-fn (recorder seen)})
    (is (= [(nrepl/hive-port)] @seen))))

(deftest the-sense-receptor-dials-the-hive-port
  (let [seen (atom [])
        feed (receptor/nrepl-feed {:eval-fn (recorder seen)})]
    (receptor/drain! feed 0 nil)
    (is (= [(nrepl/hive-port)] @seen))))
