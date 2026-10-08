(ns bb-mcp.resources
  "Resource source boundary. Addons declare :resources as a sequence of
   {:uri :name :mimeType :handler} maps on their IAddon instance. The handler
   accepts the requested URI and returns text or an MCP content map.
   Only active addons advertising :resources participate. The JVM owns the
   handlers; this client carries only data across the nREPL boundary."
  (:require [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]))

(defprotocol ResourceSource
  (list-resources [source] "Public MCP descriptors; handlers stay at the source.")
  (read-resource [source uri] "Read URI content, or nil if not found."))

(defn- hive-eval
  "Evaluate a data-built form in the hive host. URI is printed, never code."
  [form]
  (let [{:keys [result error?]} (nrepl/eval-code {:port 7910
                                                  :code (pr-str `(pr-str ~form))
                                                  :timeout-ms 10000})]
    (if error?
      (throw (ex-info "Resource source unavailable" {:cause result}))
      (edn/read-string (edn/read-string result)))))

(defrecord HiveResourceSource []
  ResourceSource
  (list-resources [_]
    (hive-eval
     '(->> ((requiring-resolve 'hive-mcp.addons.core/list-addons))
           (filter (fn [entry] (and (= :active (:state entry))
                                    (contains? (:capabilities entry) :resources))))
           (mapcat (fn [entry]
                     (let [addon ((requiring-resolve 'hive-mcp.addons.core/get-addon)
                                  (:name entry))]
                       (map (fn [r] (select-keys r [:uri :name :description :mimeType]))
                            (:resources addon)))))
           (filter (fn [r] (and (string? (:uri r)) (string? (:name r)))))
           vec)))
  (read-resource [_ uri]
    (hive-eval
     `(let [uri# ~uri]
        (some (fn [entry#]
                (when (and (= :active (:state entry#))
                           (contains? (:capabilities entry#) :resources))
                  (let [addon# ((requiring-resolve 'hive-mcp.addons.core/get-addon)
                                (:name entry#))]
                    (when-let [r# (some (fn [r#] (when (= uri# (:uri r#)) r#))
                                        (:resources addon#))]
                      (when-let [handler# (:handler r#)]
                        (let [value# (handler# uri#)]
                          (if (map? value#)
                            (merge {:uri uri#} value#)
                            {:uri uri# :text (str value#)})))))))
              ((requiring-resolve 'hive-mcp.addons.core/list-addons)))))))

(def ^:dynamic *source*
  "Port for resource lookup; bind to an in-memory stub in tests."
  (->HiveResourceSource))
