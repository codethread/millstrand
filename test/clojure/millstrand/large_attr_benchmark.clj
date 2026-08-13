(ns millstrand.large-attr-benchmark
  "Durable synthetic large-attribute load harness.

  Two measurement families answer two different questions from one seed profile:

  Family (a) — gate reproduction (`EAS-PLAN-001.BG1`-`BG4`). Paired synthetic
  fixtures in hand-SQL (a document-column baseline against the row-backed EAV
  schema) measure the write-amp, filtered-scan, `list`-of-500 assembly, and
  `ready`-latency workloads the `eav-attr-storage` merge gate accepted. The
  document column is gone from shipped code, so this family stays a
  document-vs-EAV comparison and needs no runtime; its numbers are the recorded
  durable baseline for any future storage change, not a merge-blocking gate here.

  Family (b) — residual paths (`PROP-LargeAttrScaling-001.F2`). Measures the
  *absolute* cost of the real shipped read paths — full-fidelity point read
  (`weaver/show`, archived rows included), lean list/`ready` assembly
  (`weaver/list-lean`/`ready-lean`), and the unsafe-text-search `LIKE` spool — through
  actual `millstrand.core.db` / `millstrand.spools.unsafe-text-search` code on a disposable
  `:publish? false` world under `with-runtime-binding`, across the `F2` regimes:
  values straddling the 1024-byte lean floor, inlined payloads to MB scale,
  populated archived-row volumes, and an unsafe-text-search corpus.

  Everything here is informational (`PLAN-LargeAttrScaling-001.A5`): this spike
  ships no storage change, so no scenario is merge-blocking. The only gate is the
  structural smoke `deftest` in `millstrand.large-attr-benchmark-test`, which runs one
  iteration of every scenario at tiny `N` with no wall-clock assertion. The
  full-scale run is this namespace's `-main`, gated behind
  `MILLSTRAND_LARGE_ATTR_BENCH_FULL` so the default test suite never triggers it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.db :as db]
            [millstrand.spools.test-support :as ts]
            [millstrand.spools.unsafe-text-search :as unsafe-text-search])
  (:import [java.sql Connection]
           [java.time Instant]
           [java.util Random]
           [org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteConfig$Pragma SQLiteDataSource]))

(def default-options
  "Full-scale seed profile (`PLAN-LargeAttrScaling-001.A6`).

  Gate-reproduction knobs (`:n`, `:payload-*`, `:list-size`, `:patch-size`)
  reproduce the accepted `BG1`-`BG4` baseline; the `F2` knobs
  (`:near-floor-*`, `:mb-payload-*`, `:archived-fraction`, `:corpus-*`) pin the
  residual-path regimes. `--out`/`--seed`/`--n` are the only CLI-tunable knobs;
  the rest are fixed so a later re-run is comparable by construction."
  {:n 250000
   :iterations 5
   :measure-timeout-secs 60
   :list-size 500
   :patch-size 200
   :payload-every 50
   :payload-bytes 65536
   :huge-payload-bytes 262144
   :near-floor-bytes 1100
   :near-floor-every 40
   :mb-payload-bytes (* 1 1024 1024)
   :mb-payload-count 20
   :archived-fraction 0.2
   :corpus-hot-count 100
   :corpus-archived-count 40
   :corpus-needle "ZZQNEEDLEQZZ"
   :point-read-sample 500
   :seed 1337
   :out "target/large-attr-benchmark"})

(def smoke-options
  "Tiny-`N` profile for the structural smoke `deftest`.

  Every scenario runs exactly once at a size that compiles and wires the real
  read paths without a wall-clock cost; the `deftest` supplies `:out` as a
  fresh temp dir it tears down."
  (merge default-options
         {:n 60
          :iterations 1
          :list-size 30
          :patch-size 1
          :payload-every 20
          :payload-bytes 16384
          :huge-payload-bytes 262144
          :near-floor-every 10
          :mb-payload-count 2
          :corpus-hot-count 3
          :corpus-archived-count 2
          :point-read-sample 10}))

(def ^:private usage
  (str/join
   "\n"
   ["Usage: MILLSTRAND_LARGE_ATTR_BENCH_FULL=1 clojure -M:large-attr-bench [options]"
    ""
    "Runs both measurement families at full scale and writes results.edn under --out."
    "Env-gated: without MILLSTRAND_LARGE_ATTR_BENCH_FULL set, the run refuses (this is an"
    "informational load harness, not a gate)."
    ""
    "Options:"
    "  --n N                 synthetic strand count (default 250000)"
    "  --iterations N        timed iterations per workload (default 5)"
    "  --measure-timeout N   query timeout per measured iteration, seconds (default 60)"
    "  --list-size N         rows for assembly read workload (default 500)"
    "  --patch-size N        payload-carrying rows patched for write amp (default 200)"
    "  --payload-every N     every Nth row carries a payload (default 50)"
    "  --payload-bytes N     payload bytes on payload rows (default 65536)"
    "  --huge-payload-bytes N payload bytes for the 256KiB write-amp bucket (default 262144)"
    "  --seed N              synthetic dataset seed (default 1337)"
    "  --out DIR             output directory (default target/large-attr-benchmark)"
    "  --help                print this help"]))

