(ns bb-mcp.setup.toml
  "Line-preserving read and upsert of [mcp_servers.hive] in a Codex
   config.toml. Pure text -> text: every other table, key and comment comes
   back byte for byte, and inside the hive tables only the keys this tool
   owns (command, args, the env vars it manages) are touched.

   This is not a TOML parser. It understands what a Codex config needs:
   [table] headers, `key = value` lines, basic and literal strings, arrays
   that span lines, and one-line inline tables."
  (:require [clojure.string :as str]))

(def hive-table "mcp_servers.hive")
(def env-table "mcp_servers.hive.env")

;; ---------------------------------------------------------------------------
;; Lines

(defn header-name
  "The dotted table name a `[table]` header line declares, else nil."
  [line]
  (when-let [[_ n] (re-matches #"^\s*\[\[?\s*([^\[\]]+?)\s*\]\]?\s*(?:#.*)?$" line)]
    (-> n (str/replace #"\s*\.\s*" ".") (str/replace "\"" ""))))

(defn line-key
  "The bare key a `key = value` line assigns, else nil."
  [line]
  (some-> (re-matches #"^\s*(\"[^\"]*\"|'[^']*'|[A-Za-z0-9_-]+)\s*=.*" line)
          second
          (str/replace #"[\"']" "")))

(defn- value-part [line]
  (or (second (str/split line #"=" 2)) ""))

(defn- depth
  "Open-bracket balance of `s`, ignoring strings and comments."
  [s]
  (let [bare (-> s
                 (str/replace #"\"(?:[^\"\\]|\\.)*\"" "")
                 (str/replace #"'[^']*'" "")
                 (str/replace #"#.*" ""))]
    (- (count (re-seq #"\[" bare)) (count (re-seq #"\]" bare)))))

(defn- span
  "How many lines the key at index `i` of `lines` occupies."
  [lines i]
  (loop [d (depth (value-part (lines i))) n 1]
    (if (or (<= d 0) (>= (+ i n) (count lines)))
      n
      (recur (+ d (depth (lines (+ i n)))) (inc n)))))

(defn- blocks
  "`lines` as [key-or-nil [line ...]] blocks, a key with its continuation."
  [lines]
  (let [lines (vec lines)]
    (loop [i 0 out []]
      (cond
        (>= i (count lines)) out
        (line-key (lines i)) (let [n (span lines i)]
                               (recur (+ i n) (conj out [(line-key (lines i)) (subvec lines i (+ i n))])))
        :else (recur (inc i) (conj out [nil [(lines i)]]))))))

(defn- unblocks [bs]
  (into [] (mapcat second) bs))

(defn- key-text
  "The whole text of key `k` in `lines`, continuation included, else nil."
  [lines k]
  (some (fn [[bk ls]] (when (= k bk) (str/join "\n" ls))) (blocks lines)))

(defn- drop-keys [lines ks]
  (unblocks (remove (comp ks first) (blocks lines))))

;; ---------------------------------------------------------------------------
;; Values

(def ^:private basic-string #"\"((?:[^\"\\]|\\.)*)\"")

(defn- unescape [s]
  (str/replace s #"\\(.)" "$1"))

(defn quote-str
  "`s` as a TOML basic string."
  [s]
  (str "\"" (str/replace s #"[\\\"]" #(str "\\" %)) "\""))

(defn- string-value
  "The first string in `text`, unescaped, else nil."
  [text]
  (or (some-> (re-find basic-string text) second unescape)
      (second (re-find #"'([^']*)'" text))))

(defn- strings-in [text]
  (mapv (comp unescape second) (re-seq basic-string text)))

(defn- inline-pairs
  "{k v} of a one-line inline table such as `env = { A = \"1\" }`."
  [text]
  (let [body (second (re-find #"\{(.*)\}" text))]
    (into {}
          (map (fn [[_ k v]] [(str/replace k "\"" "") (unescape v)]))
          (re-seq #"(\"[^\"]*\"|[A-Za-z0-9_-]+)\s*=\s*\"((?:[^\"\\]|\\.)*)\"" (or body "")))))

(defn- table-pairs
  "{k v} of the keys in a table's body lines."
  [lines]
  (into {}
        (keep (fn [[k ls]]
                (when k
                  (let [v (value-part (str/join "\n" ls))]
                    [k (or (string-value v) (str/trim v))]))))
        (blocks lines)))

(defn- inline-table [env]
  (str "{ " (str/join ", " (map (fn [[k v]] (str k " = " (quote-str v))) (sort env))) " }"))

;; ---------------------------------------------------------------------------
;; Sections

(defn sections
  "`text` split into [{:name table-or-nil :lines [...]}], header first."
  [text]
  (reduce (fn [acc line]
            (if-let [n (header-name line)]
              (conj acc {:name n :lines [line]})
              (update-in acc [(dec (count acc)) :lines] conj line)))
          [{:name nil :lines []}]
          (str/split (or text "") #"\n" -1)))

(defn- render [secs]
  (str/join "\n" (mapcat :lines secs)))

(defn- find-section [secs n]
  (some #(when (= n (:name %)) %) secs))

(defn- section-index [secs n]
  (first (keep-indexed (fn [i s] (when (= n (:name s)) i)) secs)))

(defn hive-entry
  "The hive server as a client entry {\"command\" \"args\" \"env\"}, nil
   when the config has no hive table."
  [text]
  (let [secs (sections text)
        main (some-> (find-section secs hive-table) :lines rest vec)
        envt (some-> (find-section secs env-table) :lines rest vec)]
    (when (or main envt)
      (let [cmd (some-> (key-text main "command") value-part string-value)
            args (some-> (key-text main "args") value-part strings-in)
            env (merge (some-> (key-text main "env") inline-pairs)
                       (some-> envt table-pairs))]
        (cond-> {"env" (or env {})}
          cmd (assoc "command" cmd)
          args (assoc "args" args))))))

;; ---------------------------------------------------------------------------
;; Upsert

(defn- content-end
  "Index just past the last non-blank line (the header counts)."
  [lines]
  (inc (or (last (keep-indexed (fn [i l] (when-not (str/blank? l) i)) lines)) 0)))

(defn- insert-at-end [lines new-lines]
  (let [lines (vec lines) i (content-end lines)]
    (into (into (subvec lines 0 i) new-lines) (subvec lines i))))

(defn- set-line
  "Replace key `k`'s block with `rendered`, or add it after the header."
  [lines k rendered]
  (if (key-text (vec (rest lines)) k)
    (unblocks (map (fn [[bk ls :as b]] (if (= k bk) [bk [rendered]] b)) (blocks lines)))
    (into [(first lines) rendered] (rest lines))))

(defn- set-inline-env
  "The hive table's inline env set to `env` (dropped when empty)."
  [lines env]
  (let [current (some-> (key-text (vec (rest lines)) "env") inline-pairs)]
    (cond
      (= current env) lines
      (empty? env) (drop-keys lines #{"env"})
      current (set-line lines "env" (str "env = " (inline-table env)))
      :else (insert-at-end lines [(str "env = " (inline-table env))]))))

(defn- reconcile-table
  "An env table's lines with changed or removed keys dropped and new or
   changed keys appended; untouched keys keep their lines."
  [lines existing env]
  (let [stale (set (filter #(not= (get env %) (get existing %)) (keys existing)))
        fresh (sort (filter (fn [[k v]] (not= v (get existing k))) env))]
    (insert-at-end (drop-keys (vec lines) stale)
                   (map (fn [[k v]] (str k " = " (quote-str v))) fresh))))

(defn- append-table
  "`text` with `lines` appended as a new table, separated by a blank line."
  [text lines]
  (let [sep (cond (str/blank? text) ""
                  (str/ends-with? text "\n\n") ""
                  (str/ends-with? text "\n") "\n"
                  :else "\n\n")]
    (str (if (str/blank? text) "" text) sep (str/join "\n" lines) "\n")))

(defn upsert-hive
  "`text` with [mcp_servers.hive] launching `command` with no args and the env
   (env-fn current-env). Everything else is preserved."
  [text command env-fn]
  (let [text (or text "")
        secs (sections text)
        mi (section-index secs hive-table)
        ei (section-index secs env-table)
        current (get (hive-entry text) "env" {})
        env (env-fn current)
        main (cond-> (-> (if mi (:lines (secs mi)) [(str "[" hive-table "]")])
                         (drop-keys #{"args"})
                         (set-line "command" (str "command = " (quote-str command))))
               (not ei) (set-inline-env env))
        secs (cond-> secs
               ei (update-in [ei :lines] reconcile-table current env)
               mi (assoc-in [mi :lines] main))]
    (if mi
      (render secs)
      (append-table (render secs) main))))
