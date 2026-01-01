(ns bb-mcp.tools.file
  "File read/write tools for bb-mcp."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; Read file tool
(def read-file-spec
  {:name "read_file"
   :description "Read the contents of a file.

Parameters:
- path: Absolute path to the file
- offset: Line number to start from (optional, default: 0)
- limit: Max lines to read (optional, default: 2000)"
   :schema {:type "object"
            :properties {:path {:type "string"
                                :description "Absolute path to the file"}
                         :offset {:type "integer"
                                  :description "Line to start from (default: 0)"}
                         :limit {:type "integer"
                                 :description "Max lines to read (default: 2000)"}}
            :required ["path"]}})

(defn read-file
  "Read a file with optional offset and limit."
  [{:keys [path offset limit]}]
  (let [offset (or offset 0)
        limit (or limit 2000)]
    (if (fs/exists? path)
      (try
        (let [lines (str/split-lines (slurp path))
              selected (->> lines
                            (drop offset)
                            (take limit))
              numbered (map-indexed
                        (fn [i line]
                          (format "%6d→%s" (+ offset i 1) line))
                        selected)]
          {:result (str/join "\n" numbered)
           :error? false})
        (catch Exception e
          {:result (str "Error reading file: " (ex-message e))
           :error? true}))
      {:result (str "File not found: " path)
       :error? true})))

;; Write file tool
(def write-file-spec
  {:name "file_write"
   :description "Write content to a file. Creates parent directories if needed."
   :schema {:type "object"
            :properties {:file_path {:type "string"
                                     :description "Absolute path to write"}
                         :content {:type "string"
                                   :description "Content to write"}}
            :required ["file_path" "content"]}})

(defn write-file
  "Write content to a file."
  [{:keys [file_path content]}]
  (try
    (let [parent (fs/parent file_path)]
      (when (and parent (not (fs/exists? parent)))
        (fs/create-dirs parent))
      (spit file_path content)
      {:result (str "File written: " file_path)
       :error? false})
    (catch Exception e
      {:result (str "Error writing file: " (ex-message e))
       :error? true})))

;; Glob tool
(def glob-spec
  {:name "glob_files"
   :description "Find files matching a glob pattern.

Examples:
- glob_files(pattern: \"**/*.clj\")
- glob_files(pattern: \"src/**/*.cljs\", path: \"/project\")"
   :schema {:type "object"
            :properties {:pattern {:type "string"
                                   :description "Glob pattern (e.g. **/*.clj)"}
                         :path {:type "string"
                                :description "Root directory (default: cwd)"}}
            :required ["pattern"]}})

(defn glob-files
  "Find files matching a glob pattern."
  [{:keys [pattern path]}]
  (let [root (or path (System/getProperty "user.dir"))]
    (try
      (let [matches (->> (fs/glob root pattern)
                         (map str)
                         (take 1000)
                         (sort))]
        {:result (if (seq matches)
                   (str/join "\n" matches)
                   "No matches found")
         :error? false})
      (catch Exception e
        {:result (str "Error: " (ex-message e))
         :error? true}))))
