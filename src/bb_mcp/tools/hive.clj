(ns bb-mcp.tools.hive
  "Hive-mcp tools loaded dynamically via nREPL.

   At startup, queries hive-mcp for all available tools and creates
   forwarding handlers. This eliminates manual tool maintenance -
   bb-mcp automatically has parity with hive-mcp."
  (:require [bb-mcp.tools.hive.dynamic :as dynamic]))

(defn init!
  "Initialize hive tools by loading them dynamically from hive-mcp.

   Options:
     :port       - nREPL port (default: 7910)
     :timeout-ms - Connection timeout (default: 10000)
     :force      - Force reload even if already loaded"
  [& {:keys [port timeout-ms force] :or {port 7910 timeout-ms 10000}}]
  (when (or force (not (dynamic/tools-loaded?)))
    (dynamic/load-dynamic-tools! :port port :timeout-ms timeout-ms)))

(defn get-tools
  "Get all hive-mcp tools. Returns empty vector if not loaded."
  []
  (or (dynamic/get-tools) []))
