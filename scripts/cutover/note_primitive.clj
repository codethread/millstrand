(ns cutover.note-primitive
  "One-shot HISTORY rewrite that re-keys every pre-cutover note strand with a
  live target onto the blessed `note/*` shape and the `notes` edge, so the
  canonical database matches the unified note reader at the user-signed weaver
  restart (PROP-Np-001.C10, PLAN-Np-001.S11/TC4). It mirrors the F2
  rehearse-on-a-copy ceremony (scripts/cutover/agent_engine_primitives.clj) and
  the F1 rename cutover (scripts/cutover/agent_layer_rename.clj).

  Scope is deliberately history-wide — every note strand with a live target,
  active or closed — the epic's one recorded departure from the F1/F2
  active-only rule, because the unified reader walks the `notes` relation
  regardless of state, so an un-rekeyed historical note would be invisible
  (PROP-Np-001.C10, PLAN-Np-001.CM3). Non-note history keeps its old shape.

  Two derivations, both keyed off the pre-cutover markers and both counted at
  cutover time (never hardcoded — the world accrues notes continuously,
  PROP-Np-001.R3/Q4):

  1. Shuttle-era notes with a live target (`shuttle/note-for` present, the named
     target still exists, the `notes` edge already in place from before the
     cutover): `shuttle/note` -> `note/text`, `shuttle/note-by` -> `note/by`,
     `shuttle/at` -> `note/at`; `shuttle/note-for` is dropped (the edge already
     carries linkage, PROP-Np-001.C8). The `notes` edge is left untouched.

  2. Kanban notes (`kanban/note \"true\"`) with a live card: `body` ->
     `note/text`, the `parent-of` edge (card -> note) becomes a `notes` edge
     (note -> card), `note/at` is synthesized from the strand's `created_at` and
     `note/by` from its `author` attr where present; the `kanban/note`,
     `kanban/handover`, and `kind` decorating attrs are kept.

  Notes with no live target are skipped and keep their old shape — the 67-style
  dangling shuttle notes whose target was burned (their `notes` edge cascaded
  away) and any kanban note whose card is gone (its `parent-of` cascaded away).
  They are inert closed memory, unreachable through the relation and read by
  nothing after cutover (PROP-Np-001.C11, Q4).

  Every `notes` edge insert refuses a self-referential edge (the relation is
  declared acyclic, PROP-Np-001.C1/R4): a malformed derivation must fail loudly
  on the rehearsal copy rather than corrupt a traversal live. The re-key drops
  and renames are idempotent — a re-run against a stamped world finds no
  `shuttle/note-for` on live shuttle notes and no `parent-of` on migrated kanban
  notes, so it reports a :total of 0 (PROP-Np-001.C10.1, DW1).

  The db target must be explicit (--db, or --workspace resolved through
  `mill weaver status`); the script refuses to guess a canonical world. A live
  world's db lives under the weaver state directory, not workspace-local `data/`
  (F1/F2 precedent, TASK-Aep-011.MI5).

  Run standalone (scripts/ is not on the :test alias registry):
    clojure -Sdeps '{:paths [\"scripts\"]}' -M -m cutover.note-primitive --db <path>

  This script is the worker-owned rehearsal half of PROP-Np-001.C10. The
  canonical live steps — quiet-board cutover (C10.3), the user-signed weaver
  restart under `cu3wz` (C13.2, a hard stop), and the post-cutover smoke
  (C13.3) — are Task 13, coordinator-run after explicit user sign-off. A worker
  never runs this against the canonical world."
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [next.jdbc :as jdbc]))

(def ^:private kanban-note-marker
  "JSON-encoded value of the `kanban/note` boolean marker on a kanban note."
  (json/write-str "true"))

;; The set of note strands (any state) whose `shuttle/note-for` names a target
;; that still exists — the live-target scope for the shuttle re-key. A dangling
;; note (burned target) is excluded, so it is skipped and keeps its old shape.
;; The `notes` edge is not consulted here: linkage is confirmed straight from
;; the surviving target, and the edge is left exactly as it is.
(def ^:private live-shuttle-notes
  (str "strand_id IN ("
       "SELECT strand_id FROM attributes "
       "WHERE key = 'shuttle/note-for' "
       "AND json_extract(value, '$') IN (SELECT id FROM strands))"))

(defn- rename-in-live-shuttle!
  "Rename one exact attribute key across live-target shuttle notes; returns rows
  changed. The scope reads the sibling `shuttle/note-for` row, never the key
  being renamed, so the rewrite never races its own scope."
  [tx old-key new-key]
  (-> (jdbc/execute-one!
       tx
       [(str "UPDATE attributes SET key = ? WHERE key = ? AND " live-shuttle-notes)
        new-key old-key])
      :next.jdbc/update-count))

