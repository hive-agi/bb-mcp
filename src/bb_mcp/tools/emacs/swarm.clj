(ns bb-mcp.tools.emacs.swarm
  "Swarm orchestration tools.

   This module provides tools for Claude swarm agent management."
  (:require [bb-mcp.tools.emacs.core :as core]
            [clojure.string :as str]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: swarm_status
;; =============================================================================

(def swarm-status-spec
  {:name "swarm_status"
   :description "Get swarm status including all active slaves, their states, and task counts."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn swarm-status [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-swarm-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: swarm_spawn
;; =============================================================================

(def swarm-spawn-spec
  {:name "swarm_spawn"
   :description "Spawn a new Claude swarm slave."
   :schema {:type "object"
            :properties {:name {:type "string" :description "Slave name"}
                         :presets {:type "array" :items {:type "string"} :description "Preset names to load"}
                         :cwd {:type "string" :description "Working directory"}
                         :role {:type "string" :description "Agent role description"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn swarm-spawn [{:keys [name presets cwd role port]}]
  (let [presets-str (if (seq presets)
                      (str "'(" (str/join " " (map pr-str presets)) ")")
                      "nil")
        code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-spawn {:name %s :presets %s :cwd %s :role %s}))"
                     (if name (pr-str name) "nil")
                     presets-str
                     (if cwd (pr-str cwd) "nil")
                     (if role (pr-str role) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: swarm_dispatch
;; =============================================================================

(def swarm-dispatch-spec
  {:name "swarm_dispatch"
   :description "Dispatch a prompt to a swarm slave."
   :schema {:type "object"
            :properties {:slave_id {:type "string" :description "Slave ID to dispatch to"}
                         :prompt {:type "string" :description "Prompt to send"}
                         :timeout_ms {:type "integer" :description "Timeout in ms"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["slave_id" "prompt"]}})

(defn swarm-dispatch [{:keys [slave_id prompt timeout_ms port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-dispatch {:slave_id %s :prompt %s :timeout_ms %s}))"
                     (pr-str slave_id) (pr-str prompt)
                     (if timeout_ms (str timeout_ms) "nil"))]
    (emacs-eval code :port port :timeout_ms (or timeout_ms 60000))))

;; =============================================================================
;; Tool: swarm_collect
;; =============================================================================

(def swarm-collect-spec
  {:name "swarm_collect"
   :description "Collect results from a dispatched task."
   :schema {:type "object"
            :properties {:task_id {:type "string" :description "Task ID to collect"}
                         :timeout_ms {:type "integer" :description "Timeout in ms"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["task_id"]}})

(defn swarm-collect [{:keys [task_id timeout_ms port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-collect {:task_id %s :timeout_ms %s}))"
                     (pr-str task_id)
                     (if timeout_ms (str timeout_ms) "nil"))]
    (emacs-eval code :port port :timeout_ms (or timeout_ms 60000))))

;; =============================================================================
;; Tool: swarm_list_presets
;; =============================================================================

(def swarm-list-presets-spec
  {:name "swarm_list_presets"
   :description "List available swarm presets."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn swarm-list-presets [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.swarm :as s])
                  (s/handle-swarm-list-presets {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: swarm_kill
;; =============================================================================

(def swarm-kill-spec
  {:name "swarm_kill"
   :description "Kill a swarm slave."
   :schema {:type "object"
            :properties {:slave_id {:type "string" :description "Slave ID to kill"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["slave_id"]}})

(defn swarm-kill [{:keys [slave_id port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-kill {:slave_id %s}))"
                     (pr-str slave_id))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: swarm_broadcast
;; =============================================================================

(def swarm-broadcast-spec
  {:name "swarm_broadcast"
   :description "Broadcast a prompt to all active swarm slaves."
   :schema {:type "object"
            :properties {:prompt {:type "string" :description "Prompt to broadcast"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["prompt"]}})

(defn swarm-broadcast [{:keys [prompt port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-broadcast {:prompt %s}))"
                     (pr-str prompt))]
    (emacs-eval code :port port :timeout_ms 60000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

;; =============================================================================
;; Tool: swarm_pending_prompts
;; =============================================================================

(def swarm-pending-prompts-spec
  {:name "swarm_pending_prompts"
   :description "Get list of pending prompts from slaves awaiting human decision. Only relevant when emacs-mcp-swarm-prompt-mode is 'human'."
   :schema {:type "object"
            :properties {:port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn swarm-pending-prompts [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools.swarm :as s])
                  (s/handle-swarm-pending-prompts {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: swarm_respond_prompt
;; =============================================================================

(def swarm-respond-prompt-spec
  {:name "swarm_respond_prompt"
   :description "Send a response to a pending prompt from a specific slave. Use to answer permission prompts when prompt-mode is 'human'."
   :schema {:type "object"
            :properties {:slave_id {:type "string" :description "ID of the slave whose prompt to respond to"}
                         :response {:type "string" :description "Response to send (e.g., 'y', 'n', or custom text)"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["slave_id" "response"]}})

(defn swarm-respond-prompt [{:keys [slave_id response port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-swarm-respond-prompt {:slave_id %s :response %s}))"
                     (pr-str slave_id) (pr-str response))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: jvm_cleanup
;; =============================================================================

(def jvm-cleanup-spec
  {:name "jvm_cleanup"
   :description "Find and optionally kill orphaned JVM processes. Uses true orphan detection."
   :schema {:type "object"
            :properties {:min_age_minutes {:type "integer" :description "Minimum age in minutes (default: 30)"}
                         :dry_run {:type "boolean" :description "Only report without killing (default: true)"}
                         :keep_types {:type "array" :items {:type "string"} :description "JVM types to protect"}
                         :swarm_only {:type "boolean" :description "Only consider swarm-spawned processes"}
                         :true_orphans_only {:type "boolean" :description "Only kill truly orphaned processes (default: true)"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn jvm-cleanup [{:keys [min_age_minutes dry_run keep_types swarm_only true_orphans_only port]}]
  (let [keep-types-str (if (seq keep_types)
                         (str "[" (str/join " " (map pr-str keep_types)) "]")
                         "nil")
        code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-jvm-cleanup {:min_age_minutes %s :dry_run %s :keep_types %s :swarm_only %s :true_orphans_only %s}))"
                     (if min_age_minutes (str min_age_minutes) "nil")
                     (if (some? dry_run) (str dry_run) "nil")
                     keep-types-str
                     (if (some? swarm_only) (str swarm_only) "nil")
                     (if (some? true_orphans_only) (str true_orphans_only) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: resource_guard
;; =============================================================================

(def resource-guard-spec
  {:name "resource_guard"
   :description "Check system resources and automatically clean up orphaned JVMs if memory is high. Use BEFORE spawning new Claude swarm slaves."
   :schema {:type "object"
            :properties {:ram_threshold {:type "integer" :description "Percentage threshold for high memory (default: 80)"}
                         :min_available_mb {:type "integer" :description "Minimum available RAM in MB (default: 2048)"}
                         :auto_cleanup {:type "boolean" :description "Auto-run jvm_cleanup when high (default: true)"}
                         :cleanup_dry_run {:type "boolean" :description "Whether to actually kill orphans (default: false)"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn resource-guard [{:keys [ram_threshold min_available_mb auto_cleanup cleanup_dry_run port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.swarm :as s])
                         (s/handle-resource-guard {:ram_threshold %s :min_available_mb %s :auto_cleanup %s :cleanup_dry_run %s}))"
                     (if ram_threshold (str ram_threshold) "nil")
                     (if min_available_mb (str min_available_mb) "nil")
                     (if (some? auto_cleanup) (str auto_cleanup) "nil")
                     (if (some? cleanup_dry_run) (str cleanup_dry_run) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

(def tools
  [{:spec swarm-status-spec :handler swarm-status}
   {:spec swarm-spawn-spec :handler swarm-spawn}
   {:spec swarm-dispatch-spec :handler swarm-dispatch}
   {:spec swarm-collect-spec :handler swarm-collect}
   {:spec swarm-list-presets-spec :handler swarm-list-presets}
   {:spec swarm-kill-spec :handler swarm-kill}
   {:spec swarm-broadcast-spec :handler swarm-broadcast}
   {:spec swarm-pending-prompts-spec :handler swarm-pending-prompts}
   {:spec swarm-respond-prompt-spec :handler swarm-respond-prompt}
   {:spec jvm-cleanup-spec :handler jvm-cleanup}
   {:spec resource-guard-spec :handler resource-guard}])
