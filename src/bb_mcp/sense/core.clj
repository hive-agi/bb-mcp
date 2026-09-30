(ns bb-mcp.sense.core
  "Sixth sense, the PURE stratum of the receptor: values and calculations only.

   A SENSE is what hive-agent perceived on the hivemind for this session
   ({:sense/id :sense/class :sense/agent :sense/project :sense/parent
     :sense/text :sense/at}, plus an optional small :sense/detail map such
   as {:termination :max-turns} or {:error/type :context-overflow}). This namespace turns one into the Claude Code
   channel notification that carries it, builds the code that asks the hive
   JVM for new senses, reads the reply, and decides what the polling loop does
   next. Nothing here touches a socket, a clock or stdout: the loop in
   `bb-mcp.sense.receptor` performs the effects this namespace describes.

   Platform contract (Claude Code channels, research preview): the server
   declares `capabilities.experimental[\"claude/channel\"] = {}` and sends
   `notifications/claude/channel` with {:content string :meta {k string}};
   meta keys are letters, digits and underscore only."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;;; ===========================================================================
;;; The channel notification
;;; ===========================================================================

(def channel-method
  "JSON-RPC method Claude Code surfaces to the model as a <channel> tag."
  "notifications/claude/channel")

(def channel-capability
  "What `initialize` advertises so Claude Code listens for channel events."
  {:experimental {"claude/channel" {}}})

(defn meta-key?
  "True when `k` is a legal channel meta key: letters, digits, underscore."
  [k]
  (let [text (cond (string? k) k
                   (keyword? k) (subs (str k) 1) ; keeps a namespace, so :a/b fails
                   :else nil)]
    (boolean (and text (re-matches #"[A-Za-z0-9_]+" text)))))

(defn- text-of
  "A meta value as text, or nil when there is nothing to say."
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    :else (let [s (str v)] (when-not (str/blank? s) s))))

(def ^:private meta-fields
  "Meta key -> the sense field it is read from."
  [[:agent :sense/agent]
   [:sense_class :sense/class]
   [:project :sense/project]
   [:parent :sense/parent]
   [:sense_id :sense/id]])

(def ^:private detail-fields
  "Meta key -> the :sense/detail keys it may arrive under. Over JSON a
   keyword key arrives as its text (\"error/type\"), so each is also looked
   up by that text. A new detail worth surfacing is one row here."
  [[:termination [:termination]]
   [:error_type [:error/type :error-type :error_type]]
   [:reason [:reason]]])

(defn- detail-value
  "The first of `ks` present in the `detail` map, as text; nil when none."
  [detail ks]
  (when (map? detail)
    (some (fn [k] (or (text-of (get detail k))
                      (text-of (get detail (subs (str k) 1)))))
          ks)))

(defn sense-detail
  "The surfaced part of a sense's :sense/detail: [[meta-key text] ...] in
   `detail-fields` order, absent ones dropped."
  [sense]
  (let [detail (:sense/detail sense)]
    (into []
          (keep (fn [[k ks]] (when-let [v (detail-value detail ks)] [k v])))
          detail-fields)))

(defn sense-meta
  "The channel meta for `sense`: string values under legal keys, blanks
   dropped; surfaced detail fields ride along under their own keys."
  [sense]
  (into {}
        (filter (fn [[k _]] (meta-key? k)))
        (concat (keep (fn [[k field]]
                        (when-let [v (text-of (get sense field))] [k v]))
                      meta-fields)
                (sense-detail sense))))

(defn- detail-suffix
  "\" [termination=max-turns]\" for the surfaced detail, \"\" when none."
  [sense]
  (let [pairs (sense-detail sense)]
    (if (seq pairs)
      (str " [" (str/join " " (map (fn [[k v]] (str (name k) "=" v)) pairs)) "]")
      "")))

(defn sense-content
  "The text the model reads, with any surfaced detail appended compactly.
   A sense with no text still says who and what."
  [sense]
  (str (or (text-of (:sense/text sense))
           (str (or (text-of (:sense/class sense)) "sense")
                " from " (or (text-of (:sense/agent sense)) "an agent")))
       (detail-suffix sense)))

(defn sense->notification
  "ONE `notifications/claude/channel` message carrying `sense`."
  [sense]
  {:jsonrpc "2.0"
   :method channel-method
   :params {:content (sense-content sense)
            :meta (sense-meta sense)}})

;;; ===========================================================================
;;; The drain request and its reply
;;; ===========================================================================

(def drain-var
  "The hive-agent entry point the receptor polls when no per-consumer port
   is there (contract owned by hive-agent): stateless, cursor held here."
  'hive-agent.sixth-sense.api/drain)

(def sense-port-var
  "The hive-agent port whose :drain! keeps a cursor PER CONSUMER in the hive
   (persisted with its snapshot), preferred over `drain-var` when loaded."
  'hive-agent.sixth-sense.api/sense-port)

(def sense-state-ns
  "Where the per-consumer cursors and the log head are read, to skip a new
   consumer's backlog in the hive itself."
  'hive-agent.sixth-sense.state)

(def ^:private sense-keys
  "The Sense fields a drain reply carries back."
  [:sense/id :sense/class :sense/agent :sense/project :sense/parent
   :sense/text :sense/at])

(defn- resolve-form
  "Code that LOOKS UP `sym` (ns-qualified) in the hive JVM, never loading it."
  [sym]
  `(some-> (find-ns '~(symbol (namespace sym))) (ns-resolve '~(symbol (name sym)))))

(defn- reply-form
  "Code turning drain result `r` into the printable reply: senses projected
   to `sense-keys` (+ a scalar-only :sense/detail), :sense/at and :epoch as
   text unless already readable."
  [r]
  `(let [plain# (fn [v#] (if (or (nil? v#) (keyword? v#) (string? v#) (number? v#) (boolean? v#))
                           v# (str v#)))
         epoch# (:epoch ~r)]
     (cond-> {:cursor (:cursor ~r)
              :senses (mapv (fn [s#]
                              (let [at# (:sense/at s#)
                                    d# (or (:sense/detail s#) (get s# "detail"))]
                                (cond-> (select-keys s# ~sense-keys)
                                  (and at# (not (number? at#)) (not (string? at#)))
                                  (assoc :sense/at (str at#))
                                  (map? d#)
                                  (assoc :sense/detail
                                         (into {} (map (fn [[k# dv#]] [(if (keyword? k#) k# (str k#)) (plain# dv#)]))
                                               (take 8 d#))))))
                            (:senses ~r))}
       (some? epoch#) (assoc :epoch (str epoch#)))))

(defn drain-form
  "Code evaluated in the hive JVM. Returns, as EDN text, one of
     {:senses [...] :cursor n :epoch id}  the drain answered (:epoch may be
                                          absent from an older hive-agent)
     {... :consumer id :primed? bool}     answered by the per-consumer port
     {:sense-feed/absent true}            hive-agent has no sixth sense loaded
     {:sense-feed/error msg}              it threw

   Two paths, chosen in the JVM:
     per-consumer  `opts` names a :consumer and `sense-port-var` is there:
                   `(:drain! (sense-port))` with that consumer; the cursor
                   lives in the hive, so `cursor` is ignored. A consumer the
                   hive has no cursor for skips its backlog there when
                   (:prime? opts): it reads from the log head. :primed? says
                   the hive's cursor is authoritative (it had one, or was just
                   moved to the head); false asks the loop to prime itself.
     stateless     otherwise `drain-var` from `cursor` (the loop's cursor).

   Every fn is LOOKED UP, never loaded: `find-ns` + `ns-resolve` only read
   what the hive JVM already has, so a poll can never load code there as a
   side effect. An unloaded hive-agent answers :absent.

   `cursor`, `receptor` and `opts` values are embedded QUOTED: data, never
   evaluated there. `:epoch` is sent as text (it only needs =), and
   `:sense/at` is printed as text unless already a number or string, so a
   java.time or UUID value can never make the reply unreadable."
  ([cursor receptor] (drain-form cursor receptor {}))
  ([cursor receptor {:keys [consumer limit prime?]}]
   (let [r (gensym "reply")]
     (pr-str
      `(binding [*print-namespace-maps* false]
         (pr-str
          (try
            (let [port# (when '~consumer ~(resolve-form sense-port-var))
                  drain# ~(resolve-form drain-var)]
              (cond
                port#
                (let [sens# ~(resolve-form (symbol (name sense-state-ns) "default-sensorium"))
                      cursors# ~(resolve-form (symbol (name sense-state-ns) "consumer-cursors"))
                      head# ~(resolve-form (symbol (name sense-state-ns) "head"))
                      known# (when (and sens# cursors#)
                               (contains? (cursors# (sens#)) '~consumer))
                      skip-to# (when (and '~prime? (false? known#) head#) (head# (sens#)))
                      ~r ((:drain! (port#))
                          (cond-> {:consumer '~consumer :receptor '~receptor}
                            '~limit (assoc :limit '~limit)
                            (some? skip-to#) (assoc :cursor skip-to#)))]
                  (assoc ~(reply-form r)
                         :consumer '~consumer
                         :primed? (or (true? known#) (some? skip-to#))))

                drain#
                (let [~r (drain# {:cursor '~cursor :receptor '~receptor})]
                  ~(reply-form r))

                :else {:sense-feed/absent true}))
            (catch Throwable e#
              {:sense-feed/error (or (ex-message e#) (str e#))}))))))))

(defn- read-edn
  "Tolerant EDN read: unknown tags keep their value."
  [s]
  (edn/read-string {:default (fn [_ v] v)} s))

(defn read-drain-reply
  "Fold the nREPL answer to `drain-form` into an OUTCOME:
     {:outcome :ok :senses [...] :cursor n :epoch id}  (:epoch only when sent)
       + :consumer id :primed? bool                   (per-consumer path only)
     {:outcome :absent}
     {:outcome :failed :detail msg}
   `res` is eval-code's {:result :error?}. Total: never throws."
  [res]
  (try
    (if (:error? res)
      {:outcome :failed :detail (str (:result res))}
      (let [v (read-edn (read-edn (:result res)))]
        (cond
          (:sense-feed/absent v) {:outcome :absent}
          (:sense-feed/error v) {:outcome :failed :detail (str (:sense-feed/error v))}
          (and (map? v) (integer? (:cursor v)))
          (cond-> {:outcome :ok :senses (vec (filter map? (:senses v))) :cursor (:cursor v)}
            (some? (:epoch v)) (assoc :epoch (str (:epoch v)))
            (some? (:consumer v)) (assoc :consumer (str (:consumer v))
                                         :primed? (true? (:primed? v))))
          :else {:outcome :failed :detail (str "unreadable drain reply: " (pr-str v))})))
    (catch Exception e
      {:outcome :failed :detail (str "unreadable drain reply: " (ex-message e))})))

;;; ===========================================================================
;;; The loop's decisions
;;; ===========================================================================

(def default-policy
  "Poll every second while the feed answers; back off to half a minute when
   it does not. `:prime?` skips the backlog from before the session existed
   (in the hive for a new per-consumer cursor, else by dropping the first
   drain), so a new session is not flooded. `:limit` caps one per-consumer
   drain; the rest comes on the next poll."
  {:base-ms 1000 :max-ms 30000 :factor 2 :prime? true :limit 100})

(defn initial-state
  "Where the loop starts: cursor 0, no log epoch seen, not yet primed,
   polling at base rate."
  [policy]
  {:cursor 0 :epoch nil :primed? (not (:prime? policy)) :delay-ms (:base-ms policy) :noted nil})

(defn- backoff [{:keys [delay-ms]} {:keys [max-ms factor]}]
  (min max-ms (* factor delay-ms)))

(defn- note-once
  "Log `line` only on the first poll of an episode of `kind`."
  [state kind line]
  (if (= kind (:noted state)) [] [line]))

(defn log-restarted?
  "True when the reply comes from a DIFFERENT sense log than the one the
   loop's cursor points into (the hive restarted). Two witnesses:
     epoch   both sides carry one and they differ: certain, whatever the
             cursors say (a new log may already have grown past the old
             cursor before this poll);
     cursor  the reply's cursor went BACKWARDS: the fallback for a hive
             that sends no epoch."
  [state {:keys [cursor epoch]}]
  (let [seen (:epoch state)]
    (boolean
     (or (and (some? seen) (some? epoch) (not= seen epoch))
         (< cursor (:cursor state))))))

(defmulti ^:private advance*
  "One decision per outcome kind (open set: a new kind is one defmethod)."
  (fn [_state outcome _policy] (:outcome outcome)))

(defn- advance-consumer
  "An :ok reply from the per-consumer port. The hive holds the cursor (and
   handles a reset log itself), so nothing here re-reads; the only local
   decision is priming, needed only when the hive could not skip the backlog
   for a new consumer (`:primed?` false on the first reply)."
  [state {:keys [senses cursor epoch primed?]} policy recovered]
  (let [drop? (not (or (:primed? state) primed?))]
    {:state (assoc state :cursor cursor :epoch epoch :primed? true :delay-ms (:base-ms policy) :noted nil)
     :deliver (if drop? [] senses) :log (vec recovered) :sleep-ms (:base-ms policy)}))

(defmethod advance* :ok [state {:keys [senses cursor epoch] :as outcome} policy]
  (let [recovered (when (:noted state) ["sixth-sense: sense feed answering again"])]
    (cond
      (:consumer outcome)
      (advance-consumer state outcome policy recovered)

      ;; A new sense log: the senses in this reply were read against a cursor
      ;; from the OLD log, so they are discarded and the new log is re-read
      ;; from its start next poll.
      (log-restarted? state outcome)
      {:state (assoc state :cursor 0 :epoch epoch :delay-ms (:base-ms policy) :noted nil)
       :deliver [] :log (conj (vec recovered) "sixth-sense: hive sense log restarted; re-reading from 0")
       :sleep-ms 0}

      (not (:primed? state))
      {:state (assoc state :cursor cursor :epoch epoch :primed? true :delay-ms (:base-ms policy) :noted nil)
       :deliver [] :log (vec recovered) :sleep-ms (:base-ms policy)}

      :else
      {:state (assoc state :cursor cursor :epoch epoch :delay-ms (:base-ms policy) :noted nil)
       :deliver senses :log (vec recovered) :sleep-ms (:base-ms policy)})))

(defmethod advance* :absent [state _ policy]
  (let [d (backoff state policy)]
    {:state (assoc state :delay-ms d :noted :absent)
     :deliver []
     :log (note-once state :absent
                     (str "sixth-sense: " drain-var " is not loaded in the hive JVM;"
                          " backing off (channel stays declared, nothing to deliver)"))
     :sleep-ms d}))

(defmethod advance* :default [state {:keys [detail]} policy]
  (let [d (backoff state policy)]
    {:state (assoc state :delay-ms d :noted :failed)
     :deliver []
     :log (note-once state :failed (str "sixth-sense: sense feed unavailable (" detail "); backing off"))
     :sleep-ms d}))

(defn advance
  "Given the loop `state` and one drain `outcome`, the next step:
     {:state s' :deliver [sense ...] :log [line ...] :sleep-ms n}"
  [state outcome policy]
  (advance* state outcome policy))

;;; ===========================================================================
;;; Configuration read from the environment (a map, so tests pass their own)
;;; ===========================================================================

(def ^:private truthy #{"1" "true" "on" "yes"})

(defn client-listens?
  "True when the client's `initialize` request itself declares it listens for
   channel events (params.capabilities.experimental[\"claude/channel\"]).
   Claude Code 2.1.x does NOT send this (its client capabilities are roots,
   elicitation, tasks, extensions); the check is here so a client that does
   is served without configuration."
  [init-request]
  (let [exp (get-in init-request [:params :capabilities :experimental])]
    (boolean (and (map? exp)
                  (or (contains? exp :claude/channel)
                      (contains? exp "claude/channel"))))))

(defn channels-wanted?
  "Channels are OPT-IN: armed only when the client's initialize request
   declares it listens, or BB_MCP_CHANNELS is 1/true/on/yes. Otherwise the
   session never polls the hive JVM."
  [env init-request]
  (or (client-listens? init-request)
      (contains? truthy (some-> (get env "BB_MCP_CHANNELS") str/trim str/lower-case))))

(defn- csv [s]
  (when-not (str/blank? s)
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split s #","))))

(defn- present [s] (when-not (str/blank? s) s))

(def ^:private every-parent
  "BB_MCP_SENSE_PARENT values that opt OUT of the parent filter: the session
   hears every swarm on the host."
  #{"*" "all"})

(defn receptor-parent
  "The parent this session listens for, from `env` and its `caller-id`:
     BB_MCP_SENSE_PARENT     explicit; `*` or `all` means no filter (nil)
     CLAUDE_SWARM_SLAVE_ID   a ling hears its own children
     caller-id               the session's own `_caller_id`
                             (\"coordinator:<instance>\"), which hive-mcp
                             records as the parent of every ling it spawns,
                             so a coordinator hears ITS swarm, not the host's
   nil when none applies (no caller-id given and nothing set)."
  [env caller-id]
  (let [explicit (some-> (present (get env "BB_MCP_SENSE_PARENT")) str/trim)]
    (cond
      (contains? every-parent (some-> explicit str/lower-case)) nil
      explicit explicit
      :else (or (present (get env "CLAUDE_SWARM_SLAVE_ID"))
                (present caller-id)))))

(defn receptor-spec
  "The receptor hive-agent filters senses by, from `env` and the session's
   `caller-id` (see `receptor-parent`):
     :receptor/parent   `receptor-parent`, omitted when nil
     :receptor/projects BB_MCP_SENSE_PROJECTS (comma list), omitted when unset
     :receptor/classes  BB_MCP_SENSE_CLASSES  (comma list of class names, e.g.
                        ask,blocked,completed,truncated,error)"
  ([env] (receptor-spec env nil))
  ([env caller-id]
   (let [parent (receptor-parent env caller-id)
         projects (csv (get env "BB_MCP_SENSE_PROJECTS"))
         classes (csv (get env "BB_MCP_SENSE_CLASSES"))]
     (cond-> {}
       parent (assoc :receptor/parent parent)
       projects (assoc :receptor/projects projects)
       classes (assoc :receptor/classes (into #{} (map #(keyword "sense" %)) classes))))))

(defn feed-spec
  "How the nREPL feed drains for a session: per-consumer under `caller-id`
   (nil = the stateless path only), `policy`'s :limit and :prime?."
  [caller-id policy]
  {:consumer (present caller-id)
   :limit (:limit policy)
   :prime? (boolean (:prime? policy))})

(defn policy-from
  "`default-policy` adjusted by BB_MCP_SENSE_POLL_MS and BB_MCP_SENSE_REPLAY=1."
  [env]
  (let [poll (some-> (get env "BB_MCP_SENSE_POLL_MS") str/trim parse-long)]
    (cond-> default-policy
      (and poll (pos? poll)) (assoc :base-ms poll)
      (= "1" (get env "BB_MCP_SENSE_REPLAY")) (assoc :prime? false))))