(defn- parse-long-option [opts k raw]
  (try
    (assoc opts k (Long/parseLong raw))
    (catch NumberFormatException e
      (throw (ex-info "Option must be an integer" {:option k :value raw} e)))))

(defn- parse-args [args]
  (loop [opts default-options
         remaining args]
    (if-let [[flag value & more] (seq remaining)]
      (case flag
        "--help" (assoc opts :help true)
        "--n" (recur (parse-long-option opts :n value) more)
        "--iterations" (recur (parse-long-option opts :iterations value) more)
        "--measure-timeout" (recur (parse-long-option opts :measure-timeout-secs value) more)
        "--list-size" (recur (parse-long-option opts :list-size value) more)
        "--patch-size" (recur (parse-long-option opts :patch-size value) more)
        "--payload-every" (recur (parse-long-option opts :payload-every value) more)
        "--payload-bytes" (recur (parse-long-option opts :payload-bytes value) more)
        "--huge-payload-bytes" (recur (parse-long-option opts :huge-payload-bytes value) more)
        "--seed" (recur (parse-long-option opts :seed value) more)
        "--out" (recur (assoc opts :out value) more)
        (throw (ex-info "Unknown benchmark option" {:option flag})))
      opts)))

(defn- require-positive! [opts k]
  (when-not (pos-int? (get opts k))
    (throw (ex-info "Benchmark option must be positive" {:option k :value (get opts k)}))))

(defn- validate-options! [opts]
  (doseq [k [:n :iterations :measure-timeout-secs :list-size :patch-size :payload-every
             :payload-bytes :huge-payload-bytes :near-floor-bytes :near-floor-every
             :mb-payload-bytes :mb-payload-count :corpus-hot-count :corpus-archived-count
             :point-read-sample]]
    (require-positive! opts k))
  (when-not (and (number? (:archived-fraction opts))
                 (< 0 (:archived-fraction opts) 1))
    (throw (ex-info "Archived fraction must be a number in (0, 1)"
                    (select-keys opts [:archived-fraction]))))
  (when (str/blank? (:corpus-needle opts))
    (throw (ex-info "Corpus needle must be a non-blank substring" (select-keys opts [:corpus-needle]))))
  (when (str/blank? (:out opts))
    (throw (ex-info "Output directory must be a non-blank path" (select-keys opts [:out]))))
  (when (< (:payload-bytes opts) 16384)
    (throw (ex-info "Payload bytes must be at least 16KiB for the structural write-amp gate"
                    (select-keys opts [:payload-bytes]))))
  (when (< (:huge-payload-bytes opts) 262144)
    (throw (ex-info "Huge payload bytes must be at least 256KiB for the structural write-amp gate"
                    (select-keys opts [:huge-payload-bytes]))))
  (when (< (:n opts) (:list-size opts))
    (throw (ex-info "List size must not exceed strand count" (select-keys opts [:n :list-size]))))
  (when (< (:n opts) (:point-read-sample opts))
    (throw (ex-info "Point-read sample must not exceed strand count"
                    (select-keys opts [:n :point-read-sample]))))
  (when (< (:n opts) (+ (:corpus-hot-count opts) (:corpus-archived-count opts)))
    (throw (ex-info "Corpus bands must not exceed strand count"
                    (select-keys opts [:n :corpus-hot-count :corpus-archived-count]))))
  (when (> (* 2 (:patch-size opts)) (quot (:n opts) (:payload-every opts)))
    (throw (ex-info "Patch size exceeds generated payload-carrying row count"
                    (select-keys opts [:n :patch-size :payload-every]))))
  opts)

;; ---------------------------------------------------------------------------
;; Shared measurement helpers
;; ---------------------------------------------------------------------------

(defn- payload [^Random rng payload-bytes]
  (let [alphabet "abcdefghijklmnopqrstuvwxyz0123456789"
        n (.length alphabet)
        sb (StringBuilder. payload-bytes)]
    (dotimes [_ payload-bytes]
      (.append sb (.charAt alphabet (.nextInt rng n))))
    (str sb)))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (if (odd? n)
      (double (nth sorted (quot n 2)))
      (/ (+ (nth sorted (dec (quot n 2)))
            (nth sorted (quot n 2)))
         2.0))))

(defn- timed-ms [f]
  (let [start (System/nanoTime)
        result (f)
        elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)]
    {:ms elapsed :result result}))

(defn- measure-workload [f opts]
  (let [samples (vec (for [_ (range (:iterations opts))]
                       (:ms (timed-ms f))))]
    {:samples-ms samples
     :median-ms (median samples)
     :max-ms (apply max samples)}))

;; ---------------------------------------------------------------------------
;; Family (a): gate reproduction (BG1-BG4) on paired synthetic fixtures.
;; Lifted from the eav-attr-storage merge-gate harness; hand-SQL,
;; document-vs-EAV, needs no runtime.
;; ---------------------------------------------------------------------------

