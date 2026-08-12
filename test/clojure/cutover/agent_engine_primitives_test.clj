(ns cutover.agent-engine-primitives-test
  "Fixture test for the F2 agent-engine-primitives cutover. Seeds a throwaway
  SQLite db with pre-cutover shape across every case — an active serving run
  carrying the `agent-run/serves` boolean, an active read-only helper, an active
  subagent gate with a current and a superseded predecessor run, a closed run,
  and a closed superseded predecessor of an active successor — runs `stamp!`, and
  asserts the full round-trip: active serving runs gain exactly one `serves` edge
  and lose the boolean; helpers keep theirs and gain none; gate/run and the
  delegates edge become the derived `serves` edge; lineage is backfilled onto
  active successors from both active and closed predecessors; retired markers are
  removed on active strands only; closed strands are untouched; delivery
  bookkeeping survives; a second run is an idempotent no-op; and a malformed
  self-referential derivation fails loudly (PROP-Aep-001.R3).

  Run standalone (scripts/ is not on the :test alias registry):
    clojure -Sdeps '{:extra-paths [\"scripts\"]}' -M -m cutover.agent-engine-primitives-test"
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is run-tests]]
   [cutover.agent-engine-primitives :as cut]
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
                        PRIMARY KEY (strand_id, key))"])
  (jdbc/execute! ds ["CREATE TABLE strand_edges (
                        from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                        to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                        edge_type TEXT NOT NULL,
                        attributes TEXT NOT NULL DEFAULT '{}',
                        PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                        CHECK (json_valid(attributes)))"]))

(defn- add-strand! [ds id state]
  (jdbc/execute! ds ["INSERT INTO strands (id, title, state) VALUES (?, ?, ?)" id id state]))

(defn- set-attr! [ds strand-id k v]
  (jdbc/execute! ds ["INSERT INTO attributes (strand_id, key, value) VALUES (?, ?, ?)"
                     strand-id k (json/write-str v)]))

(defn- add-edge! [ds from to edge-type]
  (jdbc/execute! ds ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type) VALUES (?, ?, ?)"
                     from to edge-type]))

(defn- attr-keys [ds strand-id]
  (into #{}
        (map :attributes/key)
        (jdbc/execute! ds ["SELECT key FROM attributes WHERE strand_id = ?" strand-id])))

(defn- attr-value [ds strand-id k]
  (some-> (jdbc/execute-one! ds ["SELECT value FROM attributes WHERE strand_id = ? AND key = ?"
                                 strand-id k])
          :attributes/value
          json/read-str))

(defn- out-edges [ds from edge-type]
  (into #{}
        (map :strand_edges/to_strand_id)
        (jdbc/execute! ds ["SELECT to_strand_id FROM strand_edges WHERE from_strand_id = ? AND edge_type = ?"
                           from edge-type])))

(defn- has-edge? [ds from to edge-type]
  (boolean (jdbc/execute-one!
            ds ["SELECT 1 AS found FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = ?"
                from to edge-type])))