(defn- drop-live-shuttle-note-for!
  "Drop `shuttle/note-for` from every note whose target still exists (the edge
  already carries linkage, C8); a dangling note keeps it. The row's own value is
  the target id, so this needs no sibling subquery. Returns rows removed."
  [tx]
  (-> (jdbc/execute-one!
       tx
       ["DELETE FROM attributes WHERE key = 'shuttle/note-for' AND json_extract(value, '$') IN (SELECT id FROM strands)"])
      :next.jdbc/update-count))

(defn- shuttle-skipped-dangling
  "Count of shuttle notes skipped because their `shuttle/note-for` target no
  longer exists (the 67-style dangling memory). Diagnostic, never a mutation."
  [tx]
  (-> (jdbc/execute-one!
       tx
       ["SELECT count(*) AS n FROM attributes WHERE key = 'shuttle/note-for' AND json_extract(value, '$') NOT IN (SELECT id FROM strands)"])
      :n))

(defn- kanban-parent-edges
  "Vector of {:card :note} for every `parent-of` edge whose target is a kanban
  note (`kanban/note \"true\"`). The FK guarantees the card strand exists, so
  these are exactly the kanban notes with a live card."
  [tx]
  (mapv (fn [{:strand_edges/keys [from_strand_id to_strand_id]}]
          {:card from_strand_id :note to_strand_id})
        (jdbc/execute!
         tx
         [(str "SELECT e.from_strand_id, e.to_strand_id "
               "FROM strand_edges e "
               "JOIN attributes a ON a.strand_id = e.to_strand_id "
               "AND a.key = 'kanban/note' AND a.value = ? "
               "WHERE e.edge_type = 'parent-of'")
          kanban-note-marker])))

(defn- add-notes-edge!
  "Insert a `notes` edge (note -> card), refusing a self-referential edge
  (PROP-Np-001.R4): the relation is acyclic, so a malformed derivation must fail
  loudly rather than corrupt traversal. Idempotent. Returns rows inserted."
  [tx from to]
  (when (= from to)
    (throw (ex-info "Refusing a self-referential notes edge" {:strand from})))
  (-> (jdbc/execute-one!
       tx
       ["INSERT OR IGNORE INTO strand_edges (from_strand_id, to_strand_id, edge_type) VALUES (?, ?, ?)"
        from to "notes"])
      :next.jdbc/update-count))

(defn- delete-parent-of!
  "Remove the retired card -> note `parent-of` edge; the `notes` edge now carries
  the linkage. Returns rows removed."
  [tx card note]
  (-> (jdbc/execute-one!
       tx
       ["DELETE FROM strand_edges WHERE from_strand_id = ? AND to_strand_id = ? AND edge_type = 'parent-of'"
        card note])
      :next.jdbc/update-count))

(defn- rename-attr!
  "Rename one attribute key on a single strand; returns rows changed."
  [tx strand-id old-key new-key]
  (-> (jdbc/execute-one!
       tx
       ["UPDATE attributes SET key = ? WHERE strand_id = ? AND key = ?" new-key strand-id old-key])
      :next.jdbc/update-count))

(defn- synthesize-note-at!
  "Set `note/at` from the strand's `created_at` column when absent (JSON-encoded
  to match the stored shape). Idempotent. Returns rows inserted."
  [tx strand-id]
  (-> (jdbc/execute-one!
       tx
       ["INSERT OR IGNORE INTO attributes (strand_id, key, value) SELECT id, 'note/at', json_quote(created_at) FROM strands WHERE id = ?"
        strand-id])
      :next.jdbc/update-count))

(defn- synthesize-note-by!
  "Re-key the strand's `author` attr to `note/by` when present (its value is
  already JSON-encoded). No-op when absent. Returns rows changed."
  [tx strand-id]
  (rename-attr! tx strand-id "author" "note/by"))

(defn- kanban-skipped-no-card
  "Count of kanban notes skipped because they never landed on a `notes` edge —
  their card was burned, so the `parent-of` edge cascaded away and there was no
  live target to migrate onto. Read after migration, so the migrated notes
  (which now carry a `notes` edge) are excluded. Diagnostic, never a mutation."
  [tx]
  (-> (jdbc/execute-one!
       tx
       [(str "SELECT count(*) AS n FROM attributes "
             "WHERE key = 'kanban/note' AND value = ? "
             "AND strand_id NOT IN (SELECT from_strand_id FROM strand_edges WHERE edge_type = 'notes')")
        kanban-note-marker])
      :n))

