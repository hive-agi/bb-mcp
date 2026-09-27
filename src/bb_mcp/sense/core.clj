(ns bb-mcp.sense.core
  "Sixth sense, the PURE stratum of the receptor: values and calculations only.

   A SENSE is what hive-agent perceived on the hivemind for this session
   ({:sense/id :sense/class :sense/agent :sense/project :sense/parent
     :sense/text :sense/at}). This namespace turns one into the Claude Code
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

(defn sense-meta
  "The channel meta for `sense`: string values under legal keys, blanks dropped."
  [sense]
  (into {}
        (keep (fn [[k field]]
                (when-let [v (text-of (get sense field))]
                  (when (meta-key? k) [k v]))))
        meta-fields))

(defn sense-content
  "The text the model reads. A sense with no text still says who and what."
  [sense]
  (or (text-of (:sense/text sense))
      (str (or (text-of (:sense/class sense)) "sense")
           " from " (or (text-of (:sense/agent sense)) "an agent"))))

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
  "The hive-agent entry point the receptor polls (contract owned by hive-agent)."
  'hive-agent.sixth-sense.api/drain)

(defn drain-form
  "Code evaluated in the hive JVM. Returns, as EDN text, one of
     {:senses [...] :cursor n}    the drain answered
     {:sense-feed/absent true}    hive-agent has no sixth sense loaded
     {:sense-feed/error msg}      it threw

   `cursor` and `receptor` are embedded QUOTED: data, never evaluated there.
   `:sense/at` is printed as text unless already a number or string, so a
   java.time value can never make the reply unreadable."
  [cursor receptor]
  (pr-str
   `(binding [*print-namespace-maps* false]
      (pr-str
       (try
         (if-let [drain# (try (requiring-resolve '~drain-var)
                              (catch Throwable _# nil))]
           (let [r# (drain# {:cursor '~cursor :receptor '~receptor})]
             {:cursor (:cursor r#)
              :senses (mapv (fn [s#]
                              (let [at# (:sense/at s#)]
                                (cond-> (select-keys s# [:sense/id :sense/class :sense/agent
                                                         :sense/project :sense/parent
                                                         :sense/text :sense/at])
                                  (and at# (not (number? at#)) (not (string? at#)))
                                  (assoc :sense/at (str at#)))))
                            (:senses r#))})
           {:sense-feed/absent true})
         (catch Throwable e#
           {:sense-feed/error (or (ex-message e#) (str e#))}))))))

(defn- read-edn
  "Tolerant EDN read: unknown tags keep their value."
  [s]
  (edn/read-string {:default (fn [_ v] v)} s))

(defn read-drain-reply
  "Fold the nREPL answer to `drain-form` into an OUTCOME:
     {:outcome :ok :senses [...] :cursor n}
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
          {:outcome :ok :senses (vec (filter map? (:senses v))) :cursor (:cursor v)}
          :else {:outcome :failed :detail (str "unreadable drain reply: " (pr-str v))})))
    (catch Exception e
      {:outcome :failed :detail (str "unreadable drain reply: " (ex-message e))})))

;;; ===========================================================================
;;; The loop's decisions
;;; ===========================================================================

(def default-policy
  "Poll every second while the feed answers; back off to half a minute when
   it does not. `:prime?` drops the backlog the first drain returns, so a new
   session is not flooded with senses from before it existed."
  {:base-ms 1000 :max-ms 30000 :factor 2 :prime? true})

(defn initial-state
  "Where the loop starts: cursor 0, not yet primed, polling at base rate."
  [policy]
  {:cursor 0 :primed? (not (:prime? policy)) :delay-ms (:base-ms policy) :noted nil})

(defn- backoff [{:keys [delay-ms]} {:keys [max-ms factor]}]
  (min max-ms (* factor delay-ms)))

(defn- note-once
  "Log `line` only on the first poll of an episode of `kind`."
  [state kind line]
  (if (= kind (:noted state)) [] [line]))

(defmulti ^:private advance*
  "One decision per outcome kind (open set: a new kind is one defmethod)."
  (fn [_state outcome _policy] (:outcome outcome)))

(defmethod advance* :ok [state {:keys [senses cursor]} policy]
  (let [recovered (when (:noted state) ["sixth-sense: sense feed answering again"])]
    (cond
      ;; The feed's cursor went BACKWARDS: the hive restarted and its log
      ;; began again. Re-read from the start of the new log next poll.
      (< cursor (:cursor state))
      {:state (assoc state :cursor 0 :delay-ms (:base-ms policy) :noted nil)
       :deliver [] :log (conj (vec recovered) "sixth-sense: hive sense log restarted; re-reading from 0")
       :sleep-ms 0}

      (not (:primed? state))
      {:state (assoc state :cursor cursor :primed? true :delay-ms (:base-ms policy) :noted nil)
       :deliver [] :log (vec recovered) :sleep-ms (:base-ms policy)}

      :else
      {:state (assoc state :cursor cursor :delay-ms (:base-ms policy) :noted nil)
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

(defn channels-enabled?
  "BB_MCP_CHANNELS: on unless explicitly 0/false/off/no."
  [env]
  (not (contains? #{"0" "false" "off" "no"}
                  (some-> (get env "BB_MCP_CHANNELS") str/trim str/lower-case))))

(defn- csv [s]
  (when-not (str/blank? s)
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split s #","))))

(defn receptor-spec
  "The receptor hive-agent filters senses by, from `env`:
     :receptor/parent   BB_MCP_SENSE_PARENT, else CLAUDE_SWARM_SLAVE_ID, else \"coordinator\"
     :receptor/projects BB_MCP_SENSE_PROJECTS (comma list), omitted when unset
     :receptor/classes  BB_MCP_SENSE_CLASSES  (comma list of names, e.g. ask,blocked)"
  [env]
  (let [projects (csv (get env "BB_MCP_SENSE_PROJECTS"))
        classes (csv (get env "BB_MCP_SENSE_CLASSES"))]
    (cond-> {:receptor/parent (or (get env "BB_MCP_SENSE_PARENT")
                                  (get env "CLAUDE_SWARM_SLAVE_ID")
                                  "coordinator")}
      projects (assoc :receptor/projects projects)
      classes (assoc :receptor/classes (into #{} (map #(keyword "sense" %)) classes)))))

(defn policy-from
  "`default-policy` adjusted by BB_MCP_SENSE_POLL_MS and BB_MCP_SENSE_REPLAY=1."
  [env]
  (let [poll (some-> (get env "BB_MCP_SENSE_POLL_MS") str/trim parse-long)]
    (cond-> default-policy
      (and poll (pos? poll)) (assoc :base-ms poll)
      (= "1" (get env "BB_MCP_SENSE_REPLAY")) (assoc :prime? false))))
