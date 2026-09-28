(ns bb-mcp.async
  "Background execution for the tools this head serves ITSELF.

   A forwarded tool is queued by hive-mcp's own `wrap-handler-async` when the
   caller passes async:true, and its result comes back in hive-mcp's
   TOOLRESULT block. `bash` and `clojure_eval` never reach that chain, so a
   long command held the agent loop for its whole run. This gives them the
   same contract: an ack at once, the result on a later call.

   State is one bounded buffer per bb-mcp process. Each Claude Code window
   spawns its own bb-mcp, so the process IS the caller; no caller key is
   needed. The buffer lives in memory only: a result not yet drained when
   this process exits is lost, and the ack says so."
  (:require [clojure.string :as str]))

(def ^:private max-buffered
  "Finished results held for delivery. Past this the OLDEST is dropped, so a
   caller that never makes another call cannot grow the buffer without bound."
  100)

(defonce ^:private results (atom []))

(defonce ^:private running (atom #{}))

(defn- buffer! [entry]
  (swap! results #(vec (take-last max-buffered (conj % entry)))))

(defn submit!
  "Run `work` (a 0-arg fn returning {:result str :error? bool}) in the
   background under a fresh task id. Returns the ack text for the caller."
  [tool work]
  (let [task-id (str "btask-" (random-uuid))]
    (swap! running conj task-id)
    (future
      (let [{:keys [result error?]}
            (try (work)
                 (catch Throwable t
                   {:result (str "Error: " (.getName (class t)) ": " (ex-message t))
                    :error? true}))]
        (buffer! {:task-id task-id :tool tool
                  :status (if error? :error :completed)
                  :result result})
        (swap! running disj task-id)))
    (pr-str {:queued true :task-id task-id :tool tool :durable false})))

(defn running-ids
  "Task ids started and not yet finished."
  []
  @running)

(defn drain!
  "Finished results as a TOOLRESULT block beginning with a blank line, or \"\"
   when none are waiting. Each result is delivered once."
  []
  (let [[taken _] (reset-vals! results [])]
    (if (empty? taken)
      ""
      (str "\n\n---TOOLRESULT---\n"
           (pr-str {:results taken
                    :delivered (count taken)
                    :running (count @running)})
           "\n---/TOOLRESULT---"))))

(def schema-property
  "The `async` property a native tool advertises. An MCP client forwards only
   declared params, so without it the flag never arrives."
  {:type "boolean"
   :description (str "Set true to run in the background so the call does not "
                     "block the agent loop. Returns {:queued true :task-id ...} "
                     "at once; the result arrives in a ---TOOLRESULT--- block "
                     "on a later hive call.")})

(defn with-async-property
  "`spec` with `async` declared in its schema, unless it declares one already."
  [spec]
  (update-in spec [:schema :properties]
             #(if (or (contains? % :async) (contains? % "async"))
                %
                (assoc % :async schema-property))))

(defn requested?
  "True when the call arguments ask for background execution."
  [args]
  (let [v (when (map? args) (or (get args :async) (get args "async")))]
    (or (true? v) (and (string? v) (= "true" (str/lower-case v))))))

(defn strip
  "`args` without the async flag, which addresses this head, not the tool."
  [args]
  (if (map? args) (dissoc args :async "async") args))
