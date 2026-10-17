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

(def default-probe
  "Process-wide probe: the real systemd-run check, run once."
  (caching-probe systemd-probe))
