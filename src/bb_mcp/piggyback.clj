(ns bb-mcp.piggyback
  "Piggyback blocks for the tools this head serves ITSELF.

   A forwarded tool runs inside hive-mcp's middleware chain, whose
   `wrap-handler-piggybacks` appends the HIVEMIND, MEMORY, TOOLRESULT and
   `:block/*` channels (FRONTIER, EMACS-ATTENTION, ...) to every response.
   `bash` and `clojure_eval` never reach that chain, so an agent working
   mostly through them saw no coordination message and no \"Emacs is waiting
   for input\" alert (measured 2026-09-13: a probe block rendered on a memory
   call and not on the bash call right before it).

   This asks the same wrapper for the blocks alone: it wraps a handler that
   returns an empty content array, so the answer is exactly what the chain
   would have appended. One drain, N call sites; no channel is named here.

   `drain` is TOTAL: never throws, and answers \"\" when the JVM does not
   answer, has no such wrapper, or replies with something unreadable. A head
   that cannot reach hive-mcp keeps working; it just carries no blocks.

   The transport is a collaborator: the 3-arity takes the `:eval-fn`, so a
   test drives this with a stub, the same seam `bb-mcp.guard` uses."
  (:require [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private drain-timeout-ms
  "Deadline for one drain round-trip. A drain that cannot answer promptly is
   skipped: the tool's own output is never held for it."
  3000)

(def ^:private identity-keys
  "The arguments the drain needs: who is calling and from where. The tool's
   own payload (a command, a code string) is never shipped for this."
  [:_caller_id :_caller_cwd :agent_id :directory])

(defn- drain-form
  "Code evaluated in the hive-mcp JVM. Returns the appended text as EDN.

   `args` is embedded QUOTED: it is data off the wire and must never be
   evaluated in the JVM."
  [tool args]
  (pr-str
   `(pr-str
     (try
       (let [wrap# (requiring-resolve
                    'hive-mcp.server.routes.middleware/wrap-handler-piggybacks)
             content# ((wrap# (fn [~'_] []) ~tool) '~args)]
         (apply str (keep :text content#)))
       (catch Throwable e#
         {:piggyback/gap :remote-threw :piggyback/detail (ex-message e#)})))))

(defn- read-reply
  "The drained text, or nil when the reply is not text.
   Read twice: the value is the printed form of a `pr-str`."
  [s]
  (try
    (let [v (edn/read-string (edn/read-string s))]
      (when (string? v) v))
    (catch Exception _ nil)))

(defn drain
  "Blocks hive-mcp would append to a response for this caller, as text that
   begins with a blank line, or \"\" when there are none.

   Arguments:
     tool - the tool name as this head serves it, e.g. \"bash\".
     args - the tool's arguments after context injection; only the caller
            identity keys are sent.
   Total: never nil, never throws."
  ([tool args] (drain tool args {:eval-fn nrepl/eval-code
                                 :port-fn nrepl/get-nrepl-port}))
  ([tool args {:keys [eval-fn port-fn timeout-ms]}]
   (try
     (let [res (eval-fn {:port (port-fn)
                         :code (drain-form tool (select-keys args identity-keys))
                         :timeout-ms (or timeout-ms drain-timeout-ms)})]
       (or (when-not (:error? res) (read-reply (:result res)))
           ""))
     (catch Exception _ ""))))

(defn append
  "RESULT with the drained BLOCKS appended. Blank blocks leave it untouched."
  [result blocks]
  (if (str/blank? blocks)
    result
    (str result blocks)))
