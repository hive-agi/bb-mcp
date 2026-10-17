(ns bb-mcp.tools.always-load
  "Which advertised tools a client should load up front instead of deferring.

   A client with tool search defers every MCP tool schema and makes the model
   call ToolSearch before its first use, which costs a whole turn. A tool
   marked here carries `_meta {\"anthropic/alwaysLoad\" true}` in tools/list,
   so the client sends its schema with the first request and no search turn
   is needed. Off unless BB_MCP_ALWAYS_LOAD names the tools."
  (:require [clojure.string :as str]))

(def env-var
  "Environment variable listing the tools to load up front, comma separated."
  "BB_MCP_ALWAYS_LOAD")

(defn names
  "Tool names from an environment value: \"code, memory\" -> #{\"code\" \"memory\"};
   nil or blank -> #{}. Pure."
  [env-value]
  (if (str/blank? env-value)
    #{}
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split env-value #","))))

(defn mark
  "SPECS with :always-load? true on every spec whose :name is in NAMES. Pure."
  [specs names]
  (if (empty? names)
    specs
    (map #(cond-> % (contains? names (:name %)) (assoc :always-load? true)) specs)))

(defn marked
  "SPECS marked for the tools the BB_MCP_ALWAYS_LOAD environment names."
  [specs]
  (mark specs (names (System/getenv env-var))))
