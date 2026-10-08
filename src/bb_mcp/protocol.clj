(ns bb-mcp.protocol
  "MCP JSON-RPC protocol implementation for babashka.
   Handles stdio communication with Claude."
  (:require [bb-mcp.host.port :as hp]
            [clojure.string :as str]))

;; MCP Protocol constants
;; 2024-11-05 is MCP 1.0 release date - the standard protocol version.
;; Claude Code client sends 2025-06-18 but should handle backwards compat.
(def protocol-version "2024-11-05")
(def server-name "bb-mcp")
(def server-version "0.1.0")

;; JSON-RPC helpers
(defn json-rpc-response [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn json-rpc-error [id code message & [data]]
  {:jsonrpc "2.0"
   :id id
   :error (cond-> {:code code :message message}
            data (assoc :data data))})

;; MCP message types
(def base-capabilities
  "What every bb-mcp session advertises."
  {:tools {:listChanged false}
   :resources {:listChanged false}})

(defn initialize-response
  "The initialize result. `extra-capabilities` (e.g. a receptor's
   {:experimental {\"claude/channel\" {}}}) is merged over the base set."
  ([id] (initialize-response id {}))
  ([id extra-capabilities]
   (json-rpc-response id
                      {:protocolVersion protocol-version
                       :capabilities (merge-with merge base-capabilities extra-capabilities)
                       :serverInfo {:name server-name
                                    :version server-version}})))

(defn tools-list-response [id tools]
  (json-rpc-response id
                     {:tools (mapv (fn [{:keys [name description schema]}]
                                     {:name name
                                      :description description
                                      :inputSchema schema})
                                   tools)}))

(defn tool-call-response [id result is-error?]
  (json-rpc-response id
                     {:content [{:type "text" :text (str result)}]
                      :isError is-error?}))

(defn resources-list-response [id resources]
  (json-rpc-response id {:resources resources}))

(defn resources-read-response
  "MCP resources/read result. A resource read returns a vector of contents."
  [id content]
  (json-rpc-response id {:contents [content]}))

;; Stdio communication
(defn read-message
  "Read a JSON-RPC message from stdin.
   Supports both newline-delimited JSON (Claude Code) and Content-Length format."
  []
  (try
    (loop []
      (when-let [line (read-line)]
        (cond
          ;; Skip empty lines (between messages)
          (str/blank? line)
          (recur)

          ;; Found Content-Length header (HTTP-style format)
          (str/starts-with? line "Content-Length:")
          (let [content-length (parse-long (str/trim (subs line 16)))]
            ;; Read empty line after header
            (read-line)
            ;; Read JSON body character by character
            (let [sb (StringBuilder.)]
              (dotimes [_ content-length]
                (.append sb (char (.read *in*))))
              (hp/json-decode (str sb))))

          ;; Try to parse as JSON directly (newline-delimited format)
          (str/starts-with? line "{")
          (hp/json-decode line)

          ;; Unexpected line - skip it
          :else (recur))))
    (catch Exception _
      nil)))

(defn write-message
  "Write a JSON-RPC message to stdout as newline-delimited JSON.
   The line is encoded first and written in ONE print, so a message is never
   half-written when a writer is interrupted between encode and print."
  [msg]
  (let [line (str (hp/json-encode msg) "\n")]
    (print line)
    (flush)))

;; Transport — the effectful message boundary

(defprotocol Transport
  "Reads and writes JSON-RPC messages over a byte boundary."
  (read-msg  [t]     "Read the next message map, or nil at end of input.")
  (write-msg [t msg] "Write a message map."))

(defrecord StdioTransport [out]
  Transport
  (read-msg  [_]     (read-message))
  (write-msg [_ msg] (binding [*out* out] (write-message msg))))

(defn stdio-transport
  "Build a StdioTransport over *in* and the CURRENT *out*. The writer is
   captured here so a write from another thread (the sense receptor) lands on
   the same stream as the server's responses."
  []
  (->StdioTransport *out*))

(defn serialized
  "`transport` with every write under one lock, so concurrent writers (the
   server loop answering requests, the receptor sending notifications) never
   interleave bytes: each message reaches the stream whole, one at a time.
   Reads pass through unlocked; only this server's loop reads."
  [transport]
  (let [lock (Object.)]
    (reify Transport
      (read-msg [_] (read-msg transport))
      (write-msg [_ msg] (locking lock (write-msg transport msg))))))
