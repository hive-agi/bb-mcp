(ns bb-mcp.host.retry
  "Bounded retry of a channel open on a transient refusal.

   The policy is data, the decision is a pure function, and the only effects
   (the open itself and the wait between attempts) are passed in, so any host
   adapter can reuse it and a test can drive it without a socket or a clock.")

(def default-policy
  "Two retries after the first refusal: 250 ms, then 1 s. Total wait < 2 s.
   `:retry?` decides whether a failure is transient; the default says no, so
   a policy without one never retries."
  {:delays [250 1000]
   :retry? (constantly false)})

(defn next-delay
  "Milliseconds to wait before attempt `attempt` (1-based count of failures
   so far) after failure `ex`, or nil to give up. Pure."
  [{:keys [delays retry?]} attempt ex]
  (when (and (pos-int? attempt) (retry? ex))
    (get delays (dec attempt))))

(defn call-with-retry
  "Call `thunk`; on a failure `policy` deems transient, wait the policy's next
   delay via `sleep!` and call again. Rethrows the last failure once the
   delays run out or the failure is not transient. Only the thunk and
   `sleep!` have effects."
  [policy sleep! thunk]
  (loop [attempt 1]
    (let [r (try {:ok (thunk)}
                 (catch #?(:clj Exception :cljs :default) e {:ex e}))]
      (if-not (contains? r :ex)
        (:ok r)
        (if-let [ms (next-delay policy attempt (:ex r))]
          (do (sleep! ms) (recur (inc attempt)))
          (throw (:ex r)))))))
