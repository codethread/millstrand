(ns cutover.agent-layer-rename-test
  "Fixture test for the one-shot agent-layer cutover. Seeds a throwaway SQLite db
  with old-vocabulary attributes across every family (including a dynamic
  shuttle/handle.<k> key, an off-table key, and a closed strand), runs the
  rewrite, and asserts the full round-trip: every table row in scope carries the
  correct per-key new key, off-table keys and closed strands are untouched,
  attribute values are never rewritten, and a second run is an idempotent no-op.

  Run standalone (scripts/ is not on the :test alias registry):
    clojure -Sdeps '{:extra-paths [\"scripts\"]}' -M -m cutover.agent-layer-rename-test"
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is run-tests]]
   [cutover.agent-layer-rename :as cut]
   [next.jdbc :as jdbc]))

(defn- create-schema! [ds]
  (jdbc/execute! ds ["CREATE TABLE strands (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        state TEXT NOT NULL DEFAULT 'active',
                        CHECK (state IN ('active', 'closed', 'replaced')))"])
  (jdbc/execute! ds ["CREATE TABLE attributes (
                        strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                        key TEXT NOT NULL,
                        value TEXT NOT NULL CHECK (json_valid(value)),
                        archived INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (strand_id, key))"]))

(defn- add-strand! [ds id state]
  (jdbc/execute! ds ["INSERT INTO strands (id, title, state) VALUES (?, ?, ?)" id id state]))

(defn- set-attr! [ds strand-id k v]
  (jdbc/execute! ds ["INSERT INTO attributes (strand_id, key, value) VALUES (?, ?, ?)"
                     strand-id k (json/write-str v)]))

(defn- attr-keys [ds strand-id]
  (into #{}
        (map :attributes/key)
        (jdbc/execute! ds ["SELECT key FROM attributes WHERE strand_id = ?" strand-id])))

(defn- attr-value [ds strand-id k]
  (some-> (jdbc/execute-one! ds ["SELECT value FROM attributes WHERE strand_id = ? AND key = ?"
                                 strand-id k])
          :attributes/value
          json/read-str))

;; One representative old key per family plus the dynamic-handle key, a marker,
;; and the workflow gate-outcome string — enough to prove every rewrite path.
(def ^:private seed-attrs
  {"shuttle/phase" "running"
   "shuttle/handle.claude" "sess-abc"
   "shuttle/run" true
   "shuttle/serves" true
   "shuttle/review-target" "tgt-1"
   "shuttle/panel-seat" "seat-1"
   "shuttle/role" "panel"
   "shuttle/note" "hello"
   "shuttle/note-for" "someone"
   "shuttle/round" 2
   "shuttle/at" "2026-07-09"
   "treadle/gate" "gate-step-1"
   "treadle/run" "run-9"
   "treadle/error" "boom"
   "workflow/notes" "outcome text"
   ;; Off-table key: left in the old vocabulary by design (MI3).
   "shuttle/not-in-table" "keep-me"})

(def ^:private expected-active-keys
  #{"agent-run/phase"
    "agent-run/handle.claude"
    "agent-run/run"
    "agent-run/serves"
    "review/target"
    "panel/seat"
    "panel/role"
    "note/text"
    "note/for"
    "note/round"
    "note/at"
    "gate/step"
    "gate/run"
    "gate/error"
    "workflow/outcome-notes"
    "shuttle/not-in-table"})

(defn- with-fixture [f]
  (let [file (java.io.File/createTempFile "cutover-fixture" ".sqlite")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath file)})]
    (try
      (create-schema! ds)
      (add-strand! ds "active-1" "active")
      (add-strand! ds "closed-1" "closed")
      (doseq [[k v] seed-attrs]
        (set-attr! ds "active-1" k v)
        (set-attr! ds "closed-1" k v))
      (f ds)
      (finally (.delete file)))))

(deftest rewrites-active-strand-to-new-vocabulary
  (with-fixture
    (fn [ds]
      (cut/rewrite-keys! ds)
      (is (= expected-active-keys (attr-keys ds "active-1"))
          "every in-table key on the active strand is rewritten per-key; off-table key stays")
      (is (= "running" (attr-value ds "active-1" "agent-run/phase"))
          "values are carried unchanged, only keys are rewritten")
      (is (= "sess-abc" (attr-value ds "active-1" "agent-run/handle.claude"))
          "dynamic handle suffix is preserved by the prefix rewrite"))))

(deftest leaves-closed-strands-untouched
  (with-fixture
    (fn [ds]
      (cut/rewrite-keys! ds)
      (is (= (set (keys seed-attrs)) (attr-keys ds "closed-1"))
          "closed strands are historical memory, not authority (MI3)"))))

(deftest reports-rows-and-is-idempotent
  (with-fixture
    (fn [ds]
      (let [first-run (cut/rewrite-keys! ds)
            second-run (cut/rewrite-keys! ds)]
        ;; 15 seeded in-table keys, one off-table key left alone.
        (is (= 15 (:total first-run)) "first run rewrites every in-table row on the active strand")
        (is (= {:total 0} second-run) "a re-run against a migrated world is a no-op")
        (is (= expected-active-keys (attr-keys ds "active-1"))
            "the migrated key set is stable across a second run")))))

(deftest refuses-implicit-target
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {}))
      "no --db and no --workspace must fail loudly")
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {:db "x" :workspace "y"}))
      "an ambiguous db/workspace pair must fail loudly")
  (is (= "/tmp/x.sqlite" (cut/resolve-db-path {:db "/tmp/x.sqlite"}))
      "an explicit --db is honoured verbatim"))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'cutover.agent-layer-rename-test)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
