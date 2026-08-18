(ns bb-mcp.config
  "Configuration management for bb-mcp."
  (:require [clojure.string :as str]))

(def default-config
  {:project-dir (System/getProperty "user.dir")
   :nrepl-port nil  ; Auto-detect from .nrepl-port
   :allowed-dirs []})

(defn find-nrepl-port
  "Find nREPL port from .nrepl-port file in project dir."
  [project-dir]
  (let [port-file (str project-dir "/.nrepl-port")]
    (when (.exists (java.io.File. port-file))
      (parse-long (str/trim (slurp port-file))))))

(defn load-config
  "Load configuration from environment and files."
  []
  (let [project-dir (or (System/getenv "BB_MCP_PROJECT_DIR")
                        (System/getProperty "user.dir"))
        nrepl-port (or (some-> (System/getenv "BB_MCP_NREPL_PORT") parse-long)
                       (find-nrepl-port project-dir))]
    {:project-dir project-dir
     :nrepl-port nrepl-port
     :allowed-dirs [project-dir]}))

(defonce ^:dynamic *config* (atom nil))

(defn init-config! []
  (reset! *config* (load-config)))

(defn get-config []
  (or @*config* (do (init-config!) @*config*)))
