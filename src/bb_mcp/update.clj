(ns bb-mcp.update
  "Pure decisions for the bb-mcp release notice.

   Clients launch bb-mcp through the ANCHOR, so an update is a fast-forward
   of the checkout it points at (`hive update`). This namespace only decides:
   which release is newest, whether this checkout is behind it, whether a
   cached answer is still good, and what to say. The boundary
   (bb-mcp.update.io) reads VERSION, the cache and the remote tags, and
   prints.

   A decision is {:status s :local v :latest v} with s one of
     :disabled  BB_MCP_NO_UPDATE_CHECK is set
     :unknown   the local or the latest version could not be read
     :behind    a newer release exists
     :current   this checkout is the latest release
     :ahead     this checkout is newer than any release (a dev checkout)"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Fixed names

(def repo-url
  "Where releases are tagged v<major>.<minor>.<patch>."
  "https://github.com/hive-agi/bb-mcp")

(def opt-out-var "BB_MCP_NO_UPDATE_CHECK")

(def ttl-ms
  "How long a fetched answer is trusted: 24h."
  (* 24 60 60 1000))

(def timeout-ms
  "Bound on one remote lookup."
  2000)

;; ---------------------------------------------------------------------------
;; Versions

(defn parse-version
  "\"1.2.29\" or \"v1.2.29\" -> [1 2 29]; nil for anything else, including
   pre-release or fork suffixes such as 1.2.29-hive.1."
  [s]
  (when (string? s)
    (when-let [[_ a b c] (re-matches #"v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})" (str/trim s))]
      (mapv parse-long [a b c]))))

(defn render-version
  "[1 2 29] -> \"1.2.29\"."
  [v]
  (str/join "." v))

(defn compare-versions
  "-1, 0 or 1 as version string `a` is older than, equal to or newer than
   `b`. Both must parse."
  [a b]
  (compare (parse-version a) (parse-version b)))

(defn newest-tag
  "The newest release named in `git ls-remote --tags` output, as \"x.y.z\";
   nil when no line names a release tag."
  [ls-remote-out]
  (some->> (str/split-lines (str ls-remote-out))
           (keep #(second (re-find #"refs/tags/(\S+)$" %)))
           (keep parse-version)
           seq
           (reduce #(if (pos? (compare %2 %1)) %2 %1))
           render-version))

;; ---------------------------------------------------------------------------
;; Cache

(defn cache-path
  "The cache file: $XDG_CACHE_HOME/hive-mcp/bb-mcp-latest.edn, else
   ~/.cache/hive-mcp/bb-mcp-latest.edn."
  [{:keys [xdg-cache home]}]
  (str (if (str/blank? xdg-cache) (str home "/.cache") xdg-cache)
       "/hive-mcp/bb-mcp-latest.edn"))

(defn cache-entry
  "What is written to the cache after a successful lookup."
  [latest now-ms]
  {:latest latest :checked-at now-ms})

(defn cache-fresh?
  "True when `cache` holds a release checked less than `ttl` ago. A clock
   that went backwards makes it stale, never fresh forever."
  [cache now-ms ttl]
  (let [{:keys [latest checked-at]} (when (map? cache) cache)]
    (boolean (and (parse-version latest)
                  (int? checked-at)
                  (<= 0 (- now-ms checked-at) ttl)))))

;; ---------------------------------------------------------------------------
;; Decision

(defn disabled?
  "True when the opt-out env var is set to anything but empty, 0 or false."
  [value]
  (not (contains? #{nil "" "0" "false"} (some-> value str/trim str/lower-case))))

(defn decide
  "The decision for a checkout at `local` given the newest release `latest`."
  [{:keys [local latest disabled?]}]
  (let [base {:local (some-> local str/trim) :latest latest}]
    (assoc base :status
           (cond
             disabled? :disabled
             (not (and (parse-version local) (parse-version latest))) :unknown
             :else (case (compare-versions local latest)
                     -1 :behind
                     0 :current
                     1 :ahead)))))

(defn remedy
  "How to get the new release."
  [checkout]
  (str "run hive update (or git pull in " checkout ")"))

(defn notice-line
  "The ONE stderr line printed at startup when behind; nil otherwise."
  [{:keys [status local latest]} checkout]
  (when (= :behind status)
    (str "bb-mcp " local " -> " latest " available: " (remedy checkout))))

(defn check-row
  "The `bb-mcp setup --check` row for a decision: :warn when behind, :ok
   otherwise (being offline is not a problem with the setup)."
  [{:keys [status local latest] :as decision} checkout]
  (let [row (fn [s detail] {:step "update" :status s :detail detail})]
    (case status
      :behind   (row :warn (notice-line decision checkout))
      :current  (row :ok (str local " is the latest release"))
      :ahead    (row :ok (str local " is newer than the latest release " latest))
      :disabled (row :ok (str "release check disabled (" opt-out-var ")"))
      (row :ok (str (or local "unknown version") "; latest release unknown (offline?)")))))
