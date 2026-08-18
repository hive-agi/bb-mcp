(ns bb-mcp.test-runner
  "Test runner for bb-mcp."
  (:require [clojure.test :as test]
            [bb-mcp.tool-test]
            [bb-mcp.tools.dynamic-test]
            [bb-mcp.wire.bencode-test]
            [bb-mcp.host.conformance-test]
            [bb-mcp.core-test]
            [bb-mcp.wire.bencode-props-test]
            [bb-mcp.wire.bencode-mutation-test]
            [bb-mcp.host.json-props-test]))

(def ^:private portable-suites
  "Suites every runtime must run. Nothing here names a host primitive."
  ['bb-mcp.tool-test
   'bb-mcp.tools.dynamic-test
   'bb-mcp.wire.bencode-test
   'bb-mcp.wire.bencode-props-test
   'bb-mcp.wire.bencode-mutation-test
   'bb-mcp.host.conformance-test
   'bb-mcp.host.json-props-test
   'bb-mcp.core-test])

(def ^:private host-suites
  "Suites whose SUBJECT is a host primitive some runtime may not have. A
   runtime that cannot load one is told so by name — a suite that quietly
   vanishes is a falling assertion count, and that reads as green."
  ['bb-mcp.tools.bash-test])

(defn- loadable
  "`ns-sym` when it loads on this runtime, else nil."
  [ns-sym]
  (try (require ns-sym) ns-sym (catch Exception _ nil)))

(defn -main [& _args]
  (let [served (into [] (keep loadable) host-suites)
        skipped (remove (set served) host-suites)
        suites (into portable-suites served)]
    (doseq [s skipped]
      (println "SKIP" s "— this runtime cannot serve its host primitive"))
    (let [result (apply test/run-tests suites)]
      (System/exit (if (and (zero? (:fail result))
                            (zero? (:error result)))
                     0
                     1)))))
