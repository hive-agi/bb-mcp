(ns bb-mcp.protocol
  "MCP JSON-RPC protocol implementation for babashka.
   Handles stdio communication with Claude."
  (:require [cheshire.core :as json]
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
(defn initialize-response [id]
  (json-rpc-response id
                     {:protocolVersion protocol-version
                      :capabilities {:tools {:listChanged false}
                                     :resources {:listChanged false}}
                      :serverInfo {:name server-name
                                   :version server-version}}))

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
              (json/parse-string (str sb) true)))

          ;; Try to parse as JSON directly (newline-delimited format)
          (str/starts-with? line "{")
          (json/parse-string line true)

          ;; Unexpected line - skip it
          :else (recur))))
    (catch Exception e
      nil)))

(defn write-message
  "Write a JSON-RPC message to stdout as newline-delimited JSON."
  [msg]
  (let [json-str (json/generate-string msg)]
    (println json-str)
    (flush)))

(defn send-notification
  "Send a JSON-RPC notification (no id, no response expected)."
  [method params]
  (write-message {:jsonrpc "2.0"
                  :method method
                  :params params}))
