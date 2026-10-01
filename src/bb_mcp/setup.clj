(ns bb-mcp.setup
  "`bb-mcp setup`: publish this checkout at the ANCHOR
   (~/.local/share/hive-mcp/bb-mcp) and register the `hive` MCP server with
   every detected client through it.

   Pipeline (CPPB): parse argv -> collect observed state (boundary) ->
   plan steps (pure: bb-mcp.setup.model, bb-mcp.setup.clients) -> execute
   ops (boundary, skipped under --dry-run) -> report rows.

   Usage: bb-mcp setup [--client claude|codex|all] [--scope user|project]
                       [--port N] [--dry-run] [--check] [--json]"
  (:require [babashka.fs :as fs]
            [bb-mcp.setup.clients :as clients]
            [bb-mcp.setup.io :as io]
            [bb-mcp.setup.model :as m]
            [cheshire.core :as json]
            [clojure.java.io :as jio]))

(defn checkout-dir
  "The real directory of the bb-mcp checkout this code was loaded from."
  []
  (some-> (jio/resource "bb_mcp/core.clj")
          (.toURI)
          fs/path
          fs/parent  ; src/bb_mcp
          fs/parent  ; src
          fs/parent  ; checkout
          fs/real-path
          str))

(defn- stamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
           (java.time.LocalDateTime/now)))

(defn system
  "The real world: this user's HOME, cwd, PATH, processes and clock."
  []
  {:home     (or (System/getenv "HOME") (System/getProperty "user.home"))
   :cwd      (str (fs/cwd))
   :checkout (checkout-dir)
   :getenv   #(System/getenv %)
   :which    io/which
   :exec     io/exec!
   :probe    io/port-answers?
   :now      stamp})

;; ---------------------------------------------------------------------------
;; Collect (boundary)

(defn- nrepl-state
  "The port the launcher would use from cwd, and whether it answers."
  [{:keys [getenv probe cwd]} opts]
  (let [port (m/resolve-port {:opt-port (:port opts)
                              :env-port (getenv m/port-var)
                              :port-file (io/read-file (str cwd "/.nrepl-port"))})]
    {:port port :answers? (boolean (probe port))}))

(defn collect
  "The planning context: options, paths and everything observed."
  [{:keys [home cwd checkout now] :as sys} opts]
  (let [layout (m/layout home)
        base {:home home :cwd cwd :checkout checkout :layout layout
              :opts opts :stamp (now)}
        ctx (assoc base
                   :anchor-obs (io/observe-anchor layout)
                   :obs (io/observe sys (clients/observation-spec base)))]
    (cond-> ctx
      (:check? opts) (assoc :nrepl (nrepl-state sys opts)))))

;; ---------------------------------------------------------------------------
;; Execute (boundary)

(defn execute!
  "Apply each :change step's ops (or mark it :planned under --dry-run)."
  [sys {:keys [opts]} steps]
  (mapv (fn [{:keys [status ops detail] :as s}]
          (cond
            (not= :change status) s
            (:dry-run? opts) (assoc s :status :planned)
            :else (if-let [err (io/run-ops! sys ops)]
                    (assoc s :status :failed :detail (str detail ": " err))
                    (assoc s :status :changed))))
        steps))

;; ---------------------------------------------------------------------------
;; Report

(defn- json-row [row]
  (cond-> row (:ops row) (update :ops #(mapv m/op-line %))))

(defn- heading [{:keys [checkout opts]}]
  (str "bb-mcp setup" (cond (:check? opts) " --check" (:dry-run? opts) " --dry-run" :else "")
       " (checkout: " checkout ")"))

(defn render
  "Rows as the table (or JSON under --json)."
  [ctx rows]
  (if (get-in ctx [:opts :json?])
    (json/generate-string (mapv json-row rows) {:pretty true})
    (str (heading ctx) "\n" (m/format-table rows))))

;; ---------------------------------------------------------------------------
;; Entry

(defn run
  "argv -> {:rows :exit :out}; every effect goes through `sys`."
  [sys argv]
  (let [{:keys [opts error help]} (m/parse-args argv)]
    (cond
      help {:exit 0 :out help}
      error {:exit 2 :out error}
      (nil? (:checkout sys)) {:exit 2 :out "bb-mcp setup: cannot locate this bb-mcp checkout"}
      :else (let [ctx (collect sys opts)
                  rows (if (:check? opts)
                         (clients/check-rows ctx)
                         (execute! sys ctx (clients/setup-steps ctx)))]
              {:rows rows :exit (m/exit-code rows) :out (render ctx rows)}))))

(defn -main [& argv]
  (let [{:keys [out exit]} (run (system) argv)]
    (println out)
    (System/exit exit)))