;; Pre-cutover fixture, spanning every derivation and scope rule:
;;   task-1, coord        — active anchors (a delegated task and a spawner)
;;   serving-1            — active serving run, carries agent-run/serves "true";
;;                          successor of the closed predecessor closed-pred-1
;;   helper-1            — active read-only helper (agent-run/serves "false")
;;   gate-1              — active subagent gate, gate/run -> subrun-1 + delegates
;;   subrun-1           — active current subagent run, supersedes subrun-0
;;   subrun-0           — active superseded predecessor of subrun-1
;;   succ-1             — active successor of closed-pred-1 (no serving target)
;;   closed-run-1        — closed run, full old shape, must stay untouched
;;   closed-pred-1       — closed superseded predecessor, marker must stay
(defn- seed! [ds]
  (doseq [[id state] [["task-1" "active"] ["coord" "active"]
                      ["serving-1" "active"] ["helper-1" "active"]
                      ["gate-1" "active"] ["subrun-1" "active"] ["subrun-0" "active"]
                      ["succ-1" "active"] ["stale-run" "active"]
                      ["closed-run-1" "closed"] ["closed-pred-1" "closed"]]]
    (add-strand! ds id state))
  ;; active serving run: boolean stamp present, placed under task + spawner
  (set-attr! ds "serving-1" "agent-run/run" "true")
  (set-attr! ds "serving-1" "agent-run/serves" "true")
  (set-attr! ds "serving-1" "agent-run/spawned-by" "coord")
  (add-edge! ds "task-1" "serving-1" "parent-of")
  (add-edge! ds "coord" "serving-1" "parent-of")
  ;; active read-only helper: same placement, marked non-serving
  (set-attr! ds "helper-1" "agent-run/run" "true")
  (set-attr! ds "helper-1" "agent-run/serves" "false")
  (set-attr! ds "helper-1" "agent-run/spawned-by" "coord")
  (add-edge! ds "task-1" "helper-1" "parent-of")
  (add-edge! ds "coord" "helper-1" "parent-of")
  ;; active subagent gate + its current and superseded runs
  (set-attr! ds "gate-1" "workflow/gate" "subagent")
  (set-attr! ds "gate-1" "gate/run" "subrun-1")
  (set-attr! ds "gate-1" "gate/error" "")
  (add-edge! ds "gate-1" "subrun-1" "delegates")
  (set-attr! ds "subrun-1" "agent-run/run" "true")
  (set-attr! ds "subrun-1" "gate/step" "gate-1")
  (set-attr! ds "subrun-1" "gate/run-id" "wf-1")
  (set-attr! ds "subrun-0" "agent-run/run" "true")
  (set-attr! ds "subrun-0" "gate/step" "gate-1")
  (set-attr! ds "subrun-0" "gate/superseded-by" "subrun-1")
  (set-attr! ds "subrun-0" "agent-run/phase" "failed")
  ;; active successor of a closed predecessor, no serving target of its own
  (set-attr! ds "succ-1" "agent-run/run" "true")
  ;; active run whose subagent gate was removed: a dangling target, must be
  ;; skipped rather than stamped as a dangling serves edge
  (set-attr! ds "stale-run" "agent-run/run" "true")
  (set-attr! ds "stale-run" "gate/step" "ghost-gate")
  ;; closed run: full old shape, out of scope
  (set-attr! ds "closed-run-1" "agent-run/run" "true")
  (set-attr! ds "closed-run-1" "agent-run/serves" "false")
  (set-attr! ds "closed-run-1" "gate/step" "gate-x")
  (add-edge! ds "task-1" "closed-run-1" "parent-of")
  ;; closed superseded predecessor of the active successor succ-1
  (set-attr! ds "closed-pred-1" "agent-run/run" "true")
  (set-attr! ds "closed-pred-1" "gate/superseded-by" "succ-1")
  (set-attr! ds "closed-pred-1" "agent-run/phase" "superseded"))

(defn- with-fixture [f]
  (let [file (java.io.File/createTempFile "aep-cutover-fixture" ".sqlite")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath file)})]
    (try
      (create-schema! ds)
      (seed! ds)
      (f ds)
      (finally (.delete file)))))

