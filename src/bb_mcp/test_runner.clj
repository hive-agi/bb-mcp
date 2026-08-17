(ns bb-mcp.test-runner
  "Test runner for bb-mcp."
  (:require [clojure.test :as test]
            [bb-mcp.tool-test]
            [bb-mcp.tools.dynamic-test]
            [bb-mcp.tools.bash-test]
            [bb-mcp.wire.bencode-test]
            [bb-mcp.host.conformance-test]
            [bb-mcp.core-test]))

(defn -main [& _args]
  (let [result (test/run-tests
                'bb-mcp.tool-test
                'bb-mcp.tools.dynamic-test
                'bb-mcp.tools.bash-test
                'bb-mcp.wire.bencode-test
                'bb-mcp.host.conformance-test
                'bb-mcp.core-test)]
    (System/exit (if (and (zero? (:fail result))
                          (zero? (:error result)))
                   0
                   1))))
