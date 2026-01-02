(ns bb-mcp.tools.emacs.core
  "Core helpers for emacs-mcp integration.

   Provides shared functionality for evaluating code via nREPL
   and wrapping emacsclient calls."
  (:require [bb-mcp.tools.nrepl :as nrepl]))

(defn emacs-eval
  "Evaluate code that uses emacs-mcp functions via shared nREPL."
  [code & {:keys [port timeout_ms]}]
  (nrepl/execute {:code code
                  :port port
                  :timeout_ms (or timeout_ms 30000)}))

(defn wrap-emacs-call
  "Wrap an emacsclient call with require and error handling."
  [elisp-code]
  (str "(do (require '[emacs-mcp.emacsclient :as ec])"
       "    (ec/eval-elisp! " (pr-str elisp-code) "))"))