(defn- sqlite-datasource [path journal-mode]
  (let [config (doto (SQLiteConfig.)
                 (.setBusyTimeout 5000)
                 (.enforceForeignKeys true)
                 (.setJournalMode journal-mode)
                 (.setPragma SQLiteConfig$Pragma/MMAP_SIZE (str (* 1024 1024 1024)))
                 (.setPragma SQLiteConfig$Pragma/CACHE_SIZE "-200000"))
        ds (SQLiteDataSource.)]
    (.setUrl ds (str "jdbc:sqlite:" path))
    (.setConfig ds config)
    ds))

(defn- execute! [connectable sql-params]
  (jdbc/execute! connectable sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- delete-file! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (io/delete-file file))))

(defn- clean-sqlite-files! [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (delete-file! (str path suffix))))

(defn- setup-document-schema! [ds]
  (doseq [sql ["PRAGMA foreign_keys = ON"
               "CREATE TABLE strands (
                  id TEXT PRIMARY KEY,
                  title TEXT NOT NULL,
                  state TEXT NOT NULL DEFAULT 'active',
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                  CHECK (state IN ('active', 'closed', 'replaced')),
                  CHECK (json_valid(attributes))
                )"
               "CREATE TABLE strand_edges (
                  from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  edge_type TEXT NOT NULL,
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                  CHECK (json_valid(attributes))
                )"
               "CREATE INDEX idx_doc_edges_to_type ON strand_edges(to_strand_id, edge_type)"
               "CREATE INDEX idx_doc_edges_from_type ON strand_edges(from_strand_id, edge_type)"]]
    (execute! ds [sql])))

(defn- setup-eav-schema! [ds]
  (doseq [sql ["PRAGMA foreign_keys = ON"
               "CREATE TABLE strands (
                  id TEXT PRIMARY KEY,
                  title TEXT NOT NULL,
                  state TEXT NOT NULL DEFAULT 'active',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                  CHECK (state IN ('active', 'closed', 'replaced'))
                )"
               "CREATE TABLE attributes (
                  strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  key TEXT NOT NULL,
                  value TEXT NOT NULL CHECK (json_valid(value)),
                  archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
                  PRIMARY KEY (strand_id, key)
                )"
               "CREATE INDEX idx_attributes_key_value_hot ON attributes(key, value) WHERE archived = 0"
               "CREATE INDEX idx_attributes_strand_hot ON attributes(strand_id) WHERE archived = 0"
               "CREATE TABLE strand_edges (
                  from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  edge_type TEXT NOT NULL,
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                  CHECK (json_valid(attributes))
                )"
               "CREATE INDEX idx_eav_edges_to_type ON strand_edges(to_strand_id, edge_type)"
               "CREATE INDEX idx_eav_edges_from_type ON strand_edges(from_strand_id, edge_type)"]]
    (execute! ds [sql])))

(defn- strand-id [i]
  (format "s%06d" i))

(defn- attributes-for [^Random rng i opts]
  (cond-> {:kind (case (mod i 6)
                   0 "task"
                   1 "note"
                   2 "feature"
                   3 "gate"
                   4 "run"
                   "review")
           :owner (str "agent-" (mod i 17))
           :priority (case (mod i 4)
                       0 "low"
                       1 "normal"
                       2 "high"
                       "urgent")
           :status (if (zero? (mod i 7)) "blocked" "open")
           :rank i}
    (zero? (mod i (:payload-every opts)))
    (assoc :body (payload rng (:payload-bytes opts)))))

(defn- insert-document-row! [tx id i state attributes]
  (execute! tx ["INSERT INTO strands (id, title, state, attributes)
                 VALUES (?, ?, ?, json(?))"
                id (str "Synthetic strand " i) state (json/write-str attributes)]))

(defn- insert-eav-row! [tx id i state attributes]
  (execute! tx ["INSERT INTO strands (id, title, state)
                 VALUES (?, ?, ?)"
                id (str "Synthetic strand " i) state])
  (doseq [[k v] (sort-by (comp name key) attributes)]
    (execute! tx ["INSERT INTO attributes (strand_id, key, value)
                   VALUES (?, ?, json(?))"
                  id (name k) (json/write-str v)])))

(defn- insert-edge! [tx from to]
  (execute! tx ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                 VALUES (?, ?, 'depends-on', '{}')"
                from to]))

(defn- generate-fixtures! [doc-ds eav-ds opts]
  (let [doc-rng (Random. (:seed opts))
        eav-rng (Random. (:seed opts))]
    (jdbc/with-transaction [doc-tx doc-ds]
      (jdbc/with-transaction [eav-tx eav-ds]
        (dotimes [idx (:n opts)]
          (let [i (inc idx)
                id (strand-id i)
                state (if (< (mod i 10) 3) "active" "closed")
                doc-attrs (attributes-for doc-rng i opts)
                eav-attrs (attributes-for eav-rng i opts)]
            (insert-document-row! doc-tx id i state doc-attrs)
            (insert-eav-row! eav-tx id i state eav-attrs)
            (when (and (> i 1)
                       (zero? (mod i 4)))
              (insert-edge! doc-tx id (strand-id (dec i)))
              (insert-edge! eav-tx id (strand-id (dec i))))))))))

