(ns bb-mcp.tools.route-map
  "The MCP `instructions` this server sends at initialize: one line per
   advertised tool, naming the area it owns.

   A client that defers tool schemas shows the model only tool NAMES until it
   loads one, and a name such as `cloak` does not say it owns memory sealing.
   Server instructions are shown even when every schema is deferred, so they
   are where a model learns which tool to load. Each line is the lead sentence
   of the tool's own description: one source, nothing to keep in step by hand.
   Off when BB_MCP_INSTRUCTIONS is \"off\"."
  (:require [clojure.string :as str]))

(def env-var
  "Environment variable that turns the instructions off with \"off\"."
  "BB_MCP_INSTRUCTIONS")

(def max-lead-chars
  "Longest lead sentence kept per tool, so the whole map stays small."
  200)

(def header
  "The paragraph before the per-tool lines."
  (str "hive tools, one per area. Most take a `command` and answer command=\"help\" with their verbs. "
       "A client that defers tool schemas must load a tool before calling it "
       "(Claude Code: ToolSearch \"select:mcp__<server>__<tool>\"). Pick the tool by area:"))

(defn- clip
  "TEXT cut to at most N characters at a word boundary, with an ellipsis. Pure."
  [text n]
  (if (<= (count text) n)
    text
    (let [cut (subs text 0 n)
          sp  (str/last-index-of cut " ")]
      (str (if (and sp (pos? sp)) (subs cut 0 sp) cut) "…"))))

(defn lead-sentence
  "The first sentence of DESCRIPTION (through its first \". \" or newline),
   trimmed and clipped to `max-lead-chars`. nil when blank. Pure."
  [description]
  (when-not (str/blank? description)
    (let [text  (str/trim description)
          ends  (keep identity [(some-> (str/index-of text ". ") inc)
                                (str/index-of text "\n")])
          end   (if (seq ends) (apply min ends) (count text))]
      (clip (str/trim (subs text 0 end)) max-lead-chars))))

(defn route-lines
  "One \"- name: lead sentence\" line per tool SPEC ({:name :description}),
   in the given order. A spec without a description gets its name alone. Pure."
  [specs]
  (for [{:keys [name description]} specs
        :when (not (str/blank? name))]
    (if-let [lead (lead-sentence description)]
      (str "- " name ": " lead)
      (str "- " name))))

(defn instructions
  "The instructions text for tool SPECS, or nil when there are none. Pure."
  [specs]
  (let [lines (route-lines specs)]
    (when (seq lines)
      (str/join "\n" (cons header lines)))))

(defn enabled?
  "Whether an environment VALUE leaves the instructions on. Pure."
  [value]
  (not= "off" (some-> value str/trim str/lower-case)))

(defn from-env
  "`instructions` for SPECS unless BB_MCP_INSTRUCTIONS turns them off."
  [specs]
  (when (enabled? (System/getenv env-var))
    (instructions specs)))
