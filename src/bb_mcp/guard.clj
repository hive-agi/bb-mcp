(ns bb-mcp.guard
  "The guard seam for the tools this head serves ITSELF.

   bb-mcp answers `bash` and `clojure_eval` in its own process, so those calls
   never reach hive-mcp's tool-dispatch gate: the two widest-blast-radius tools
   on the server were the two nothing judged. This carries the same moment to
   the same `:guard/decide` seam, over the nREPL socket this head already holds
   for every forwarded tool. One decision seam, N call sites — no rule and no
   matching logic is restated here.

   `decide` is TOTAL: never nil, never throws. A JVM that does not answer, an
   absent seam, and an unreadable reply all fold to an ALLOW stamped
   `:guard/enforcing? false` carrying a `:guard/gap`. A head that cannot reach
   the guard has to keep working — and has to SAY the call went unjudged, or
   `allowed` and `never judged` become the same answer at the call site.

   The transport is a collaborator, not a dependency: the 3-arity takes the
   `:eval-fn` that carries the request, so a test drives this with a stub."
  (:require [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private guard-timeout-ms
  "Deadline for one decision round-trip. Short on purpose — a guard that cannot
   answer promptly must fail open rather than hold the tool call."
  5000)

(defn- gap
  "An allow that says nobody judged it."
  [reason detail]
  (cond-> {:guard/verdict :allow :guard/enforcing? false :guard/gap reason}
    detail (assoc :guard/gap-detail (str detail))))

(defn- decide-form
  "Code evaluated in the hive-mcp JVM. Returns the decision as EDN text.

   `payload` is embedded QUOTED: it is data off the wire and must never be
   evaluated in the JVM."
  [payload]
  (pr-str
   `(pr-str
     (try
       (if-let [d# ((requiring-resolve 'hive-mcp.extensions.registry/get-extension)
                    :guard/decide)]
         (into {} (d# :mcp '~payload))
         {:guard/gap :seam-absent})
       (catch Throwable e#
         {:guard/gap :remote-threw :guard/gap-detail (ex-message e#)})))))

(defn- ->payload
  "The `:mcp` projection's raw shape for one tool call.

   `args` is passed through unchanged, bb-mcp's injected `:_caller_id` /
   `:_caller_cwd` included, so this gate and hive-mcp's see the same input."
  [tool args]
  {:tool tool
   :input args
   :agent-id (or (:agent_id args) (:_caller_id args))
   :cwd (or (:directory args) (:_caller_cwd args))})

(defn- read-decision
  "Parse the nREPL value into a decision map, or nil when it is not one.
   Read twice: the value is the printed form of a `pr-str`."
  [s]
  (try
    (let [v (edn/read-string (edn/read-string s))]
      (when (map? v) v))
    (catch Exception _ nil)))

(defn- fold
  "Fold a parsed remote reply into a decision. A reply that carries only a
   `:guard/gap` is the JVM telling us it could not judge — keep it a gap."
  [m]
  (cond
    (nil? m)                    (gap :unreadable-reply nil)
    (:guard/gap m)              (gap (:guard/gap m) (:guard/gap-detail m))
    (:guard/verdict m)          m
    :else                       (gap :unreadable-reply (pr-str m))))

(defn decide
  "Judge one tool call against the live hive-mcp guard.

   Arguments:
     tool — the tool name as this head serves it, e.g. \"bash\".
     args — the tool's arguments, after context injection.
   Returns: a decision map. Read `:guard/verdict`; when it is `:deny` or
            `:warn`, `:guard/reason`, `:guard/rule-id` and `:guard/citations`
            carry the rest.
   Total: never nil, never throws."
  ([tool args] (decide tool args {:eval-fn nrepl/eval-code
                                  :port-fn nrepl/get-nrepl-port}))
  ([tool args {:keys [eval-fn port-fn timeout-ms]}]
   (let [res (try
               (eval-fn {:port (port-fn)
                         :code (decide-form (->payload tool args))
                         :timeout-ms (or timeout-ms guard-timeout-ms)})
               (catch Exception e {:error? true :result (ex-message e)}))]
     (if (:error? res)
       (gap :guard-unreachable (:result res))
       (fold (read-decision (:result res)))))))

(defn denied?
  "True when `decision` refuses the call."
  [decision]
  (= :deny (:guard/verdict decision)))

(defn warned?
  "True when `decision` lets the call run but has something to say."
  [decision]
  (= :warn (:guard/verdict decision)))

(defn refusal-text
  "The user-facing body of a refused call."
  [tool decision]
  (str "REFUSED by the hive guard — " tool "\n\n"
       (:guard/reason decision)
       (when-let [cites (seq (:guard/citations decision))]
         (str "\n\nStated by: " (str/join ", " cites)))
       (when-let [rid (:guard/rule-id decision)]
         (str "\nRule: " rid))))

(defn warning-text
  "The advisory appended to a call that ran under a `:warn`."
  [decision]
  (str "GUARD WARNING — " (:guard/reason decision)
       (when-let [rid (:guard/rule-id decision)]
         (str " [" rid "]"))))