(defn- measure-query [ds sql-params opts]
  (measure-workload
   #(doall (jdbc/execute! ds
                          sql-params
                          {:builder-fn rs/as-unqualified-lower-maps
                           :timeout (:measure-timeout-secs opts)}))
   opts))

(def ^:private doc-filtered-sql
  ["SELECT id, title, state, attributes, created_at, updated_at
    FROM strands
    WHERE json_extract(attributes, '$.\"kind\"') = 'task'
      AND state = 'active'
    ORDER BY id"])

(def ^:private eav-filtered-sql
  ["SELECT t.id, t.title, t.state,
          COALESCE((
            SELECT json_group_object(key, json(value))
            FROM (
              SELECT key, value
              FROM attributes
              WHERE strand_id = t.id
                AND archived = 0
              ORDER BY key
            )
          ), '{}') AS attributes,
          t.created_at, t.updated_at
    FROM strands t
    WHERE t.state = 'active'
      AND t.id IN (
        SELECT a.strand_id
        FROM attributes AS a
        WHERE a.archived = 0
          AND a.key = 'kind'
          AND json_extract(a.value, '$') = 'task'
      )
    ORDER BY t.id"])

(def ^:private doc-ready-sql
  ["SELECT id, title, state, attributes, created_at, updated_at
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
    ORDER BY t.id"])

(def ^:private eav-ready-base-sql
  ["SELECT t.id, t.title, t.state, t.created_at, t.updated_at
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
    ORDER BY t.id"])

(def ^:private eav-ready-sql
  ["SELECT t.id, t.title, t.state,
          COALESCE((
            SELECT json_group_object(key, json(value))
            FROM (
              SELECT key, value
              FROM attributes
              WHERE strand_id = t.id
                AND archived = 0
              ORDER BY key
            )
          ), '{}') AS attributes,
          t.created_at, t.updated_at
    FROM strands t
    WHERE t.id IN (
      SELECT id FROM (
        SELECT t.id
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
      )
    )
    ORDER BY t.id"])

(def ^:private attribute-assembly-batch-size 30000)
(def ^:private lean-attribute-byte-floor
  "The shipped lean-read omission floor (`millstrand.spools.batteries`), matched here
  so the residual-path family measures `weaver/list-lean`/`ready-lean` with the
  production floor value."
  1024)

(defn- eav-attribute-value-sql [lean?]
  (if lean?
    (str "CASE WHEN length(CAST(value AS BLOB)) > ? "
         "THEN json_object('millstrand/omitted', json('true'), 'bytes', length(CAST(value AS BLOB))) "
         "ELSE json(value) END")
    "json(value)"))

(defn- assemble-attribute-batches [ds ids opts lean?]
  (let [attrs-by-id
        (into {}
              (map (juxt :strand_id :attributes))
              (mapcat
               (fn [batch]
                 (jdbc/execute! ds
                                (into [(str "SELECT strand_id,
                                                    COALESCE(json_group_object(key, " (eav-attribute-value-sql lean?) "), '{}') AS attributes
                                             FROM (
                                               SELECT strand_id, key, value
                                               FROM attributes
                                               WHERE archived = 0
                                                 AND strand_id IN (" (str/join ", " (repeat (count batch) "?")) ")
                                             )
                                             GROUP BY strand_id")]
                                      (cond-> []
                                        lean? (conj lean-attribute-byte-floor)
                                        true (into batch)))
                                {:builder-fn rs/as-unqualified-lower-maps
                                 :timeout (:measure-timeout-secs opts)}))
               (partition-all attribute-assembly-batch-size ids)))]
    attrs-by-id))

(defn- eav-ready-batched [ds opts]
  (let [rows (jdbc/execute! ds eav-ready-base-sql {:builder-fn rs/as-unqualified-lower-maps
                                                   :timeout (:measure-timeout-secs opts)})
        ids (mapv :id rows)
        attrs-by-id (assemble-attribute-batches ds ids opts true)]
    (mapv #(assoc % :attributes (get attrs-by-id (:id %) "{}")) rows)))

(defn- list-sql [table-shape ids]
  (let [placeholders (str/join ", " (repeat (count ids) "?"))]
    (case table-shape
      :document (into [(str "SELECT id, title, state, attributes, created_at, updated_at
                             FROM strands
                             WHERE id IN (" placeholders ")
                             ORDER BY id")]
                      ids)
      :eav (into [(str "SELECT t.id, t.title, t.state,
                               COALESCE((
                                 SELECT json_group_object(key, json(value))
                                 FROM (
                                   SELECT key, value
                                   FROM attributes
                                   WHERE strand_id = t.id
                                     AND archived = 0
                                   ORDER BY key
                                 )
                               ), '{}') AS attributes,
                               t.created_at, t.updated_at
                        FROM strands t
                        WHERE t.id IN (" placeholders ")
                        ORDER BY t.id")]
                 ids))))

(defn- payload-target-ids [opts offset]
  (mapv #(strand-id (* (:payload-every opts) (+ offset (inc %)))) (range (:patch-size opts))))

(defn- payload-free-target-ids [opts]
  (mapv #(strand-id (inc (* (:payload-every opts) %))) (range (:patch-size opts))))

(defn- file-size [path]
  (let [file (io/file path)]
    (if (.exists file)
      (.length file)
      0)))

(defn- with-rollback-journal [db-path f]
  (let [ds (sqlite-datasource db-path SQLiteConfig$JournalMode/DELETE)]
    (execute! ds ["PRAGMA journal_mode = DELETE"])
    (f ds)))

(defn- patch-write-samples [db-path sql-fn ids]
  (let [wal-path (str db-path "-wal")
        ds (sqlite-datasource db-path SQLiteConfig$JournalMode/WAL)]
    (execute! ds ["PRAGMA wal_autocheckpoint = 1000000"])
    (execute! ds ["PRAGMA wal_checkpoint(TRUNCATE)"])
    (with-open [conn (jdbc/get-connection ds)]
      (execute! conn ["PRAGMA wal_autocheckpoint = 0"])
      (mapv
       (fn [[idx id]]
         (let [before-size (file-size wal-path)]
           (.setAutoCommit conn false)
           ((sql-fn conn id idx))
           (.commit conn)
           (.setAutoCommit conn true)
           (max 0 (- (file-size wal-path) before-size))))
       (map-indexed vector ids)))))

(defn- document-patch-fn [^Connection conn id idx]
  (fn []
    (execute! conn ["UPDATE strands
                    SET attributes = json_patch(attributes, json(?)),
                        updated_at = datetime('now')
                    WHERE id = ?"
                    (json/write-str {:status (str "done-" idx) :_w4seq idx}) id])))

(defn- eav-patch-fn [^Connection conn id idx]
  (fn []
    (execute! conn ["INSERT INTO attributes (strand_id, key, value)
                    VALUES (?, 'status', json(?))
                    ON CONFLICT(strand_id, key) DO UPDATE
                    SET value = excluded.value,
                        archived = 0"
                    id (json/write-str (str "done-" idx))])
    (execute! conn ["INSERT INTO attributes (strand_id, key, value)
                    VALUES (?, '_w4seq', json(?))
                    ON CONFLICT(strand_id, key) DO UPDATE
                    SET value = excluded.value,
                        archived = 0"
                    id (json/write-str idx)])
    (execute! conn ["UPDATE strands SET updated_at = datetime('now') WHERE id = ?" id])))

(defn- set-document-payload! [tx id value]
  (execute! tx ["UPDATE strands
                 SET attributes = json_set(attributes, '$.body', json(?))
                 WHERE id = ?"
                (json/write-str value) id]))

(defn- set-eav-payload! [tx id value]
  (execute! tx ["INSERT INTO attributes (strand_id, key, value)
                 VALUES (?, 'body', json(?))
                 ON CONFLICT(strand_id, key) DO UPDATE
                 SET value = excluded.value,
                     archived = 0"
                id (json/write-str value)]))

(defn- seed-huge-payload-bucket! [doc-path eav-path ids opts]
  (let [value (payload (Random. (+ 31 (:seed opts))) (:huge-payload-bytes opts))]
    (with-rollback-journal
      doc-path
      (fn [ds]
        (jdbc/with-transaction [tx ds]
          (doseq [id ids]
            (set-document-payload! tx id value)))))
    (with-rollback-journal
      eav-path
      (fn [ds]
        (jdbc/with-transaction [tx ds]
          (doseq [id ids]
            (set-eav-payload! tx id value)))))))

(defn- write-amp-bucket [doc-path eav-path ids]
  (let [doc-samples (patch-write-samples doc-path document-patch-fn ids)
        eav-samples (patch-write-samples eav-path eav-patch-fn ids)
        doc-median (median doc-samples)
        eav-median (median eav-samples)]
    {:patched-rows (count ids)
     :document-patch-bytes {:median doc-median :samples doc-samples}
     :eav-patch-bytes {:median eav-median :samples eav-samples}
     :reduction (if (pos? eav-median) (/ (double doc-median) eav-median) ##Inf)}))

(defn- combined-write-amp-bucket [buckets]
  (let [doc-samples (mapcat #(get-in % [:document-patch-bytes :samples]) buckets)
        eav-samples (mapcat #(get-in % [:eav-patch-bytes :samples]) buckets)
        doc-median (median doc-samples)
        eav-median (median eav-samples)]
    {:patched-rows (count doc-samples)
     :document-patch-bytes {:median doc-median :samples (vec doc-samples)}
     :eav-patch-bytes {:median eav-median :samples (vec eav-samples)}
     :reduction (if (pos? eav-median) (/ (double doc-median) eav-median) ##Inf)}))

(defn- measure-write-amp [doc-path eav-path opts]
  (let [free-ids (payload-free-target-ids opts)
        large-ids (payload-target-ids opts 0)
        huge-ids (payload-target-ids opts (:patch-size opts))
        _ (seed-huge-payload-bucket! doc-path eav-path huge-ids opts)
        free (write-amp-bucket doc-path eav-path free-ids)
        large (write-amp-bucket doc-path eav-path large-ids)
        huge (write-amp-bucket doc-path eav-path huge-ids)
        ge-16kb (combined-write-amp-bucket [large huge])
        eav-independence (if (pos? (get-in free [:eav-patch-bytes :median]))
                           (/ (double (get-in huge [:eav-patch-bytes :median]))
                              (get-in free [:eav-patch-bytes :median]))
                           ##Inf)]
    {:accepted-small-row-cost "Payload-free rows may be slower than document rewrites because an EAV patch has a fixed page-granularity floor."
     :payload-free free
     :payload-64kb large
     :payload-ge-16kb ge-16kb
     :payload-256kb huge
     :eav-256kb-vs-free-patch-bytes eav-independence}))

(defn- row-count [ds sql-params]
  (count (execute! ds sql-params)))

(defn- build-gate-result [opts]
  (let [out-dir (io/file (:out opts))
        _ (.mkdirs out-dir)
        doc-path (.getPath (io/file out-dir "document.sqlite"))
        eav-path (.getPath (io/file out-dir "eav.sqlite"))
        _ (doseq [path [doc-path eav-path]] (clean-sqlite-files! path))
        doc-ds (sqlite-datasource doc-path SQLiteConfig$JournalMode/WAL)
        eav-ds (sqlite-datasource eav-path SQLiteConfig$JournalMode/WAL)
        list-ids (mapv strand-id (range 1 (inc (:list-size opts))))]
    (setup-document-schema! doc-ds)
    (setup-eav-schema! eav-ds)
    (let [generated (:ms (timed-ms #(generate-fixtures! doc-ds eav-ds opts)))
          _ (doseq [ds [doc-ds eav-ds]]
              (execute! ds ["ANALYZE"])
              (execute! ds ["PRAGMA optimize"]))
          filtered-document (measure-query doc-ds doc-filtered-sql opts)
          filtered-eav (measure-query eav-ds eav-filtered-sql opts)
          ready-document (measure-query doc-ds doc-ready-sql opts)
          ready-eav (measure-workload #(eav-ready-batched eav-ds opts) opts)
          list-document (measure-query doc-ds (list-sql :document list-ids) opts)
          list-eav (measure-query eav-ds (list-sql :eav list-ids) opts)
          write-amp (measure-write-amp doc-path eav-path opts)]
      {:started-at (str (Instant/now))
       :generation-ms generated
       :fixture-paths {:document doc-path :eav eav-path}
       :row-counts {:strands (:n opts)
                    :document-filtered (row-count doc-ds doc-filtered-sql)
                    :eav-filtered (row-count eav-ds eav-filtered-sql)
                    :document-ready (row-count doc-ds doc-ready-sql)
                    :eav-ready (row-count eav-ds eav-ready-sql)}
       :workloads {:write-amp write-amp
                   :filtered-scan {:document filtered-document :eav filtered-eav}
                   :ready {:document ready-document :eav ready-eav}
                   :list-assembly-500 {:document list-document :eav list-eav}}})))

(defn- gate-checks [result]
  (let [w (get-in result [:workloads :write-amp])
        filtered (get-in result [:workloads :filtered-scan])
        ready (get-in result [:workloads :ready])
        assembly (get-in result [:workloads :list-assembly-500])]
    {:write-amp-payload-ge-16kb (>= (get-in w [:payload-ge-16kb :reduction]) 5.0)
     :write-amp-payload-independence (<= (:eav-256kb-vs-free-patch-bytes w) 1.5)
     :filtered-scan (<= (get-in filtered [:eav :median-ms])
                        (get-in filtered [:document :median-ms]))
     ;; 1.7x rounds up the accepted 1.69x in EAS plan P7/BG2; ncso4 tracks bounded
     ;; frontier queries.
     :ready (<= (get-in ready [:eav :median-ms])
                (* 1.7 (get-in ready [:document :median-ms])))
     :list-assembly-500 (<= (get-in assembly [:eav :median-ms])
                            (* 2.0 (get-in assembly [:document :median-ms])))}))

(defn- run-gate-reproduction
  "Family (a): reproduce the `BG1`-`BG4` gate on paired synthetic fixtures.

  Returns the workloads and row counts plus the informational `:checks` against
  the recorded durable baseline — the accepted targets are preserved as data,
  not re-imposed as a merge-blocking gate (`PLAN-LargeAttrScaling-001.A5`)."
  [opts]
  (let [result (build-gate-result opts)
        checks (gate-checks result)]
    (assoc result
           :checks checks
           :meets-recorded-baseline? (every? true? (vals checks)))))

;; ---------------------------------------------------------------------------
;; Family (b): F2 residual read paths through shipped millstrand.core.db /
;; millstrand.spools.unsafe-text-search on a disposable :publish? false runtime.
;; ---------------------------------------------------------------------------

(defn- residual-attributes
  "Synthetic attribute map for strand `idx` carrying the `F2` regimes.

  Base attrs on every strand; a `:body` payload every `:payload-every`; a
  `:near-floor` value straddling the 1024-byte lean floor every
  `:near-floor-every`; an MB-scale `:mb-payload` on the first `:mb-payload-count`
  strands; and a distinctive `:corpus` needle on the fixed hot + archived text
  bands so unsafe-text-search has bounded matches at any `N`."
  [^Random rng idx opts]
  (let [{:keys [payload-every payload-bytes near-floor-bytes near-floor-every
                mb-payload-bytes mb-payload-count corpus-hot-count
                corpus-archived-count corpus-needle]} opts
        needle-end (+ corpus-hot-count corpus-archived-count)]
    (cond-> {:kind (case (mod idx 6)
                     0 "task"
                     1 "note"
                     2 "feature"
                     3 "gate"
                     4 "run"
                     "review")
             :owner (str "agent-" (mod idx 17))
             :priority (case (mod idx 4)
                         0 "low"
                         1 "normal"
                         2 "high"
                         "urgent")
             :status (if (zero? (mod idx 7)) "blocked" "open")
             :rank idx}
      (zero? (mod idx payload-every))
      (assoc :body (payload rng payload-bytes))
      (zero? (mod idx near-floor-every))
      (assoc :near-floor (payload rng near-floor-bytes))
      (< idx mb-payload-count)
      (assoc :mb-payload (payload rng mb-payload-bytes))
      (< idx needle-end)
      (assoc :corpus (str (payload rng 24) corpus-needle (payload rng 24))))))

(defn- residual-archived?
  "True when strand `idx` should carry archived rows.

  The fixed needle-archived band is always archived so unsafe-text-search `--archived`
  has cold matches; a deterministic fraction of the remaining strands is archived
  for volume so the archived-included point read scans populated cold data."
  [idx opts]
  (let [{:keys [corpus-hot-count corpus-archived-count archived-fraction]} opts
        needle-end (+ corpus-hot-count corpus-archived-count)]
    (or (and (>= idx corpus-hot-count) (< idx needle-end))
        (and (>= idx needle-end)
             (zero? (mod idx (max 1 (long (/ 1.0 archived-fraction)))))))))

(defn- strand-count [rt]
  (-> (db/execute-one! (:datasource rt) ["SELECT count(*) AS n FROM strands"]) :n))

(defn- await-world-ready!
  "Block until the seeded world reports `expected` strands.

  Seeding writes synchronously, so this normally returns on the first poll; it
  exists so world readiness honors `MILLSTRAND_TEST_AWAIT_SCALE`
  (`PLAN-LargeAttrScaling-001.V4`) via the shared poll budget."
  [rt expected]
  (ts/poll-until
   #(= expected (strand-count rt))
   {:timeout-ms (ts/await-budget-ms)
    :on-timeout #(throw (ex-info "Seeded world did not reach expected strand count"
                                 {:expected expected :actual (strand-count rt)}))}))

(defn- seed-residual-world!
  "Seed the disposable world through the shipped storage write path.

  `db/add-strand!` and `db/archive-attributes!` write the exact rows, indexes,
  and `archived` flags production carries; going through core storage rather than
  `weaver/add!` keeps seeding off the event queue (whose 1024-slot backpressure a
  250k bulk seed would trip) while measuring storage, not event fanout. Returns
  seed metadata including the ids sampled for point reads."
  [rt opts]
  (let [ds (:datasource rt)
        rng (Random. (:seed opts))
        n (:n opts)]
    (loop [idx 0
           ids (transient [])
           archived-ids (transient [])]
      (if (< idx n)
        (let [attrs (residual-attributes rng idx opts)
              created (db/add-strand! ds {:title (str "Synthetic strand " idx)
                                          :attributes attrs})
              id (:id created)
              archived? (residual-archived? idx opts)]
          (when archived?
            (db/archive-attributes! ds id))
          (recur (inc idx)
                 (conj! ids id)
                 (if archived? (conj! archived-ids id) archived-ids)))
        (let [ids (persistent! ids)
              archived-ids (persistent! archived-ids)]
          {:strand-count n
           :ids ids
           :archived-ids archived-ids
           :archived-count (count archived-ids)
           :point-read-ids (vec (take (:point-read-sample opts) ids))})))))

(defn- attribute-keys [strand]
  (vec (sort (map name (keys (:attributes strand))))))

(defn- measure-point-read
  "Full-fidelity point read through `weaver/show` (archived rows included)."
  [rt seed opts]
  (let [sample-ids (:point-read-ids seed)
        archived-id (first (:archived-ids seed))
        m (measure-workload #(mapv (fn [id] (weaver/show rt id)) sample-ids) opts)
        sample (weaver/show rt (first sample-ids))
        archived-sample (when archived-id (weaver/show rt archived-id))]
    (assoc m
           :scenario :point-read-archived-included
           :sample-size (count sample-ids)
           :sample-strand-id (:id sample)
           :sample-attribute-keys (attribute-keys sample)
           :archived-sample-strand-id (:id archived-sample)
           :archived-sample-attribute-keys (when archived-sample (attribute-keys archived-sample)))))

(defn- omitted-descriptor [rows]
  (some (fn [strand]
          (some (fn [[k v]] (when (and (map? v) (:millstrand/omitted v)) [(name k) v]))
                (:attributes strand)))
        rows))

(defn- measure-lean-assembly
  "Lean list/`ready` assembly through the shipped `attribute-json-by-strand-id`
  path with the production 1024-byte floor."
  [rt scenario read-fn opts]
  (let [result (volatile! nil)
        m (measure-workload #(vreset! result (read-fn rt lean-attribute-byte-floor)) opts)
        rows @result]
    (assoc m
           :scenario scenario
           :rows (count rows)
           :omitted-descriptor (omitted-descriptor rows)
           ;; sample the first row with hot attributes: strand ids are random, so the
           ;; id-ordered first row may be a fully-archived strand whose lean map is empty
           :sample-attribute-keys (some->> rows (map attribute-keys) (filter seq) first))))

(defn- measure-unsafe-text-search
  "Text-search `LIKE` scan through the shipped spool.

  `archived?` selects the branch that also scans cold rows the query language
  cannot see. `:limit` is sized to the fixed corpus bands so a match at any `N`
  never overflows the loud row cap."
  [rt archived? opts]
  (let [needle (:corpus-needle opts)
        limit (+ (:corpus-hot-count opts) (:corpus-archived-count opts) 50)
        result (volatile! nil)
        m (measure-workload
           #(vreset! result (unsafe-text-search/search-rows rt
                                                            (cond-> {:substring needle :limit limit}
                                                              archived? (assoc :archived? true))))
           opts)
        rows @result]
    (assoc m
           :scenario (if archived? :unsafe-text-search-like-archived :unsafe-text-search-like-hot)
           :archived? (boolean archived?)
           :rows (count rows)
           :sample (first rows))))

(defn- run-residual-family
  "Family (b): boot a disposable `:publish? false` world under
  `with-runtime-binding`, seed the `F2` regimes through the real write path, and
  measure every residual read path with the runtime passed explicitly."
  [opts]
  (ts/with-runtime
    {:publish? false :prefix "large-attr-bench"}
    (fn [rt _config-dir]
      (let [seed (seed-residual-world! rt opts)]
        (await-world-ready! rt (:strand-count seed))
        {:seed (dissoc seed :ids :archived-ids)
         :point-read (measure-point-read rt seed opts)
         :lean-list (measure-lean-assembly rt :lean-list-assembly weaver/list-lean opts)
         :lean-ready (measure-lean-assembly rt :lean-ready-assembly weaver/ready-lean opts)
         :unsafe-text-search-hot (measure-unsafe-text-search rt false opts)
         :unsafe-text-search-archived (measure-unsafe-text-search rt true opts)}))))

;; ---------------------------------------------------------------------------
;; Combined run + entrypoint
;; ---------------------------------------------------------------------------

(defn run-all
  "Run both measurement families for `opts` and return a combined result map.

  `opts` is validated first; the gate-reproduction family writes throwaway
  SQLite fixtures under `:out`, and the residual-path family boots its own
  disposable in-process world. The structural smoke and the full-scale `-main`
  both funnel through here — the smoke at `smoke-options`, the run at
  `default-options`."
  [opts]
  (let [opts (validate-options! opts)]
    {:started-at (str (Instant/now))
     :options opts
     :gate-reproduction (run-gate-reproduction opts)
     :residual-paths (run-residual-family opts)}))

(defn- write-result! [out-dir result]
  (let [file (io/file out-dir "results.edn")]
    (.mkdirs (io/file out-dir))
    (spit file (with-out-str (pp/pprint result)))
    (.getPath file)))

(defn -main
  "Env-gated full-scale entrypoint.

  Refuses unless `MILLSTRAND_LARGE_ATTR_BENCH_FULL` is set (this is an informational
  load harness, never a gate; the default suite must never trigger a 250k run —
  `PLAN-LargeAttrScaling-001.A4`/`R2`). Runs both families at the parsed profile,
  writes `results.edn` under `--out`, and prints the informational baseline
  checks."
  [& args]
  (try
    (let [opts (parse-args args)]
      (cond
        (:help opts)
        (println usage)

        (str/blank? (or (System/getenv "MILLSTRAND_LARGE_ATTR_BENCH_FULL") ""))
        (binding [*out* *err*]
          (println "Refusing the full-scale large-attr benchmark: set MILLSTRAND_LARGE_ATTR_BENCH_FULL=1.")
          (println "This is an informational load harness, not a merge gate (see PLAN-LargeAttrScaling-001.A4).")
          (System/exit 3))

        :else
        (let [result (run-all opts)
              result-path (write-result! (:out opts) result)]
          (println "Large-attr benchmark complete.")
          (println "Result file:" result-path)
          (println "Gate-reproduction baseline checks (informational):")
          (pp/pprint (get-in result [:gate-reproduction :checks])))))
    (catch Throwable t
      (binding [*out* *err*]
        (println (.getMessage t))
        (when-let [data (ex-data t)]
          (pp/pprint data)))
      (System/exit 2))))
