(ns bb-mcp.tools.emacs.magit
  "Magit/Git tools.

   This module provides tools for git operations via Emacs Magit."
  (:require [bb-mcp.tools.emacs.core :as core]
            [clojure.string :as str]))

(def ^:private emacs-eval core/emacs-eval)

;; =============================================================================
;; Tool: magit_status
;; =============================================================================

(def magit-status-spec
  {:name "magit_status"
   :description "Get comprehensive git status via Magit.

Returns branch, staged/unstaged/untracked files, ahead/behind counts."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-status [{:keys [port]}]
  (let [code "(do (require '[emacs-mcp.tools :as tools])
                  (tools/handle-magit-status {}))"]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: magit_branches
;; =============================================================================

(def magit-branches-spec
  {:name "magit_branches"
   :description "List git branches via Magit."
   :schema {:type "object"
            :properties {:directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-branches [{:keys [directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-branches {:directory %s}))"
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: magit_log
;; =============================================================================

(def magit-log-spec
  {:name "magit_log"
   :description "Get git log via Magit."
   :schema {:type "object"
            :properties {:count {:type "integer" :description "Number of commits (default: 10)"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-log [{:keys [count directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-log {:count %d :directory %s}))"
                     (or count 10) (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: magit_diff
;; =============================================================================

(def magit-diff-spec
  {:name "magit_diff"
   :description "Get git diff via Magit."
   :schema {:type "object"
            :properties {:target {:type "string" :description "Diff target (e.g., HEAD~1, branch name)"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-diff [{:keys [target directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-diff {:target %s :directory %s}))"
                     (if target (pr-str target) "nil")
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: magit_stage
;; =============================================================================

(def magit-stage-spec
  {:name "magit_stage"
   :description "Stage files for commit via Magit."
   :schema {:type "object"
            :properties {:files {:type "array" :items {:type "string"} :description "Files to stage (empty = all)"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-stage [{:keys [files directory port]}]
  (let [files-str (if (seq files)
                    (str "'(" (str/join " " (map pr-str files)) ")")
                    "nil")
        code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-stage {:files %s :directory %s}))"
                     files-str (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Tool: magit_commit
;; =============================================================================

(def magit-commit-spec
  {:name "magit_commit"
   :description "Create a git commit via Magit."
   :schema {:type "object"
            :properties {:message {:type "string" :description "Commit message"}
                         :all {:type "boolean" :description "Stage all changes before commit"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required ["message"]}})

(defn magit-commit [{:keys [message all directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-commit {:message %s :all %s :directory %s}))"
                     (pr-str message) (if all "t" "nil")
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 30000)))

;; =============================================================================
;; Tool: magit_push
;; =============================================================================

(def magit-push-spec
  {:name "magit_push"
   :description "Push commits via Magit."
   :schema {:type "object"
            :properties {:set_upstream {:type "boolean" :description "Set upstream tracking"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-push [{:keys [set_upstream directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-push {:set_upstream %s :directory %s}))"
                     (if set_upstream "t" "nil")
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: magit_pull
;; =============================================================================

(def magit-pull-spec
  {:name "magit_pull"
   :description "Pull changes via Magit."
   :schema {:type "object"
            :properties {:directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-pull [{:keys [directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-pull {:directory %s}))"
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: magit_fetch
;; =============================================================================

(def magit-fetch-spec
  {:name "magit_fetch"
   :description "Fetch from a remote via Magit."
   :schema {:type "object"
            :properties {:remote {:type "string" :description "Remote name (e.g., origin)"}
                         :directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-fetch [{:keys [remote directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-fetch {:remote %s :directory %s}))"
                     (if remote (pr-str remote) "nil")
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 60000)))

;; =============================================================================
;; Tool: magit_feature_branches
;; =============================================================================

(def magit-feature-branches-spec
  {:name "magit_feature_branches"
   :description "List feature branches in the repository."
   :schema {:type "object"
            :properties {:directory {:type "string" :description "Repository directory"}
                         :port {:type "integer" :description "nREPL port (default: 7910)"}}
            :required []}})

(defn magit-feature-branches [{:keys [directory port]}]
  (let [code (format "(do (require '[emacs-mcp.tools.magit :as m])
                         (m/handle-magit-feature-branches {:directory %s}))"
                     (if directory (pr-str directory) "nil"))]
    (emacs-eval code :port port :timeout_ms 15000)))

;; =============================================================================
;; Exported tools vector
;; =============================================================================

(def tools
  [{:spec magit-status-spec :handler magit-status}
   {:spec magit-branches-spec :handler magit-branches}
   {:spec magit-log-spec :handler magit-log}
   {:spec magit-diff-spec :handler magit-diff}
   {:spec magit-stage-spec :handler magit-stage}
   {:spec magit-commit-spec :handler magit-commit}
   {:spec magit-push-spec :handler magit-push}
   {:spec magit-pull-spec :handler magit-pull}
   {:spec magit-fetch-spec :handler magit-fetch}
   {:spec magit-feature-branches-spec :handler magit-feature-branches}])
