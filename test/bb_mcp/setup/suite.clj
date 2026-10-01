(ns bb-mcp.setup.suite
  "Runs the bb-mcp setup tests (babashka only: they use babashka.fs and
   babashka.process). The bb test task runs this after bb-mcp.test-runner."
  (:require [bb-mcp.setup.fs-test]
            [bb-mcp.setup.model-test]
            [bb-mcp.setup.toml-test]
            [clojure.test :as test]
            [bb-mcp.update-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'bb-mcp.setup.model-test
                                             'bb-mcp.setup.toml-test
                                             'bb-mcp.setup.fs-test
                                             'bb-mcp.update-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
