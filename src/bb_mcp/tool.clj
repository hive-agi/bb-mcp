(ns bb-mcp.tool
  "The Tool abstraction: one protocol, many record types.

   NativeTool runs in-process; ForwardingTool delegates to hive-mcp over
   nREPL. Callers depend on the Tool protocol, never on a tool's kind.")

(defprotocol Tool
  "A callable MCP tool."
  (tool-name  [t] "Unique tool name string.")
  (tool-spec  [t] "MCP tool spec map: {:name :description :schema}.")
  (deprecated? [t] "True when hidden from tools/list but still callable by name.")
  (invoke     [t args] "Handle a call; returns {:result str :error? bool}."))

(defrecord NativeTool [spec handler]
  Tool
  (tool-name  [_] (:name spec))
  (tool-spec  [_] spec)
  (deprecated? [_] false)
  (invoke     [_ args] (handler args)))

(defrecord ForwardingTool [spec handler deprecated]
  Tool
  (tool-name  [_] (:name spec))
  (tool-spec  [_] spec)
  (deprecated? [_] (boolean deprecated))
  (invoke     [_ args] (handler args)))

(defn native-tool
  "Build a NativeTool from a spec map and a 1-arg handler fn."
  [spec handler]
  (->NativeTool spec handler))

(defn forwarding-tool
  "Build a ForwardingTool from a spec map, a 1-arg handler fn, and a
   deprecated flag."
  [spec handler deprecated]
  (->ForwardingTool spec handler (boolean deprecated)))
