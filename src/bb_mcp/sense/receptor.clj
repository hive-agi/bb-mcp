(ns bb-mcp.sense.receptor
  "Sixth sense, the PROTOCOL and BOUNDARY strata of the receptor.

   Two small ports:

     SenseFeed  where senses come from. `nrepl-feed` asks the hive JVM over the
                nREPL link this head already holds; a test passes a stub.
     Receptor   what a SESSION does with them. Chosen once, at `initialize`:
                  `channel-receptor` declares claude/channel and runs a
                                     background loop that writes one
                                     notifications/claude/channel per sense;
                  `null-receptor`    declares nothing and does nothing, so a
                                     session without channels behaves exactly
                                     as before (hivemind still reaches it via
                                     piggyback blocks).
                Both honour the same contract, so the server loop never asks
                which one it holds (LSP).

   Every decision the loop makes (what to send, how long to wait, what to log)
   is computed by `bb-mcp.sense.core/advance`; this namespace only performs
   it. The loop runs on its own thread and writes through the session's
   Transport, which serialises every write with the server's responses."
  (:require [bb-mcp.protocol :as proto]
            [bb-mcp.sense.core :as sense]
            [bb-mcp.tools.nrepl :as nrepl]))

;;; ===========================================================================
;;; SenseFeed
;;; ===========================================================================

(defprotocol SenseFeed
  "A source of senses addressed by cursor."
  (drain! [feed cursor receptor]
    "Senses after `cursor` for `receptor`, as a `bb-mcp.sense.core` outcome:
     {:outcome :ok :senses [...] :cursor n} | {:outcome :absent}
     | {:outcome :failed :detail s}. Must not throw."))

(def ^:private drain-timeout-ms
  "Deadline for one drain round-trip over nREPL."
  5000)

(defn nrepl-feed
  "The hive JVM's sense log, polled with `hive-agent.sixth-sense.api/drain`.
   `opts` may carry :eval-fn and :port-fn (defaults: the shared nREPL client)."
  ([] (nrepl-feed {}))
  ([{:keys [eval-fn port-fn timeout-ms]
     :or {eval-fn nrepl/eval-code port-fn nrepl/get-nrepl-port
          timeout-ms drain-timeout-ms}}]
   (reify SenseFeed
     (drain! [_ cursor receptor]
       (try
         (sense/read-drain-reply
          (eval-fn {:port (port-fn)
                    :code (sense/drain-form cursor receptor)
                    :timeout-ms timeout-ms}))
         (catch Exception e
           {:outcome :failed :detail (or (ex-message e) (str e))}))))))

;;; ===========================================================================
;;; Receptor
;;; ===========================================================================

(defprotocol Receptor
  "How a session is made aware of the hivemind."
  (capabilities [r]
    "Capabilities this receptor adds to the initialize result (a map, maybe empty).")
  (arm! [r transport]
    "Begin delivering senses through `transport`. Idempotent. Returns r.")
  (disarm! [r]
    "Stop delivering. Idempotent. Returns r."))

(defn null-receptor
  "A session without channels: declares nothing, delivers nothing."
  []
  (reify Receptor
    (capabilities [_] {})
    (arm! [r _] r)
    (disarm! [r] r)))

(defn- stderr-log
  "Default log sink: stderr, never stdout (stdout is the JSON-RPC stream)."
  [line]
  (binding [*out* *err*]
    (println (str "bb-mcp: " line))))

(defn- deliver!
  "Write one notification per sense. False once the transport refuses."
  [transport senses log-fn]
  (try
    (doseq [s senses]
      (proto/write-msg transport (sense/sense->notification s)))
    true
    (catch Exception e
      (log-fn (str "sixth-sense: transport closed (" (ex-message e) "); receptor stopping"))
      false)))

(defn- nap!
  "Sleep `ms` in short slices, returning early once `running` is false."
  [running ms sleep-fn]
  (loop [left ms]
    (when (and (pos? left) @running)
      (let [slice (min left 100)]
        (sleep-fn slice)
        (recur (- left slice))))))

(defn run-loop!
  "The receptor loop, on the CALLING thread, until `running` turns false.
   Each turn: drain once, let `sense/advance` decide, perform the decision.
   Returns the final loop state (for tests)."
  [{:keys [feed transport receptor-spec policy log-fn sleep-fn running]}]
  (loop [state (sense/initial-state policy)]
    (if-not @running
      state
      (let [outcome (drain! feed (:cursor state) receptor-spec)
            {:keys [state deliver log sleep-ms]} (sense/advance state outcome policy)]
        (run! log-fn log)
        (if (deliver! transport deliver log-fn)
          (do (nap! running sleep-ms sleep-fn)
              (recur state))
          (do (reset! running false) state))))))

(defn channel-receptor
  "A session with channels: advertises claude/channel and, once armed, polls
   `feed` on a background thread and writes each sense as a channel event.

   opts: :feed (SenseFeed, default `nrepl-feed`), :receptor-spec, :policy,
         :log-fn, :sleep-fn (all defaulted for production)."
  ([] (channel-receptor {}))
  ([{:keys [feed receptor-spec policy log-fn sleep-fn]}]
   (let [running (atom false)
         worker (atom nil)
         cfg {:feed (or feed (nrepl-feed))
              :receptor-spec (or receptor-spec {:receptor/parent "coordinator"})
              :policy (or policy sense/default-policy)
              :log-fn (or log-fn stderr-log)
              :sleep-fn (or sleep-fn #(Thread/sleep (long %)))
              :running running}]
     (reify Receptor
       (capabilities [_] sense/channel-capability)
       (arm! [r transport]
         (when (compare-and-set! running false true)
           (reset! worker
                   (future
                     (try (run-loop! (assoc cfg :transport transport))
                          (catch Throwable e
                            ((:log-fn cfg) (str "sixth-sense: receptor died: " (ex-message e))))
                          (finally (reset! running false))))))
         r)
       (disarm! [r]
         (reset! running false)
         r)))))

(defn select
  "The receptor for this session, from `env` (a map of environment strings):
   a channel receptor unless BB_MCP_CHANNELS turns channels off."
  [env]
  (if (sense/channels-enabled? env)
    (channel-receptor {:receptor-spec (sense/receptor-spec env)
                       :policy (sense/policy-from env)})
    (null-receptor)))
