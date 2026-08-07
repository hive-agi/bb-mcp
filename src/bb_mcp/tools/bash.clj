(ns bb-mcp.tools.bash
  "Bash command execution tool for bb-mcp."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)))

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

(def ^:private flush-grace-ms
  "How long a drain is given to finish after the command itself has exited."
  1000)

(defn- drain
  "Copy `stream` into a StringBuilder on a daemon thread.

  Returns `[sb thread]`. The thread ends when the stream reaches EOF, which is
  when the LAST holder of the write end closes it — not when the command exits."
  [stream]
  (let [sb (StringBuilder.)
        t (doto (Thread.
                 (fn []
                   (try
                     (with-open [r (io/reader stream)]
                       (let [buf (char-array 8192)]
                         (loop []
                           (let [n (.read r buf)]
                             (when (pos? n)
                               (.append sb buf 0 n)
                               (recur))))))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))]
    [sb t]))

(defn- collected
  "What `sb` holds once `t` has had `grace-ms` to finish. `[text complete?]`."
  [[^StringBuilder sb ^Thread t] grace-ms]
  (.join t (long grace-ms))
  [(.toString sb) (not (.isAlive t))])

(defn execute
  "Execute a bash command and return the result.

   Returns `:exit-code`, `:stdout`, `:stderr`, `:timed-out`, and `:detached`
   when output was still open after the command exited. Never blocks longer
   than `timeout_ms` plus a one-second flush.

   Working directory resolution (CLARITY-I guarded input):
   1. Explicit :working_directory arg (caller override)
   2. :_caller_cwd (injected by inject-agent-context in core.clj — carries
      bb-mcp's per-session cwd so `pwd` matches user's actual session cwd,
      not the bb-mcp process's cwd which is always bb-mcp/ after
      start-bb-mcp.sh cd's into $SCRIPT_DIR)
   3. Process cwd (bb-mcp/) — last-resort fallback"
  [{:keys [command working_directory timeout_ms _caller_cwd]}]
  (let [timeout (or timeout_ms 180000)
        effective-dir (or working_directory _caller_cwd)
        opts (cond-> {:out :stream
                      :err :stream
                      :shutdown p/destroy-tree}
               effective-dir (assoc :dir effective-dir))]
    (try
      (let [proc (p/process opts "bash" "-c" command)
            ^Process handle (:proc proc)
            out (drain (:out proc))
            err (drain (:err proc))
            finished? (.waitFor handle (long timeout) TimeUnit/MILLISECONDS)
            timed-out? (not finished?)
            _ (when timed-out?
                (p/destroy-tree proc)
                (.waitFor handle (long flush-grace-ms) TimeUnit/MILLISECONDS))
            exit (if finished? (.exitValue handle) -1)
            [stdout out-done?] (collected out flush-grace-ms)
            [stderr err-done?] (collected err flush-grace-ms)
            detached? (not (and out-done? err-done?))]
        (cond-> {:exit-code (if timed-out? -1 exit)
                 :stdout stdout
                 :stderr (if timed-out?
                           (str stderr (when-not (str/blank? stderr) "\n")
                                "Command timed out")
                           stderr)
                 :timed-out timed-out?}
          detached? (assoc :detached true)))
      (catch Exception e
        {:exit-code -1
         :stdout ""
         :stderr (ex-message e)
         :error (ex-message e)}))))

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
