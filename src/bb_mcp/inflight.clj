(ns bb-mcp.inflight
  "In-flight `tools/call` requests, each run on its own worker.

   The server loop used to read, run and answer one message at a time, so one
   slow call (a `git ship land` of minutes) held every later call of the
   session behind it while the hive answered in milliseconds. Now the loop
   only READS: a `tools/call` is handed to a bounded pool and answered from
   the worker that ran it. Responses carry their request id, so answering out
   of order is valid JSON-RPC.

   Each call has one state cell that moves exactly once away from :pending:
     :answered  - the worker claimed the right to write the response
     :cancelled - a `notifications/cancelled` arrived first; no response
     :abandoned - input ended and the drain gave up on it; no response
   So a response is written at most once, and never after a cancellation.

   Writes are NOT serialised here: `write!` is the caller's, and the server
   passes its serialised transport, one lock per whole message.

   A runtime that cannot make threads gets no pool (`pool` answers nil) and
   the server runs every call inline, as before."
  (:import [java.util.concurrent ExecutorService Executors ThreadFactory TimeUnit]))

(defn pool
  "A pool of `n` daemon workers with its call table, or nil when this runtime
   cannot make one."
  [n]
  (try
    (let [counter (atom 0)
          factory (reify ThreadFactory
                    (newThread [_ r]
                      (doto (Thread. ^Runnable r (str "bb-mcp-call-" (swap! counter inc)))
                        (.setDaemon true))))]
      {:executor (Executors/newFixedThreadPool (int n) factory)
       :calls    (atom {})})
    (catch Throwable _no-threads nil)))

(defn- forget!
  "Drop call `id` from `calls` if it is still the entry `token` made."
  [calls id token]
  (swap! calls (fn [m] (if (identical? token (get-in m [id :token])) (dissoc m id) m))))

(defn submit!
  "Run `(work)` on a worker of pool `p` and `(write! response)` its result.

   `work` returns the response map (nil = nothing to send). A throw becomes
   `(on-error throwable)`. Neither is written when the call was cancelled or
   abandoned first. Dynamic bindings of the calling thread are conveyed."
  [{:keys [^ExecutorService executor calls]} id work on-error write!]
  (let [state  (atom :pending)
        token  (Object.)
        fut    (promise)
        answer (fn [resp]
                 (when (and resp (compare-and-set! state :pending :answered))
                   (write! resp)))
        ^Callable task
        (bound-fn []
          (try
            (answer (work))
            (catch Throwable e
              (answer (on-error e)))
            (finally
              (forget! calls id token))))]
    ;; Registered BEFORE submit, so a task that finishes at once still finds
    ;; (and removes) its own entry.
    (swap! calls assoc id {:token token :state state :future fut})
    (deliver fut (.submit executor task))
    nil))

(defn cancel!
  "Cancel in-flight call `id`: its response is suppressed and its worker
   interrupted (a still-queued call never starts). True when a pending call
   was cancelled; false when it was unknown or already answered.

   The tool itself may not stop: work already handed to the hive JVM runs on
   there. Only this server's attention and the worker are reclaimed."
  [{:keys [calls]} id]
  (if-let [{:keys [state future]} (get @calls id)]
    (if (compare-and-set! state :pending :cancelled)
      (do (when-let [f (deref future 1000 nil)]
            (.cancel ^java.util.concurrent.Future f true))
          true)
      false)
    false))

(defn in-flight
  "Ids of the calls not yet finished."
  [{:keys [calls]}]
  (set (keys @calls)))

(defn drain!
  "Stop taking calls, then wait up to `timeout-ms` for the in-flight ones to
   answer. Calls still running at the deadline are abandoned: marked so they
   never write, and their workers interrupted. Returns {:drained? :abandoned}."
  [{:keys [^ExecutorService executor calls]} timeout-ms]
  (.shutdown executor)
  (let [done? (.awaitTermination executor (long timeout-ms) TimeUnit/MILLISECONDS)
        left  (when-not done?
                (into [] (keep (fn [[id {:keys [state]}]]
                                 (when (compare-and-set! state :pending :abandoned) id)))
                      @calls))]
    (when-not done? (.shutdownNow executor))
    {:drained? done? :abandoned (vec left)}))
