(ns bb-mcp.tools.bash.scope
  "Where a bash tool command lands in the cgroup tree.

  hive-mcp's bin/hive-mcp runs the server in a systemd user scope with
  ManagedOOMPreference=avoid, and every process it forks inherits that
  cgroup, the bash tool's commands included. A runaway build started through
  the bash tool is then shielded from systemd-oomd, and when oomd finally acts
  it kills the whole scope, server included.

  So the command's argv is prefixed with
  `systemd-run --user --scope --collect --quiet -p ManagedOOMPreference=none`
  when that works here: the command gets a sibling scope oomd may judge on
  its own. systemd-run --scope execs in place, so the pid the tool holds is
  the command itself (destroy-tree still reaches it and its children), and
  stdin, stdout, stderr, cwd, environment and exit code pass through.

  Same probe semantics as hive-mcp's bin/oom-policy.sh and
  hive-mcp.system.child-scope: one real
  `systemd-run --user --scope --collect --quiet -p ManagedOOMPreference=none true`
  per process, cached; any failure means a plain spawn. Never fails a command.

  `scoped-argv` is pure; `argv-for` applies an injected probe (0-arg fn);
  `systemd-probe` is the real adapter, `default-probe` the cached instance.
  Opt-out: HIVE_MCP_CHILD_SCOPE=off."
  (:require [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)))

(def scope-prefix
  "argv prefix that runs a command in its own transient user scope."
  ["systemd-run" "--user" "--scope" "--collect" "--quiet"
   "-p" "ManagedOOMPreference=none"])

(def probe-argv
  "The real command the probe runs; exit 0 means scoped spawns work here."
  (conj scope-prefix "true"))

(defn scoped-argv
  "argv to spawn given the probe's answer: `scope-prefix` + argv when
  available? is exactly true, argv unchanged (as a vector) otherwise."
  [available? argv]
  (let [argv (vec argv)]
    (if (and (true? available?) (seq argv))
      (into scope-prefix argv)
      argv)))

