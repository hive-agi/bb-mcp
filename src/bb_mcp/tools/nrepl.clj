(ns bb-mcp.tools.nrepl
  "nREPL client for delegating Clojure eval to shared JVM."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.net Socket]
           [java.io BufferedReader InputStreamReader PrintWriter]))

;; Bencode implementation for nREPL protocol
(declare bencode)

(defn- bencode-string [s]
  (str (count s) ":" s))

(defn- bencode-int [n]
  (str "i" n "e"))

(defn- bencode-list [coll]
  (str "l" (str/join "" (map bencode coll)) "e"))

(defn- bencode-dict [m]
  (str "d"
       (str/join ""
                 (mapcat (fn [[k v]]
                           [(bencode-string (name k)) (bencode v)])
                         (sort-by (comp str key) m)))
       "e"))

(defn bencode [x]
  (cond
    (string? x) (bencode-string x)
    (integer? x) (bencode-int x)
    (map? x) (bencode-dict x)
    (sequential? x) (bencode-list x)
    :else (bencode-string (str x))))

(defn- read-bencode-string [^BufferedReader reader length]
  (let [sb (StringBuilder.)]
    (dotimes [_ length]
      (.append sb (char (.read reader))))
    (str sb)))

(defn- read-bencode-int [^BufferedReader reader]
  (loop [sb (StringBuilder.)]
    (let [c (char (.read reader))]
      (if (= c \e)
        (parse-long (str sb))
        (recur (.append sb c))))))

(declare bdecode)

(defn- read-bencode-list [^BufferedReader reader]
  (loop [result []]
    (let [c (char (.read reader))]
      (if (= c \e)
        result
        (do
          (.unread reader (int c))
          (recur (conj result (bdecode reader))))))))

(defn- read-bencode-dict [^BufferedReader reader]
  (loop [result {}]
    (let [c (char (.read reader))]
      (if (= c \e)
        result
        (do
          (.unread reader (int c))
          (let [k (bdecode reader)
                v (bdecode reader)]
            (recur (assoc result (keyword k) v))))))))

(defn bdecode [^BufferedReader reader]
  (let [c (char (.read reader))]
    (cond
      (= c \i) (read-bencode-int reader)
      (= c \l) (read-bencode-list reader)
      (= c \d) (read-bencode-dict reader)
      (Character/isDigit c)
      (let [length-str (loop [sb (StringBuilder. (str c))]
                         (let [nc (char (.read reader))]
                           (if (= nc \:)
                             (str sb)
                             (recur (.append sb nc)))))]
        (read-bencode-string reader (parse-long length-str)))
      :else nil)))

;; nREPL client
(defn eval-code
  "Evaluate Clojure code on a remote nREPL server."
  [{:keys [port host code timeout-ms]}]
  (let [host (or host "localhost")
        timeout (or timeout-ms 30000)]
    (try
      (with-open [socket (doto (Socket. host port)
                           (.setSoTimeout timeout))
                  out (PrintWriter. (.getOutputStream socket) true)
                  in (java.io.PushbackReader.
                      (BufferedReader.
                       (InputStreamReader. (.getInputStream socket))))]
        ;; Send eval request
        (.print out (bencode {:op "eval" :code code}))
        (.flush out)

        ;; Read responses until "done" status
        (loop [results {:value nil :out "" :err ""}]
          (if-let [response (bdecode in)]
            (let [status (get response :status [])]
              (if (some #(= % "done") status)
                (if (:ex response)
                  {:result (str "Error: " (:err results) "\n" (:value response))
                   :error? true}
                  {:result (or (:value results) (:out results) "nil")
                   :error? false})
                (recur (cond-> results
                         (:value response) (assoc :value (:value response))
                         (:out response) (update :out str (:out response))
                         (:err response) (update :err str (:err response))))))
            {:result "No response from nREPL"
             :error? true})))
      (catch Exception e
        {:result (str "nREPL connection failed: " (ex-message e))
         :error? true}))))

(def tool-spec
  {:name "clojure_eval"
   :description "Evaluate Clojure code on a shared nREPL server.

Examples:
- clojure_eval(code: \"(+ 1 2)\")
- clojure_eval(code: \"(require '[my.ns])\", port: 7888)"
   :schema {:type "object"
            :properties {:code {:type "string"
                                :description "Clojure code to evaluate"}
                         :port {:type "integer"
                                :description "nREPL port (default from .nrepl-port)"}
                         :timeout_ms {:type "integer"
                                      :description "Timeout in ms (default: 30000)"}}
            :required ["code"]}})

(defn find-nrepl-port
  "Find nREPL port from .nrepl-port file."
  [dir]
  (let [port-file (str dir "/.nrepl-port")]
    (when (.exists (java.io.File. port-file))
      (parse-long (str/trim (slurp port-file))))))

(defn execute
  "Execute Clojure code via nREPL."
  [{:keys [code port timeout_ms]}]
  (let [port (or port (find-nrepl-port ".") 7888)]
    (eval-code {:port port
                :code code
                :timeout-ms (or timeout_ms 30000)})))
