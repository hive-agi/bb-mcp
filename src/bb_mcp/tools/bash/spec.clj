(ns bb-mcp.tools.bash.spec
  "The bash tool's wire half: its MCP spec and its result formatting.

   Split out of `bb-mcp.tools.bash` because that namespace names
   babashka.process at load time, so it cannot be loaded at all on a runtime
   with no subprocess — while the SPEC must be identical on every runtime or
   the same tool would advertise two different schemas depending on which head
   answered. One definition, two executors: the local one here beside it, the
   remote one in `bb-mcp.tools.bash.remote`."
  (:require [clojure.string :as str]))

(def tool-spec
  {:name "bash"
   :description "Execute bash shell commands on the host system.

Examples:
1. List files: bash(command: \"ls -la\")
2. Find text: bash(command: \"grep -r 'pattern' /path\")
3. With timeout: bash(command: \"sleep 10\", timeout_ms: 5000)

Note: Non-zero exit codes are NOT treated as errors."
   :schema {:type "object"
            :properties {:command {:type "string"
                                   :description "The shell command to execute"}
                         :working_directory {:type "string"
                                             :description "Directory to run in (optional)"}
                         :timeout_ms {:type "integer"
                                      :description "Max execution time in ms (default: 180000)"}}
            :required ["command"]}})

(defn format-result
  "Format bash result for MCP response."
  [{:keys [exit-code stdout stderr timed-out error detached]}]
  (let [parts (cond-> []
                error (conj (str "Error: " error))
                :always (conj (str "Exit code: " exit-code
                                   (when timed-out " (timed out)")))
                detached (conj (str "Note: a background process still holds this "
                                    "command's output; anything it writes from here on "
                                    "is not captured."))
                (not (str/blank? stdout)) (conj (str "Standard output:\n" stdout))
                (not (str/blank? stderr)) (conj (str "Standard error:\n" stderr)))]
    {:result (str/join "\n" parts)
     :error? (boolean error)}))