(defn resolve-executable
  "The executable File `argv0` names for a child whose PATH is `path` and whose
  working directory is `cwd` (nil: this process's), or nil when it does not
  resolve. A name with a slash is a path (relative to cwd); a bare name is
  looked up in the CHILD's PATH, which is what systemd-run searches. Mirrors
  hive-mcp.system.child-scope/resolve-executable."
  [argv0 path cwd]
  (when-not (str/blank? argv0)
    (let [cwd    (or cwd (System/getProperty "user.dir"))
          exe?   (fn [^java.io.File f] (and (.isFile f) (.canExecute f)))
          dir-of (fn [^String d]
                   (let [f (java.io.File. (if (str/blank? d) "." d))]
                     (if (.isAbsolute f) f (java.io.File. ^String cwd (.getPath f)))))]
      (if (str/includes? argv0 "/")
        (let [f (java.io.File. ^String argv0)
              f (if (.isAbsolute f) f (java.io.File. ^String cwd ^String argv0))]
          (when (exe? f) f))
        (some (fn [d]
                (let [f (java.io.File. ^java.io.File (dir-of d) ^String argv0)]
                  (when (exe? f) f)))
              (str/split (or path "") #":" -1))))))

(defn plan-argv
  "`prefix` (default `scope-prefix`) + argv when available? is exactly true and
  argv's first element resolves for the child's PATH and cwd; argv unchanged
  otherwise, so a missing command fails plain, as it always did. `bash`
  always resolves; the check keeps this helper the same as hive-mcp's."
  [available? argv {:keys [path cwd prefix]}]
  (let [argv (vec argv)]
    (if (and (true? available?) (seq argv) (resolve-executable (first argv) path cwd))
      (into (vec (or prefix scope-prefix)) argv)
      argv)))

(def scope-failure-signatures
  "How systemd-run's own failures begin on stderr, the ones where it exits 1
  without running the command."
  ["Failed to start transient scope unit"
   "Failed to connect to bus"
   "Failed to connect to user scope bus"
   "Failed to create bus connection"])

(defn scope-start-failure?
  "True when a scoped run's result looks like systemd-run failing to set up the
  scope rather than the command failing: exit 1, empty stdout, and stderr of at
  most two lines starting with one of `scope-failure-signatures`. A run that
  wrote anything to stdout ran the command and is never treated as one."
  [{:keys [exit-code stdout stderr timed-out]}]
  (let [text (str/trim (str stderr))]
    (boolean
     (and (not timed-out)
          (= 1 exit-code)
          (= "" (str stdout))
          (<= 1 (count (str/split-lines text)) 2)
          (some #(str/starts-with? text %) scope-failure-signatures)))))

(def exec-trampoline
  "bash script that runs first INSIDE the scope: it writes its first argument
  (the per-call marker) and a newline to stderr, then execs the rest of its
  argv. Seeing the marker proves systemd-run got as far as exec'ing the
  command; its absence means the user command never started."
  "printf '%s\\n' \"$1\" >&2; shift; exec \"$@\"")

(defn new-marker
  "A marker unique to one call."
  []
  (str "bb-mcp-scope-exec:" (java.util.UUID/randomUUID)))

(defn exec-proof-argv
  "argv that writes `marker` to stderr, then execs argv unchanged (each
  element stays its own argument, so quoting, environment and exit code are
  the command's own)."
  [marker argv]
  (into ["bash" "-c" exec-trampoline "bb-mcp-scope" marker] argv))

(defn strip-marker
  "[text marker-seen?]: stderr text with the first `marker` line removed, and
  whether it was there."
  [marker stderr]
  (let [s (str stderr)]
    (if-let [i (str/index-of s marker)]
      (let [end (+ i (count marker))
            end (if (and (< end (count s)) (= \newline (.charAt s end))) (inc end) end)]
        [(str (subs s 0 i) (subs s end)) true])
      [s false])))

(defn retry-plain?
  "True only when the marker never showed (the command did not start) AND the
  result carries systemd-run's own failure signature. A run that showed the
  marker is never retried, whatever its exit."
  [marker-seen? result]
  (and (not marker-seen?) (scope-start-failure? result)))

(defn argv-for
  "Apply `probe` to argv. A probe that throws or answers anything but true
  yields the plain argv."
  [probe argv]
  (scoped-argv (try (probe) (catch Throwable _ false)) argv))

(defn systemd-probe
  "Run `probe-argv` once; true when it exits 0 within 10 s. Any exception,
  timeout or non-zero exit answers false. Honours HIVE_MCP_CHILD_SCOPE=off."
  []
  (if (= "off" (some-> (System/getenv "HIVE_MCP_CHILD_SCOPE") str/trim str/lower-case))
    false
    (try
      (let [p (-> (ProcessBuilder. ^java.util.List probe-argv)
                  (.redirectErrorStream true)
                  (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                  (.start))]
        (.close (.getOutputStream p))
        (if (.waitFor p 10000 TimeUnit/MILLISECONDS)
          (zero? (.exitValue p))
          (do (.destroyForcibly p) false)))
      (catch Throwable _ false))))

(defn caching-probe
  "Wrap a probe so it runs at most once; a probe that throws caches false."
  [probe]
  (let [answer (delay (try (true? (probe)) (catch Throwable _ false)))]
    (fn [] @answer)))

(def default-ttl-ms
  "How long `default-probe`'s answer stands before the next command re-probes."
  (* 5 60 1000))

(defn expiring-probe
  "Wrap a probe so its answer is reused for `ttl-ms`, then probed again. With
  one argument it RECORDS that answer as of now (a command that saw the scope
  fail to start records false). A probe that throws answers false. opts:
  :ttl-ms, :clock (0-arg fn, epoch ms)."
  ([probe] (expiring-probe probe {}))
  ([probe {:keys [ttl-ms clock] :or {ttl-ms default-ttl-ms}}]
   (let [clock (or clock #(System/currentTimeMillis))
         state (atom nil)]
     (fn
       ([]
        (let [{:keys [answer at]} @state]
          (if (and (some? at) (< (- (long (clock)) (long at)) (long ttl-ms)))
            answer
            (let [a (try (true? (probe)) (catch Throwable _ false))]
              (reset! state {:answer a :at (clock)})
              a))))
       ([answer]
        (reset! state {:answer (true? answer) :at (clock)})
        (true? answer))))))

(defn record-answer!
  "Tell `probe` scoped spawns do or do not work as of now; a plain 0-arg probe
  cannot record and is left alone."
  [probe answer]
  (try (probe answer) (catch Throwable _ nil))
  nil)

(def default-probe
  "Process-wide probe: the real systemd-run check. Its answer stands for
  `default-ttl-ms`; a command that sees the scope fail to start records false
  on it. Not cached for the process's life: the user manager can go away
  (daemon-reexec, user@ restart, a D-Bus timeout under memory pressure)."
  (expiring-probe systemd-probe))
