(ns cutover.note-primitive-test
  "Fixture test for the F3 note-primitive HISTORY rewrite. Seeds a throwaway
  SQLite db with the full pre-cutover spread — a shuttle-era note with a live
  target, a 67-style dangling shuttle note whose target was burned, a kanban
  note with a live card and author, a kanban note with a live card and no
  author, and a kanban note with no card — runs `rewrite!`, and asserts the
  round trip: live shuttle notes are re-keyed onto `note/*` and lose
  `shuttle/note-for` while keeping their `notes` edge; kanban notes gain a
  `notes` edge from `parent-of` and re-key `body` -> `note/text`; dangling notes
  are skipped and keep their old shape (PROP-Np-001.C11); a re-run is idempotent;
  and a self-referential derivation fails loudly (PROP-Np-001.R4).

  Run standalone (scripts/ is not on the :test alias registry):
    clojure -Sdeps '{:paths [\"scripts\"]}' -M -m cutover.note-primitive-test"
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is run-tests]]
   [cutover.note-primitive :as cut]
   [next.jdbc :as jdbc]))

(defn- create-schema! [ds]
  (jdbc/execute! ds ["PRAGMA foreign_keys = ON"])
  (jdbc/execute! ds ["CREATE TABLE strands (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        state TEXT NOT NULL DEFAULT 'active',
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
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

(defn- add-strand!
  ([ds id state] (add-strand! ds id state nil))
  ([ds id state created-at]
   (if created-at
     (jdbc/execute! ds ["INSERT INTO strands (id, title, state, created_at) VALUES (?, ?, ?, ?)" id id state created-at])
     (jdbc/execute! ds ["INSERT INTO strands (id, title, state) VALUES (?, ?, ?)" id id state]))))

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

(defn- has-edge? [ds from to edge-type]
  (boolean (jdbc/execute-one!
            ds ["SELECT 1 AS found FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = ?"
                from to edge-type])))

;; Pre-cutover fixture, spanning every derivation and scope rule:
;;   tgt-live         — a live target for a shuttle-era note
;;   card-1           — a live kanban card
;;   snote-live       — closed shuttle note with a live target + its notes edge
;;   snote-dangling   — closed shuttle note whose target was burned (67-style)
;;   knote-authored   — closed kanban handover note with an author, on card-1
;;   knote-anon       — closed kanban note with no author, on card-1
;;   knote-orphan     — closed kanban note with no card (parent-of cascaded away)
(defn- seed! [ds]
  (add-strand! ds "tgt-live" "active")
  (add-strand! ds "card-1" "active")
  ;; live-target shuttle note: old shape + its already-present notes edge
  (add-strand! ds "snote-live" "closed")
  (set-attr! ds "snote-live" "shuttle/note" "shuttle says hi")
  (set-attr! ds "snote-live" "shuttle/note-by" "agent-old")
  (set-attr! ds "snote-live" "shuttle/at" "2026-01-01T00:00:00.111Z")
  (set-attr! ds "snote-live" "shuttle/note-for" "tgt-live")
  (add-edge! ds "snote-live" "tgt-live" "notes")
  ;; dangling shuttle note: target burned, no notes edge, must be skipped
  (add-strand! ds "snote-dangling" "closed")
  (set-attr! ds "snote-dangling" "shuttle/note" "ghost note")
  (set-attr! ds "snote-dangling" "shuttle/note-by" "agent-old")
  (set-attr! ds "snote-dangling" "shuttle/at" "2026-01-02T00:00:00.222Z")
  (set-attr! ds "snote-dangling" "shuttle/note-for" "burned-target")
  ;; kanban handover note with author, on a live card
  (add-strand! ds "knote-authored" "closed" "2026-02-01 10:00:00")
  (set-attr! ds "knote-authored" "kanban/note" "true")
  (set-attr! ds "knote-authored" "kanban/handover" "true")
  (set-attr! ds "knote-authored" "kind" "note")
  (set-attr! ds "knote-authored" "body" "done X, next Y")
  (set-attr! ds "knote-authored" "author" "adam")
  (add-edge! ds "card-1" "knote-authored" "parent-of")
  ;; kanban note without an author, on a live card
  (add-strand! ds "knote-anon" "closed" "2026-02-02 11:00:00")
  (set-attr! ds "knote-anon" "kanban/note" "true")
  (set-attr! ds "knote-anon" "kind" "note")
  (set-attr! ds "knote-anon" "body" "anonymous note")
  (add-edge! ds "card-1" "knote-anon" "parent-of")
  ;; kanban note with no card: skipped, keeps old shape
  (add-strand! ds "knote-orphan" "closed")
  (set-attr! ds "knote-orphan" "kanban/note" "true")
  (set-attr! ds "knote-orphan" "kind" "note")
  (set-attr! ds "knote-orphan" "body" "orphaned note"))

(defn- with-fixture [f]
  (let [file (java.io.File/createTempFile "np-cutover-fixture" ".sqlite")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath file)})]
    (try
      (create-schema! ds)
      (seed! ds)
      (f ds)
      (finally (.delete file)))))

