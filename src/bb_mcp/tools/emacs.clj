(ns bb-mcp.tools.emacs
  "Emacs tools that delegate to emacs-mcp via shared nREPL.

   This module aggregates all domain-specific tool modules following
   DDD bounded contexts. Each module handles ONE domain:
   - buffer: Buffer operations, file handling, elisp evaluation
   - magit: Git operations via Magit
   - memory: Project memory CRUD
   - kanban: Kanban task management
   - swarm: Swarm agent orchestration
   - projectile: Project navigation
   - org: Org-mode operations
   - prompt: Prompt capture and search
   - cider: CIDER REPL integration
   - context: Full context aggregation"
  (:require [bb-mcp.tools.emacs.core :as core]
            [bb-mcp.tools.emacs.buffer :as buffer]
            [bb-mcp.tools.emacs.magit :as magit]
            [bb-mcp.tools.emacs.memory :as memory]
            [bb-mcp.tools.emacs.kanban :as kanban]
            [bb-mcp.tools.emacs.swarm :as swarm]
            [bb-mcp.tools.emacs.projectile :as projectile]
            [bb-mcp.tools.emacs.org :as org]
            [bb-mcp.tools.emacs.prompt :as prompt]
            [bb-mcp.tools.emacs.cider :as cider]
            [bb-mcp.tools.emacs.context :as context]))

;; Re-export core helpers for backwards compatibility
(def emacs-eval core/emacs-eval)
(def wrap-emacs-call core/wrap-emacs-call)

;; =============================================================================
;; Aggregated Tools Vector
;; =============================================================================

(def tools
  "All emacs-mcp tools aggregated from domain modules."
  (vec (concat buffer/tools
               magit/tools
               memory/tools
               kanban/tools
               swarm/tools
               projectile/tools
               org/tools
               prompt/tools
               cider/tools
               context/tools)))
