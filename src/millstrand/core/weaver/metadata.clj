(ns millstrand.core.weaver.metadata
  "Publish, read, and clean up weaver runtime metadata files."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [millstrand.core.weaver.protocol :as protocol])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files StandardCopyOption]
           [java.util UUID]))

(def ^:private json-file-name "weaver.json")
(def ^:private edn-file-name "weaver.edn")
(def ^:private socket-file-name "weaver.sock")
(def ^:private artifact-monitor (Object.))
(def ^:private pre-publication-claims (atom {}))

(defn- release-pre-publication-claim-unlocked!
  [world claim]
  (let [state-dir (:state-dir world)
        token @claim]
    (when (identical? token (get @pre-publication-claims state-dir))
      (swap! pre-publication-claims dissoc state-dir))
    (reset! claim nil)))

(defn claim-pre-publication-artifacts!
  "Claim `world`'s socket artifacts for one local startup attempt.

  Call immediately before binding the socket. Pass a local `claim` atom, which
  is released after publication or by `rollback-pre-publication-artifacts!`."
  [world claim]
  (locking artifact-monitor
    (let [state-dir (:state-dir world)]
      (when (get @pre-publication-claims state-dir)
        (throw (ex-info "Weaver startup already owns the world socket before publication"
                        {:state-dir state-dir})))
      (let [token (Object.)]
        (swap! pre-publication-claims assoc state-dir token)
        (reset! claim token)))))

(defn release-pre-publication-artifacts!
  "Release `claim` after its startup has published metadata successfully."
  [world claim]
  (locking artifact-monitor
    (release-pre-publication-claim-unlocked! world claim)))

(defn canonical-db-path
  "Return the canonical filesystem path for `db-file`."
  [db-file]
  (.getPath (.getCanonicalFile (io/file db-file))))

(defn metadata-file
  "Return the EDN metadata file for `world`."
  ^java.io.File [world]
  (io/file (:state-dir world) edn-file-name))

(defn json-metadata-file
  "Return the JSON metadata file for `world`."
  ^java.io.File [world]
  (io/file (:state-dir world) json-file-name))

(defn socket-file
  "Return the Unix-domain socket file for `world`."
  ^java.io.File [world]
  (io/file (:state-dir world) socket-file-name))

(defn new-nonce
  "Return a fresh weaver identity nonce."
  []
  (str (UUID/randomUUID)))

