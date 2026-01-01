(ns bb-mcp.protocol
  "MCP JSON-RPC protocol implementation for babashka.
   Handles stdio communication with Claude."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; MCP Protocol constants
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
   MCP uses Content-Length header + JSON body."
  []
  (when-let [header-line (read-line)]
    (when (str/starts-with? header-line "Content-Length:")
      (let [content-length (parse-long (str/trim (subs header-line 16)))]
        ;; Read empty line after header
        (read-line)
        ;; Read JSON body
        (let [buffer (char-array content-length)
              _ (.read *in* buffer 0 content-length)]
          (json/parse-string (String. buffer) true))))))

(defn write-message
  "Write a JSON-RPC message to stdout with Content-Length header."
  [msg]
  (let [json-str (json/generate-string msg)
        content-length (count (.getBytes json-str "UTF-8"))]
    (println (str "Content-Length: " content-length))
    (println)
    (print json-str)
    (flush)))

(defn send-notification
  "Send a JSON-RPC notification (no id, no response expected)."
  [method params]
  (write-message {:jsonrpc "2.0"
                  :method method
                  :params params}))
