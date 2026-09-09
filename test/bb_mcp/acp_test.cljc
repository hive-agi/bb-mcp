(ns bb-mcp.acp-test
  #?(:cljs (:require [cljs.test :refer-macros [deftest is]])
     :clj (:require [clojure.test :refer [deftest is]]))
  (:require [bb-mcp.acp :as acp]))

(deftest constructors-follow-acp-json-rpc
  (is (= {:jsonrpc "2.0" :id "1" :method "session/prompt"
          :params {:sessionId "s" :prompt "hello"}}
         (acp/prompt "1" "s" "hello")))
  (is (= "session/load" (:method (acp/session-load "2" {:sessionId "s"}))))
  (is (= "session/cancel" (:method (acp/cancel "3" "s")))))

(deftest client-delegates-through-injected-exchange
  (let [seen (atom [])
        exchange! (fn [request]
                    (swap! seen conj request)
                    {:result {:sessionId "s"}})]
    (is (= {:sessionId "s"}
           (acp/prompt! exchange! "1" "s" "hello")))
    (is (= "session/prompt" (:method (first @seen))))))