(deftest re-keys-live-shuttle-notes-and-keeps-the-edge
  (with-fixture
    (fn [ds]
      (cut/rewrite! ds)
      (is (= "shuttle says hi" (attr-value ds "snote-live" "note/text"))
          "shuttle/note is re-keyed to note/text")
      (is (= "agent-old" (attr-value ds "snote-live" "note/by"))
          "shuttle/note-by is re-keyed to note/by")
      (is (= "2026-01-01T00:00:00.111Z" (attr-value ds "snote-live" "note/at"))
          "shuttle/at is re-keyed to note/at")
      (is (= #{"note/text" "note/by" "note/at"} (attr-keys ds "snote-live"))
          "the old shuttle/* keys, including shuttle/note-for, are gone")
      (is (has-edge? ds "snote-live" "tgt-live" "notes")
          "the pre-existing notes edge is left in place"))))

(deftest skips-dangling-shuttle-notes
  (with-fixture
    (fn [ds]
      (let [result (cut/rewrite! ds)]
        (is (= 1 (:shuttle-skipped-dangling result))
            "a shuttle note whose target was burned is counted as skipped")
        (is (= #{"shuttle/note" "shuttle/note-by" "shuttle/at" "shuttle/note-for"}
               (attr-keys ds "snote-dangling"))
            "a dangling shuttle note keeps its full old shape (C11 inert memory)")
        (is (nil? (attr-value ds "snote-dangling" "note/text"))
            "a dangling shuttle note is not re-keyed")))))

(deftest migrates-kanban-notes-onto-the-notes-edge
  (with-fixture
    (fn [ds]
      (cut/rewrite! ds)
      (is (= "done X, next Y" (attr-value ds "knote-authored" "note/text"))
          "kanban body is re-keyed to note/text")
      (is (= "2026-02-01 10:00:00" (attr-value ds "knote-authored" "note/at"))
          "note/at is synthesized from created_at")
      (is (= "adam" (attr-value ds "knote-authored" "note/by"))
          "note/by is synthesized from the author attr")
      (is (has-edge? ds "knote-authored" "card-1" "notes")
          "the note gains a notes edge to its card")
      (is (not (has-edge? ds "card-1" "knote-authored" "parent-of"))
          "the retired parent-of edge is removed")
      (is (= #{"kanban/note" "kanban/handover" "kind" "note/text" "note/at" "note/by"}
             (attr-keys ds "knote-authored"))
          "decorating attrs stay, body is renamed, no stray keys")
      (is (nil? (attr-value ds "knote-anon" "note/by"))
          "a kanban note with no author gets no note/by")
      (is (= "anonymous note" (attr-value ds "knote-anon" "note/text"))
          "an author-less kanban note is still re-keyed")
      (is (has-edge? ds "knote-anon" "card-1" "notes")
          "an author-less kanban note still gains its notes edge"))))

(deftest skips-cardless-kanban-notes
  (with-fixture
    (fn [ds]
      (let [result (cut/rewrite! ds)]
        (is (= 1 (:kanban-skipped-no-card result))
            "a kanban note with no card is counted as skipped")
        (is (= #{"kanban/note" "kind" "body"} (attr-keys ds "knote-orphan"))
            "a card-less kanban note keeps its old shape")
        (is (empty? (attr-value ds "knote-orphan" "note/text"))
            "a card-less kanban note is not re-keyed")))))

(deftest reports-changes-and-is-idempotent
  (with-fixture
    (fn [ds]
      (let [first-run (cut/rewrite! ds)
            second-run (cut/rewrite! ds)]
        (is (= 1 (:shuttle-note-text first-run)) "one live shuttle note is re-keyed")
        (is (= 1 (:shuttle-note-for-dropped first-run)) "one live shuttle/note-for is dropped")
        (is (= 2 (:kanban-notes-edges first-run)) "both live-card kanban notes gain a notes edge")
        (is (= 2 (:kanban-parent-of-removed first-run)) "both retired parent-of edges are removed")
        (is (= 2 (:kanban-note-text first-run)) "both live-card kanban bodies are re-keyed")
        (is (= 1 (:kanban-note-by first-run)) "only the authored kanban note gains note/by")
        (is (= 13 (:total first-run)) "the first run reports every mutation")
        (is (= 0 (:total second-run)) "a re-run against a stamped world is a no-op")
        (is (= 1 (:shuttle-skipped-dangling second-run)) "the dangling shuttle note is still skipped on re-run")
        (is (= 1 (:kanban-skipped-no-card second-run)) "the card-less kanban note is still skipped on re-run"))))
  (with-fixture
    (fn [ds]
      (cut/rewrite! ds)
      (cut/rewrite! ds)
      (is (has-edge? ds "knote-authored" "card-1" "notes")
          "the migrated notes edge is stable across a second run")
      (is (= #{"note/text" "note/by" "note/at"} (attr-keys ds "snote-live"))
          "the re-keyed shuttle note is stable across a second run"))))

(deftest fails-loud-on-self-referential-derivation
  (let [file (java.io.File/createTempFile "np-cutover-selfnote" ".sqlite")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath file)})]
    (try
      (create-schema! ds)
      (add-strand! ds "bad-note" "closed")
      (set-attr! ds "bad-note" "kanban/note" "true")
      (set-attr! ds "bad-note" "body" "malformed")
      (add-edge! ds "bad-note" "bad-note" "parent-of")
      (is (thrown? clojure.lang.ExceptionInfo (cut/rewrite! ds))
          "a self-referential notes derivation fails loudly rather than corrupting traversal (R4)")
      (finally (.delete file)))))

(deftest refuses-implicit-target
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {}))
      "no --db and no --workspace must fail loudly")
  (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {:db "x" :workspace "y"}))
      "an ambiguous db/workspace pair must fail loudly")
  (is (= "/tmp/x.sqlite" (cut/resolve-db-path {:db "/tmp/x.sqlite"}))
      "an explicit --db is honoured verbatim"))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'cutover.note-primitive-test)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
