(ns skein.core.db-test
  "Tests for skein.core.db: strand/edge persistence, queries, and SQLite behavior."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]))

(defn delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn temp-db-file []
  (let [file (java.io.File/createTempFile "skein-db-test" ".sqlite")]
    (.delete file)
    (.getAbsolutePath file)))

(defn with-db [f]
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (db/init! ds)
      (f ds)
      (finally
        (delete-sqlite-family! db-file)))))

(defn- with-raw-db [f]
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (f ds)
      (finally
        (delete-sqlite-family! db-file)))))

(defn- pragma-value [ds pragma]
  (first (vals (db/execute-one! ds [(str "PRAGMA " pragma)]))))

(defn- private-db-var [symbol]
  (or (ns-resolve 'skein.core.db symbol)
      (throw (ex-info "Missing private database test seam" {:symbol symbol}))))

(defn- schema-statements []
  @(private-db-var 'schema-sql))

(defn- execute-schema! [ds statements]
  (doseq [statement statements]
    (db/execute! ds statement)))

(defn- replace-schema-sql [statements needle replacement]
  (mapv (fn [[sql]]
          [(if (str/includes? sql needle)
             (str/replace sql needle replacement)
             sql)])
        statements))

(defn- caught-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest sqlite-file-storage-applies-open-pragmas
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (is (= "wal" (pragma-value ds "journal_mode")))
      (is (= 268435456 (pragma-value ds "mmap_size")))
      (is (= -20000 (pragma-value ds "cache_size")))
      (finally
        (delete-sqlite-family! db-file)))))

(deftest init-creates-strand-schema
  (with-db
    (fn [ds]
      (is (= 1 (pragma-value ds "user_version")))
      (is (= #{"id" "title" "state" "created_at" "updated_at"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strands)"])))))
      (is (= #{"strand_id" "key" "value" "archived"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(attributes)"])))))
      (is (some #(= "idx_attributes_key_value_hot" (:name %))
                (db/execute! ds ["PRAGMA index_list(attributes)"])))
      (is (= #{"from_strand_id" "to_strand_id" "edge_type" "attributes"}
             (set (map :name (db/execute! ds ["PRAGMA table_info(strand_edges)"])))))
      (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'indexed_attr_keys'"])))
      (is (= #{"depends-on" "parent-of" "serves" "supersedes" "notes"}
             (set (db/list-acyclic-relations ds))))
      (is (some #(= "idx_strand_edges_single_serves" (:name %))
                (db/execute! ds ["PRAGMA index_list(strand_edges)"])))
      (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tasks', 'task_edges')"]))))))

(deftest schema-generation-classification-covers-every-route
  (let [classify (private-db-var 'classify-generation)]
    (is (= :bootstrap (classify 0 1 true)))
    (is (= :adopt (classify 0 1 false)))
    (is (= :proceed (classify 1 1 false)))
    (is (= :refuse-newer (classify 2 1 false)))
    (is (= :refuse-older (classify 1 2 false)))
    (is (thrown? clojure.lang.ExceptionInfo (classify -1 1 false)))
    (is (thrown? clojure.lang.ExceptionInfo (classify 0 0 true)))
    (is (thrown? clojure.lang.ExceptionInfo (classify 0 1 nil)))))

(def ^:private managed-table-order
  [["strands" "id"]
   ["attributes" "strand_id, key"]
   ["strand_edges" "from_strand_id, to_strand_id, edge_type"]
   ["acyclic_relations" "relation"]
   ["scheduler_wakes" "key"]
   ["scheduler_history" "id"]])

(defn- logical-managed-rows [ds]
  (into {}
        (map (fn [[table order-by]]
               [table (db/execute! ds [(str "SELECT * FROM " table " ORDER BY " order-by)])]))
        managed-table-order))

(defn- populate-managed-tables! [ds]
  (db/execute! ds ["INSERT INTO strands (id, title) VALUES ('a', 'A'), ('b', 'B')"])
  (db/execute! ds ["INSERT INTO attributes (strand_id, key, value) VALUES ('a', 'owner', '\"agent\"')"])
  (db/execute! ds ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type) VALUES ('a', 'b', 'depends-on')"])
  (doseq [relation ["depends-on" "parent-of" "supersedes" "serves" "notes"]]
    (db/execute! ds ["INSERT INTO acyclic_relations (relation) VALUES (?)" relation]))
  (db/execute! ds ["INSERT INTO scheduler_wakes (key, wake_at, handler) VALUES ('wake', 10, 'handler')"])
  (db/execute! ds ["INSERT INTO scheduler_history (id, key, wake_at, handler, status) VALUES (7, 'past', 5, 'handler', 'completed')"]))

(deftest unstamped-current-schema-is-adopted-without-changing-logical-rows
  (with-raw-db
    (fn [ds]
      (execute-schema! ds (schema-statements))
      (populate-managed-tables! ds)
      (let [before (logical-managed-rows ds)]
        (is (zero? (pragma-value ds "user_version")))
        (db/init! ds)
        (is (= 1 (pragma-value ds "user_version")))
        (is (= before (logical-managed-rows ds)))
        (db/init! ds)
        (is (= before (logical-managed-rows ds)))))))

(deftest bootstrap-ignores-and-preserves-non-skein-objects
  (with-raw-db
    (fn [ds]
      (db/execute! ds ["CREATE TABLE user_notes (body TEXT NOT NULL)"])
      (db/execute! ds ["INSERT INTO user_notes (body) VALUES ('keep me')"])
      (db/init! ds)
      (is (= 1 (pragma-value ds "user_version")))
      (is (= [{:body "keep me"}]
             (db/execute! ds ["SELECT body FROM user_notes"]))))))

(deftest incompatible-unstamped-schemas-are-refused-without-stamping
  (let [schema (schema-statements)
        cases
        {"missing column"
         (replace-schema-sql schema
                             "       updated_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (state"
                             "       CHECK (state")
         "extra column"
         (replace-schema-sql schema
                             "       title TEXT NOT NULL,"
                             "       title TEXT NOT NULL,\n       unexpected TEXT,")
         "wrong index"
         (replace-schema-sql schema
                             "attributes(key, value)"
                             "attributes(value, key)")
         "legacy edge constraint"
         (replace-schema-sql schema
                             "       PRIMARY KEY (from_strand_id, to_strand_id, edge_type),"
                             "       PRIMARY KEY (from_strand_id, to_strand_id, edge_type),\n       CHECK (edge_type IN ('depends-on', 'related-to')),")}]
    (doseq [[label statements] cases]
      (testing label
        (with-raw-db
          (fn [ds]
            (execute-schema! ds statements)
            (let [error (caught-ex-info #(db/init! ds))]
              (is (= :schema-mismatch (:reason (ex-data error))))
              (is (zero? (:found-generation (ex-data error))))
              (is (= 1 (:expected-generation (ex-data error))))
              (is (zero? (pragma-value ds "user_version"))))))))))

(def ^:private synthetic-additive-sql
  ["CREATE TABLE IF NOT EXISTS synthetic_aux (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL CHECK (length(value) > 0)
    )"])

(def ^:private synthetic-additive-object #{[:table "synthetic_aux"]})

(defn- initialize-synthetic-schema! [ds generation]
  (db/init!
   ds
   (conj (schema-statements) synthetic-additive-sql)
   generation
   synthetic-additive-object))

(deftest additive-object-seam-backfills-only-declared-additions
  (testing "a stamped current database gains a missing additive object"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (schema-statements))
        (db/execute! ds ["PRAGMA user_version = 1"])
        (initialize-synthetic-schema! ds 1)
        (is (= "synthetic_aux"
               (:name (db/execute-one! ds ["SELECT name FROM sqlite_master WHERE name = 'synthetic_aux'"]))))
        (is (= 1 (pragma-value ds "user_version"))))))
  (testing "an unstamped current database gains the addition before it is stamped"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (schema-statements))
        (initialize-synthetic-schema! ds 1)
        (is (= "synthetic_aux"
               (:name (db/execute-one! ds ["SELECT name FROM sqlite_master WHERE name = 'synthetic_aux'"]))))
        (is (= 1 (pragma-value ds "user_version"))))))
  (testing "a missing baseline object is refused before DDL can recreate it"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (conj (schema-statements) synthetic-additive-sql))
        (db/execute! ds ["DROP INDEX idx_strand_edges_to"])
        (db/execute! ds ["PRAGMA user_version = 1"])
        (let [error (caught-ex-info #(initialize-synthetic-schema! ds 1))]
          (is (= :schema-mismatch (:reason (ex-data error))))
          (is (= :pre-ddl (:mode (ex-data error))))
          (is (nil? (db/execute-one! ds ["SELECT name FROM sqlite_master WHERE name = 'idx_strand_edges_to'"])))
          (is (= 1 (pragma-value ds "user_version")))))))
  (testing "a present malformed additive object is refused"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (schema-statements))
        (db/execute! ds ["CREATE TABLE synthetic_aux (key TEXT PRIMARY KEY, value INTEGER)"])
        (let [error (caught-ex-info #(initialize-synthetic-schema! ds 1))]
          (is (= :schema-mismatch (:reason (ex-data error))))
          (is (= [[:table "synthetic_aux"]]
                 (:mismatched-objects (ex-data error))))
          (is (zero? (pragma-value ds "user_version"))))))))

(deftest schema-generation-skew-is-refused-with-diagnostic-data
  (testing "a newer database identifies the binary as too old"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (schema-statements))
        (db/execute! ds ["INSERT INTO strands (id, title) VALUES ('kept', 'Kept')"])
        (db/execute! ds ["PRAGMA user_version = 2"])
        (let [error (caught-ex-info #(db/init! ds))]
          (is (str/includes? (ex-message error) "binary is too old"))
          (is (= {:reason :newer-schema-generation
                  :found-generation 2
                  :expected-generation 1}
                 (ex-data error)))
          (is (= "Kept" (:title (db/execute-one! ds ["SELECT title FROM strands WHERE id = 'kept'"]))))
          (is (= 2 (pragma-value ds "user_version")))))))
  (testing "an older database points at the maintained migration path"
    (with-raw-db
      (fn [ds]
        (execute-schema! ds (schema-statements))
        (db/execute! ds ["INSERT INTO strands (id, title) VALUES ('kept', 'Kept')"])
        (db/execute! ds ["PRAGMA user_version = 1"])
        (let [error (caught-ex-info #(db/init! ds (schema-statements) 2 #{}))]
          (is (str/includes? (ex-message error) "maintained forward-migration path"))
          (is (= {:reason :older-schema-generation
                  :found-generation 1
                  :expected-generation 2}
                 (ex-data error)))
          (is (= "Kept" (:title (db/execute-one! ds ["SELECT title FROM strands WHERE id = 'kept'"]))))
          (is (= 1 (pragma-value ds "user_version"))))))))

(deftest strand-creation-and-validation
  (with-db
    (fn [ds]
      (let [strand (-> (db/add-strand! ds {:title "Sketch model" :attributes {:priority "high"}})
                       (update :attributes db/<-json))]
        (is (re-matches #"[a-z0-9]+" (:id strand)))
        (is (= {:title "Sketch model" :state "active" :attributes {:priority "high"}}
               (select-keys strand [:title :state :attributes]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :status "todo"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                            (db/add-strand! ds {:title "Old" :final_at "now"})))
      (is (s/valid? ::specs/state "replaced"))
      (is (not (s/valid? ::specs/strand-input {:title "Bad" :state "replaced"})))
      (is (not (s/valid? ::specs/strand-input {:title "Bad" :attributes {:owner (Object.)}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :state "true"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid strand"
                            (db/add-strand! ds {:title "Bad" :state "replaced"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                            (db/add-strand! ds {:title "Bad" :priority "high"}))))))

(defn attr-rows [ds strand-id]
  (mapv #(update % :value db/<-json)
        (db/execute! ds ["SELECT strand_id, key, value, archived FROM attributes WHERE strand_id = ? ORDER BY key" strand-id])))

(defn sqlite-json-patch [ds current patch]
  (:patched (db/execute-one! ds ["SELECT json_patch(?, ?) AS patched" current patch])))

(deftest strand-attributes-use-eav-rows-and-assembled-reads
  (with-db
    (fn [ds]
      (let [strand (db/add-strand! ds {:title "Attrs"
                                       :attributes {:z 1 "a" {:nested true} :drop "x"}})
            id (:id strand)]
        (is (nil? (some #(= "attributes" (:name %))
                        (db/execute! ds ["PRAGMA table_info(strands)"]))))
        (is (= [{:strand_id id :key "a" :value {:nested true} :archived 0}
                {:strand_id id :key "drop" :value "x" :archived 0}
                {:strand_id id :key "z" :value 1 :archived 0}]
               (attr-rows ds id)))
        (is (= "{\"a\":{\"nested\":true},\"drop\":\"x\",\"z\":1}"
               (:attributes (db/get-strand ds id))))
        (is (= "{\"a\":{\"nested\":true},\"drop\":\"x\",\"z\":1}"
               (-> (db/ready-strands ds) first :attributes)))
        (db/update-strand! ds id {:attributes {:b 2 :drop nil}})
        (is (= {:a {:nested true} :b 2 :z 1}
               (db/<-json (:attributes (db/get-strand ds id)))))
        (db/update-strand! ds id {:attributes {:a {:extra true}}})
        (is (= {:a {:nested true :extra true} :b 2 :z 1}
               (db/<-json (:attributes (db/get-strand ds id)))))
        (db/update-strand! ds id {:attributes {:a {:nested nil}}})
        (is (= {:a {:extra true} :b 2 :z 1}
               (db/<-json (:attributes (db/get-strand ds id)))))
        (is (= ["a" "b" "z"] (mapv :key (attr-rows ds id))))
        (db/update-strand! ds id {:attributes {:b nil}})
        (is (= {:a {:extra true} :z 1}
               (db/<-json (:attributes (db/get-strand ds id)))))))))

(deftest strand-attribute-json-patch-matches-sqlite-object-semantics
  (with-db
    (fn [ds]
      (let [strand (db/add-strand! ds {:title "Patch"
                                       :attributes {:scalar "old"
                                                    :existing {:keep true
                                                               :drop "x"}}})
            id (:id strand)]
        (db/update-strand! ds id {:attributes {:absent {:keep {:nested true
                                                               :drop nil}
                                                        :drop nil}}})
        (is (= {:keep {:nested true}}
               (-> (db/get-strand ds id) :attributes db/<-json :absent)))

        (db/update-strand! ds id {:attributes {:scalar {:keep {:nested true
                                                               :drop nil}
                                                        :drop nil}}})
        (is (= {:keep {:nested true}}
               (-> (db/get-strand ds id) :attributes db/<-json :scalar)))

        (db/update-strand! ds id {:attributes {:existing {:keep {:nested true}
                                                          :drop nil}}})
        (is (= {:keep {:nested true}}
               (-> (db/get-strand ds id) :attributes db/<-json :existing)))

        (let [patch (json/write-str {:keep {:nested true :drop nil} :drop nil})
              expected (db/<-json (sqlite-json-patch ds "\"old\"" patch))]
          (is (= expected
                 (-> (db/get-strand ds id) :attributes db/<-json :scalar))))))))

(deftest attribute-rows-cascade-when-strand-is-burned
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Cascade" :attributes {:owner "agent"}}))]
        (is (= 1 (:c (db/execute-one! ds ["SELECT count(*) AS c FROM attributes WHERE strand_id = ?" strand]))))
        (db/burn-by-id! ds strand)
        (is (zero? (:c (db/execute-one! ds ["SELECT count(*) AS c FROM attributes WHERE strand_id = ?" strand]))))))))

(deftest full-reads-include-archived-attributes-and-hot-reads-exclude-them
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Archive candidate"
                                            :attributes {:owner "agent" :payload "cold"}}))]
        (db/execute! ds ["UPDATE attributes SET archived = 1 WHERE strand_id = ? AND key = 'payload'" strand])
        (is (= {:owner "agent" :payload "cold"}
               (db/<-json (:attributes (db/get-strand ds strand)))))
        (is (= {:owner "agent"}
               (-> (db/all-strands ds)
                   first
                   :attributes
                   db/<-json)))
        (is (= {:owner "agent"}
               (-> (db/ready-strands ds)
                   first
                   :attributes
                   db/<-json)))))))

(deftest archive-primitives-are-atomic-idempotent-and-fail-loud
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Archive candidate"
                                            :attributes {:z 1 :a "hot" :payload "cold"}}))]
        (is (= {:strand-id strand
                :keys ["a" "payload"]
                :archived? true
                :changed 2}
               (db/archive-attributes! ds strand [:payload :a])))
        (is (= {:strand-id strand
                :keys ["a" "payload"]
                :archived? true
                :changed 0}
               (db/archive-attributes! ds strand [:a :payload])))
        (is (= {:z 1}
               (-> (db/all-strands ds) first :attributes db/<-json)))
        (is (= {:z 1}
               (-> (db/all-strands ds [:= [:attr :z] 1]) first :attributes db/<-json)))
        (is (empty? (db/all-strands ds [:exists [:attr :payload]])))
        (is (= {:a "hot" :payload "cold" :z 1}
               (db/<-json (:attributes (db/get-strand ds strand)))))
        (let [missing (is (thrown? clojure.lang.ExceptionInfo
                                   (db/unarchive-attributes! ds strand [:payload :missing])))]
          (is (= {:reason :missing-keys
                  :strand-id strand
                  :keys ["missing"]}
                 (ex-data missing))))
        (is (= #{"a" "payload"}
               (set (map :key (filter #(= 1 (:archived %)) (attr-rows ds strand))))))
        (is (= {:strand-id strand
                :keys ["a" "payload" "z"]
                :archived? false
                :changed 2}
               (db/unarchive-attributes! ds strand)))
        (is (= {:a "hot" :payload "cold" :z 1}
               (-> (db/all-strands ds) first :attributes db/<-json)))))))

(deftest writing-archived-key-makes-only-that-key-hot
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Archive candidate"
                                            :attributes {:owner "agent"
                                                         :payload "cold"
                                                         :status "stale"}}))]
        (db/archive-attributes! ds strand [:payload :status])
        (db/update-strand! ds strand {:attributes {:payload "fresh"}})
        (is (= {:owner "agent" :payload "fresh"}
               (-> (db/all-strands ds) first :attributes db/<-json)))
        (is (= [strand]
               (db/query-strand-ids ds [:= [:attr :payload] "fresh"] {})))
        (is (empty? (db/query-strand-ids ds [:= [:attr :status] "stale"] {})))
        (is (= {"owner" 0
                "payload" 0
                "status" 1}
               (into {} (map (juxt :key :archived)) (attr-rows ds strand))))))))

