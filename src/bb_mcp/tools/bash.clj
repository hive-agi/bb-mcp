(ns bb-mcp.tools.bash
  "Bash command execution tool for bb-mcp."
  (:require [babashka.process :as p]
            [bb-mcp.tools.bash.spec :as spec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bb-mcp.tools.bash.scope :as scope])
  (:import (java.util.concurrent TimeUnit)))

(def tool-spec
  "The one spec, shared with the remote executor."
  spec/tool-spec)

(defn format-result
  "Delegates to the shared formatter, so both executors render identically."
  [result]
  (spec/format-result result))

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

(defn- detached?
  "Returns true when at least one drain did not complete within the grace period."
  [out-done? err-done?]
  (not (and out-done? err-done?)))

(defn- run-argv
  "Run argv with babashka.process opts, collect its output within `timeout`
   ms, and return the execute result map. With `marker`, the first line
   holding it is removed from stderr before anything else touches stderr,
   and the result carries `::marker-seen?`."
  ([opts argv timeout] (run-argv opts argv timeout nil))
  ([opts argv timeout marker]
   (try
     (let [proc (apply p/process opts argv)
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
           [raw-err err-done?] (collected err flush-grace-ms)
           [stderr seen?] (if marker (scope/strip-marker marker raw-err) [raw-err false])
           cut-short? (detached? out-done? err-done?)]
       (cond-> {:exit-code (if timed-out? -1 exit)
                :stdout stdout
                :stderr (if timed-out?
                          (str stderr (when-not (str/blank? stderr) "\n")
                               "Command timed out")
                          stderr)
                :timed-out timed-out?}
         cut-short? (assoc :detached true)
         marker (assoc ::marker-seen? seen?)))
     (catch Exception e
       {:exit-code -1
        :stdout ""
        :stderr (ex-message e)
        :error (ex-message e)}))))

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
   3. Process cwd (bb-mcp/) — last-resort fallback

   OOM scope: the command runs in a sibling ManagedOOMPreference=none user
   scope when systemd-run supports it here (see `bb-mcp.tools.bash.scope`),
   so a runaway build is not shielded by the host server's avoid scope.
   `:scope-probe` (0-arg fn) overrides the cached default probe;
   `:scope-prefix` (argv vector) overrides the systemd-run prefix, a test seam.

   Scope recovery: inside the scope the first thing to run is a trampoline
   (`scope/exec-proof-argv`) that writes a per-call marker to stderr and then
   execs the command; the marker is stripped from the returned stderr. Only
   when the marker is ABSENT and the result carries systemd-run's own failure
   (`scope/scope-start-failure?`) does the probe record false (re-probed after
   its ttl) and the command run once more, plain. A run that showed the marker
   started the command and is never retried, whatever its exit. When the probe
   says no, the command runs plain with no trampoline."
  [{:keys [command working_directory timeout_ms _caller_cwd scope-probe scope-prefix]}]
  (let [timeout (or timeout_ms 180000)
        effective-dir (or working_directory _caller_cwd)
        opts (cond-> {:out :stream
                      :err :stream
                      :shutdown p/destroy-tree}
               effective-dir (assoc :dir effective-dir))
        probe (or scope-probe scope/default-probe)
        plain ["bash" "-c" command]
        marker (scope/new-marker)
        proof (scope/exec-proof-argv marker plain)
        argv (scope/plan-argv (try (true? (probe)) (catch Throwable _ false))
                              proof
                              {:path (System/getenv "PATH")
                               :cwd effective-dir
                               :prefix scope-prefix})]
    (if (= argv proof)
      (run-argv opts plain timeout)
      (let [r (run-argv opts argv timeout marker)
            result (dissoc r ::marker-seen?)]
        (if (scope/retry-plain? (::marker-seen? r) result)
          (do (scope/record-answer! probe false)
              (run-argv opts plain timeout))
          result)))))
