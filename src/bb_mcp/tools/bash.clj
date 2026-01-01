(ns bb-mcp.tools.bash
  "Bash command execution tool for bb-mcp."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

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

(defn execute
  "Execute a bash command and return the result."
  [{:keys [command working_directory timeout_ms]}]
  (let [timeout (or timeout_ms 180000)
        opts (cond-> {:out :string
                      :err :string
                      :timeout timeout}
               working_directory (assoc :dir working_directory))]
    (try
      (let [result (p/shell opts "bash" "-c" command)]
        {:exit-code (:exit result)
         :stdout (or (:out result) "")
         :stderr (or (:err result) "")
         :timed-out false})
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (:timeout data)
            {:exit-code -1
             :stdout ""
             :stderr "Command timed out"
             :timed-out true}
            {:exit-code (or (:exit data) -1)
             :stdout (or (:out data) "")
             :stderr (or (:err data) (ex-message e))
             :timed-out false})))
      (catch Exception e
        {:exit-code -1
         :stdout ""
         :stderr (ex-message e)
         :error (ex-message e)}))))

(defn format-result
  "Format bash result for MCP response."
  [{:keys [exit-code stdout stderr timed-out error]}]
  (let [parts (cond-> []
                error (conj (str "Error: " error))
                :always (conj (str "Exit code: " exit-code
                                   (when timed-out " (timed out)")))
                (not (str/blank? stdout)) (conj (str "Standard output:\n" stdout))
                (not (str/blank? stderr)) (conj (str "Standard error:\n" stderr)))]
    {:result (str/join "\n" parts)
     :error? (boolean error)}))