(deftest archive-primitives-reject-bad-input
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Archive candidate"
                                            :attributes {:payload "cold"}}))]
        (let [bad-id (is (thrown? clojure.lang.ExceptionInfo
                                  (db/archive-attributes! ds "")))]
          (is (= :malformed-strand-id (:reason (ex-data bad-id)))))
        (let [unknown-id (is (thrown? clojure.lang.ExceptionInfo
                                      (db/archive-attributes! ds "missing")))]
          (is (= {:reason :missing-strand
                  :strand-id "missing"
                  :keys nil}
                 (ex-data unknown-id))))
        (doseq [keys [[] nil [""] [42]]]
          (let [bad-keys (is (thrown? clojure.lang.ExceptionInfo
                                      (db/archive-attributes! ds strand keys)))]
            (is (= :malformed-keys (:reason (ex-data bad-keys))))))))))

(deftest active-readiness-and-reactivation
  (with-db
    (fn [ds]
      (let [design (:id (db/add-strand! ds {:title "Design" :state "closed"}))
            schema (:id (db/add-strand! ds {:title "Schema"}))
            docs (:id (db/add-strand! ds {:title "Docs"}))]
        (db/add-edge! ds {:from schema :to design :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from docs :to schema :type "depends-on" :attributes {}})
        (is (= [schema] (mapv :id (db/ready-strands ds))))
        (let [closed (db/update-strand! ds schema {:state "closed"})]
          (is (= "closed" (:state closed))))
        (is (= [docs] (mapv :id (db/ready-strands ds))))
        (let [reactivated (db/update-strand! ds schema {:state "active"})]
          (is (= "active" (:state reactivated))))
        (is (= [schema] (mapv :id (db/ready-strands ds))))))))

