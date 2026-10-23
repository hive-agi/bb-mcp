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
  "\"full\" or \"compact\" from an environment value; unset or unknown reads
   compact. Compact is the default since the carto lab gate (2026-10-01, haiku,
   12 trials per arm): tokens -43%, wall time -49%, pass rate 9/12 against
   11/12, a gap inside noise. full stays one variable away for any client
   that needs the whole union up front."
  [env-value]
  (if (contains? modes env-value) env-value "compact"))

(defn compact?
  "True when `env-value` selects the compact mode."
  [env-value]
  (= "compact" (mode env-value)))

(def ^:private code-edit-properties
  "Parameters a strict MCP client must see to invoke carto editing through code.
   Other tools retain the small core-only projection. A key absent from the
   full upstream schema is never invented here."
  #{"scope" "directory" "file" "qn" "address" "content" "new-body"
    "ops" "after" "before" "position" "target-ns" "target-file"
    "source-ns" "source-qns" "moved-qns" "new-ns" "new-name"
    "new-as" "symbol" "ns" "as" "refer" "op" "class"
    "target-line" "target-col" "start-line" "end-line"
    "start-col" "end-col" "new-text" "line-range" "byte-range"
    "preview?" "dry-run?" "verify-load?" "rollback-on-load-fail?"
    "skip-verify?" "allow-ns-spec-loss?" "allowed-new-edges"
    "allowed-removed-edges" "allowed-missing-requires" "qns"
    "target-alias" "remove-source-if-unused?"})

(defn- hint
  "The description suffix for a compacted tool NAME that dropped DROPPED
   parameters. Only `code` carries carto commands, so only its hint names
   `carto describe`."
  [name dropped]
  (str " Compact schema: only the shared parameters are listed here; each command"
       " also accepts its own (" dropped " more). command='help' lists the commands"
       (if (= "code" name)
         "; for a carto command, command='carto describe' of='<command>' returns its :params."
         ".")))

(defn compact-tool
  "TOOL with :schema reduced to CORE-SCHEMA's properties, open to further
   properties, and a description that says where the per-command ones live.
   For code, keep carto edit properties visible to schema-bound MCP clients.
   TOOL unchanged when no core schema is known or nothing would be dropped."
  [{:keys [name schema description] :as tool} core-schema]
  (let [full-props (or (:properties schema) {})
        core-keys  (set (keys (:properties core-schema)))
        visible    (if (= "code" name)
                     (into core-keys code-edit-properties)
                     core-keys)
        dropped    (remove visible (keys full-props))]
    (if (or (nil? core-schema) (empty? dropped))
      tool
      (assoc tool
             :schema (assoc schema
                            :properties (select-keys full-props visible)
                            :additionalProperties true)
             :description (str description (hint name (count dropped)))))))