(deftest stamps-serves-edges-and-retires-boolean
  (with-fixture
    (fn [ds]
      (cut/stamp! ds)
      (is (= #{"task-1"} (out-edges ds "serving-1" "serves"))
          "an active serving run gains exactly one serves edge to its derived target")
      (is (nil? (attr-value ds "serving-1" "agent-run/serves"))
          "a stamped serving run loses its agent-run/serves boolean")
      (is (empty? (out-edges ds "helper-1" "serves"))
          "a read-only helper gains no serves edge")
      (is (= "false" (attr-value ds "helper-1" "agent-run/serves"))
          "a helper keeps its inert agent-run/serves boolean (idempotency marker)"))))

(deftest derives-gate-serves-and-retires-links
  (with-fixture
    (fn [ds]
      (cut/stamp! ds)
      (is (has-edge? ds "subrun-1" "gate-1" "serves")
          "the gate's current run gets a serves edge derived from gate/run and gate/step")
      (is (has-edge? ds "subrun-0" "gate-1" "serves")
          "an active superseded predecessor keeps serving its gate (excluded later by lineage)")
      (is (empty? (out-edges ds "gate-1" "delegates"))
          "the retired delegates edge is removed from the active gate")
      (is (nil? (attr-value ds "gate-1" "gate/run")) "gate/run is retired on the active gate")
      (is (nil? (attr-value ds "subrun-1" "gate/step")) "gate/step is retired on the active run")
      (is (nil? (attr-value ds "subrun-0" "gate/superseded-by"))
          "gate/superseded-by is retired on the active predecessor")
      (is (= "wf-1" (attr-value ds "subrun-1" "gate/run-id"))
          "gate/run-id delivery pointer survives")
      (is (= "" (attr-value ds "gate-1" "gate/error"))
          "gate/error delivery bookkeeping survives"))))

(deftest skips-dangling-serves-target
  (with-fixture
    (fn [ds]
      (let [result (cut/stamp! ds)]
        (is (= 1 (:serves-skipped-missing-target result))
            "a run whose derived target no longer exists is skipped, not stamped dangling")
        (is (empty? (out-edges ds "stale-run" "serves"))
            "no serves edge is stamped for the removed target")
        (is (nil? (attr-value ds "stale-run" "gate/step"))
            "the stale run's retired gate/step is still cleaned up")))))

(deftest backfills-lineage-onto-active-successors
  (with-fixture
    (fn [ds]
      (cut/stamp! ds)
      (is (has-edge? ds "subrun-1" "subrun-0" "supersedes")
          "an active successor supersedes its active predecessor")
      (is (= "subrun-0" (attr-value ds "subrun-1" "agent-run/supersedes"))
          "the successor's agent-run/supersedes attr names the predecessor")
      (is (has-edge? ds "succ-1" "closed-pred-1" "supersedes")
          "lineage is backfilled even when the linked predecessor is closed")
      (is (= "closed-pred-1" (attr-value ds "succ-1" "agent-run/supersedes"))
          "the closed-predecessor lineage attr is stamped on the active successor"))))

(deftest leaves-closed-strands-untouched
  (with-fixture
    (fn [ds]
      (cut/stamp! ds)
      (is (= #{"agent-run/run" "agent-run/serves" "gate/step"} (attr-keys ds "closed-run-1"))
          "a closed run keeps its full old-shape attributes (C13 memory)")
      (is (empty? (out-edges ds "closed-run-1" "serves"))
          "a closed run gains no serves edge")
      (is (= "succ-1" (attr-value ds "closed-pred-1" "gate/superseded-by"))
          "a closed predecessor keeps its gate/superseded-by marker"))))

(deftest reports-changes-and-is-idempotent
  (with-fixture
    (fn [ds]
      (let [first-run (cut/stamp! ds)
            second-run (cut/stamp! ds)]
        (is (= 3 (:serves-edges first-run))
            "serving-1, subrun-1, and subrun-0 gain serves edges (gate + run derivations dedup)")
        (is (= 2 (:supersedes-edges first-run)) "both active successors gain a supersedes edge")
        (is (= 2 (:supersedes-attrs first-run)) "both active successors gain the lineage attr")
        (is (= 1 (:agent-run-serves-removed first-run)) "only serving-1's boolean is retired; the helper's stays")
        (is (pos? (:total first-run)) "the first run reports changes")
        (is (= 0 (:total second-run)) "a re-run against a stamped world is a no-op"))))
  (with-fixture
    (fn [ds]
      (cut/stamp! ds)
      (cut/stamp! ds)
      (is (= #{"task-1"} (out-edges ds "serving-1" "serves"))
          "the stamped serves edge is stable across a second run")
      (is (= "false" (attr-value ds "helper-1" "agent-run/serves"))
          "the helper is never misclassified into a serves edge on re-run"))))

(deftest fails-loud-on-self-referential-derivation
  (let [file (java.io.File/createTempFile "aep-cutover-selfserve" ".sqlite")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath file)})]
    (try
      (create-schema! ds)
      (add-strand! ds "bad-gate" "active")
      (set-attr! ds "bad-gate" "workflow/gate" "subagent")
      (set-attr! ds "bad-gate" "gate/run" "bad-gate")
      (is (thrown? clojure.lang.ExceptionInfo (cut/stamp! ds))
          "a self-serve derivation fails loudly rather than corrupting traversal (R3)")
      (finally (.delete file)))))

(deftest refuses-implicit-target
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {}))
      "no --db and no --workspace must fail loudly")
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {:db "x" :workspace "y"}))
      "an ambiguous db/workspace pair must fail loudly")
  (is (= "/tmp/x.sqlite" (cut/resolve-db-path {:db "/tmp/x.sqlite"}))
      "an explicit --db is honoured verbatim"))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'cutover.agent-engine-primitives-test)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
