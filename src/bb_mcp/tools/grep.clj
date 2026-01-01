(ns bb-mcp.tools.grep
  "Grep/search tool for bb-mcp using ripgrep."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def tool-spec
  {:name "grep"
   :description "Search for patterns in files using ripgrep.

Examples:
- grep(pattern: \"defn.*foo\")
- grep(pattern: \"TODO\", path: \"src/\", include: \"*.clj\")"
   :schema {:type "object"
            :properties {:pattern {:type "string"
                                   :description "Regex pattern to search"}
                         :path {:type "string"
                                :description "Directory to search (default: cwd)"}
                         :include {:type "string"
                                   :description "File pattern to include (e.g. *.clj)"}
                         :max_results {:type "integer"
                                       :description "Max results (default: 100)"}}
            :required ["pattern"]}})

(defn execute
  "Search for pattern using ripgrep."
  [{:keys [pattern path include max_results]}]
  (let [root (or path ".")
        limit (or max_results 100)
        args (cond-> ["rg" "--line-number" "--no-heading"]
               include (concat ["--glob" include])
               :always (concat [pattern root]))]
    (try
      (let [result (p/shell {:out :string :err :string :continue true}
                            (str/join " " args))
            lines (when (:out result)
                    (->> (str/split-lines (:out result))
                         (take limit)))]
        (if (seq lines)
          {:result (str/join "\n" lines)
           :error? false}
          {:result "No matches found"
           :error? false}))
      (catch Exception e
        {:result (str "Error: " (ex-message e))
         :error? true}))))