(defn rewrite!
  "Apply the whole HISTORY rewrite to `ds` (a next.jdbc datasource) in one
  transaction: re-key live-target shuttle and kanban notes onto the blessed
  `note/*` shape and the `notes` edge, retire the old markers, and leave
  dangling notes as inert old-shape memory. Returns a map of per-change counts
  plus :total; the two skip counts are diagnostic and kept out of :total so an
  idempotent re-run (which re-skips the same debris) still totals 0
  (PROP-Np-001.C10.1, DW1)."
  [ds]
  (jdbc/with-transaction [tx ds]
    (let [;; Shuttle-era re-key: renames read the sibling shuttle/note-for row,
          ;; so they must run before that row is dropped last.
          shuttle-text (rename-in-live-shuttle! tx "shuttle/note" "note/text")
          shuttle-by (rename-in-live-shuttle! tx "shuttle/note-by" "note/by")
          shuttle-at (rename-in-live-shuttle! tx "shuttle/at" "note/at")
          shuttle-for-dropped (drop-live-shuttle-note-for! tx)
          ;; Kanban re-key + edge conversion, keyed off the surviving card edges.
          kedges (kanban-parent-edges tx)
          knotes (distinct (map :note kedges))
          kanban-edges (reduce (fn [n {:keys [card note]}] (+ n (add-notes-edge! tx note card))) 0 kedges)
          kanban-parent-removed (reduce (fn [n {:keys [card note]}] (+ n (delete-parent-of! tx card note))) 0 kedges)
          kanban-text (reduce (fn [n note] (+ n (rename-attr! tx note "body" "note/text"))) 0 knotes)
          kanban-at (reduce (fn [n note] (+ n (synthesize-note-at! tx note))) 0 knotes)
          kanban-by (reduce (fn [n note] (+ n (synthesize-note-by! tx note))) 0 knotes)
          changes {:shuttle-note-text shuttle-text
                   :shuttle-note-by shuttle-by
                   :shuttle-note-at shuttle-at
                   :shuttle-note-for-dropped shuttle-for-dropped
                   :kanban-notes-edges kanban-edges
                   :kanban-parent-of-removed kanban-parent-removed
                   :kanban-note-text kanban-text
                   :kanban-note-at kanban-at
                   :kanban-note-by kanban-by}]
      (assoc changes
             :total (reduce + 0 (vals changes))
             :shuttle-skipped-dangling (shuttle-skipped-dangling tx)
             :kanban-skipped-no-card (kanban-skipped-no-card tx)))))

(defn- resolve-workspace-db
  "Resolve a workspace's live SQLite path from `mill weaver status`. The live db
  lives under the weaver state dir, not workspace-local data/, so we ask mill for
  database_path rather than assuming a location. Fails loudly if mill errors or
  reports no path."
  [workspace]
  (let [{:keys [exit out err]}
        (shell/sh "mill" "weaver" "status" "--workspace" workspace)]
    (when-not (zero? exit)
      (throw (ex-info (str "mill weaver status failed for workspace " workspace)
                      {:exit exit :err (str/trim (str err))})))
    (let [path (get (json/read-str out) "database_path")]
      (when (str/blank? path)
        (throw (ex-info "mill weaver status returned no database_path"
                        {:workspace workspace :out out})))
      path)))

(defn resolve-db-path
  "Resolve the explicit db target from parsed options. Refuses to run without an
  explicit target and refuses an ambiguous pair — no implicit canonical-world
  discovery, no workspace-local data/ assumption (PROP-Np-001.C10.1)."
  [{:keys [db workspace]}]
  (cond
    (and db workspace)
    (throw (ex-info "Pass exactly one of --db or --workspace, not both" {:db db :workspace workspace}))
    db db
    workspace (resolve-workspace-db workspace)
    :else
    (throw (ex-info "Refusing to run without an explicit db target: pass --db <path> or --workspace <dir>" {}))))

(def ^:private cli-options
  [["-d" "--db PATH" "Explicit path to the target millstrand.sqlite"]
   ["-w" "--workspace DIR" "Workspace whose live db is resolved via `mill weaver status`"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (when (seq errors)
      (binding [*out* *err*] (run! println errors))
      (System/exit 2))
    (when (:help options)
      (println "Usage: cutover.note-primitive --db <path> | --workspace <dir>")
      (println summary)
      (System/exit 0))
    (try
      (let [db-path (resolve-db-path options)
            ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})
            result (rewrite! ds)]
        (println (str "Cutover complete against " db-path))
        (println (str "Total changes: " (:total result)))
        (doseq [[k n] (sort-by key (dissoc result :total))]
          (println (format "  %-28s %d" (name k) n)))
        (System/exit 0))
      (catch Exception e
        (binding [*out* *err*]
          (println "Cutover aborted:" (ex-message e))
          (when-let [data (ex-data e)] (println (pr-str data))))
        (System/exit 1)))))