(deftest removed-lifecycle-fields-are-rejected
  (with-db
    (fn [ds]
      (let [strand (:id (db/add-strand! ds {:title "Strand"}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                              (db/update-strand! ds strand {:priority "high"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Removed lifecycle fields"
                              (db/update-strand! ds strand {:final_at "now"})))))))

(deftest burn-deletes-strands-and-incident-edges
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from c :to b :type "related-to" :attributes {}})
        (is (= {:burned [b] :count 1} (db/burn-by-id! ds b)))
        (is (nil? (db/get-strand ds b)))
        (is (= #{a c} (set (map :id (db/all-strands ds)))))
        (is (empty? (db/execute! ds ["SELECT 1 FROM strand_edges WHERE from_strand_id = ? OR to_strand_id = ?" b b])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                              (db/burn-by-ids! ds [b])))))))

(deftest burn-records-one-tombstone-per-strand
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A" :attributes {:owner "agent"}}))
            b (:id (db/add-strand! ds {:title "B"}))]
        (db/burn-by-ids! ds [a b])
        (let [ta (db/burn-history-for-strand ds a)
              tb (db/burn-history-for-strand ds b)
              tomb (first ta)]
          (is (= 1 (count ta)))
          (is (= 1 (count tb)))
          (is (= a (:strand_id tomb)))
          (is (= "A" (:title tomb)))
          (is (= "active" (:state tomb)))
          (is (string? (:created_at tomb)))
          (is (string? (:updated_at tomb)))
          (is (string? (:recorded_at tomb)))
          (is (= {:owner {:value "agent" :archived false}} (:attributes tomb)))
          (is (= [] (:edges tomb))))))))

(deftest batch-burn-records-tombstone
  (with-db
    (fn [ds]
      (let [doomed (:id (db/add-strand! ds {:title "Doomed" :attributes {:kind "scratch"}}))]
        (db/apply-batch! ds {:refs {:doomed doomed} :burn [:doomed]})
        (let [tomb (first (db/burn-history-for-strand ds doomed))]
          (is (some? tomb))
          (is (= "Doomed" (:title tomb)))
          (is (= {:kind {:value "scratch" :archived false}} (:attributes tomb))))))))

(deftest tombstone-captures-archived-attributes-and-incident-edges
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B" :attributes {:owner "agent" :payload "cold"}}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/execute! ds ["UPDATE attributes SET archived = 1 WHERE strand_id = ? AND key = 'payload'" b])
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {:reason "needs"}})
        (db/add-edge! ds {:from b :to c :type "related-to" :attributes {:note "link"}})
        (db/burn-by-id! ds b)
        (let [tomb (first (db/burn-history-for-strand ds b))]
          (is (= {:owner {:value "agent" :archived false}
                  :payload {:value "cold" :archived true}}
                 (:attributes tomb)))
          (is (= #{{:from a :to b :type "depends-on" :attributes {:reason "needs"}}
                   {:from b :to c :type "related-to" :attributes {:note "link"}}}
                 (set (:edges tomb)))))))))

(deftest co-burning-connected-strands-records-shared-edge-in-both-tombstones
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            shared {:from a :to b :type "depends-on" :attributes {:reason "needs"}}]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {:reason "needs"}})
        (db/burn-by-ids! ds [a b])
        (let [tomb-a (first (db/burn-history-for-strand ds a))
              tomb-b (first (db/burn-history-for-strand ds b))]
          (is (= [shared] (:edges tomb-a)))
          (is (= [shared] (:edges tomb-b))))))))

(deftest batch-co-burning-connected-refs-records-shared-edge-in-both-tombstones
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            shared {:from a :to b :type "depends-on" :attributes {:reason "needs"}}]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {:reason "needs"}})
        (db/apply-batch! ds {:refs {:a a :b b} :burn [:a :b]})
        (let [tomb-a (first (db/burn-history-for-strand ds a))
              tomb-b (first (db/burn-history-for-strand ds b))]
          (is (= [shared] (:edges tomb-a)))
          (is (= [shared] (:edges tomb-b))))))))

(deftest tombstone-well-formed-with-no-edges-or-attributes
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "Bare"}))]
        (db/burn-by-id! ds a)
        (let [tomb (first (db/burn-history-for-strand ds a))]
          (is (= "Bare" (:title tomb)))
          (is (= {} (:attributes tomb)))
          (is (= [] (:edges tomb))))))))

(deftest burn-history-reads-are-newest-first
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/burn-by-id! ds a)
        (db/burn-by-id! ds b)
        (db/burn-by-id! ds c)
        (is (= [c b a] (mapv :strand_id (db/recent-burn-history ds 10))))
        (is (= [c b] (mapv :strand_id (db/recent-burn-history ds 2))))
        (is (= [a] (mapv :strand_id (db/burn-history-for-strand ds a))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                              (db/recent-burn-history ds 0)))))))

