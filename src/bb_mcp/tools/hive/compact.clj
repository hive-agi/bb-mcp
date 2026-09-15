(ns bb-mcp.tools.hive.compact
  "Per-session compact tool schemas.

   A consolidated hive tool advertises the union of every subcommand's
   parameters (the code tool: 197 properties, 42 KB of JSON). A client that
   fetches tool schemas on demand pays that whole union the first time it
   touches the tool, and re-reads it on every later turn. Under
   BB_MCP_TOOL_SCHEMA=compact this head advertises only the tool's CORE
   parameters, the ones its own tool-def declares, keeps the schema open, and
   says in the description where the per-command ones are listed. Routing is
   untouched: every parameter is still forwarded and still accepted.")

(def modes
  "Recognised values of BB_MCP_TOOL_SCHEMA."
  #{"full" "compact"})

(defn mode
  "\"full\" or \"compact\" from an environment value; unset or unknown reads full."
  [env-value]
  (if (contains? modes env-value) env-value "full"))

(defn compact?
  "True when `env-value` selects the compact mode."
  [env-value]
  (= "compact" (mode env-value)))

(defn- hint [dropped]
  (str " Compact schema: only the shared parameters are listed here; each command"
       " also accepts its own (" dropped " more), named by command='help' and,"
       " for carto, by 'carto describe'."))

(defn compact-tool
  "TOOL with :schema reduced to CORE-SCHEMA's properties, open to further
   properties, and a description that says where the per-command ones live.
   TOOL unchanged when no core schema is known or nothing would be dropped."
  [{:keys [schema description] :as tool} core-schema]
  (let [full-props (or (:properties schema) {})
        core-keys  (set (keys (:properties core-schema)))
        dropped    (remove core-keys (keys full-props))]
    (if (or (nil? core-schema) (empty? dropped))
      tool
      (assoc tool
             :schema (assoc schema
                            :properties (select-keys full-props core-keys)
                            :additionalProperties true)
             :description (str description (hint (count dropped)))))))
