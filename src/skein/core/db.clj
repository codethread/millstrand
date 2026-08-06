(ns skein.core.db
  "SQLite persistence for strands, edges, relation metadata, graph queries, and
  weaver-owned scheduler wakes."
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util UUID]
           [org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteConfig$Pragma SQLiteConfig$TransactionMode SQLiteDataSource])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.transaction :as tx]
            [skein.core.query :as query]
            [skein.core.specs :as specs]))

(def ^:private default-db-file "millstrand.sqlite")

(def ^:private sqlite-mmap-size-bytes 268435456)
(def ^:private sqlite-cache-size-kib -20000)

(def ^:private id-alphabet "abcdefghijklmnopqrstuvwxyz0123456789")
(def ^:private id-length 5)
(def ^:private max-id-attempts 32)
(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn- generate-id
  "Return a random short strand id candidate."
  []
  (str/join
   (repeatedly id-length
               #(nth id-alphabet (.nextInt secure-random (count id-alphabet))))))

(defn- sqlite-config
  "Return the shared SQLite connection configuration for every storage kind."
  ^SQLiteConfig []
  (doto (SQLiteConfig.)
    ;; Concurrent writers (socket connections, event handlers, spool worker
    ;; threads) must queue on the lock rather than fail SQLITE_BUSY. IMMEDIATE
    ;; transactions take the write lock at BEGIN, where busy_timeout applies;
    ;; DEFERRED transactions upgrading mid-transaction fail fast by design.
    (.setBusyTimeout 5000)
    (.setTransactionMode SQLiteConfig$TransactionMode/IMMEDIATE)
    (.enforceForeignKeys true)
    (.setJournalMode SQLiteConfig$JournalMode/WAL)
    (.setPragma SQLiteConfig$Pragma/MMAP_SIZE (str sqlite-mmap-size-bytes))
    (.setPragma SQLiteConfig$Pragma/CACHE_SIZE (str sqlite-cache-size-kib))))

(defn- sqlite-data-source
  "Return a SQLite datasource for jdbc-url with the shared open-time pragmas."
  [jdbc-url]
  (doto (SQLiteDataSource. (sqlite-config))
    (.setUrl jdbc-url)))

(defn datasource
  "Create a SQLite datasource for db-file, creating parent directories first.

  With no argument, uses the internal default database filename."
  ([] (datasource default-db-file))
  ([db-file]
   (io/make-parents db-file)
   (sqlite-data-source (str "jdbc:sqlite:" db-file))))

(defn file-storage
  "Return a file-backed SQLite storage handle for db-file.

  A storage handle is the weaver runtime's storage identity: :storage-kind,
  :storage-label, :canonical-db-path (file storage only), a next.jdbc-compatible
  :connectable, and an optional :close-fn the owning runtime must call on stop.
  File-backed datasources open a connection per operation, so this handle has
  no :close-fn."
  [db-file]
  (let [canonical (.getPath (.getCanonicalFile (io/file db-file)))]
    {:storage-kind :sqlite-file
     :storage-label canonical
     :canonical-db-path canonical
     :connectable (datasource canonical)}))

(defn memory-storage
  "Return an in-memory SQLite storage handle backed by one held connection.

  Xerial keeps an in-memory database alive only while a connection anchors it,
  so the :connectable is a single held java.sql.Connection — a datasource would
  open a fresh connection per operation and lose the schema. :close-fn destroys
  the database; later use fails loudly with the driver's connection-closed
  error. One held connection makes this serialized trusted test storage, not
  production-like pooled storage."
  []
  (let [^java.sql.Connection conn (.createConnection (sqlite-config) "jdbc:sqlite::memory:")]
    {:storage-kind :sqlite-memory
     :storage-label (str "sqlite-memory:" (UUID/randomUUID))
     :canonical-db-path nil
     :connectable conn
     :close-fn #(.close conn)}))

(defn execute!
  "Execute SQL params against ds and return unqualified lower-case map rows."
  [ds sql-params]
  (jdbc/execute! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one!
  "Execute SQL params against ds and return one unqualified lower-case map row."
  [ds sql-params]
  (jdbc/execute-one! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- run-owned-transaction
  "Call f in a transaction owned by this operation.

  next.jdbc tracks nested transactions itself, including over raw SQLite
  connections used by memory storage."
  [connectable f]
  (binding [tx/*nested-tx* :ignore]
    (jdbc/with-transaction [tx connectable]
      (f tx))))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message {:value value :explain (s/explain-str spec value)})))
  value)

(defn- require-positive-limit! [limit]
  (require-valid! ::specs/read-limit limit "Read result limit must be a positive integer"))

(defn json-key
  "Render a map key as JSON object key text, preserving keyword/symbol namespaces.

  data.json's default write key-fn renders keywords via `name`, silently
  dropping the namespace, so `:workflow/role` and `:devflow/role` would collide
  as `\"role\"`. Rendering the full `ns/name` form keeps keyword keys a fixed
  point of the JSON round-trip (`<-json` keywordizes them back)."
  [k]
  (if (instance? clojure.lang.Named k)
    (if-let [key-ns (namespace k)]
      (str key-ns "/" (name k))
      (name k))
    (str k)))

(defn ->json
  "Encode an attribute map as JSON object text.

  Nil encodes as an empty JSON object. Invalid attribute values throw."
  [m]
  (require-valid! ::specs/attributes m "Attributes must be nil or a map that encodes to a JSON object")
  (json/write-str (or m {}) :key-fn json-key))

(defn <-json
  "Decode JSON object text into a keyword-keyed Clojure map.

  Nil decodes as an empty map."
  [s]
  (json/read-str (or s "{}") :key-fn keyword))

(defn- attr-value->json
  "Encode one attribute value as JSON text."
  [v]
  (json/write-str v :key-fn json-key))

(def ^:private schema-sql
  [["PRAGMA foreign_keys = ON"]
   ["CREATE TABLE IF NOT EXISTS strands (
       id TEXT PRIMARY KEY,
       title TEXT NOT NULL,
       state TEXT NOT NULL DEFAULT 'active',
       created_at TEXT NOT NULL DEFAULT (datetime('now')),
       updated_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (state IN ('active', 'closed', 'replaced'))
     )"]
   ["CREATE TABLE IF NOT EXISTS attributes (
       strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       key TEXT NOT NULL,
       value TEXT NOT NULL CHECK (json_valid(value)),
       archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
       PRIMARY KEY (strand_id, key)
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_attributes_key_value_hot ON attributes(key, value) WHERE archived = 0"]
   ["CREATE INDEX IF NOT EXISTS idx_attributes_strand_hot ON attributes(strand_id) WHERE archived = 0"]
   ["CREATE TABLE IF NOT EXISTS strand_edges (
       from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       edge_type TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_strand_edges_to ON strand_edges(to_strand_id, edge_type)"]
   ["CREATE UNIQUE INDEX IF NOT EXISTS idx_strand_edges_single_serves
       ON strand_edges(from_strand_id) WHERE edge_type = 'serves'"]
   ["CREATE TABLE IF NOT EXISTS acyclic_relations (
       relation TEXT PRIMARY KEY
     )"]
   ["CREATE TABLE IF NOT EXISTS immutable_keys (
       key TEXT PRIMARY KEY
     )"]
   ["CREATE TABLE IF NOT EXISTS scheduler_wakes (
       key TEXT PRIMARY KEY,
       wake_at INTEGER NOT NULL,
       handler TEXT NOT NULL,
       payload TEXT NOT NULL DEFAULT '{}',
       attempts INTEGER NOT NULL DEFAULT 0,
       created_at TEXT NOT NULL DEFAULT (datetime('now')),
       updated_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (json_valid(payload))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_scheduler_wakes_wake_at ON scheduler_wakes(wake_at)"]
   ["CREATE TABLE IF NOT EXISTS scheduler_history (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       key TEXT NOT NULL,
       wake_at INTEGER NOT NULL,
       handler TEXT NOT NULL,
       payload TEXT NOT NULL DEFAULT '{}',
       status TEXT NOT NULL,
       attempts INTEGER NOT NULL DEFAULT 0,
       error TEXT,
       recorded_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (status IN ('completed', 'cancelled', 'failed')),
       CHECK (json_valid(payload))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_scheduler_history_status ON scheduler_history(status, id)"]
   ["CREATE TABLE IF NOT EXISTS burn_history (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       strand_id TEXT NOT NULL,
       title TEXT NOT NULL,
       state TEXT NOT NULL,
       created_at TEXT NOT NULL,
       updated_at TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       edges TEXT NOT NULL DEFAULT '[]',
       recorded_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (json_valid(attributes)),
       CHECK (json_valid(edges))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_burn_history_strand ON burn_history(strand_id, id)"]])

(def ^:private current-generation 1)

;; Objects that arrived additively after the EAV-era core and flow into
;; existing worlds via IF NOT EXISTS without a generation bump: pre-DDL
;; screening permits their absence, everything else in the reference schema is
;; generation-1 baseline. The next generation bump folds these into its
;; baseline (SPEC-004.C91c/C91d).
(def ^:private additive-schema-objects
  #{[:index "idx_strand_edges_single_serves"]
    [:table "acyclic_relations"]
    [:table "immutable_keys"]
    [:table "scheduler_wakes"]
    [:index "idx_scheduler_wakes_wake_at"]
    [:table "scheduler_history"]
    [:index "idx_scheduler_history_status"]
    [:table "burn_history"]
    [:index "idx_burn_history_strand"]})

(def ^:private shipped-acyclic-relations #{"depends-on" "parent-of" "supersedes" "serves" "notes"})
(def ^:private shipped-immutable-keys #{"note/text" "note/at"})

(defn- classify-generation
  "Classify startup from the stored generation, binary generation, and emptiness."
  [found-generation binary-generation empty?]
  (when-not (and (int? found-generation)
                 (not (neg? found-generation))
                 (int? binary-generation)
                 (pos? binary-generation)
                 (boolean? empty?))
    (throw (ex-info "Schema generation classification requires non-negative found and positive binary generations plus boolean emptiness"
                    {:found-generation found-generation
                     :binary-generation binary-generation
                     :empty? empty?})))
  (cond
    (> found-generation binary-generation) :refuse-newer
    (and (zero? found-generation) empty?) :bootstrap
    (zero? found-generation) :adopt
    (< found-generation binary-generation) :refuse-older
    :else :proceed))

(defn- quote-sql-identifier [identifier]
  (str "\"" (str/replace identifier "\"" "\"\"") "\""))

(defn- normalize-sql-expression
  "Normalize insignificant case and whitespace outside SQL string literals."
  [expression]
  (loop [remaining (seq expression)
         quoted? false
         normalized (StringBuilder.)]
    (if-let [ch (first remaining)]
      (let [next-ch (second remaining)]
        (cond
          (and (= ch \') quoted? (= next-ch \'))
          (do (.append normalized ch)
              (.append normalized next-ch)
              (recur (nnext remaining) quoted? normalized))

          (= ch \')
          (do (.append normalized ch)
              (recur (next remaining) (not quoted?) normalized))

          (and (not quoted?) (Character/isWhitespace ^char ch))
          (recur (next remaining) quoted? normalized)

          :else
          (do (.append normalized (if quoted? ch (Character/toLowerCase ^char ch)))
              (recur (next remaining) quoted? normalized))))
      (str normalized))))

(defn- parenthesized-expressions-after
  "Return balanced parenthesized expressions following marker in SQL text."
  [sql marker]
  (let [^String sql (or sql "")
        matcher (re-matcher marker sql)]
    (loop [expressions []]
      (if (.find matcher)
        (let [start (.end matcher)
              expression
              (loop [idx start
                     depth 1
                     quoted? false]
                (when (>= idx (count sql))
                  (throw (ex-info "Unbalanced schema SQL expression" {:sql sql :offset start})))
                (let [ch (.charAt sql idx)
                      next-ch (when (< (inc idx) (count sql)) (.charAt sql (inc idx)))]
                  (cond
                    (and (= ch \') quoted? (= next-ch \'))
                    (recur (+ idx 2) depth quoted?)

                    (= ch \')
                    (recur (inc idx) depth (not quoted?))

                    quoted?
                    (recur (inc idx) depth quoted?)

                    (= ch \()
                    (recur (inc idx) (inc depth) quoted?)

                    (= ch \))
                    (if (= depth 1)
                      (subs sql start idx)
                      (recur (inc idx) (dec depth) quoted?))

                    :else
                    (recur (inc idx) depth quoted?))))]
          (recur (conj expressions expression)))
        expressions))))

(defn- schema-object-rows [ds]
  (->> (execute! ds ["SELECT type, name, tbl_name, sql
                      FROM sqlite_master
                      WHERE type IN ('table', 'index')"])
       (remove #(str/starts-with? (:name %) "sqlite_"))))

(defn- pragma-rows [ds pragma object-name]
  (execute! ds [(str "PRAGMA " pragma "(" (quote-sql-identifier object-name) ")")]))

(defn- normalize-column [{:keys [cid name type notnull dflt_value pk]}]
  {:position cid
   :name name
   :type (-> type str/trim str/upper-case)
   :not-null (pos? notnull)
   :default (some-> dflt_value normalize-sql-expression)
   :primary-key-position pk})

(defn- normalize-foreign-key [{:keys [id seq table from to on_update on_delete match]}]
  {:id id
   :position seq
   :table table
   :from from
   :to to
   :on-update on_update
   :on-delete on_delete
   :match match})

(defn- table-shape [ds {:keys [name sql]}]
  {:columns (mapv normalize-column (pragma-rows ds "table_info" name))
   :foreign-keys (->> (pragma-rows ds "foreign_key_list" name)
                      (map normalize-foreign-key)
                      (sort-by (juxt :id :position))
                      vec)
   :checks (->> (parenthesized-expressions-after sql #"(?i)\bCHECK\s*\(")
                (map normalize-sql-expression)
                sort
                vec)})

(defn- partial-index-predicate [sql]
  (some->> (re-find #"(?is)\bWHERE\b(.*)$" (or sql ""))
           second
           normalize-sql-expression))

(defn- index-shape [ds {:keys [name tbl_name sql]}]
  (let [index-listing (some #(when (= name (:name %)) %)
                            (pragma-rows ds "index_list" tbl_name))]
    {:table tbl_name
     :unique (pos? (:unique index-listing))
     :columns (->> (pragma-rows ds "index_info" name)
                   (sort-by :seqno)
                   (mapv #(select-keys % [:seqno :cid :name])))
     :predicate (partial-index-predicate sql)}))

(defn- schema-shapes [ds object-names]
  (into {}
        (keep (fn [{:keys [type name] :as row}]
                (let [object-key [(keyword type) name]]
                  (when (contains? object-names object-key)
                    [object-key
                     (case type
                       "table" (table-shape ds row)
                       "index" (index-shape ds row))]))))
        (schema-object-rows ds)))

(defn- reference-schema [schema-statements]
  (let [{:keys [connectable close-fn]} (memory-storage)]
    (try
      (doseq [statement schema-statements]
        (execute! connectable statement))
      (let [object-keys (set (map (juxt (comp keyword :type) :name)
                                  (schema-object-rows connectable)))]
        {:object-keys object-keys
         :shapes (schema-shapes connectable object-keys)})
      (finally
        (close-fn)))))

(defn- database-empty? [ds managed-object-keys]
  (not-any? managed-object-keys
            (map (juxt (comp keyword :type) :name)
                 (schema-object-rows ds))))

(defn- schema-mismatch! [mode found-generation expected-generation missing mismatched]
  (throw (ex-info "Database schema does not match the expected Skein schema"
                  {:reason :schema-mismatch
                   :mode mode
                   :found-generation found-generation
                   :expected-generation expected-generation
                   :missing-objects (vec (sort missing))
                   :mismatched-objects (vec (sort mismatched))})))

(defn- validate-schema!
  "Validate managed objects against reference in pre-DDL or full mode."
  [ds reference mode additive-objects found-generation expected-generation]
  (when-not (#{:pre-ddl :full} mode)
    (throw (ex-info "Unknown schema validation mode" {:mode mode})))
  (let [{:keys [object-keys shapes]} reference]
    (when-not (every? object-keys additive-objects)
      (throw (ex-info "Additive schema objects must belong to the reference schema"
                      {:additive-objects additive-objects
                       :reference-objects object-keys})))
    (let [actual (schema-shapes ds object-keys)
          required (if (= :pre-ddl mode)
                     (reduce disj object-keys additive-objects)
                     object-keys)
          missing (remove #(contains? actual %) required)
          mismatched (for [[object-key expected-shape] shapes
                           :let [actual-shape (get actual object-key)]
                           :when (and actual-shape (not= expected-shape actual-shape))]
                       object-key)]
      (when (or (seq missing) (seq mismatched))
        (schema-mismatch! mode found-generation expected-generation missing mismatched))))
  ds)

(defn- schema-generation [ds]
  (:user_version (execute-one! ds ["PRAGMA user_version"])))

(defn- stamp-generation! [ds generation]
  (execute! ds [(str "PRAGMA user_version = " generation)]))

(defn- incompatible-generation! [classification found-generation expected-generation]
  (case classification
    :refuse-newer
    (throw (ex-info "This Skein binary is too old for the database schema generation"
                    {:reason :newer-schema-generation
                     :found-generation found-generation
                     :expected-generation expected-generation}))

    :refuse-older
    (throw (ex-info "Database schema generation is older; use the maintained forward-migration path"
                    {:reason :older-schema-generation
                     :found-generation found-generation
                     :expected-generation expected-generation}))))

(defn- serving-targets [ds run-id]
  (mapv :to_strand_id
        (execute! ds ["SELECT to_strand_id
                       FROM strand_edges
                       WHERE from_strand_id = ? AND edge_type = 'serves'
                       ORDER BY to_strand_id"
                      run-id])))

(defn- require-valid-existing-serves! [ds]
  (when-let [run-id (:from_strand_id
                     (execute-one! ds ["SELECT from_strand_id
                                        FROM strand_edges
                                        WHERE edge_type = 'serves'
                                        GROUP BY from_strand_id
                                        HAVING COUNT(*) > 1
                                        ORDER BY from_strand_id
                                        LIMIT 1"]))]
    (let [targets (serving-targets ds run-id)]
      (throw (ex-info (str "Run " run-id " has multiple outgoing serves targets: "
                           (str/join ", " targets))
                      {:run-id run-id
                       :relation "serves"
                       :existing-targets targets})))))

(declare bootstrap-acyclic-relation! bootstrap-immutable-key! immutable-key?)

(defn init!
  "Initialize ds with the current schema, shipped acyclic relations, and
  shipped immutable keys.

  Existing compatible unstamped schemas are adopted as generation 1. Schema
  mismatches and generation skew throw before initialization writes. The
  4-arity runs the schema orchestration alone against explicit statements, a
  binary generation, and a declared-additive object set; it is the seam for
  exercising generations and additive sets that cannot occur in production,
  where only the 1-arity is called."
  ([ds]
   (init! ds schema-sql current-generation additive-schema-objects)
   (doseq [relation shipped-acyclic-relations]
     (bootstrap-acyclic-relation! ds relation))
   (doseq [k shipped-immutable-keys]
     (bootstrap-immutable-key! ds k))
   ds)
  ([ds schema-statements binary-generation additive-objects]
   (let [reference (reference-schema schema-statements)
         found-generation (schema-generation ds)
         classification (classify-generation found-generation
                                             binary-generation
                                             (database-empty? ds (:object-keys reference)))]
     (case classification
       (:refuse-newer :refuse-older)
       (incompatible-generation! classification found-generation binary-generation)

       :bootstrap
       (do
         (doseq [statement schema-statements]
           (execute! ds statement))
         (validate-schema! ds reference :full additive-objects found-generation binary-generation)
         (stamp-generation! ds binary-generation))

       (:adopt :proceed)
       (do
         (validate-schema! ds reference :pre-ddl additive-objects found-generation binary-generation)
         (require-valid-existing-serves! ds)
         (doseq [statement schema-statements]
           (execute! ds statement))
         (validate-schema! ds reference :full additive-objects found-generation binary-generation)
         (when (= :adopt classification)
           (stamp-generation! ds binary-generation)))))
   ds))

(declare get-strand update-strand! add-edge! strands-by-ids require-updated-strand require-existing-strand-ids! placeholders)

(def ^:private batch-strand-keys #{:title :state :attributes :ref :edges})
(def ^:private batch-edge-keys #{:type :to :attributes})

(defn- require-json-object-encodable! [attributes context]
  (when-not (s/valid? ::specs/attributes attributes)
    (throw (ex-info "Attributes must be nil or an EDN map that encodes to a JSON object"
                    {:context context :attributes attributes})))
  attributes)

(defn- require-no-unknown-keys! [m allowed context]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (throw (ex-info "Unknown keys in batch input" {:context context :keys (vec unknown)}))))

(defn- validate-batch-edge! [edge]
  (when-not (map? edge)
    (throw (ex-info "Batch edge must be a map" {:edge edge})))
  (require-no-unknown-keys! edge batch-edge-keys :edge)
  (require-valid! ::specs/batch-edge edge "Batch edge is invalid")
  edge)

(defn- validate-batch-strand! [strand]
  (when-not (map? strand)
    (throw (ex-info "Batch strand must be a map" {:strand strand})))
  (require-no-unknown-keys! strand batch-strand-keys :strand)
  (require-valid! ::specs/batch-strand strand "Batch strand is invalid")
  (doseq [edge (:edges strand)]
    (validate-batch-edge! edge))
  strand)

(defn- validate-batch! [strands]
  (require-valid! ::specs/batch-input strands "Batch input is invalid")
  (doseq [strand strands]
    (validate-batch-strand! strand))
  (let [refs (keep :ref strands)
        duplicate-ref (->> refs frequencies (filter (fn [[_ n]] (> n 1))) ffirst)]
    (when duplicate-ref
      (throw (ex-info "Duplicate batch ref" {:ref duplicate-ref}))))
  strands)

(def ^:private strand-columns "id, title, state, created_at, updated_at")

(defn- assembled-attributes-sql [strand-id-expr hot-only?]
  (str "COALESCE((
          SELECT json_group_object(key, json(value))
          FROM (
            SELECT key, value
            FROM attributes
            WHERE strand_id = " strand-id-expr
       (when hot-only? " AND archived = 0")
       " ORDER BY key
          )
        ), '{}') AS attributes"))

(defn- strand-select-sql [alias hot-only?]
  (str alias ".id, " alias ".title, " alias ".state, "
       (assembled-attributes-sql (str alias ".id") hot-only?)
       ", " alias ".created_at, " alias ".updated_at"))

(def ^:private attribute-assembly-batch-size 30000)

(defn- attribute-value-sql [lean-byte-floor]
  (if lean-byte-floor
    (str "CASE WHEN length(CAST(value AS BLOB)) > ? "
         "THEN json_object('skein/omitted', json('true'), 'bytes', length(CAST(value AS BLOB))) "
         "ELSE json(value) END")
    "json(value)"))

(defn- attribute-json-by-strand-id [ds strand-ids hot-only? lean-byte-floor]
  (into {}
        (map (juxt :strand_id :attributes))
        (mapcat
         (fn [ids]
           (execute! ds (into [(str "SELECT strand_id,
                                             COALESCE(json_group_object(key, " (attribute-value-sql lean-byte-floor) "), '{}') AS attributes
                                      FROM (
                                        SELECT strand_id, key, value
                                        FROM attributes
                                        WHERE strand_id IN (" (placeholders ids) ")"
                                    (when hot-only? " AND archived = 0")
                                    " ORDER BY strand_id, key)
                                      GROUP BY strand_id")]
                              (cond-> []
                                lean-byte-floor (conj lean-byte-floor)
                                true (into ids)))))
         (partition-all attribute-assembly-batch-size strand-ids))))

(defn- attach-batched-attributes
  ([ds rows hot-only?]
   (attach-batched-attributes ds rows hot-only? nil))
  ([ds rows hot-only? lean-byte-floor]
   (let [ids (mapv :id rows)
         attrs-by-id (attribute-json-by-strand-id ds ids hot-only? lean-byte-floor)]
     (mapv #(assoc % :attributes (get attrs-by-id (:id %) "{}")) rows))))

(defn- strand-columns-sql [alias]
  (str alias ".id, " alias ".title, " alias ".state, "
       alias ".created_at, " alias ".updated_at"))

(defn- strand-row-by-id [ds strand-id hot-only?]
  (execute-one! ds
                [(str "SELECT " (strand-select-sql "t" hot-only?)
                      " FROM strands t WHERE t.id = ?")
                 strand-id]))

(defn- throw-immutable-violation! [json-k strand-id existing attempted]
  (throw (ex-info (str "Attribute key " json-k " is write-once and cannot be changed")
                  {:key json-k :strand-id strand-id :existing existing :attempted attempted})))

(defn- immutable-attribute-row [ds strand-id json-k]
  (execute-one! ds ["SELECT value FROM attributes WHERE strand_id = ? AND key = ?" strand-id json-k]))

(defn- guard-immutable-write!
  "Reject a value-changing write to a registered immutable key.

  Reads the existing row in the caller's transaction; a decoded value equal to
  the decoded attempt passes (idempotent re-assert), so first writes and
  identical carry-throughs stay legal while genuine changes throw."
  [ds strand-id k v]
  (let [json-k (json-key k)]
    (when (immutable-key? ds json-k)
      (when-let [row (immutable-attribute-row ds strand-id json-k)]
        (let [existing (<-json (:value row))
              attempted (<-json (attr-value->json v))]
          (when-not (= existing attempted)
            (throw-immutable-violation! json-k strand-id existing attempted)))))))

(defn- guard-immutable-drop!
  "Reject deleting or archiving a registered immutable key that has a stored row.

  Attempted is nil to distinguish a removal from a value change."
  [ds strand-id json-k]
  (when (immutable-key? ds json-k)
    (when-let [row (immutable-attribute-row ds strand-id json-k)]
      (throw-immutable-violation! json-k strand-id (<-json (:value row)) nil))))

(defn- guard-immutable-replace!
  "Reject a full-map replace that drops or changes any registered immutable key.

  Runs BEFORE the delete-all so existing rows are still visible; a key carried
  through with its decoded value unchanged is legal, an absent or changed key
  throws."
  [ds strand-id attributes]
  (let [new-by-json (into {} (map (fn [[k v]] [(json-key k) v])) (or attributes {}))]
    (doseq [{:keys [key value]} (execute! ds ["SELECT key, value FROM attributes WHERE strand_id = ?" strand-id])]
      (when (immutable-key? ds key)
        (if (contains? new-by-json key)
          (let [existing (<-json value)
                attempted (<-json (attr-value->json (get new-by-json key)))]
            (when-not (= existing attempted)
              (throw-immutable-violation! key strand-id existing attempted)))
          (throw-immutable-violation! key strand-id (<-json value) nil))))))

(defn- write-attribute-rows!
  "Write attribute rows as fresh hot data.

  Updating an archived key intentionally resets only that key to
  `archived = 0`; untouched archived keys remain cold."
  [ds strand-id attributes]
  (doseq [[k v] (sort-by (comp str key) attributes)]
    (guard-immutable-write! ds strand-id k v)
    (execute! ds ["INSERT INTO attributes (strand_id, key, value)
                   VALUES (?, ?, json(?))
                   ON CONFLICT(strand_id, key) DO UPDATE
                   SET value = excluded.value,
                       archived = 0"
                  strand-id (json-key k) (attr-value->json v)])))

(defn- replace-attribute-rows! [ds strand-id attributes]
  (guard-immutable-replace! ds strand-id attributes)
  (execute! ds ["DELETE FROM attributes WHERE strand_id = ?" strand-id])
  (write-attribute-rows! ds strand-id (or attributes {})))

(defn- merge-json-patch-value [current patch]
  (if (map? patch)
    (reduce-kv (fn [m k v]
                 (if (nil? v)
                   (dissoc m k)
                   (update m k merge-json-patch-value v)))
               (if (map? current) current {})
               patch)
    patch))

(defn- patch-attribute-rows! [ds strand-id attributes]
  (let [current (-> (strand-row-by-id ds strand-id false) :attributes <-json)
        patched (reduce-kv (fn [m k v]
                             (if (nil? v)
                               (dissoc m k)
                               (update m k merge-json-patch-value v)))
                           current
                           attributes)]
    (require-valid! ::specs/attributes patched "Attributes must be nil or a map that encodes to a JSON object")
    (doseq [[k v] attributes]
      (if (nil? v)
        (do
          (guard-immutable-drop! ds strand-id (json-key k))
          (execute! ds ["DELETE FROM attributes WHERE strand_id = ? AND key = ?" strand-id (json-key k)]))
        (write-attribute-rows! ds strand-id {k (get patched k)})))))

(def ^:private generic-states #{"active" "closed"})

(defn- require-generic-state! [value field]
  (when-not (contains? generic-states value)
    (throw (ex-info "Lifecycle state must be active or closed" {:field field :value value :allowed generic-states})))
  value)

(defn- insert-strand! [ds id title state attributes]
  (execute-one! ds
                [(str "INSERT INTO strands (id, title, state)
                       VALUES (?, ?, ?)
                       RETURNING " strand-columns)
                 id title state])
  (replace-attribute-rows! ds id attributes)
  (strand-row-by-id ds id false))

(defn- unique-strand-id-error? [^Exception e]
  (str/includes? (.getMessage e) "UNIQUE constraint failed: strands.id"))

(def ^:private strand-input-keys #{:title :state :attributes})
(def ^:private strand-patch-keys #{:title :state :attributes})
(def ^:private removed-lifecycle-fields #{:status :final_at :active :inactive_at})

(defn- reject-removed-lifecycle-fields! [m context]
  (when-let [fields (seq (filter removed-lifecycle-fields (keys m)))]
    (throw (ex-info "Removed lifecycle fields are not core strand fields"
                    {:context context :fields (vec fields)}))))

(defn- reject-unknown-strand-keys! [m allowed context]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (throw (ex-info "Unknown core strand fields" {:context context :fields (vec unknown)}))))

(defn add-strand!
  "Create a strand row and return it.

  Generates a unique id, defaults missing state to active, validates core strand fields,
  and throws if an id cannot be allocated after bounded retries."
  [ds {:keys [title attributes] :as strand}]
  (reject-removed-lifecycle-fields! strand :create)
  (reject-unknown-strand-keys! strand strand-input-keys :create)
  (let [strand (merge {:state "active"} strand)]
    (require-valid! ::specs/strand-input strand "Invalid strand")
    (loop [attempt 1]
      (when (> attempt max-id-attempts)
        (throw (ex-info "Unable to generate unique strand id" {:attempts max-id-attempts})))
      (let [id (generate-id)
            result (try
                     [:created (run-owned-transaction
                                ds
                                (fn [tx]
                                  (insert-strand! tx id title (:state strand) attributes)))]
                     (catch org.sqlite.SQLiteException e
                       (if (unique-strand-id-error? e)
                         [:retry nil]
                         (throw e))))]
        (case (first result)
          :created (second result)
          :retry (recur (inc attempt)))))))

(defn ^:no-doc add-strand-batch-in-transaction!
  [tx strands]
  (validate-batch! strands)
  (let [resolve-existing-ref (fn [refs value context]
                               (cond
                                 (symbol? value) (or (get refs (str value))
                                                     (throw (ex-info "Batch ref not found; symbolic targets only resolve to batch refs, use a string for durable ids"
                                                                     {:context context :value value})))
                                 (string? value) (do
                                                   (when-not (get-strand tx value)
                                                     (throw (ex-info "Batch target strand not found" {:context context :value value})))
                                                   value)))]
    (loop [remaining strands
           created []
           refs {}]
      (if-let [strand (first remaining)]
        (let [{:keys [title state attributes]} strand
              created-strand (add-strand! tx (cond-> {:title title :attributes attributes}
                                               (contains? strand :state) (assoc :state state)))
              refs (cond-> refs
                     (:ref strand) (assoc (str (:ref strand)) (:id created-strand)))]
          (recur (rest remaining) (conj created created-strand) refs))
        (let [edge-outcomes (mapv (fn [[strand created-strand edge]]
                                    (let [{:keys [to type attributes]} edge
                                          resolved-to (resolve-existing-ref refs to :edge)]
                                      {:op :upsert
                                       :from (or (some-> (:ref strand) str) (:id created-strand))
                                       :to (if (symbol? to) (str to) resolved-to)
                                       :type type
                                       :edge (add-edge! tx {:from (:id created-strand)
                                                            :to resolved-to
                                                            :type type
                                                            :attributes attributes})}))
                                  (for [[strand created-strand] (map vector strands created)
                                        edge (:edges strand)]
                                    [strand created-strand edge]))]
          {:created created
           :refs refs
           :edges edge-outcomes})))))

(defn add-strand-batch!
  "Create multiple strands and their batch-local edges in one transaction.

  Symbolic :ref values may be used by edge targets anywhere in the batch."
  [ds strands]
  (let [result (jdbc/with-transaction [tx ds]
                 (add-strand-batch-in-transaction! tx strands))]
    (select-keys result [:created :refs])))

(defn- path-exists? [ds type from to]
  (boolean
   (execute-one! ds
                 ["WITH RECURSIVE reachable(id) AS (
                     SELECT to_strand_id
                     FROM strand_edges
                     WHERE from_strand_id = ?
                       AND edge_type = ?
                   UNION
                     SELECT e.to_strand_id
                     FROM reachable r
                     JOIN strand_edges e ON e.from_strand_id = r.id
                     WHERE e.edge_type = ?
                   )
                   SELECT 1 AS found
                   FROM reachable
                   WHERE id = ?
                   LIMIT 1"
                  from type type to])))

(defn acyclic-relation?
  "Return true when relation is declared acyclic in ds."
  [ds relation]
  (boolean (execute-one! ds ["SELECT 1 AS found FROM acyclic_relations WHERE relation = ?" relation])))

(defn list-acyclic-relations
  "Return all declared acyclic relation names in sorted order."
  [ds]
  (mapv :relation (execute! ds ["SELECT relation FROM acyclic_relations ORDER BY relation"])))

(defn- require-valid-relation-name! [relation]
  (when-not (s/valid? ::specs/edge-type relation)
    (throw (ex-info "Relation must be a valid relation name" {:relation relation})))
  relation)

(defn- require-existing-relation-acyclic! [ds relation]
  (doseq [{:keys [from_strand_id to_strand_id]} (execute! ds ["SELECT from_strand_id, to_strand_id FROM strand_edges WHERE edge_type = ?" relation])]
    (when (path-exists? ds relation to_strand_id from_strand_id)
      (throw (ex-info "Existing relation contains a cycle" {:relation relation :from from_strand_id :to to_strand_id})))))

(defn- insert-acyclic-relation! [ds relation]
  (execute-one! ds ["INSERT INTO acyclic_relations (relation) VALUES (?) RETURNING relation" relation])
  {:relation relation :acyclic true})

(defn- bootstrap-acyclic-relation! [ds relation]
  (if (acyclic-relation? ds relation)
    {:relation relation :acyclic true}
    (do
      (require-existing-relation-acyclic! ds relation)
      (insert-acyclic-relation! ds relation))))

(defn- immutable-key?
  "Return true when key is declared write-once immutable in ds.

  Key is the stored JSON object-key text (see `json-key`)."
  [ds key]
  (boolean (execute-one! ds ["SELECT 1 AS found FROM immutable_keys WHERE key = ?" key])))

(defn- bootstrap-immutable-key!
  "Seed key as write-once immutable, tolerating an already-present declaration."
  [ds key]
  (if (immutable-key? ds key)
    {:key key :immutable true}
    (do
      (execute-one! ds ["INSERT INTO immutable_keys (key) VALUES (?) RETURNING key" key])
      {:key key :immutable true})))

(defn declare-acyclic-relation!
  "Declare relation acyclic before edges of that relation exist.

  Existing declarations are idempotent. Invalid relation names or late declarations throw."
  [ds relation]
  (require-valid-relation-name! relation)
  (if (acyclic-relation? ds relation)
    {:relation relation :acyclic true}
    (do
      (when (execute-one! ds ["SELECT 1 AS found FROM strand_edges WHERE edge_type = ? LIMIT 1" relation])
        (throw (ex-info "Cannot declare relation acyclic after edges of that relation exist" {:relation relation})))
      (insert-acyclic-relation! ds relation))))

(defn- require-acyclic-edge! [ds from to type]
  (when (= from to)
    (throw (ex-info "Strand edges must not point to the same strand" {:from from :to to :type type})))
  (when (and (acyclic-relation? ds type)
             (path-exists? ds type to from))
    (throw (ex-info "Strand edge would create a cycle" {:from from :to to :type type}))))

(defn- require-single-serves-edge! [ds from to type]
  (when (= "serves" type)
    (let [targets (serving-targets ds from)]
      (cond
        (< 1 (count targets))
        (throw (ex-info (str "Run " from " has multiple outgoing serves targets: "
                             (str/join ", " targets))
                        {:run-id from
                         :relation type
                         :existing-targets targets
                         :attempted-target to}))

        (and (seq targets) (not= to (first targets)))
        (throw (ex-info (str "Run " from " already serves target " (first targets)
                             "; cannot also serve " to)
                        {:run-id from
                         :relation type
                         :existing-target (first targets)
                         :attempted-target to}))))))

(defn add-edge!
  "Upsert an edge row and return it.

  Validates endpoints, attributes, and acyclicity for declared acyclic relations."
  [ds {:keys [from to type attributes] :as edge}]
  (require-valid! ::specs/edge-input edge "Invalid edge")
  (require-existing-strand-ids! ds [from to] :edge)
  (require-single-serves-edge! ds from to type)
  (require-acyclic-edge! ds from to type)
  (execute-one! ds
                ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                  VALUES (?, ?, ?, json(?))
                  ON CONFLICT(from_strand_id, to_strand_id, edge_type) DO UPDATE SET attributes = excluded.attributes
                  RETURNING from_strand_id, to_strand_id, edge_type, attributes"
                 from to type (->json attributes)]))

(defn- edge-row [ds from to type]
  (execute-one! ds
                ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                  FROM strand_edges
                  WHERE from_strand_id = ?
                    AND to_strand_id = ?
                    AND edge_type = ?"
                 from to type]))

(defn- delete-edge! [ds from to type]
  (let [row (edge-row ds from to type)]
    (when row
      (execute! ds
                ["DELETE FROM strand_edges
                  WHERE from_strand_id = ?
                    AND to_strand_id = ?
                    AND edge_type = ?"
                 from to type]))
    row))

(defn- remove-batch-edge!
  "Delete the exact (from-id, to-id, type) edge and return its pre-delete row.

  Fails loudly when a clean post-lock lookup finds no matching row — a wrong
  direction or wrong relation type included — so a stale remover rereads instead
  of silently succeeding (TEN-003). The submitted refs and resolved durable ids
  are distinct values in the absence ex-data."
  [tx {:keys [from to type from-id to-id]}]
  (or (delete-edge! tx from-id to-id type)
      (throw (ex-info "Batch edge remove found no matching edge"
                      {:from from :to to :from-id from-id :to-id to-id :type type}))))

(defn- set-strand-state-internal! [ds strand-id state]
  (require-updated-strand
   strand-id
   (do
     (execute-one! ds
                   [(str "UPDATE strands
                           SET state = ?,
                               updated_at = datetime('now')
                           WHERE id = ?
                           RETURNING " strand-columns)
                    state strand-id])
     (strand-row-by-id ds strand-id false))))

(defn ^:no-doc supersede-strand-in-transaction!
  [tx old-id replacement-id]
  (when (= old-id replacement-id)
    (throw (ex-info "A strand cannot supersede itself" {:old-id old-id :replacement-id replacement-id})))
  (let [old-before (or (get-strand tx old-id)
                       (throw (ex-info "Old strand not found" {:old-id old-id})))
        replacement (or (get-strand tx replacement-id)
                        (throw (ex-info "Replacement strand not found" {:replacement-id replacement-id})))]
    (when (= "replaced" (:state old-before))
      (throw (ex-info "Old strand is already replaced" {:old-id old-id :state (:state old-before)})))
    (when-not (= "active" (:state replacement))
      (throw (ex-info "Replacement strand must be active" {:replacement-id replacement-id :state (:state replacement)})))
    (let [supersedes-edge (add-edge! tx {:from replacement-id
                                         :to old-id
                                         :type "supersedes"
                                         :attributes {}})
          old-after (set-strand-state-internal! tx old-id "replaced")
          incoming-depends (execute! tx ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                                          FROM strand_edges
                                          WHERE to_strand_id = ?
                                            AND edge_type = 'depends-on'
                                          ORDER BY from_strand_id"
                                         old-id])
          rewired (mapv (fn [edge]
                          (let [dependent (:from_strand_id edge)
                                existing-edge (edge-row tx dependent replacement-id "depends-on")
                                new-edge (or existing-edge
                                             (add-edge! tx {:from dependent
                                                            :to replacement-id
                                                            :type "depends-on"
                                                            :attributes (some-> (:attributes edge) <-json)}))
                                deleted-edge (delete-edge! tx dependent old-id "depends-on")]
                            {:from dependent
                             :old-to old-id
                             :new-to replacement-id
                             :type "depends-on"
                             :deleted-edge deleted-edge
                             :edge new-edge}))
                        incoming-depends)]
      {:old {:before old-before :after old-after}
       :replacement-id replacement-id
       :supersedes-edge supersedes-edge
       :rewired-dependencies rewired})))

(defn supersede-strand!
  "Mark old-id as replaced by replacement-id and rewire incoming dependencies.

  Creates a supersedes edge from replacement to old and returns before/after details."
  [ds old-id replacement-id]
  (jdbc/with-transaction [tx ds]
    (supersede-strand-in-transaction! tx old-id replacement-id)))

(defn get-strand
  "Return the strand row for strand-id, or nil when it does not exist."
  [ds strand-id]
  (strand-row-by-id ds strand-id false))

(defn- require-updated-strand
  "Return row or throw a Strand not found error for strand-id."
  [strand-id row]
  (or row
      (throw (ex-info "Strand not found" {:strand-id strand-id}))))

(defn update-strand!
  "Patch a strand row and return the updated row.

  Attribute patches are merged with SQLite json_patch. Unknown or removed core fields throw."
  [ds strand-id {:keys [title state attributes] :as patch}]
  (reject-removed-lifecycle-fields! patch :update)
  (reject-unknown-strand-keys! patch strand-patch-keys :update)
  (when (and title (str/blank? title))
    (throw (ex-info "Strand title must be non-blank" {:title title})))
  (when (contains? patch :state)
    (require-generic-state! state :state))
  (run-owned-transaction
   ds
   (fn [tx]
     (require-updated-strand strand-id (get-strand tx strand-id))
     (when (contains? patch :attributes)
       (patch-attribute-rows! tx strand-id attributes))
     (require-updated-strand
      strand-id
      (do
        (execute-one! tx
                      [(str "UPDATE strands
                             SET title = COALESCE(?, title),
                                 state = COALESCE(?, state),
                                 updated_at = datetime('now')
                             WHERE id = ?
                             RETURNING " strand-columns)
                       title (when (contains? patch :state) state)
                       strand-id])
        (strand-row-by-id tx strand-id false))))))

(defn- compile-query-for-ds [_ds query-def params]
  (query/compile-query query-def params))

(defn query-strands
  "Return strand rows matching query-def and optional query params."
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (let [{:keys [sql params]} (compile-query-for-ds ds query-def params)]
     (mapv identity
           (execute! ds (into [(str "SELECT " (strand-select-sql "t" true)
                                    " FROM strands t WHERE " sql " ORDER BY t.id")]
                              params))))))

(defn query-strand-ids
  "Return sorted strand ids matching query-def and optional query params."
  ([ds query-def]
   (query-strand-ids ds query-def {}))
  ([ds query-def params]
   (let [{:keys [sql params]} (compile-query-for-ds ds query-def params)]
     (mapv :id (execute! ds (into [(str "SELECT t.id FROM strands t WHERE " sql " ORDER BY t.id")]
                                  params))))))

(defn- ordered-distinct [xs]
  (loop [remaining xs
         seen #{}
         result []]
    (if-let [items (seq remaining)]
      (let [x (first items)]
        (if (contains? seen x)
          (recur (rest items) seen result)
          (recur (rest items) (conj seen x) (conj result x))))
      result)))

(defn- placeholders [xs]
  (str/join ", " (repeat (count xs) "?")))

(defn- require-existing-strand-ids! [ds ids context]
  (let [ids (ordered-distinct ids)]
    (when (seq ids)
      (let [found (set (map :id (execute! ds (into [(str "SELECT id FROM strands WHERE id IN (" (placeholders ids) ")")]
                                                   ids))))
            missing (vec (remove found ids))]
        (when (seq missing)
          (throw (ex-info "Strand ids not found" {:context context :missing missing})))))
    ids))

(defn- archive-error [reason strand-id keys]
  {:reason reason
   :strand-id strand-id
   :keys (when (coll? keys) (vec keys))})

(defn- require-archive-strand-id! [strand-id keys]
  (when-not (s/valid? ::specs/id strand-id)
    (throw (ex-info "Attribute archive strand id is invalid"
                    (archive-error :malformed-strand-id strand-id keys))))
  strand-id)

(defn- normalize-archive-keys! [keys]
  (when-not (s/valid? ::specs/attribute-key-set keys)
    (throw (ex-info "Attribute archive keys must be a non-empty collection of attribute keys"
                    (archive-error :malformed-keys nil keys))))
  (->> keys (map json-key) ordered-distinct sort vec))

(defn- archive-rows [ds strand-id keys]
  (execute! ds (into [(str "SELECT key, archived
                            FROM attributes
                            WHERE strand_id = ?
                              AND key IN (" (placeholders keys) ")
                            ORDER BY key")
                      strand-id]
                     keys)))

(defn- require-archive-keys-present! [rows strand-id keys]
  (let [found (set (map :key rows))
        missing (vec (remove found keys))]
    (when (seq missing)
      (throw (ex-info "Attribute archive keys not found"
                      (archive-error :missing-keys strand-id missing))))))

(defn- all-attribute-keys [ds strand-id]
  (mapv :key (execute! ds ["SELECT key FROM attributes WHERE strand_id = ? ORDER BY key" strand-id])))

(def ^:private all-archive-keys :skein.core.db/all-archive-keys)

(defn- archive-attributes-in-transaction! [tx strand-id requested-keys archived?]
  (require-archive-strand-id! strand-id requested-keys)
  (when-not (get-strand tx strand-id)
    (throw (ex-info "Attribute archive strand not found"
                    (archive-error :missing-strand strand-id requested-keys))))
  (let [archive-keys (if (= all-archive-keys requested-keys)
                       (all-attribute-keys tx strand-id)
                       (normalize-archive-keys! requested-keys))
        rows (if (empty? archive-keys) [] (archive-rows tx strand-id archive-keys))
        target (if archived? 1 0)
        changed (count (remove #(= target (:archived %)) rows))]
    (require-archive-keys-present! rows strand-id archive-keys)
    (when archived?
      (doseq [k archive-keys]
        (guard-immutable-drop! tx strand-id k)))
    (when (seq archive-keys)
      (execute! tx (into [(str "UPDATE attributes
                                SET archived = ?
                                WHERE strand_id = ?
                                  AND key IN (" (placeholders archive-keys) ")")
                          target strand-id]
                         archive-keys))
      (let [post-count (:c (execute-one! tx (into [(str "SELECT count(*) AS c
                                                      FROM attributes
                                                      WHERE strand_id = ?
                                                        AND archived = ?
                                                        AND key IN (" (placeholders archive-keys) ")")
                                                   strand-id target]
                                                  archive-keys)))]
        (when-not (= (count archive-keys) post-count)
          (throw (ex-info "Attribute archive write count mismatch"
                          (archive-error :write-count-mismatch strand-id archive-keys))))))
    (assoc {:strand-id strand-id
            :archived? archived?
            :changed changed}
           :keys archive-keys)))

(defn archive-attributes!
  "Mark all attributes, or an explicit non-empty key set, archived for strand-id.

  The requested keys are validated before writing and committed atomically.
  Repeating an already-applied archive returns `:changed 0`."
  ([ds strand-id]
   (run-owned-transaction
    ds
    #(archive-attributes-in-transaction! % strand-id all-archive-keys true)))
  ([ds strand-id keys]
   (run-owned-transaction
    ds
    #(archive-attributes-in-transaction! % strand-id keys true))))

(defn unarchive-attributes!
  "Mark all attributes, or an explicit non-empty key set, hot for strand-id.

  The requested keys are validated before writing and committed atomically.
  Repeating an already-applied unarchive returns `:changed 0`."
  ([ds strand-id]
   (run-owned-transaction
    ds
    #(archive-attributes-in-transaction! % strand-id all-archive-keys false)))
  ([ds strand-id keys]
   (run-owned-transaction
    ds
    #(archive-attributes-in-transaction! % strand-id keys false))))

(defn- capture-burn-tombstone!
  "Record a burn_history tombstone for id when it still exists in tx.

  Captures the strand's core row, its full attribute map (each value tagged
  with its archived flag so archived keys stay distinguishable), and its
  incident edges in both directions, then inserts one tombstone row. The
  attribute map and edge vector are shaped to map onto the batch-mutation
  payload's :strands and :edges entries so recovery is mechanical. Ids already
  gone at capture time are skipped. Runs inside the caller's burn transaction,
  so a failed insert propagates and aborts the burn."
  [tx id]
  ;; A missing row deletes nothing, so skipping it records no unrecorded burn:
  ;; every strand that is actually deleted still has its tombstone captured.
  (when-let [row (execute-one! tx [(str "SELECT " strand-columns " FROM strands WHERE id = ?") id])]
    (let [attributes (reduce (fn [acc {:keys [key value archived]}]
                               (assoc acc key {:value (<-json value) :archived (= 1 archived)}))
                             {}
                             (execute! tx ["SELECT key, value, archived
                                            FROM attributes WHERE strand_id = ? ORDER BY key" id]))
          edges (mapv (fn [{:keys [from_strand_id to_strand_id edge_type attributes]}]
                        {:from from_strand_id
                         :to to_strand_id
                         :type edge_type
                         :attributes (<-json attributes)})
                      (execute! tx ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                                     FROM strand_edges
                                     WHERE from_strand_id = ? OR to_strand_id = ?
                                     ORDER BY from_strand_id, to_strand_id, edge_type" id id]))]
      (execute-one! tx ["INSERT INTO burn_history
                           (strand_id, title, state, created_at, updated_at, attributes, edges)
                         VALUES (?, ?, ?, ?, ?, json(?), json(?))"
                        id (:title row) (:state row) (:created_at row) (:updated_at row)
                        (->json attributes) (json/write-str edges :key-fn json-key)]))))

(defn- delete-strands! [ds ids]
  ;; Capture every tombstone before deleting any strand: a delete cascades the
  ;; incident edges, so interleaving capture and deletion would drop a shared
  ;; edge from the later co-burned strand's tombstone.
  (doseq [id ids]
    (capture-burn-tombstone! ds id))
  (doseq [id ids]
    (execute! ds ["DELETE FROM strand_edges WHERE from_strand_id = ? OR to_strand_id = ?" id id])
    (execute! ds ["DELETE FROM strands WHERE id = ?" id])))

(defn burn-by-ids!
  "Delete existing strands by id and return burn metadata."
  [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :burn-by-ids)]
    (jdbc/with-transaction [tx ds]
      (delete-strands! tx ids)
      {:burned ids :count (count ids)})))

(defn burn-by-id!
  "Delete one existing strand by id and return burn metadata."
  [ds id]
  (burn-by-ids! ds [id]))

(defn- decode-burn-history-row
  "Decode one burn_history row's JSON attributes and edges into Clojure data."
  [row]
  (-> row
      (update :attributes <-json)
      (update :edges #(json/read-str % :key-fn keyword))))

(defn burn-history-for-strand
  "Return every burn tombstone recorded for burned strand-id, newest first.

  Each tombstone is a keyword-keyed map with the burned strand's core fields,
  its full attribute map (each value carried as {:value ... :archived ...} so
  archived keys stay distinguishable), its incident edges, and recorded_at.
  Attributes and edges are decoded from JSON."
  [ds strand-id]
  (mapv decode-burn-history-row
        (execute! ds ["SELECT id, strand_id, title, state, created_at, updated_at, attributes, edges, recorded_at
                       FROM burn_history WHERE strand_id = ? ORDER BY id DESC" strand-id])))

(defn recent-burn-history
  "Return the latest limit burn tombstones across all strands, newest first.

  limit is required and must be a positive integer; there is no unbounded read.
  Each tombstone is decoded to a keyword-keyed map as in burn-history-for-strand."
  [ds limit]
  (require-positive-limit! limit)
  (mapv decode-burn-history-row
        (execute! ds ["SELECT id, strand_id, title, state, created_at, updated_at, attributes, edges, recorded_at
                       FROM burn_history ORDER BY id DESC LIMIT ?" limit])))

(def ^:private batch-mutation-top-level-keys #{:refs :strands :edges :burn})
(def ^:private batch-mutation-strand-keys #{:ref :title :state :attributes})
(def ^:private batch-mutation-edge-keys #{:op :from :to :type :attributes})
(def ^:private batch-mutation-edge-remove-keys #{:op :from :to :type})

(defn- require-batch-ref! [ref context]
  (when-not (and (keyword? ref)
                 (nil? (namespace ref))
                 (not (str/blank? (name ref))))
    (throw (ex-info "Batch refs must be unqualified non-blank keywords"
                    {:context context :ref ref})))
  ref)

(defn- require-vector-section! [value section]
  (when-not (vector? value)
    (throw (ex-info "Batch section must be a vector" {:section section :value value})))
  value)

(defn- require-map-section! [value section]
  (when-not (map? value)
    (throw (ex-info "Batch section must be a map" {:section section :value value})))
  value)

(defn- duplicate-keys-by-value [m]
  (->> m
       (group-by val)
       (keep (fn [[id entries]]
               (when (< 1 (count entries))
                 {:id id :refs (mapv key entries)})))
       vec))

(defn- duplicate-item [xs]
  (->> xs frequencies (some (fn [[x n]] (when (< 1 n) x)))))

(defn- section [payload k default require-fn]
  (require-fn (if (contains? payload k) (get payload k) default) k))

(defn- valid-title? [title]
  (and (string? title) (not (str/blank? title))))

(defn ^:no-doc normalize-batch-payload! [payload]
  (when-not (map? payload)
    (throw (ex-info "Batch payload must be a map" {:value payload})))
  (require-no-unknown-keys! payload batch-mutation-top-level-keys :batch)
  (let [refs (section payload :refs {} require-map-section!)
        strands (section payload :strands [] require-vector-section!)
        edges (section payload :edges [] require-vector-section!)
        burn (section payload :burn [] require-vector-section!)]
    (doseq [[ref id] refs]
      (require-batch-ref! ref :refs)
      (when-not (string? id)
        (throw (ex-info "Batch ref targets must be durable strand id strings" {:ref ref :id id}))))
    (when-let [dupes (seq (duplicate-keys-by-value refs))]
      (throw (ex-info "Multiple batch refs cannot alias the same existing strand id" {:duplicates dupes})))
    (doseq [[idx strand] (map-indexed vector strands)]
      (when-not (map? strand)
        (throw (ex-info "Batch strand entry must be a map" {:index idx :strand strand})))
      (reject-removed-lifecycle-fields! strand :batch-strand)
      (reject-unknown-strand-keys! strand batch-mutation-strand-keys :batch-strand)
      (when-not (contains? strand :ref)
        (throw (ex-info "Batch strand entry requires :ref" {:index idx :strand strand})))
      (require-batch-ref! (:ref strand) :strands)
      (when (contains? strand :title)
        (when-not (valid-title? (:title strand))
          (throw (ex-info "Batch strand :title must be a non-blank string" {:index idx :strand strand}))))
      (when (contains? strand :state)
        (require-generic-state! (:state strand) :state))
      (when (contains? strand :attributes)
        (require-json-object-encodable! (:attributes strand) :strand)))
    (when-let [duplicate-ref (duplicate-item (map :ref strands))]
      (throw (ex-info "Duplicate batch strand ref" {:ref duplicate-ref})))
    (doseq [[idx ref] (map-indexed vector burn)]
      (require-batch-ref! ref {:section :burn :index idx}))
    (when-let [duplicate-ref (duplicate-item burn)]
      (throw (ex-info "Duplicate batch burn ref" {:ref duplicate-ref})))
    (doseq [[idx edge] (map-indexed vector edges)]
      (when-not (map? edge)
        (throw (ex-info "Batch edge entry must be a map" {:index idx :edge edge})))
      (case (:op edge)
        :upsert (require-no-unknown-keys! edge batch-mutation-edge-keys :batch-edge)
        :remove (require-no-unknown-keys! edge batch-mutation-edge-remove-keys :batch-edge)
        (throw (ex-info "Unsupported batch edge operation" {:index idx :op (:op edge)})))
      (doseq [k [:from :to]]
        (when-not (contains? edge k)
          (throw (ex-info "Batch edge endpoint is required" {:index idx :field k :edge edge})))
        (require-batch-ref! (get edge k) {:section :edges :index idx :field k}))
      (when-not (s/valid? ::specs/edge-type (:type edge))
        (throw (ex-info "Batch edge :type must be a valid relation name"
                        {:index idx :edge edge})))
      (when (= :upsert (:op edge))
        (require-json-object-encodable! (:attributes edge) :edge)))
    {:refs refs :strands strands :edges edges :burn burn}))

(defn ^:no-doc apply-batch-in-transaction!
  [tx payload]
  (let [{:keys [refs strands edges burn]} (normalize-batch-payload! payload)]
    (require-existing-strand-ids! tx (vals refs) :batch-refs)
    (let [strand-refs (set (map :ref strands))
          burn-refs (set burn)
          bound-refs (set (keys refs))
          known-refs (into bound-refs strand-refs)]
      (doseq [strand strands]
        (when-not (contains? refs (:ref strand))
          (when-not (valid-title? (:title strand))
            (throw (ex-info "Batch strand create requires a non-blank :title"
                            {:ref (:ref strand) :strand strand})))))
      (doseq [ref burn]
        (when-not (contains? refs ref)
          (throw (ex-info "Batch burn refs must name existing bound refs" {:ref ref}))))
      (when-let [ref (first (filter strand-refs burn-refs))]
        (throw (ex-info "Batch ref cannot be both mutated and burned" {:ref ref})))
      (doseq [{:keys [op from to]} edges]
        (doseq [ref [from to]]
          (when-not (contains? known-refs ref)
            (throw (ex-info "Batch edge references unknown ref" {:ref ref})))
          (when (contains? burn-refs ref)
            (throw (ex-info "Batch edge cannot reference a burned ref" {:ref ref})))
          (when (and (= :remove op) (not (contains? bound-refs ref)))
            (throw (ex-info "Batch edge remove endpoints must be top-level pre-bound refs"
                            {:ref ref :op op})))))
      (let [{final-refs :refs created-rows :rows}
            (reduce (fn [acc strand]
                      (if (contains? refs (:ref strand))
                        acc
                        (let [created-row (add-strand! tx (select-keys strand [:title :state :attributes]))]
                          (-> acc
                              (update :refs assoc (:ref strand) (:id created-row))
                              (update :rows conj created-row)))))
                    {:refs refs :rows []}
                    strands)
            updated (->> strands
                         (keep (fn [strand]
                                 (when-let [id (get refs (:ref strand))]
                                   {:ref (:ref strand)
                                    :id id
                                    :before (get-strand tx id)
                                    :after (update-strand! tx id (dissoc strand :ref))})))
                         vec)
            edge-outcomes (mapv (fn [edge]
                                  (let [from-id (get final-refs (:from edge))
                                        to-id (get final-refs (:to edge))
                                        type (:type edge)
                                        transition {:op (:op edge)
                                                    :from (:from edge)
                                                    :to (:to edge)
                                                    :type type}]
                                    (case (:op edge)
                                      ;; Load the real pre-image before writing so a
                                      ;; replacement upsert reports its actual :before.
                                      :upsert (let [before (edge-row tx from-id to-id type)
                                                    after (add-edge! tx {:from from-id
                                                                         :to to-id
                                                                         :type type
                                                                         :attributes (:attributes edge)})]
                                                (assoc transition :before before :after after))
                                      :remove (let [before (remove-batch-edge! tx {:from (:from edge)
                                                                                   :to (:to edge)
                                                                                   :type type
                                                                                   :from-id from-id
                                                                                   :to-id to-id})]
                                                (assoc transition :before before :after nil)))))
                                edges)
            burn-ids (mapv final-refs burn)
            burned-rows (strands-by-ids tx burn-ids)]
        (delete-strands! tx burn-ids)
        {:refs final-refs
         :created created-rows
         :updated updated
         :burned (mapv (fn [ref id row] {:ref ref :id id :before row}) burn burn-ids burned-rows)
         :edges edge-outcomes}))))

(defn ^:no-doc apply-batch!
  "Apply a mixed batch mutation transaction.

  Payload refs bind existing and newly-created strands, edges run ordered
  `:upsert`/`:remove` operations, and burns delete strands after validation
  succeeds."
  [ds payload]
  (jdbc/with-transaction [tx ds]
    (apply-batch-in-transaction! tx payload)))

(defn strands-by-ids
  "Return existing strand rows in first-seen id order.

  Duplicate ids are collapsed by first occurrence. Missing ids throw."
  [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :strands-by-ids)]
    (if (empty? ids)
      []
      (let [rows-by-id (into {}
                             (map (juxt :id identity))
                             (execute! ds (into [(str "SELECT " (strand-select-sql "t" false)
                                                      " FROM strands t WHERE t.id IN (" (placeholders ids) ")")]
                                                ids)))]
        (mapv rows-by-id ids)))))

(defn ancestor-root-ids
  "Return root ancestor ids for seed-ids along an acyclic relation.

  Optional opts support :type and a query :where filter with :params."
  ([ds seed-ids]
   (ancestor-root-ids ds seed-ids {}))
  ([ds seed-ids {:keys [where params type] :or {type "parent-of"}}]
   (require-valid-relation-name! type)
   (when-not (acyclic-relation? ds type)
     (throw (ex-info "Graph traversal requires a declared acyclic relation" {:relation type})))
   (let [seed-ids (require-existing-strand-ids! ds seed-ids :ancestor-root-ids)]
     (if (empty? seed-ids)
       []
       (if where
         (let [{where-sql :sql where-params :params} (compile-query-for-ds ds where (or params {}))]
           (mapv :id
                 (execute! ds (into [(str "WITH RECURSIVE paths(id, candidate_id) AS (
                                           SELECT t.id,
                                                  CASE WHEN " where-sql " THEN t.id ELSE NULL END
                                           FROM strands t
                                           WHERE t.id IN (" (placeholders seed-ids) ")
                                         UNION ALL
                                           SELECT t.id,
                                                  CASE WHEN " where-sql " THEN t.id ELSE paths.candidate_id END
                                           FROM paths
                                           JOIN strand_edges e ON e.to_strand_id = paths.id
                                           JOIN strands t ON t.id = e.from_strand_id
                                           WHERE e.edge_type = ?
                                         )
                                         SELECT DISTINCT candidate_id AS id
                                         FROM paths
                                         WHERE candidate_id IS NOT NULL
                                           AND NOT EXISTS (
                                             SELECT 1
                                             FROM strand_edges e
                                             WHERE e.to_strand_id = paths.id
                                               AND e.edge_type = ?
                                           )
                                         ORDER BY id")]
                                    (concat where-params seed-ids where-params [type type])))))
         (mapv :id
               (execute! ds (into [(str "WITH RECURSIVE ancestors(id) AS (
                                         SELECT id FROM strands WHERE id IN (" (placeholders seed-ids) ")
                                       UNION
                                         SELECT e.from_strand_id
                                         FROM ancestors a
                                         JOIN strand_edges e ON e.to_strand_id = a.id
                                         WHERE e.edge_type = ?
                                       )
                                       SELECT a.id
                                       FROM ancestors a
                                       WHERE NOT EXISTS (
                                         SELECT 1
                                         FROM strand_edges e
                                         WHERE e.to_strand_id = a.id AND e.edge_type = ?
                                       )
                                       ORDER BY a.id")]
                                  (concat seed-ids [type type])))))))))

(defn subgraph
  "Return strands and relation-scoped edges reachable from root-ids.

  Traversal requires a declared acyclic relation, defaulting to parent-of."
  ([ds root-ids]
   (subgraph ds root-ids {}))
  ([ds root-ids {:keys [type] :or {type "parent-of"}}]
   (require-valid-relation-name! type)
   (when-not (acyclic-relation? ds type)
     (throw (ex-info "Graph traversal requires a declared acyclic relation" {:relation type})))
   (let [root-ids (require-existing-strand-ids! ds root-ids :subgraph)]
     (if (empty? root-ids)
       {:root-ids [] :strands [] :edges []}
       (let [cte (str "WITH RECURSIVE nodes(id) AS (
                        SELECT id FROM strands WHERE id IN (" (placeholders root-ids) ")
                      UNION
                        SELECT e.to_strand_id
                        FROM nodes n
                        JOIN strand_edges e ON e.from_strand_id = n.id
                        WHERE e.edge_type = ?
                      )")
             rows (mapv identity
                        (execute! ds (into [(str cte "
                                       SELECT " (strand-select-sql "t" false) "
                                       FROM strands t
                                       WHERE id IN (SELECT id FROM nodes)
                                       ORDER BY id")]
                                           (concat root-ids [type]))))
             edges (execute! ds (into [(str cte "
                                       SELECT e.from_strand_id, e.to_strand_id,
                                              e.edge_type, e.attributes
                                       FROM strand_edges e
                                       WHERE e.edge_type = ?
                                         AND e.from_strand_id IN (SELECT id FROM nodes)
                                         AND e.to_strand_id IN (SELECT id FROM nodes)
                                       ORDER BY e.from_strand_id, e.to_strand_id, e.edge_type")]
                                      (concat root-ids [type type])))]
         {:root-ids root-ids
          :strands rows
          :edges edges})))))

(defn incoming-edges
  "Return edges of `edge-type` whose target is one of `to-ids`.

  Backed by the (to_strand_id, edge_type) index, so a strand's parents or
  annotators resolve in one bounded query rather than a graph scan. Unlike
  subgraph this does not traverse, so the relation need not be acyclic.

  Adjacency is lenient by design (contrast subgraph/ancestor-root-ids/
  strands-by-ids, which validate seed ids and fail loudly on a missing one):
  an id absent from `strands` is indistinguishable from one with no matching
  edges — both contribute no rows. These are edge-projection primitives, not
  traversal seeds; the hot-path caller passes ids it already loaded, so an
  existence guard would only add a query. Callers that need a missing-id error
  must validate ids at their own boundary."
  [ds to-ids edge-type]
  (require-valid-relation-name! edge-type)
  (let [to-ids (ordered-distinct to-ids)]
    (if (empty? to-ids)
      []
      (execute! ds (into [(str "SELECT from_strand_id, to_strand_id, edge_type, attributes
                                FROM strand_edges
                                WHERE edge_type = ?
                                  AND to_strand_id IN (" (placeholders to-ids) ")
                                ORDER BY to_strand_id, from_strand_id")
                          edge-type]
                         to-ids)))))

(defn outgoing-edges
  "Return edges of `edge-type` whose source is one of `from-ids`.

  Backed by the strand_edges primary key (from_strand_id prefix), so a
  strand's children resolve in one bounded query without a graph scan.

  Lenient adjacency, same contract as `incoming-edges`: an absent id yields no
  rows rather than an error, so callers wanting a missing-id error validate at
  their own boundary."
  [ds from-ids edge-type]
  (require-valid-relation-name! edge-type)
  (let [from-ids (ordered-distinct from-ids)]
    (if (empty? from-ids)
      []
      (execute! ds (into [(str "SELECT from_strand_id, to_strand_id, edge_type, attributes
                                FROM strand_edges
                                WHERE edge_type = ?
                                  AND from_strand_id IN (" (placeholders from-ids) ")
                                ORDER BY from_strand_id, to_strand_id")
                          edge-type]
                         from-ids)))))

(defn all-strands
  "Return all strand rows, or rows matching query-def and optional params."
  ([ds]
   (mapv identity (execute! ds [(str "SELECT " (strand-select-sql "t" true)
                                     " FROM strands t ORDER BY t.id")])))
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (query-strands ds query-def params)))

(defn- bounded-select!
  "Return selected rows, failing loudly when more than limit rows match."
  [ds select-sql params count-sql limit]
  (require-positive-limit! limit)
  (let [probe (execute! ds (into [(str select-sql " LIMIT ?")] (conj (vec params) (inc limit))))]
    (if (<= (count probe) limit)
      probe
      (let [total (:total (execute-one! ds (into [count-sql] params)))]
        (throw (ex-info (str "Read result matched " total " strands, exceeding the configured cap of " limit
                             ". Narrow the read with --query/--param/--state or pass explicit --limit N; "
                             "set --limit above " total " for an intentional full read.")
                        {:code "read-limit-exceeded"
                         :total total
                         :limit limit
                         :remedies ["narrow via --query/--param/--state"
                                    "pass explicit --limit N above the total for an intentional full read"]}))))))

(defn- clamped-select
  "Return at most limit selected rows without counting matches beyond the limit."
  [ds select-sql params limit]
  (require-positive-limit! limit)
  (execute! ds (into [(str select-sql " LIMIT ?")] (conj (vec params) limit))))

(defn all-strands-lean
  "Return all strand rows with oversized hot attributes replaced by descriptors."
  ([ds lean-byte-floor]
   (attach-batched-attributes
    ds
    (execute! ds [(str "SELECT " (strand-columns-sql "t")
                       " FROM strands t ORDER BY t.id")])
    true
    lean-byte-floor))
  ([ds lean-byte-floor query-def params]
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)]
     (attach-batched-attributes
      ds
      (execute! ds
                (into [(str "SELECT " (strand-columns-sql "t") "
                             FROM strands t
                             WHERE " query-sql "
                             ORDER BY t.id")]
                      query-params))
      true
      lean-byte-floor)))
  ([ds lean-byte-floor query-def params limit]
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)
         select-sql (str "SELECT " (strand-columns-sql "t") "
                          FROM strands t
                          WHERE " query-sql "
                          ORDER BY t.id")
         count-sql (str "SELECT count(*) AS total
                         FROM strands t
                         WHERE " query-sql)]
     (attach-batched-attributes
      ds
      (bounded-select! ds select-sql query-params count-sql limit)
      true
      lean-byte-floor)))
  ([ds lean-byte-floor query-def params limit opts]
   (when-not (= {:clamp? true} opts)
     (throw (ex-info "all-strands-lean options require :clamp? true"
                     {:options opts})))
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)
         select-sql (str "SELECT " (strand-columns-sql "t") "
                          FROM strands t
                          WHERE " query-sql "
                          ORDER BY t.id")]
     (attach-batched-attributes
      ds
      (clamped-select ds select-sql query-params limit)
      true
      lean-byte-floor))))

(defn ready-strands
  "Return active strands with no active depends-on blockers.

  Optional query-def and params further filter candidate strands."
  ([ds]
   (attach-batched-attributes
    ds
    (execute! ds
              [(str "SELECT " (strand-columns-sql "t") "
               FROM strands t
               WHERE t.state = 'active'
                 AND NOT EXISTS (
                   SELECT 1
                   FROM strand_edges e
                   JOIN strands dep ON dep.id = e.to_strand_id
                   WHERE e.from_strand_id = t.id
                     AND e.edge_type = 'depends-on'
                     AND dep.state = 'active'
                 )
               ORDER BY t.id")])
    true))
  ([ds query-def]
   (ready-strands ds query-def {}))
  ([ds query-def params]
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)]
     (attach-batched-attributes
      ds
      (execute! ds
                (into [(str "SELECT " (strand-columns-sql "t") "
                 FROM strands t
                 WHERE t.state = 'active'
                   AND NOT EXISTS (
                     SELECT 1
                     FROM strand_edges e
                     JOIN strands dep ON dep.id = e.to_strand_id
                     WHERE e.from_strand_id = t.id
                       AND e.edge_type = 'depends-on'
                       AND dep.state = 'active'
                   )
                   AND " query-sql "
                 ORDER BY t.id")]
                      query-params))
      true))))

(defn ready-strands-lean
  "Return ready strands with oversized hot attributes replaced by descriptors.

  This is the storage-owned projection used by lean list/ready command surfaces
  so they do not fetch payloads they will omit from the wire result."
  ([ds lean-byte-floor]
   (attach-batched-attributes
    ds
    (execute! ds
              [(str "SELECT " (strand-columns-sql "t") "
               FROM strands t
               WHERE t.state = 'active'
                 AND NOT EXISTS (
                   SELECT 1
                   FROM strand_edges e
                   JOIN strands dep ON dep.id = e.to_strand_id
                   WHERE e.from_strand_id = t.id
                     AND e.edge_type = 'depends-on'
                     AND dep.state = 'active'
                 )
               ORDER BY t.id")])
    true
    lean-byte-floor))
  ([ds lean-byte-floor query-def params]
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)]
     (attach-batched-attributes
      ds
      (execute! ds
                (into [(str "SELECT " (strand-columns-sql "t") "
                 FROM strands t
                 WHERE t.state = 'active'
                   AND NOT EXISTS (
                     SELECT 1
                     FROM strand_edges e
                     JOIN strands dep ON dep.id = e.to_strand_id
                     WHERE e.from_strand_id = t.id
                       AND e.edge_type = 'depends-on'
                       AND dep.state = 'active'
                   )
                   AND " query-sql "
                 ORDER BY t.id")]
                      query-params))
      true
      lean-byte-floor)))
  ([ds lean-byte-floor query-def params limit]
   (let [{query-sql :sql query-params :params} (compile-query-for-ds ds query-def params)
         ready-sql "t.state = 'active'
                    AND NOT EXISTS (
                      SELECT 1
                      FROM strand_edges e
                      JOIN strands dep ON dep.id = e.to_strand_id
                      WHERE e.from_strand_id = t.id
                        AND e.edge_type = 'depends-on'
                        AND dep.state = 'active'
                    )"
         where-sql (str ready-sql " AND " query-sql)
         select-sql (str "SELECT " (strand-columns-sql "t") "
                          FROM strands t
                          WHERE " where-sql "
                          ORDER BY t.id")
         count-sql (str "SELECT count(*) AS total
                         FROM strands t
                         WHERE " where-sql)]
     (attach-batched-attributes
      ds
      (bounded-select! ds select-sql query-params count-sql limit)
      true
      lean-byte-floor))))

;; Scheduler wakes are weaver runtime coordination state (RFC-009.Q1.OUT), not
;; strands: dedicated tables, no edges, and no participation in the strand
;; query/traversal/burn surface above.

(def ^:private scheduler-wake-keys #{:key :wake-at :handler :payload})

(def ^:private scheduler-history-limit
  "Newest-wins cap on retained scheduler history rows per status (SQL prune)."
  100)

(def ^:private scheduler-wake-columns "key, wake_at, handler, payload, attempts, created_at, updated_at")
(def ^:private scheduler-history-columns "id, key, wake_at, handler, payload, status, attempts, error, recorded_at")

(defn schedule-wake!
  "Persist or replace a durable scheduler wake and return its row.

  wake is a map of :key (non-blank string), :wake-at (java.time.Instant),
  :handler (fully qualified symbol), and optional :payload (nil or a JSON
  object-encodable map). Replacing an existing key resets its attempt count.
  The whole wake map is validated through the shared `::specs/scheduler-wake`
  boundary contract, so DB persistence, the API tiers, and the prose spec share
  one source of truth. Malformed keys, instants, handlers, unsupported payloads,
  or unknown keys throw; wakes are never strands and never appear in strand
  list/ready/query/traversal/burn paths."
  [ds {:keys [key wake-at handler payload] :as wake}]
  (when-not (map? wake)
    (throw (ex-info "Scheduler wake must be a map" {:wake wake})))
  (require-no-unknown-keys! wake scheduler-wake-keys :scheduler-wake)
  (require-valid! ::specs/scheduler-wake wake "Scheduler wake is invalid")
  (execute-one! ds
                [(str "INSERT INTO scheduler_wakes (key, wake_at, handler, payload)
                       VALUES (?, ?, ?, json(?))
                       ON CONFLICT(key) DO UPDATE SET
                         wake_at = excluded.wake_at,
                         handler = excluded.handler,
                         payload = excluded.payload,
                         attempts = 0,
                         updated_at = datetime('now')
                       RETURNING " scheduler-wake-columns)
                 key (.toEpochMilli ^Instant wake-at) (str handler) (->json payload)]))

(defn get-pending-wake
  "Return the pending wake row for key, or nil when it does not exist."
  [ds key]
  (execute-one! ds
                [(str "SELECT " scheduler-wake-columns " FROM scheduler_wakes WHERE key = ?")
                 key]))

(defn- require-pending-wake [ds key]
  (or (get-pending-wake ds key)
      (throw (ex-info "Scheduler wake not found" {:key key}))))

(defn pending-wakes
  "Return all pending wakes ordered by wake-at ascending with a stable key tie-break."
  [ds]
  (execute! ds
            [(str "SELECT " scheduler-wake-columns " FROM scheduler_wakes ORDER BY wake_at ASC, key ASC")]))

(defn due-wakes
  "Return pending wakes with wake-at at or before now, earliest first with a stable key tie-break.

  now is an explicit java.time.Instant so callers control the clock."
  [ds now]
  (require-valid! :skein.scheduler-wake/wake-at now "Scheduler wake-at must be a java.time.Instant")
  (execute! ds
            [(str "SELECT " scheduler-wake-columns " FROM scheduler_wakes WHERE wake_at <= ? ORDER BY wake_at ASC, key ASC")
             (.toEpochMilli ^Instant now)]))

(defn mark-wake-attempt!
  "Atomically increment a pending wake's attempt count, returning the updated row.

  The claim is generation-specific: the single conditional UPDATE matches both
  `key` and the caller-selected `wake-at-millis` (the wake generation), so a key
  that is no longer pending, or that was rescheduled to a different wake-at in a
  concurrent race, yields nil rather than incrementing the replacement row.
  Callers treat nil as an ordinary lost race, never an error."
  [ds key wake-at-millis]
  (require-valid! :skein.scheduler-wake/key key "Scheduler key must be a non-blank string")
  (when-not (integer? wake-at-millis)
    (throw (ex-info "Scheduler wake-at generation must be epoch millis" {:key key :wake-at wake-at-millis})))
  (execute-one! ds
                [(str "UPDATE scheduler_wakes
                       SET attempts = attempts + 1,
                           updated_at = datetime('now')
                       WHERE key = ? AND wake_at = ?
                       RETURNING " scheduler-wake-columns)
                 key wake-at-millis]))

(defn- prune-history! [ds status]
  (execute! ds
            ["DELETE FROM scheduler_history
              WHERE status = ?
                AND id NOT IN (
                  SELECT id FROM scheduler_history
                  WHERE status = ?
                  ORDER BY id DESC
                  LIMIT ?
                )"
             status status scheduler-history-limit]))

(defn- record-retirement! [tx row status error]
  (let [history-row (execute-one! tx
                                  [(str "INSERT INTO scheduler_history
                                            (key, wake_at, handler, payload, status, attempts, error)
                                          VALUES (?, ?, ?, ?, ?, ?, ?)
                                          RETURNING " scheduler-history-columns)
                                   (:key row) (:wake_at row) (:handler row) (:payload row)
                                   status (:attempts row) error])]
    (prune-history! tx status)
    history-row))

(defn- retire-wake!
  "Retire the delivered generation of a wake and record its history from `row`.

  `row` is the delivered wake read before the handler ran. The delete is
  generation-specific — key AND the delivered `wake_at` — mirroring the claim in
  `mark-wake-attempt!`, so a same-key replacement armed during delivery (a
  distinct generation) is left untouched. When the delivered generation no longer
  holds the key, no pending row is removed, but history is still recorded from
  `row` so at-least-once delivery stays observable."
  [ds row status error]
  (let [key (:key row)
        wake-at (:wake_at row)]
    (require-valid! :skein.scheduler-wake/key key "Scheduler key must be a non-blank string")
    (when-not (integer? wake-at)
      (throw (ex-info "Scheduler wake-at generation must be epoch millis" {:key key :wake-at wake-at})))
    (jdbc/with-transaction [tx ds]
      (execute! tx ["DELETE FROM scheduler_wakes WHERE key = ? AND wake_at = ?" key wake-at])
      (record-retirement! tx row status error))))

(defn cancel-wake!
  "Cancel a pending wake by key, recording cancellation history, and return the history row.

  Cancellation is key-based — it removes whichever generation currently holds the
  key, unlike the generation-aware delivery retirements. Missing keys throw."
  [ds key]
  (require-valid! :skein.scheduler-wake/key key "Scheduler key must be a non-blank string")
  (jdbc/with-transaction [tx ds]
    (let [row (require-pending-wake tx key)]
      (execute! tx ["DELETE FROM scheduler_wakes WHERE key = ?" key])
      (record-retirement! tx row "cancelled" nil))))

(defn complete-wake!
  "Record a delivered wake generation as fired-and-completed and return the history row.

  `row` is the delivered wake read before the handler ran. Retirement is
  generation-aware (see `retire-wake!`): a same-key replacement armed during
  delivery survives, and an already-superseded generation records history only."
  [ds row]
  (retire-wake! ds row "completed" nil))

(defn fail-wake!
  "Record a delivered wake generation as failed with error text and return the history row.

  `row` is the delivered wake read before the handler ran; retirement is
  generation-aware (see `retire-wake!`)."
  [ds row error]
  (when-not (and (string? error) (not (str/blank? error)))
    (throw (ex-info "Scheduler failure error must be a non-blank string" {:key (:key row) :error error})))
  (retire-wake! ds row "failed" error))

(defn- recent-history [ds status]
  (execute! ds
            [(str "SELECT " scheduler-history-columns "
                   FROM scheduler_history
                   WHERE status = ?
                   ORDER BY id DESC
                   LIMIT ?")
             status scheduler-history-limit]))

(defn recent-fires
  "Return the most recent completed wakes, newest first, capped by `scheduler-history-limit`."
  [ds]
  (recent-history ds "completed"))

(defn recent-cancellations
  "Return the most recent cancelled wakes, newest first, capped by `scheduler-history-limit`."
  [ds]
  (recent-history ds "cancelled"))

(defn recent-failures
  "Return the most recent failed wakes, newest first, capped by `scheduler-history-limit`."
  [ds]
  (recent-history ds "failed"))
