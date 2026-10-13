(ns bb-mcp.resources
  "Resource source boundary. Addons implement the optional
   hive-addon.resources/IAddonResources protocol, which lists descriptors with
   {:uri :name :mimeType :handler} maps. The handler accepts the requested URI
   and returns text or an MCP content map. Only active addons advertising
   :resources participate. The JVM owns the handlers; this client carries
   only data across the nREPL boundary. The hive-addon.resources/resources
   accessor validates and lists optional contributions; read-resource gates
   reads to advertised URIs. Legacy hosts without the protocol list nothing.

   URI policy, applied here before anything crosses the socket and again in
   the host form: only workflow:// and verb://, no parent segment or encoded
   path separator, and a read answers only a URI the listing holds."
  (:require [bb-mcp.tools.nrepl :as nrepl]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defprotocol ResourceSource
  (list-resources [source] "Public MCP descriptors; handlers stay at the source.")
  (read-resource [source uri] "Read URI content, or nil if not found."))

(def schemes
  "The only URI schemes this server lists or reads."
  ["workflow://" "verb://"])

(def ^:private forbidden
  "Lower-cased fragments no served URI may contain: a parent segment, a
   backslash, and the percent-encodings of dot, slash, backslash and NUL."
  [".." "\\" "%2e" "%2f" "%5c" "%00"])

(defn allowed-uri?
  "True when `uri` is a string in one of `schemes` with something after the
   scheme, free of every `forbidden` fragment, whitespace and control
   characters. Anything else is never sent to the host."
  [uri]
  (and (string? uri)
       (boolean (some #(and (str/starts-with? uri %) (< (count %) (count uri)))
                      schemes))
       (let [lower (str/lower-case uri)]
         (not-any? #(str/includes? lower %) forbidden))
       (not-any? #(or (<= (int %) 32) (= 127 (int %))) uri)))

(def uri-policy-form
  "`allowed-uri?` as a form the host evaluates, so the JVM applies the same
   policy to what its addons declare. Built from the same `schemes` and
   `forbidden` data; the policy test holds the two verdicts equal."
  `(fn [uri#]
     (and (string? uri#)
          (boolean (some (fn [scheme#]
                           (and (clojure.string/starts-with? uri# scheme#)
                                (< (count scheme#) (count uri#))))
                         ~schemes))
          (not-any? (fn [bad#]
                      (clojure.string/includes? (clojure.string/lower-case uri#) bad#))
                    ~forbidden)
          (not-any? (fn [c#] (or (<= (int c#) 32) (= 127 (int c#)))) uri#))))

(defn- hive-eval
  "Evaluate a data-built form in the hive host. URI is printed, never code.
   The port comes from `port-fn`, by default the configured nREPL port
   (BB_MCP_NREPL_PORT, then .nrepl-port, then 7910)."
  ([form] (hive-eval form {}))
  ([form {:keys [eval-fn port-fn]
          :or {eval-fn nrepl/eval-code port-fn nrepl/get-nrepl-port}}]
   (let [{:keys [result error?]} (eval-fn {:port (port-fn)
                                           :code (pr-str `(pr-str ~form))
                                           :timeout-ms 10000})]
     (if error?
       (throw (ex-info "Resource source unavailable" {:cause result}))
       (edn/read-string (edn/read-string result))))))

(def ^:private listing-form
  "Host form: every [addon descriptor] pair an active addon advertising
   :resources declares and the policy allows; nil on a host without the
   optional contract. List and read are both built on it, so a read can only
   reach a URI a list shows."
  `(let [accessor# (try (requiring-resolve 'hive-addon.resources/resources)
                        (catch java.io.FileNotFoundException _# nil))
         allowed?# ~uri-policy-form]
     (when accessor#
       (->> ((requiring-resolve 'hive-mcp.addons.core/list-addons))
            (filter (fn [entry#] (and (= :active (:state entry#))
                                      (contains? (:capabilities entry#) :resources))))
            (mapcat (fn [entry#]
                      (let [addon# ((requiring-resolve 'hive-mcp.addons.core/get-addon)
                                    (:name entry#))]
                        (->> (accessor# addon#)
                             (filter (fn [r#] (and (allowed?# (:uri r#))
                                                   (string? (:name r#)))))
                             (map (fn [r#] [addon# r#]))))))))))

(def ^:private list-form
  "Host form: the public descriptors of `listing-form`."
  `(mapv (fn [pair#] (select-keys (second pair#) [:uri :name :description :mimeType]))
         ~listing-form))

(defn- read-form
  "Host form: the content of `uri` when `listing-form` holds it, else nil."
  [uri]
  `(let [uri# ~uri
         reader# (try (requiring-resolve 'hive-addon.resources/read-resource)
                      (catch java.io.FileNotFoundException _# nil))]
     (when reader#
       (some (fn [pair#]
               (when (= uri# (:uri (second pair#)))
                 (when-some [value# (reader# (first pair#) uri#)]
                   (if (map? value#)
                     (assoc value# :uri uri#)
                     {:uri uri# :text (str value#)}))))
             ~listing-form))))

(defrecord HiveResourceSource [evaluate]
  ResourceSource
  (list-resources [_]
    (filterv (fn [r] (and (map? r) (allowed-uri? (:uri r)) (string? (:name r))))
             (evaluate list-form)))
  (read-resource [_ uri]
    (when (allowed-uri? uri)
      (when-some [content (evaluate (read-form uri))]
        (when (= uri (:uri content))
          content)))))

(def ^:dynamic *source*
  "Port for resource lookup; bind to an in-memory stub in tests."
  (->HiveResourceSource hive-eval))