(def ^:private storage-kinds #{:sqlite-file :sqlite-memory})

(defn- require-storage-identity!
  "Fail loudly unless storage kind, label, and path are mutually consistent."
  [{:keys [storage-kind storage-label canonical-db-path]}]
  (when-not (contains? storage-kinds storage-kind)
    (throw (ex-info "Unknown weaver storage kind" {:storage-kind storage-kind})))
  (when (str/blank? storage-label)
    (throw (ex-info "Weaver storage label must not be blank" {:storage-label storage-label})))
  (case storage-kind
    :sqlite-file (when-not (= storage-label canonical-db-path)
                   (throw (ex-info "File storage label must be the canonical database path"
                                   {:storage-label storage-label :canonical-db-path canonical-db-path})))
    :sqlite-memory (when (some? canonical-db-path)
                     (throw (ex-info "In-memory storage must not publish a database path"
                                     {:canonical-db-path canonical-db-path}))))
  nil)

(defn metadata-shape
  "Return the canonical EDN metadata map for a running weaver."
  [{:keys [pid host port storage-kind storage-label canonical-db-path nonce started-at world name]
    :as shape}]
  (let [socket-path (.getPath (socket-file world))
        name (or name (.getName (io/file (:config-dir world))))]
    (when (str/blank? name)
      (throw (ex-info "Weaver name must not be blank" {:name name})))
    (require-storage-identity! shape)
    {:pid pid
     :transport :nrepl
     :protocol-version protocol/version
     :endpoint {:host host :port port}
     :config-dir (:config-dir world)
     :name name
     :state-dir (:state-dir world)
     :data-dir (:data-dir world)
     :storage-kind storage-kind
     :storage-label storage-label
     :canonical-db-path canonical-db-path
     :nonce nonce
     :socket-path socket-path
     :started-at started-at}))

(defn- json-metadata-shape
  "Return the public JSON metadata shape consumed by non-Clojure clients."
  [metadata]
  {"protocol_version" protocol/version
   "pid" (:pid metadata)
   "weaver_id" (:nonce metadata)
   "config_dir" (:config-dir metadata)
   "state_dir" (:state-dir metadata)
   "name" (:name metadata)
   "data_dir" (:data-dir metadata)
   "database_kind" (name (:storage-kind metadata))
   "database_label" (:storage-label metadata)
   "database_path" (:canonical-db-path metadata)
   "socket_path" (:socket-path metadata)
   "started_at" (:started-at metadata)
   "nrepl" {"host" (get-in metadata [:endpoint :host])
            "port" (get-in metadata [:endpoint :port])}})

(defn- write-atomic!
  "Write pretty-printed EDN `data` to `file` via an atomic rename."
  [^java.io.File file data]
  (.mkdirs (.getParentFile file))
  (let [tmp (io/file (.getParentFile file) (str (.getName file) "." (new-nonce) ".tmp"))]
    (spit tmp (with-out-str (pp/pprint data)))
    (Files/move (.toPath tmp)
                (.toPath file)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn- write-raw-atomic!
  "Write raw string `content` to `file` via an atomic rename."
  [^java.io.File file content]
  (.mkdirs (.getParentFile file))
  (let [tmp (io/file (.getParentFile file) (str (.getName file) "." (new-nonce) ".tmp"))]
    (spit tmp content)
    (Files/move (.toPath tmp)
                (.toPath file)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn publish!
  "Publish EDN and JSON metadata files for `metadata`."
  [metadata]
  (let [world {:state-dir (:state-dir metadata)}
        file (metadata-file world)]
    (write-atomic! file metadata)
    (write-raw-atomic! (json-metadata-file world)
                       (json/write-str (json-metadata-shape metadata)))
    file))

(defn read-metadata
  "Read the EDN metadata map for `world`, returning nil when absent."
  [world]
  (let [file (metadata-file world)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn delete!
  "Delete metadata and socket files for `world`."
  [world]
  (locking artifact-monitor
    (let [primary (atom nil)]
      (doseq [^java.io.File file [(metadata-file world)
                                  (json-metadata-file world)
                                  (socket-file world)]]
        (try
          (Files/deleteIfExists (.toPath file))
          (catch Throwable t
            (if-let [first-failure @primary]
              (.addSuppressed ^Throwable first-failure t)
              (reset! primary t)))))
      (when-let [failure @primary]
        (throw failure))
      nil)))

(declare current?)

(defn delete-owned!
  "Delete discovery artifacts still owned by `expected`.

  Return true when the artifacts were removed. Teardown owns artifacts only
  after its metadata has been published with the matching nonce."
  [expected world]
  (locking artifact-monitor
    (let [actual (read-metadata world)]
      (when (current? expected actual)
        (delete! world)
        true))))

(defn rollback-pre-publication-artifacts!
  "Roll back artifacts owned by the active startup `claim`.

  Before publication, the claim authorizes deleting nil or partial metadata.
  After publication, the generation nonce is the sole ownership proof. A
  different nonce always belongs to another generation and is left intact."
  [expected world claim]
  (locking artifact-monitor
    (try
      (let [actual (read-metadata world)]
        (when (or (current? expected actual)
                  (and (nil? actual)
                       (identical? @claim
                                   (get @pre-publication-claims (:state-dir world)))))
          (delete! world)
          true))
      (finally
        (release-pre-publication-claim-unlocked! world claim)))))

(defn current?
  "Return true when `actual` is metadata published by `expected`.

  A nonce identifies one weaver generation. Callers read metadata and use this
  predicate before deleting generation-owned world artifacts."
  [expected actual]
  (let [nonce (:nonce expected)]
    (boolean (and nonce (= nonce (:nonce actual))))))

(defn pid-alive?
  "Return true when `pid` identifies a live OS process."
  [pid]
  (boolean (some-> (ProcessHandle/of (long pid)) ^ProcessHandle (.orElse nil) .isAlive)))

(defn- valid-pid? [pid]
  (and (integer? pid)
       (<= Long/MIN_VALUE pid Long/MAX_VALUE)))

(defn- valid-storage-identity?
  "Return true when storage kind, label, and path are mutually consistent."
  [{:keys [storage-kind storage-label canonical-db-path]}]
  (case storage-kind
    :sqlite-file (and (string? canonical-db-path)
                      (= storage-label canonical-db-path))
    :sqlite-memory (and (nil? canonical-db-path)
                        (string? storage-label)
                        (not (str/blank? storage-label)))
    false))

(defn valid-metadata?
  "Return true when `metadata` has the supported weaver runtime metadata shape."
  [metadata]
  (and (map? metadata)
       (valid-pid? (:pid metadata))
       (= :nrepl (:transport metadata))
       (= protocol/version (:protocol-version metadata))
       (string? (:config-dir metadata))
       (not (str/blank? (:name metadata)))
       (string? (:data-dir metadata))
       (string? (:socket-path metadata))
       (string? (get-in metadata [:endpoint :host]))
       (int? (get-in metadata [:endpoint :port]))
       (valid-storage-identity? metadata)
       (string? (:nonce metadata))))

(defn stale-or-missing?
  "Return true when metadata is absent, malformed, unsupported, or points at a dead process."
  [metadata]
  (not (and (valid-metadata? metadata)
            (pid-alive? (:pid metadata)))))
