(ns bb-mcp.acp
  "Portable OpenClaw ACP JSON-RPC message helpers.

   `exchange!` is injected so the same client works over bb-mcp stdio,
   cljw TCP, JVM sockets, or a ClojureScript transport.")

(defn rpc-request [id method params]
  {:jsonrpc "2.0" :id id :method method :params (or params {})})

(defn initialize [id client-info]
  (rpc-request id "initialize" client-info))

(defn session-new [id params]
  (rpc-request id "session/new" params))

(defn session-load [id params]
  (rpc-request id "session/load" params))

(defn prompt [id session-id prompt-text]
  (rpc-request id "session/prompt"
               {:sessionId session-id :prompt prompt-text}))

(defn cancel [id session-id]
  (rpc-request id "session/cancel" {:sessionId session-id}))

(defn call!
  "Call one ACP method through an injected `(fn [request] response)` seam." 
  [exchange! request]
  (let [response (exchange! request)]
    (if-let [error (:error response)]
      (throw (ex-info "ACP request failed" {:error error :response response}))
      (:result response))))

(defn initialize! [exchange! id client-info]
  (call! exchange! (initialize id client-info)))

(defn session-new! [exchange! id params]
  (call! exchange! (session-new id params)))

(defn session-load! [exchange! id params]
  (call! exchange! (session-load id params)))

(defn prompt! [exchange! id session-id prompt-text]
  (call! exchange! (prompt id session-id prompt-text)))

(defn cancel! [exchange! id session-id]
  (call! exchange! (cancel id session-id)))