(deftest capturing-a-missing-id-skips-without-tombstone-or-error
  (with-db
    (fn [ds]
      (is (nil? (#'db/capture-burn-tombstone! ds "absent")))
      (is (empty? (db/recent-burn-history ds 10)))
      (is (empty? (db/burn-history-for-strand ds "absent"))))))

(deftest query-fields-use-state-and-attributes
  (with-db
    (fn [ds]
      (let [agent (:id (db/add-strand! ds {:title "Agent" :attributes {:owner "agent"}}))
            _human (db/add-strand! ds {:title "Human" :attributes {:owner "human"}})
            scratch (:id (db/add-strand! ds {:title "Scratch" :attributes {:kind "scratch"}}))]
        (is (= [agent]
               (mapv :id (db/all-strands ds [:and [:= :state "active"] [:= [:attr :owner] "agent"]]))))
        (is (= [scratch] (mapv :id (db/all-strands ds [:= [:attr :kind] "scratch"]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :kind "scratch"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:= :status "todo"] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query field"
                              (query/compile-query [:exists :final_at] {})))))))

(deftest edge-query-predicates-and-ready-semantics
  (with-db
    (fn [ds]
      (let [active-blocker (:id (db/add-strand! ds {:title "Active blocker"}))
            closed-blocker (:id (db/add-strand! ds {:title "Closed blocker" :state "closed"}))
            blocked (:id (db/add-strand! ds {:title "Blocked" :attributes {:owner "agent"}}))
            ready (:id (db/add-strand! ds {:title "Ready" :attributes {:owner "agent"}}))]
        (db/add-edge! ds {:from blocked :to active-blocker :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from ready :to closed-blocker :type "depends-on" :attributes {}})
        (is (= #{blocked ready}
               (set (mapv :id (db/all-strands ds [:edge/out "depends-on" [:exists :id]])))))
        (is (= #{active-blocker closed-blocker}
               (set (mapv :id (db/all-strands ds [:edge/in [:param :relation] [:= [:attr :owner] "agent"]]
                                              {:relation "depends-on"})))))
        (is (= #{active-blocker ready} (set (mapv :id (db/ready-strands ds)))))
        (is (= (set (mapv :id (db/ready-strands ds)))
               (set (mapv :id (db/all-strands ds [:and
                                                  [:= :state "active"]
                                                  [:not [:edge/out "depends-on" [:= :state "active"]]]])))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (query/compile-query [:edge/out "depends-on" [:edge/in "depends-on" [:= :state "active"]]] {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                              (query/compile-query [:edge/out [:param :relation] [:exists :id]] {:relation "Bad Relation"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                              (query/compile-query [:edge/out [:param :relation] [:exists :id]] {:relation ["__skein_query_param__"]})))))))

(deftest graph-primitives-use-strand-edge-columns
  (with-db
    (fn [ds]
      (let [root (:id (db/add-strand! ds {:title "Root"}))
            child (:id (db/add-strand! ds {:title "Child"}))
            custom-root (:id (db/add-strand! ds {:title "Custom root" :attributes {:kind "root"}}))
            custom-child (:id (db/add-strand! ds {:title "Custom child" :attributes {:kind "leaf"}}))]
        (db/add-edge! ds {:from root :to child :type "parent-of" :attributes {}})
        (db/declare-acyclic-relation! ds "blocks")
        (db/add-edge! ds {:from custom-root :to custom-child :type "blocks" :attributes {}})
        (db/add-edge! ds {:from child :to custom-child :type "related-to" :attributes {}})
        (is (= (sort [root child]) (mapv :id (:strands (db/subgraph ds [root])))))
        (is (= [custom-root] (db/ancestor-root-ids ds [custom-child] {:type "blocks"})))
        (is (= [custom-root]
               (db/ancestor-root-ids ds [custom-child] {:type "blocks"
                                                        :where [:= [:attr :kind] [:param :kind]]
                                                        :params {:kind "root"}})))
        (is (= []
               (db/ancestor-root-ids ds [custom-child] {:where [:= [:attr :kind] [:param :kind]]
                                                        :params {:kind "root"}})))
        (is (= (sort [custom-root custom-child])
               (mapv :id (:strands (db/subgraph ds [custom-root] {:type "blocks"})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declared acyclic relation"
                              (db/subgraph ds [child] {:type "related-to"})))
        (let [edges (:edges (db/subgraph ds [root]))]
          (is (= [[root child "parent-of"]]
                 (mapv (juxt :from_strand_id :to_strand_id :edge_type) edges)))
          (is (not-any? #(contains? % :from_task_id) edges))
          (is (not-any? #(contains? % :to_task_id) edges)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same strand"
                              (db/add-edge! ds {:from root :to root :type "related-to" :attributes {}})))))))

(deftest incoming-and-outgoing-edges-resolve-adjacency-without-traversal
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from a :to c :type "parent-of" :attributes {}})
        (db/add-edge! ds {:from b :to c :type "parent-of" :attributes {}})
        (db/add-edge! ds {:from a :to b :type "related-to" :attributes {}})
        (testing "incoming-edges returns sources for a target by edge type"
          (is (= #{[a c] [b c]}
                 (set (mapv (juxt :from_strand_id :to_strand_id)
                            (db/incoming-edges ds [c] "parent-of")))))
          (is (empty? (db/incoming-edges ds [a] "parent-of"))))
        (testing "outgoing-edges returns targets for a source by edge type"
          (is (= #{[a c]}
                 (set (mapv (juxt :from_strand_id :to_strand_id)
                            (db/outgoing-edges ds [a] "parent-of")))))
          (is (= #{[a b]}
                 (set (mapv (juxt :from_strand_id :to_strand_id)
                            (db/outgoing-edges ds [a] "related-to"))))))
        (testing "an empty id set short-circuits without a query"
          (is (= [] (db/incoming-edges ds [] "parent-of")))
          (is (= [] (db/outgoing-edges ds [] "parent-of"))))
        (testing "adjacency is lenient: an absent id yields no rows, not an error"
          ;; documented contract (SPEC-004.C55a) — unlike subgraph/ancestor-root-ids
          ;; seeds, these edge-projection primitives do not validate id existence
          (is (= [] (db/incoming-edges ds ["nope-missing"] "parent-of")))
          (is (= [] (db/outgoing-edges ds ["nope-missing"] "parent-of"))))))))

(deftest row-backed-attr-queries-have-uniform-capability
  (with-db
    (fn [ds]
      (db/add-strand! ds {:title "Agent" :attributes {:owner "agent" :rank 5 :kind "impl" :profile {:name "ann"}}})
      (db/add-strand! ds {:title "Human" :attributes {:owner "human" :rank 2 :kind "impl"}})
      (db/add-strand! ds {:title "Scratch" :attributes {:rank 9 :kind "scratch"}})
      (doseq [[query titles]
              [[[:= [:attr :owner] "agent"] ["Agent"]]
               [[:!= [:attr :owner] "agent"] ["Human"]]
               [[:< [:attr :rank] 5] ["Human"]]
               [[:<= [:attr :rank] 5] ["Agent" "Human"]]
               [[:> [:attr :rank] 5] ["Scratch"]]
               [[:>= [:attr :rank] 5] ["Agent" "Scratch"]]
               [[:in [:attr :owner] ["agent" "human"]] ["Agent" "Human"]]
               [[:exists [:attr :owner]] ["Agent" "Human"]]
               [[:missing [:attr :owner]] ["Scratch"]]
               [[:= [:attr :profile :name] "ann"] ["Agent"]]
               [[:and [:= [:attr :owner] "agent"] [:= [:attr :kind] "impl"]] ["Agent"]]]]
        (is (= titles (sort (mapv :title (db/all-strands ds query)))) (pr-str query))))))

(deftest row-backed-attr-query-plans-use-correlated-exists-probes
  (with-db
    (fn [ds]
      (db/add-strand! ds {:title "Agent" :attributes {:owner "agent"}})
      (db/add-strand! ds {:title "Human" :attributes {:owner "human"}})
      (is (= ["Agent"] (mapv :title (db/all-strands ds [:= [:attr :owner] "agent"]))))
      (let [{eq-sql :sql eq-params :params} (query/compile-query [:= [:attr :owner] "agent"] {})
            eq-plan (db/execute! ds (into [(str "EXPLAIN QUERY PLAN SELECT t.id FROM strands t WHERE " eq-sql)] eq-params))
            {exists-sql :sql exists-params :params} (query/compile-query [:exists [:attr :owner]] {})
            exists-plan (db/execute! ds (into [(str "EXPLAIN QUERY PLAN SELECT t.id FROM strands t WHERE " exists-sql)] exists-params))]
        (is (= "EXISTS (SELECT 1 FROM attributes AS a WHERE a.strand_id = t.id AND a.archived = 0 AND a.key = ? AND json_extract(a.value, ?) = ?)" eq-sql))
        (is (= ["owner" "$" "agent"] eq-params))
        (is (some #(str/includes? (:detail %) "sqlite_autoindex_attributes_1") eq-plan))
        (is (= "EXISTS (SELECT 1 FROM attributes AS a WHERE a.strand_id = t.id AND a.archived = 0 AND a.key = ? AND json_extract(a.value, ?) IS NOT NULL)" exists-sql))
        (is (= ["owner" "$"] exists-params))
        (is (some #(str/includes? (:detail %) "sqlite_autoindex_attributes_1") exists-plan))))))

(deftest relation-declarations-and-scoped-cycle-checks
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (testing "arbitrary valid annotation relation names store and may cycle"
          (db/add-edge! ds {:from a :to b :type "related-to" :attributes {}})
          (db/add-edge! ds {:from b :to a :type "related-to" :attributes {}})
          (db/add-edge! ds {:from a :to c :type "agent.notes/v1" :attributes {}})
          (is (= #{"agent.notes/v1" "related-to"}
                 (set (map :edge_type (db/execute! ds ["SELECT edge_type FROM strand_edges WHERE edge_type IN ('related-to', 'agent.notes/v1')"]))))))
        (testing "declared acyclic relations reject relation-local cycles"
          (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
          (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/add-edge! ds {:from c :to a :type "depends-on" :attributes {}}))))
        (testing "invalid edge endpoints fail before storage"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                                (db/add-edge! ds {:from "missing-from" :to b :type "related-to" :attributes {}})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Strand ids not found"
                                (db/add-edge! ds {:from a :to "missing-to" :type "related-to" :attributes {}})))
          (is (empty? (db/execute! ds ["SELECT 1 FROM strand_edges WHERE from_strand_id LIKE 'missing%' OR to_strand_id LIKE 'missing%'"]))))
        (testing "invalid relation names fail at storage boundaries"
          (doseq [relation ["" "Related-To" ".hidden" "has space" ":keyword" 42]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid edge"
                                  (db/add-edge! ds {:from a :to b :type relation :attributes {}})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"valid relation name"
                                  (db/declare-acyclic-relation! ds relation)))))
        (testing "custom declarations are idempotent and declare-before-use"
          (is (= {:relation "blocks/v2" :acyclic true}
                 (db/declare-acyclic-relation! ds "blocks/v2")))
          (is (= {:relation "blocks/v2" :acyclic true}
                 (db/declare-acyclic-relation! ds "blocks/v2")))
          (db/add-edge! ds {:from a :to b :type "blocks/v2" :attributes {}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/add-edge! ds {:from b :to a :type "blocks/v2" :attributes {}})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"after edges"
                                (db/declare-acyclic-relation! ds "related-to"))))))))

(deftest serves-relation-allows-one-outgoing-target-per-run
  (with-db
    (fn [ds]
      (let [run (:id (db/add-strand! ds {:title "Run"}))
            first-target (:id (db/add-strand! ds {:title "First target"}))
            second-target (:id (db/add-strand! ds {:title "Second target"}))]
        (db/add-edge! ds {:from run :to first-target :type "serves" :attributes {:attempt 1}})
        (is (= {:attempt 2}
               (-> (db/add-edge! ds {:from run
                                     :to first-target
                                     :type "serves"
                                     :attributes {:attempt 2}})
                   :attributes
                   db/<-json)))
        (try
          (db/add-edge! ds {:from run :to second-target :type "serves" :attributes {}})
          (is false "expected a second serves target to fail")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (.getMessage e) first-target))
            (is (= {:run-id run
                    :relation "serves"
                    :existing-target first-target
                    :attempted-target second-target}
                   (ex-data e)))))
        (is (= [first-target]
               (mapv :to_strand_id
                     (db/execute! ds ["SELECT to_strand_id
                                      FROM strand_edges
                                      WHERE from_strand_id = ? AND edge_type = 'serves'"
                                      run]))))))))

(deftest init-rejects-legacy-runs-with-multiple-serves-targets
  (with-db
    (fn [ds]
      (let [run (:id (db/add-strand! ds {:title "Legacy run"}))
            target-a (:id (db/add-strand! ds {:title "Target A"}))
            target-b (:id (db/add-strand! ds {:title "Target B"}))]
        (db/execute! ds ["DROP INDEX idx_strand_edges_single_serves"])
        (doseq [target [target-a target-b]]
          (db/execute! ds ["INSERT INTO strand_edges
                            (from_strand_id, to_strand_id, edge_type, attributes)
                            VALUES (?, ?, 'serves', '{}')"
                           run target]))
        (try
          (db/init! ds)
          (is false "expected malformed legacy serves rows to fail")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (.getMessage e) run))
            (is (= #{target-a target-b} (set (:existing-targets (ex-data e)))))))))))

(deftest init-rejects-old-document-strand-schema
  (let [db-file (temp-db-file)
        ds (db/datasource db-file)]
    (try
      (db/execute! ds ["CREATE TABLE strands (
                         id TEXT PRIMARY KEY,
                         title TEXT NOT NULL,
                         state TEXT NOT NULL DEFAULT 'active',
                         attributes TEXT NOT NULL DEFAULT '{}',
                         created_at TEXT NOT NULL DEFAULT (datetime('now')),
                         updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                       )"])
      (db/execute! ds ["CREATE TABLE strand_edges (
                         from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                         to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                         edge_type TEXT NOT NULL,
                         attributes TEXT NOT NULL DEFAULT '{}',
                         PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                         CHECK (edge_type IN ('depends-on', 'related-to', 'parent-of', 'supersedes')),
                         CHECK (json_valid(attributes))
                       )"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match the expected Skein schema"
                            (db/init! ds)))
      (is (zero? (pragma-value ds "user_version")))
      (is (empty? (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'attributes'"])))
      (finally
        (delete-sqlite-family! db-file)))))

(declare edge-rows graph-snapshot)

(deftest batch-creation-uses-strand-lifecycle
  (with-db
    (fn [ds]
      (let [result (db/add-strand-batch! ds [{:ref 'design :title "Design" :state "closed"}
                                             {:ref 'docs :title "Docs" :edges [{:type "depends-on" :to 'design}]}])
            refs (:refs result)]
        (is (= #{:created :refs} (set (keys result))))
        (is (= #{"design" "docs"} (set (keys refs))))
        (is (= "closed" (:state (db/get-strand ds (get refs "design")))))
        (is (= [{:to_strand_id (get refs "design") :edge_type "depends-on"}]
               (db/execute! ds ["SELECT to_strand_id, edge_type FROM strand_edges WHERE from_strand_id = ?" (get refs "docs")])))))))

(deftest batch-creation-accepts-explicit-nil-edges
  (with-db
    (fn [ds]
      (let [result (db/add-strand-batch! ds [{:title "Standalone" :edges nil}])]
        (is (= 1 (count (:created result))))))))

(deftest supersession-transaction-replaces-and-rewires
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            active-dependent (:id (db/add-strand! ds {:title "Active dependent"}))
            closed-dependent (:id (db/add-strand! ds {:title "Closed dependent" :state "closed"}))
            already-linked (:id (db/add-strand! ds {:title "Already linked"}))]
        (db/add-edge! ds {:from active-dependent :to old :type "depends-on" :attributes {:reason "active"}})
        (db/add-edge! ds {:from closed-dependent :to old :type "depends-on" :attributes {:reason "closed"}})
        (db/add-edge! ds {:from already-linked :to old :type "depends-on" :attributes {:reason "old"}})
        (db/add-edge! ds {:from already-linked :to replacement :type "depends-on" :attributes {:reason "existing"}})
        (let [result (db/supersede-strand! ds old replacement)]
          (is (= "active" (get-in result [:old :before :state])))
          (is (= "replaced" (get-in result [:old :after :state])))
          (is (= replacement (:replacement-id result)))
          (is (= [replacement old "supersedes"]
                 ((juxt :from_strand_id :to_strand_id :edge_type) (:supersedes-edge result))))
          (is (= #{active-dependent closed-dependent already-linked}
                 (set (map :from (:rewired-dependencies result)))))
          (is (= #{[replacement old "supersedes"]
                   [active-dependent replacement "depends-on"]
                   [closed-dependent replacement "depends-on"]
                   [already-linked replacement "depends-on"]}
                 (set (map (juxt :from_strand_id :to_strand_id :edge_type) (edge-rows ds)))))
          (is (= {:reason "existing"}
                 (:attributes (first (filter #(= already-linked (:from_strand_id %)) (edge-rows ds))))))
          (is (= [old]
                 (mapv :id (db/all-strands ds [:edge/in "supersedes" [:= :id replacement]]))))
          (is (nil? (some #(= "replaced_by" (:name %))
                          (db/execute! ds ["PRAGMA table_info(strands)"])))))))))

(deftest supersession-missing-id-failures-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))]
        (doseq [[message f] [[#"Old strand not found" #(db/supersede-strand! ds "missing" replacement)]
                             [#"Replacement strand not found" #(db/supersede-strand! ds old "missing")]]]
          (let [before (graph-snapshot ds)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo message (f)))
            (is (= before (graph-snapshot ds)))))))))

(deftest supersession-validation-failures-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            closed-replacement (:id (db/add-strand! ds {:title "Closed replacement" :state "closed"}))
            dependent (:id (db/add-strand! ds {:title "Dependent"}))]
        (db/add-edge! ds {:from dependent :to old :type "depends-on" :attributes {}})
        (db/supersede-strand! ds old replacement)
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already replaced"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds))))
        (doseq [[message f] [[#"Replacement strand must be active" #(db/supersede-strand! ds replacement closed-replacement)]
                             [#"cannot supersede itself" #(db/supersede-strand! ds replacement replacement)]]]
          (let [before (graph-snapshot ds)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo message (f)))
            (is (= before (graph-snapshot ds)))))))))

(deftest supersession-lineage-and-dependency-cycles-roll-back
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))]
        (db/add-edge! ds {:from old :to replacement :type "supersedes" :attributes {}})
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds)))))))
  (with-db
    (fn [ds]
      (let [old (:id (db/add-strand! ds {:title "Old"}))
            replacement (:id (db/add-strand! ds {:title "Replacement"}))
            dependent (:id (db/add-strand! ds {:title "Dependent"}))]
        (db/add-edge! ds {:from dependent :to old :type "depends-on" :attributes {}})
        (db/add-edge! ds {:from replacement :to dependent :type "depends-on" :attributes {}})
        (let [before (graph-snapshot ds)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"create a cycle"
                                (db/supersede-strand! ds old replacement)))
          (is (= before (graph-snapshot ds))))))))

(defn edge-rows [ds]
  (mapv #(update % :attributes db/<-json)
        (db/execute! ds ["SELECT from_strand_id, to_strand_id, edge_type, attributes FROM strand_edges ORDER BY from_strand_id, to_strand_id, edge_type"])))

(defn graph-snapshot [ds]
  {:strands (mapv #(update % :attributes db/<-json) (db/all-strands ds))
   :edges (edge-rows ds)})

(defn assert-batch-fails-without-mutation [ds message-re payload]
  (let [before (graph-snapshot ds)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message-re
                          (db/apply-batch! ds payload)))
    (is (= before (graph-snapshot ds)))))

(deftest apply-batch-happy-path-mutates-graph-and-returns-final-refs
  (with-db
    (fn [ds]
      (let [old-doc (db/add-strand! ds {:title "Old doc" :attributes {:state "draft"}})
            old-design (db/add-strand! ds {:title "Old design"})
            dependency (db/add-strand! ds {:title "Dependency"})
            result (db/apply-batch! ds {:refs {:old-doc (:id old-doc)
                                               :old-design (:id old-design)
                                               :dependency (:id dependency)}
                                        :strands [{:ref :old-doc
                                                   :state "closed"
                                                   :attributes {:state "superseded"}}
                                                  {:ref :new-doc
                                                   :title "New doc"
                                                   :attributes {:kind "doc"}}]
                                        :edges [{:op :upsert
                                                 :from :new-doc
                                                 :to :dependency
                                                 :type "depends-on"
                                                 :attributes {:reason "needed"}}]
                                        :burn [:old-design]})
            new-doc-id (get-in result [:refs :new-doc])]
        (is (= {:old-doc (:id old-doc)
                :old-design (:id old-design)
                :dependency (:id dependency)
                :new-doc new-doc-id}
               (:refs result)))
        (is (string? new-doc-id))
        (is (= #{(:id old-doc) (:id dependency) new-doc-id}
               (set (map :id (db/all-strands ds)))))
        (is (= {:title "Old doc" :state "closed" :attributes {:state "superseded"}}
               (select-keys (update (db/get-strand ds (:id old-doc)) :attributes db/<-json)
                            [:title :state :attributes])))
        (is (= [{:from_strand_id new-doc-id
                 :to_strand_id (:id dependency)
                 :edge_type "depends-on"
                 :attributes {:reason "needed"}}]
               (edge-rows ds)))))))

(deftest apply-batch-validates-shape-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"Unknown keys" {:refs {:a a} :surprise true}]
                 [#"must be a map" {:refs []}]
                 [#"must be a vector" {:refs {:a a} :strands {}}]
                 [#"must be a vector" {:refs {:a a} :edges {}}]
                 [#"must be a vector" {:refs {:a a} :burn {}}]
                 [#"Strand ids not found" {:refs {:missing "nope"}}]
                 [#"Batch refs" {:refs {(keyword "") a}}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-ref-bindings-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"Multiple batch refs" {:refs {:a a :b a}}]
                 [#"Batch refs" {:refs {"a" a}}]
                 [#"Batch refs" {:refs {:ns/a a}}]
                 [#"Batch refs" {:refs {:a a} :edges [{:op :upsert :from :a :to "b" :type "depends-on"}]}]
                 [#"Batch refs" {:refs {:a a} :edges [{:op :upsert :from :a :to :ns/b :type "depends-on"}]}]
                 [#"Batch refs" {:refs {:a a} :burn ["b"]}]
                 [#"Batch refs" {:refs {:a a} :burn [:ns/b]}]
                 [#"unknown ref" {:refs {:a a} :edges [{:op :upsert :from :a :to :missing :type "depends-on"}]}]
                 [#"existing bound refs" {:refs {:a a} :burn [:missing]}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-strand-and-burn-conflicts-without-mutation
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (doseq [[message payload]
                [[#"requires a non-blank" {:strands [{:ref :new}]}]
                 [#"Duplicate batch strand ref" {:strands [{:ref :new :title "One"} {:ref :new :title "Two"}]}]
                 [#"Lifecycle state must be active or closed" {:strands [{:ref :new :title "New" :state "replaced"}]}]
                 [#"Removed lifecycle fields are not core strand fields" {:strands [{:ref :new :title "New" :active true}]}]
                 [#"existing bound refs" {:strands [{:ref :new :title "New"}] :burn [:new]}]
                 [#"both mutated and burned" {:refs {:a a} :strands [{:ref :a :title "Changed"}] :burn [:a]}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest apply-batch-validates-edge-behavior-and-replaces-attributes
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
        (assert-batch-fails-without-mutation ds #"Unsupported batch edge operation"
                                             {:refs {:a a :b b} :edges [{:op :delete :from :a :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"burned ref"
                                             {:refs {:a a :b b} :burn [:b]
                                              :edges [{:op :upsert :from :a :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"create a cycle"
                                             {:refs {:a a :b b :c c}
                                              :edges [{:op :upsert :from :c :to :b :type "depends-on"}]})
        (assert-batch-fails-without-mutation ds #"valid relation name"
                                             {:refs {:a a :b b}
                                              :edges [{:op :upsert :from :a :to :b :type "Blocks"}]})
        (db/apply-batch! ds {:refs {:a a :b b}
                             :edges [{:op :upsert :from :a :to :b :type "references"}
                                     {:op :upsert :from :b :to :a :type "references"}]})
        (is (= 2 (count (filter #(= "references" (:edge_type %)) (edge-rows ds)))))
        (db/add-edge! ds {:from a :to b :type "related-to" :attributes {:old true}})
        (db/apply-batch! ds {:refs {:a a :b b}
                             :edges [{:op :upsert :from :a :to :b :type "related-to" :attributes {:new true}}]})
        (is (= {:new true}
               (-> (db/execute-one! ds ["SELECT attributes FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'related-to'" a b])
                   :attributes
                   db/<-json)))))))

(deftest apply-batch-rejects-multiple-serves-targets-atomically
  (with-db
    (fn [ds]
      (let [run (:id (db/add-strand! ds {:title "Run"}))
            target-a (:id (db/add-strand! ds {:title "Target A"}))
            target-b (:id (db/add-strand! ds {:title "Target B"}))]
        (assert-batch-fails-without-mutation
         ds
         (re-pattern target-a)
         {:refs {:run run :target-a target-a :target-b target-b}
          :strands [{:ref :run :title "Changed before edge failure"}]
          :edges [{:op :upsert :from :run :to :target-a :type "serves"}
                  {:op :upsert :from :run :to :target-b :type "serves"}]})))))

(deftest apply-batch-rolls-back-earlier-valid-mutations
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
        (assert-batch-fails-without-mutation ds #"create a cycle"
                                             {:refs {:a a :b b :c c}
                                              :strands [{:ref :a :title "Changed before failure"}
                                                        {:ref :new :title "Created before failure"}]
                                              :edges [{:op :upsert :from :c :to :b :type "depends-on"}]})))))

;; ---------------------------------------------------------------------------
;; Exact batch edge removal (PROP-Xer-001): the closed `:remove` op, its exact
;; identity and fail-loud-on-absence contract, submitted-order execution, and
;; the uniform before/after transition outcome shared by every edge op.
;; ---------------------------------------------------------------------------

(def ^:private transition-keys #{:op :from :to :type :before :after})
(def ^:private storage-edge-keys #{:from_strand_id :to_strand_id :edge_type :attributes})

(defn- batch-error-data
  "Return the ex-data of the ExceptionInfo apply-batch! throws for payload."
  [ds payload]
  (try
    (db/apply-batch! ds payload)
    (is false "expected apply-batch! to throw")
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest remove-is-monotone-and-returns-the-storage-shaped-removed-row
  ;; PROP-Xer-001.PO1, PO6: one present removal deletes only that row, leaves
  ;; every other strand and edge untouched, and reports the exact removed row
  ;; in a raw storage-shaped :before with a nil :after.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {:reason "stale"}})
        (db/add-edge! ds {:from b :to c :type "depends-on" :attributes {}})
        (let [result (db/apply-batch! ds {:refs {:a a :b b}
                                          :edges [{:op :remove :from :a :to :b :type "depends-on"}]})
              [transition] (:edges result)
              before (:before transition)]
          (is (= {:op :remove :from :a :to :b :type "depends-on" :after nil}
                 (dissoc transition :before)))
          (is (= transition-keys (set (keys transition))))
          (is (not (contains? transition :edge)))
          (is (= storage-edge-keys (set (keys before))))
          (is (= {:from_strand_id a :to_strand_id b :edge_type "depends-on"}
                 (dissoc before :attributes)))
          (is (string? (:attributes before)) "core :before carries raw JSON text, not a decoded map")
          (is (= {:reason "stale"} (db/<-json (:attributes before))))
          (is (= [{:from_strand_id b :to_strand_id c :edge_type "depends-on" :attributes {}}]
                 (edge-rows ds)))
          (is (= #{a b c} (set (map :id (db/all-strands ds))))))))))

(deftest remove-absence-fails-loud-with-exact-ex-data-and-no-mutation
  ;; PROP-Xer-001.PO2, C2: an absent edge, a wrong direction, and a wrong
  ;; relation type each roll back with ex-data carrying the submitted refs and
  ;; the resolved durable ids as distinct values.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
        (let [before (graph-snapshot ds)]
          (testing "no edge at all for that identity"
            (is (= {:from :b :to :a :from-id b :to-id a :type "depends-on"}
                   (batch-error-data ds {:refs {:a a :b b}
                                         :edges [{:op :remove :from :b :to :a :type "depends-on"}]}))))
          (testing "wrong relation type on an existing pair"
            (is (= {:from :a :to :b :from-id a :to-id b :type "parent-of"}
                   (batch-error-data ds {:refs {:a a :b b}
                                         :edges [{:op :remove :from :a :to :b :type "parent-of"}]}))))
          (is (= before (graph-snapshot ds)) "every absent-remove rolled back"))))))

(deftest remove-rejects-malformed-shape-and-non-pre-bound-refs-without-mutation
  ;; PROP-Xer-001.PO2 shape matrix and DELTA-Xer-001.D2: closed grammar, no
  ;; attributes, no unknown keys, and both endpoints must be top-level refs.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
        (doseq [[message payload]
                [[#"must be a map" {:refs {:a a :b b} :edges [nil]}]
                 [#"must be a map" {:refs {:a a :b b} :edges [[:op :remove]]}]
                 [#"Unsupported batch edge operation" {:refs {:a a :b b}
                                                       :edges [{:from :a :to :b :type "depends-on"}]}]
                 [#"Unknown keys" {:refs {:a a :b b}
                                   :edges [{:op :remove :from :a :to :b :type "depends-on" :extra 1}]}]
                 [#"Unknown keys" {:refs {:a a :b b}
                                   :edges [{:op :remove :from :a :to :b :type "depends-on" :attributes {}}]}]
                 [#"endpoint is required" {:refs {:a a :b b}
                                           :edges [{:op :remove :to :b :type "depends-on"}]}]
                 [#"valid relation name" {:refs {:a a :b b}
                                          :edges [{:op :remove :from :a :to :b :type "Bad Relation"}]}]
                 [#"pre-bound" {:refs {:a a}
                                :strands [{:ref :new :title "New"}]
                                :edges [{:op :upsert :from :a :to :new :type "depends-on"}
                                        {:op :remove :from :a :to :new :type "depends-on"}]}]
                 [#"burned ref" {:refs {:a a :b b} :burn [:b]
                                 :edges [{:op :remove :from :a :to :b :type "depends-on"}]}]]]
          (assert-batch-fails-without-mutation ds message payload))))))

(deftest remove-tolerates-a-raw-invalid-self-edge
  ;; PROP-Xer-001.PO2, TC4: removal runs no insertion-only check, so it retires
  ;; a self-edge that a privileged raw write left behind and reduces the
  ;; violation rather than rejecting it.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))]
        (db/execute! ds ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                          VALUES (?, ?, 'depends-on', json('{}'))" a a])
        (let [result (db/apply-batch! ds {:refs {:a a}
                                          :edges [{:op :remove :from :a :to :a :type "depends-on"}]})]
          (is (= a (get-in result [:edges 0 :before :from_strand_id])))
          (is (= [] (edge-rows ds))))))))

(deftest serves-swap-commits-in-submitted-order-and-rolls-back-reversed
  ;; PROP-Xer-001.T1/PO3: the single-serves rule forces remove-before-upsert; the
  ;; reversed order trips the cardinality guard with the prestate unchanged.
  (with-db
    (fn [ds]
      (let [run (:id (db/add-strand! ds {:title "Run"}))
            old-target (:id (db/add-strand! ds {:title "Old target"}))
            new-target (:id (db/add-strand! ds {:title "New target"}))]
        (db/add-edge! ds {:from run :to old-target :type "serves" :attributes {}})
        (assert-batch-fails-without-mutation
         ds (re-pattern old-target)
         {:refs {:run run :old-target old-target :new-target new-target}
          :edges [{:op :upsert :from :run :to :new-target :type "serves" :attributes {}}
                  {:op :remove :from :run :to :old-target :type "serves"}]})
        (db/apply-batch! ds {:refs {:run run :old-target old-target :new-target new-target}
                             :edges [{:op :remove :from :run :to :old-target :type "serves"}
                                     {:op :upsert :from :run :to :new-target :type "serves" :attributes {}}]})
        (is (= [{:from_strand_id run :to_strand_id new-target :edge_type "serves" :attributes {}}]
               (edge-rows ds)))))))

(deftest dag-reversal-commits-in-submitted-order-and-rolls-back-reversed
  ;; PROP-Xer-001.T2/PO3: reversing a depends-on edge only commits when the
  ;; remove precedes the upsert; the reversed order rejects the transient cycle.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {}})
        (assert-batch-fails-without-mutation
         ds #"create a cycle"
         {:refs {:a a :b b}
          :edges [{:op :upsert :from :b :to :a :type "depends-on" :attributes {}}
                  {:op :remove :from :a :to :b :type "depends-on"}]})
        (db/apply-batch! ds {:refs {:a a :b b}
                             :edges [{:op :remove :from :a :to :b :type "depends-on"}
                                     {:op :upsert :from :b :to :a :type "depends-on" :attributes {}}]})
        (is (= [{:from_strand_id b :to_strand_id a :edge_type "depends-on" :attributes {}}]
               (edge-rows ds)))))))

(deftest repeated-identity-in-one-batch-is-a-deterministic-ordered-program
  ;; PROP-Xer-001.PO4: remove/remove of one identity fails at the second op by
  ;; exact presence; remove-then-upsert and upsert-then-remove are deterministic.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))]
        (db/add-edge! ds {:from a :to b :type "depends-on" :attributes {:v 1}})
        (testing "the second remove of one identity fails on absence and rolls back"
          (is (= {:from :a :to :b :from-id a :to-id b :type "depends-on"}
                 (batch-error-data ds {:refs {:a a :b b}
                                       :edges [{:op :remove :from :a :to :b :type "depends-on"}
                                               {:op :remove :from :a :to :b :type "depends-on"}]})))
          (is (= [{:from_strand_id a :to_strand_id b :edge_type "depends-on" :attributes {:v 1}}]
                 (edge-rows ds))))
        (testing "remove then upsert of one identity ends present with the new attributes"
          (db/apply-batch! ds {:refs {:a a :b b}
                               :edges [{:op :remove :from :a :to :b :type "depends-on"}
                                       {:op :upsert :from :a :to :b :type "depends-on" :attributes {:v 2}}]})
          (is (= [{:from_strand_id a :to_strand_id b :edge_type "depends-on" :attributes {:v 2}}]
                 (edge-rows ds))))
        (testing "upsert then remove of one identity ends absent"
          (db/apply-batch! ds {:refs {:a a :b b}
                               :edges [{:op :upsert :from :a :to :b :type "depends-on" :attributes {:v 3}}
                                       {:op :remove :from :a :to :b :type "depends-on"}]})
          (is (= [] (edge-rows ds))))))))

(deftest multi-op-batches-are-all-or-none
  ;; PROP-Xer-001.PO5: three obsolete depends-on removals commit together (the
  ;; 4cdsu case); a later absent remove restores the whole graph.
  (with-db
    (fn [ds]
      (let [dependent (:id (db/add-strand! ds {:title "Dependent"}))
            x (:id (db/add-strand! ds {:title "X"}))
            y (:id (db/add-strand! ds {:title "Y"}))
            z (:id (db/add-strand! ds {:title "Z"}))]
        (doseq [t [x y z]]
          (db/add-edge! ds {:from dependent :to t :type "depends-on" :attributes {}}))
        (testing "an absent remove after two valid removes rolls the batch back"
          (let [before (graph-snapshot ds)]
            (is (= {:from :dependent :to :z :from-id dependent :to-id z :type "parent-of"}
                   (batch-error-data ds {:refs {:dependent dependent :x x :y y :z z}
                                         :edges [{:op :remove :from :dependent :to :x :type "depends-on"}
                                                 {:op :remove :from :dependent :to :y :type "depends-on"}
                                                 {:op :remove :from :dependent :to :z :type "parent-of"}]})))
            (is (= before (graph-snapshot ds)))))
        (testing "all three obsolete edges retire atomically"
          (db/apply-batch! ds {:refs {:dependent dependent :x x :y y :z z}
                               :edges [{:op :remove :from :dependent :to :x :type "depends-on"}
                                       {:op :remove :from :dependent :to :y :type "depends-on"}
                                       {:op :remove :from :dependent :to :z :type "depends-on"}]})
          (is (= [] (edge-rows ds))))))))

(deftest edge-transitions-are-uniform-across-new-replaced-and-removed
  ;; PROP-Xer-001.PO6: every outcome shares the transition key set; a new upsert
  ;; has nil :before, a replacement upsert carries its actual pre-image, and a
  ;; remove carries the removed row. Outcomes stay aligned to submitted order.
  (with-db
    (fn [ds]
      (let [a (:id (db/add-strand! ds {:title "A"}))
            b (:id (db/add-strand! ds {:title "B"}))
            c (:id (db/add-strand! ds {:title "C"}))]
        (db/add-edge! ds {:from a :to b :type "references" :attributes {:v "old"}})
        (db/add-edge! ds {:from b :to c :type "references" :attributes {}})
        (let [result (db/apply-batch! ds {:refs {:a a :b b :c c}
                                          :edges [{:op :upsert :from :a :to :c :type "references" :attributes {:v "new-edge"}}
                                                  {:op :upsert :from :a :to :b :type "references" :attributes {:v "replaced"}}
                                                  {:op :remove :from :b :to :c :type "references"}]})
              [new-upsert replacement removal] (:edges result)]
          (is (every? #(= transition-keys (set (keys %))) (:edges result)))
          (is (every? #(not (contains? % :edge)) (:edges result)))
          (testing "a new upsert has a nil pre-image"
            (is (= {:op :upsert :from :a :to :c :type "references" :before nil}
                   (dissoc new-upsert :after)))
            (is (= {:from_strand_id a :to_strand_id c :edge_type "references"}
                   (dissoc (:after new-upsert) :attributes)))
            (is (= {:v "new-edge"} (db/<-json (:attributes (:after new-upsert))))))
          (testing "a replacement upsert reports its actual pre-image, not the submitted op"
            (is (= {:v "old"} (db/<-json (:attributes (:before replacement)))))
            (is (= {:v "replaced"} (db/<-json (:attributes (:after replacement))))))
          (testing "a remove reports the removed row and a nil after"
            (is (nil? (:after removal)))
            (is (= {:from_strand_id b :to_strand_id c :edge_type "references"}
                   (dissoc (:before removal) :attributes)))))))))

(deftest removing-depends-on-or-parent-of-commits-without-core-rejection
  ;; PROP-Xer-001.PO7: a depends-on removal that unblocks work and a parent-of
  ;; removal that creates a root are legal domain policy, not engine corruption.
  (with-db
    (fn [ds]
      (testing "removing depends-on makes previously blocked work ready"
        (let [blocker (:id (db/add-strand! ds {:title "Blocker"}))
              blocked (:id (db/add-strand! ds {:title "Blocked"}))]
          (db/add-edge! ds {:from blocked :to blocker :type "depends-on" :attributes {}})
          (is (not (contains? (set (map :id (db/ready-strands ds))) blocked)))
          (db/apply-batch! ds {:refs {:blocked blocked :blocker blocker}
                               :edges [{:op :remove :from :blocked :to :blocker :type "depends-on"}]})
          (is (contains? (set (map :id (db/ready-strands ds))) blocked))))
      (testing "removing parent-of leaves the former child a parent-of root"
        (let [parent (:id (db/add-strand! ds {:title "Parent"}))
              child (:id (db/add-strand! ds {:title "Child"}))]
          (db/add-edge! ds {:from parent :to child :type "parent-of" :attributes {}})
          (is (empty? (db/all-strands ds [:and [:= :id child]
                                          [:not [:edge/in "parent-of" [:exists :id]]]])))
          (db/apply-batch! ds {:refs {:parent parent :child child}
                               :edges [{:op :remove :from :parent :to :child :type "parent-of"}]})
          (is (= [child]
                 (mapv :id (db/all-strands ds [:and [:= :id child]
                                               [:not [:edge/in "parent-of" [:exists :id]]]])))))))))

(deftest memory-storage-runs-schema-crud-and-transactions-on-held-connection
  (let [{:keys [connectable close-fn] :as storage} (db/memory-storage)]
    (is (= :sqlite-memory (:storage-kind storage)))
    (is (nil? (:canonical-db-path storage)))
    (try
      (testing "sqlite-memory runs through the shared open path but keeps memory journaling"
        (is (= "memory" (pragma-value connectable "journal_mode")))
        ;; SQLite reports no mmap_size row for a pure in-memory database even
        ;; when the shared open-time config requests one.
        (is (nil? (pragma-value connectable "mmap_size")))
        (is (= -20000 (pragma-value connectable "cache_size"))))
      (db/init! connectable)
      (let [strand (db/add-strand! connectable {:title "Mem"})]
        (is (= "Mem" (:title (db/get-strand connectable (:id strand))))))
      (testing "with-transaction rolls back on failure"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                              (jdbc/with-transaction [tx connectable]
                                (db/add-strand! tx {:title "Rolled back"})
                                (throw (ex-info "boom" {})))))
        (is (= ["Mem"] (mapv :title (db/all-strands connectable)))))
      (testing "top-level operations own a transaction on held memory connections"
        (db/execute! connectable ["CREATE TRIGGER fail_attribute_insert
                                   BEFORE INSERT ON attributes
                                   BEGIN
                                     SELECT RAISE(ABORT, 'boom');
                                   END"])
        (is (thrown-with-msg? org.sqlite.SQLiteException #"boom"
                              (db/add-strand! connectable {:title "Rolled back top-level"
                                                           :attributes {:owner "agent"}})))
        (db/execute! connectable ["DROP TRIGGER fail_attribute_insert"])
        (is (= ["Mem"] (mapv :title (db/all-strands connectable)))))
      (finally
        (close-fn)))
    (testing "use after close fails loudly"
      (is (thrown? java.sql.SQLException (db/all-strands connectable))))))

;; ---------------------------------------------------------------------------
;; Immutable (write-once) attribute keys: the shipped registration is
;; note/text + note/at. First writes are legal everywhere; once a row exists the
;; key cannot be changed, deleted, or archived on any mutation path.
;; ---------------------------------------------------------------------------

(defn- note-strand!
  "Create a strand carrying the shipped immutable note keys (a note birth write)."
  [ds]
  (:id (db/add-strand! ds {:title "note"
                           :state "closed"
                           :attributes {"note/text" "original"
                                        "note/at" "2026-01-01T00:00:00.000Z"}})))

(defn- decoded-attrs [ds id]
  (db/<-json (:attributes (db/get-strand ds id))))

(deftest init-seeds-immutable-keys
  (with-db
    (fn [ds]
      (is (seq (db/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'immutable_keys'"])))
      (is (= #{"note/text" "note/at"}
             (set (map :key (db/execute! ds ["SELECT key FROM immutable_keys ORDER BY key"]))))))))

(deftest immutable-key-birth-write-is-legal
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (is (= "original" (:note/text (decoded-attrs ds id))))
        (is (= "2026-01-01T00:00:00.000Z" (:note/at (decoded-attrs ds id))))))))

(deftest immutable-key-first-write-on-existing-strand-is-legal
  (with-db
    (fn [ds]
      (let [id (:id (db/add-strand! ds {:title "plain" :attributes {:owner "a"}}))]
        (db/update-strand! ds id {:attributes {"note/text" "first"}})
        (is (= "first" (:note/text (decoded-attrs ds id))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write-once"
                              (db/update-strand! ds id {:attributes {"note/text" "second"}})))))))

(deftest immutable-key-change-rejected-via-patch
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write-once"
                              (db/update-strand! ds id {:attributes {"note/text" "changed"}})))
        (testing "the rejected transaction leaves storage untouched"
          (is (= "original" (:note/text (decoded-attrs ds id)))))))))

(deftest immutable-key-identical-rewrite-is-legal
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (db/update-strand! ds id {:attributes {"note/text" "original"}})
        (is (= "original" (:note/text (decoded-attrs ds id))))))))

(deftest immutable-key-nil-patch-deletion-rejected
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write-once"
                              (db/update-strand! ds id {:attributes {"note/text" nil}})))
        (is (= "original" (:note/text (decoded-attrs ds id))))))))

