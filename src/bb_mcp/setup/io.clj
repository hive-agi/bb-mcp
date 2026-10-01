(ns bb-mcp.setup.io
  "The boundary of `bb-mcp setup`: reading observed state and applying ops.
   Nothing here decides anything; bb-mcp.setup.model and .clients plan."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Collect

(defn read-file
  "Contents of the regular file at `path`, else nil."
  [path]
  (when (fs/regular-file? path)
    (slurp (str path))))

(defn which
  "Absolute path of `bin` on PATH, else nil."
  [bin]
  (some-> (fs/which bin) str))

(defn observe-anchor
  "{:kind :absent|:symlink|:dir|:file, :target, :resolved, :dir?, :launcher-exec?}."
  [{:keys [anchor launcher]}]
  (let [link? (fs/sym-link? anchor)
        exists? (fs/exists? anchor)]
    (cond-> {:kind (cond link? :symlink
                         (fs/directory? anchor) :dir
                         exists? :file
                         :else :absent)
             :dir? (fs/directory? anchor)
             :launcher-exec? (and (fs/regular-file? launcher) (fs/executable? launcher))}
      link? (assoc :target (str (fs/read-link anchor)))
      exists? (assoc :resolved (str (fs/real-path anchor))))))

(defn observe
  "Read what `spec` names: {:files {path text} :dirs {path bool} :bins {name path}}."
  [{:keys [which]} {:keys [files dirs bins]}]
  {:files (into {} (map (juxt identity read-file)) (distinct files))
   :dirs  (into {} (map (juxt identity fs/directory?)) (distinct dirs))
   :bins  (into {} (map (juxt identity which)) (distinct bins))})

(defn port-answers?
  "True when something accepts a TCP connection on localhost:`port`."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 500)
      true)
    ;; A refused or timed-out connect IS the answer ("nothing listens");
    ;; the caller reports it as a warning row.
    (catch Exception _ false)))

(defn exec!
  "Run `argv`; {:exit :out :err}."
  [argv]
  (try
    (p/sh argv)
    (catch Exception e {:exit 127 :out "" :err (ex-message e)})))

;; ---------------------------------------------------------------------------
;; Apply

(defmulti apply-op!
  "Perform one planned op; throws on failure."
  (fn [_sys [op]] op))

(defmethod apply-op! :mkdirs [_ [_ path]]
  (fs/create-dirs path))

(defmethod apply-op! :symlink [_ [_ path target]]
  (fs/create-sym-link path target))

(defmethod apply-op! :delete-link [_ [_ path]]
  (when (fs/sym-link? path)
    (fs/delete path)))

(defmethod apply-op! :backup [_ [_ path dest]]
  (when-not (fs/exists? dest)
    (fs/copy path dest)))

(defmethod apply-op! :write-file [_ [_ path content]]
  (some-> (fs/parent path) fs/create-dirs)
  (spit (str path) content))

(defmethod apply-op! :exec [{:keys [exec]} [_ argv {:keys [ignore-failure?]}]]
  (let [{:keys [exit out err]} (exec argv)]
    (when-not (or ignore-failure? (zero? exit))
      (throw (ex-info (str (str/join " " argv) " exited " exit ": " (str/trim (str err " " out)))
                      {:argv argv :exit exit})))))

(defn run-ops!
  "Apply `ops` in order; nil on success, else the first failure's message."
  [sys ops]
  (try
    (doseq [op ops] (apply-op! sys op))
    nil
    (catch Exception e
      (or (ex-message e) (str e)))))
