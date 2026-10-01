(ns bb-mcp.update.io
  "The boundary of the bb-mcp release notice: VERSION, the 24h cache, one
   bounded `git ls-remote`, and the stderr line. Every effect goes through
   the `sys` map (see `system`), so tests swap in stubs. bb-mcp.update
   decides.

   The startup path never throws and never touches stdout: stdout is the MCP
   stdio channel."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-mcp.update :as u]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Effects

(defn- read-text
  "Contents of the regular file at `path`, else nil."
  [path]
  (when (fs/regular-file? path)
    (slurp (str path))))

(defn- write-text!
  [path text]
  (some-> (fs/parent path) fs/create-dirs)
  (spit (str path) text))

(defn run-bounded
  "stdout of `argv` when it exits zero within `ms`, else nil. A process that
   outlives `ms` is destroyed with its children."
  [argv ms]
  (try
    (let [proc (apply p/process {:out :string :err :string :in ""
                                 :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                      argv)
          res (deref proc ms ::timeout)]
      (if (= ::timeout res)
        (do (p/destroy-tree proc) nil)
        (when (zero? (:exit res)) (:out res))))
    ;; No git, no network: "no answer" is the answer; the caller stays silent.
    (catch Exception _no-answer nil)))

(defn- ls-remote-tags
  [url ms]
  (run-bounded ["git" "ls-remote" "--tags" "--refs" url "v*"] ms))

(defn- say-err!
  [line]
  (binding [*out* *err*]
    (println line)
    (flush)))

(defn system
  "The real world."
  []
  {:getenv     #(System/getenv %)
   :home       (or (System/getenv "HOME") (System/getProperty "user.home"))
   :now-ms     #(System/currentTimeMillis)
   :read-file  read-text
   :write-file write-text!
   :ls-remote  ls-remote-tags
   :say        say-err!})

;; ---------------------------------------------------------------------------
;; Collect

(defn local-version
  "The VERSION of `checkout`, trimmed; nil when unreadable."
  [{:keys [read-file]} checkout]
  (some-> (read-file (str checkout "/VERSION")) str/trim not-empty))

(defn- cache-file [{:keys [getenv home]}]
  (u/cache-path {:xdg-cache (getenv "XDG_CACHE_HOME") :home home}))

(defn- read-cache [{:keys [read-file] :as sys}]
  (try (some-> (read-file (cache-file sys)) edn/read-string)
       ;; A corrupt cache is a stale cache: look the release up again.
       (catch Exception _corrupt-cache nil)))

(defn latest!
  "The newest release: from the cache when fresh, else one bounded lookup
   (cached on success). nil when offline; nothing is cached then, so the
   next start asks again."
  [{:keys [now-ms ls-remote write-file] :as sys}]
  (let [now (now-ms)
        cache (read-cache sys)]
    (if (u/cache-fresh? cache now u/ttl-ms)
      (:latest cache)
      (when-let [latest (u/newest-tag (ls-remote u/repo-url u/timeout-ms))]
        (try (write-file (cache-file sys) (pr-str (u/cache-entry latest now)))
             ;; An unwritable cache only costs a lookup on the next start.
             (catch Exception _cache-unwritable nil))
        latest))))

(defn status!
  "The decision for `checkout`. With the opt-out set nothing is looked up."
  [{:keys [getenv] :as sys} checkout]
  (if (u/disabled? (getenv u/opt-out-var))
    (u/decide {:disabled? true})
    (u/decide {:local (local-version sys checkout) :latest (latest! sys)})))

;; ---------------------------------------------------------------------------
;; Startup

(defn notify!
  "Print the one-line notice when `checkout` is behind; silent otherwise,
   and silent on any failure. Returns the decision (nil on failure)."
  [{:keys [say] :as sys} checkout]
  (try
    (let [decision (status! sys checkout)]
      (some-> (u/notice-line decision checkout) say)
      decision)
    ;; A release notice must never break or delay the MCP server.
    (catch Exception _never-break-startup nil)))

(defn start!
  "Run `notify!` on a daemon thread so MCP startup never waits for it and
   the process never stays up for it. Returns the thread."
  ([checkout] (start! (system) checkout))
  ([sys checkout]
   (doto (Thread. ^Runnable (fn [] (notify! sys checkout)))
     (.setDaemon true)
     (.start))))