(deftest immutable-key-archive-rejected-unarchive-legal
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write-once"
                              (db/archive-attributes! ds id ["note/text"])))
        (is (= "original" (:note/text (decoded-attrs ds id))))
        (testing "unarchiving a row archived before enforcement existed is the recovery path"
          (db/execute! ds ["UPDATE attributes SET archived = 1 WHERE strand_id = ? AND key = ?" id "note/text"])
          (is (= 1 (:changed (db/unarchive-attributes! ds id ["note/text"]))))
          (is (= "original" (:note/text (decoded-attrs ds id)))))))))

(deftest immutable-key-batch-update-enforced
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write-once"
                              (db/apply-batch! ds {:refs {:n id}
                                                   :strands [{:ref :n :attributes {"note/text" "changed"}}]})))
        (is (= "original" (:note/text (decoded-attrs ds id))))
        (testing "an identical carry-through through the batch path is legal"
          (db/apply-batch! ds {:refs {:n id}
                               :strands [{:ref :n :attributes {"note/text" "original"}}]})
          (is (= "original" (:note/text (decoded-attrs ds id)))))))))

(deftest non-immutable-key-mutates-freely-on-every-path
  (with-db
    (fn [ds]
      (let [id (:id (db/add-strand! ds {:title "free" :attributes {:owner "a"}}))]
        (testing "patch change and nil-patch deletion"
          (db/update-strand! ds id {:attributes {:owner "b"}})
          (db/update-strand! ds id {:attributes {:owner nil}})
          (is (nil? (:owner (decoded-attrs ds id))))
          (db/update-strand! ds id {:attributes {:owner "c"}}))
        (testing "archive and unarchive"
          (db/archive-attributes! ds id [:owner])
          (db/unarchive-attributes! ds id [:owner])
          (is (= "c" (:owner (decoded-attrs ds id)))))
        (testing "batch update"
          (db/apply-batch! ds {:refs {:f id} :strands [{:ref :f :attributes {:owner "d"}}]})
          (is (= "d" (:owner (decoded-attrs ds id)))))))))

(deftest immutable-violation-ex-data-shape
  (with-db
    (fn [ds]
      (let [id (note-strand! ds)]
        (testing "a value change reports existing and attempted values"
          (let [ex (try (db/update-strand! ds id {:attributes {"note/text" "changed"}})
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= {:key "note/text" :strand-id id :existing "original" :attempted "changed"}
                   (ex-data ex)))))
        (testing "a deletion reports :attempted nil"
          (let [ex (try (db/update-strand! ds id {:attributes {"note/text" nil}})
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= {:key "note/text" :strand-id id :existing "original" :attempted nil}
                   (ex-data ex)))))))))
