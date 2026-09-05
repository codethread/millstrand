(ns cutover.agent-engine-primitives
  "One-shot cutover that stamps the F2 agent-engine-primitives shape onto the
  *active* runs and gates of a live world, so the canonical database matches the
  rewired engine at the user-signed weaver restart (PROP-Aep-001.C12.1/C12.2,
  PLAN-Aep-001.S11/TC4). The three derivations mirror the F1 rehearse-on-a-copy
  ceremony (scripts/cutover/agent_layer_rename.clj):

  1. `serves` edges (PROP-Aep-001.C1). Serving is moving from the
     `parent-of`-plus-`agent-run/serves`-boolean overload to a single `serves`
     edge (run -> served target). For each *active* serving run this stamps that
     edge, derived from the pre-cutover heuristic — `gate/step` for a subagent
     run, else the first `parent-of` source that is not the run's `spawned-by`
     provenance. A read-only helper is a run carrying `agent-run/serves \"false\"`;
     it gets no `serves` edge. Active gates are the other authority: a gate's
     `gate/run` names its current delegated run, so `run -> gate` is stamped from
     it too (the current run's edge is derived from both sides and de-duplicated).

  2. `supersedes` lineage (PROP-Aep-001.C4/C5). Pre-cutover, the only structural
     succession link is the subagent executor's `gate/superseded-by` back-marker
     (predecessor -> successor). For each still-linked pair whose *successor* is
     active, this backfills the successor's `supersedes` edge (successor
     --supersedes--> predecessor) and its `agent-run/supersedes` attr. A plain
     `agent retry` recorded no link (bare `agent-run/phase \"superseded\"`), so
     its predecessors are orphaned closed memory — nothing to backfill.

  3. Retirement. The retired markers — `agent-run/serves` (on stamped serving
     runs only, see below), `gate/run`, `gate/step`, `gate/superseded-by`, and
     the `delegates` edge — are removed from *active* strands. `gate/run-id`
     (delivery pointer) and `gate/error`/`gate/delivered`/`gate/delivery-blocked`
     (delivery bookkeeping) are unaffected.

  Scope is active strands only (PROP-Aep-001.C12.1, C13): archived/closed strands
  are historical memory, not authority — the code wins, and the new readers are
  scoped to active work so a historical strand's old shape is never misread.
  Their retired attrs are left in place.

  A `false`-valued `agent-run/serves` boolean is deliberately *kept* on active
  helpers. The post-cutover engine never reads it, so it is inert; keeping it is
  what makes the script idempotent — with the boolean gone a helper is
  structurally indistinguishable from an unstamped serving run (both carry only
  `parent-of` placement), so a re-run would misclassify it and stamp a spurious
  `serves` edge. Serving runs, which carry no such marker, lose any
  `agent-run/serves` they had.

  Every `serves`/`supersedes` insert refuses a self-referential edge
  (PROP-Aep-001.R3): both relations are declared acyclic, so a malformed
  derivation must fail loudly on the rehearsal copy rather than corrupt
  traversal live.

  The db target must be explicit (--db, or --workspace resolved through
  `mill weaver status`); the script refuses to guess a canonical world. A live
  world's db lives under the weaver state directory, not workspace-local `data/`
  (SPEC-Aep-003).

  Run standalone (scripts/ is not on the :test alias registry):
    clojure -Sdeps '{:extra-paths [\"scripts\"]}' -M -m cutover.agent-engine-primitives \\
      --db <path>

  This script is the worker-owned rehearsal half of PROP-Aep-001.C12. The
  canonical live steps — quiet-board stamping (C12.3), the user-signed weaver
  restart (C12.4, a hard stop), and the post-cutover smoke (C12.5) — are Task 13,
  coordinator-run after explicit user sign-off. A worker never runs this against
  the canonical world."
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [next.jdbc :as jdbc]))

(def ^:private helper-marker
  "JSON-encoded value of the `agent-run/serves` boolean on a read-only helper."
  (json/write-str "false"))

(defn- decode
  "Decode one JSON attribute value into its Clojure value (ids are strings)."
  [text]
  (json/read-str text))

(defn- active-ids
  "The set of active strand ids — the whole authority scope for this cutover."
  [tx]
  (into #{}
        (map :strands/id)
        (jdbc/execute! tx ["SELECT id FROM strands WHERE state = 'active'"])))

(defn- strand-ids
  "The set of every strand id, regardless of state — the domain a derived edge
  target must land in, so the cutover never stamps a dangling edge to a strand
  that has since been removed."
  [tx]
  (into #{}
        (map :strands/id)
        (jdbc/execute! tx ["SELECT id FROM strands"])))

(defn- active-attrs
  "Map active strand-id -> {attr-key decoded-value} for the keys this cutover
  reads, from one indexed fetch. `active` is the active-id set (scope filter)."
  [tx active keys]
  (let [placeholders (str/join ", " (repeat (count keys) "?"))]
    (reduce (fn [m {:attributes/keys [strand_id key value]}]
              (cond-> m
                (contains? active strand_id) (assoc-in [strand_id key] (decode value))))
            {}
            (jdbc/execute!
             tx
             (into [(str "SELECT strand_id, key, value FROM attributes WHERE key IN (" placeholders ")")]
                   keys)))))

(defn- parents-by-run
  "Map run-id -> vector of its `parent-of` source ids (the strands placing it in
  the graph), across the given active runs."
  [tx run-ids]
  (if (empty? run-ids)
    {}
    (let [placeholders (str/join ", " (repeat (count run-ids) "?"))]
      (reduce (fn [m {:strand_edges/keys [from_strand_id to_strand_id]}]
                (update m to_strand_id (fnil conj []) from_strand_id))
              {}
              (jdbc/execute!
               tx
               (into [(str "SELECT from_strand_id, to_strand_id FROM strand_edges "
                           "WHERE edge_type = 'parent-of' AND to_strand_id IN (" placeholders ")")]
                     run-ids))))))

(defn- superseded-links
  "Vector of [predecessor-id successor-id] from every `gate/superseded-by`
  back-marker, regardless of strand state, so an active successor's lineage can
  be backfilled from a predecessor in any state (the marker is the still-linked
  relation, PROP-Aep-001.C4)."
  [tx]
  (mapv (fn [{:attributes/keys [strand_id value]}]
          [strand_id (decode value)])
        (jdbc/execute! tx ["SELECT strand_id, value FROM attributes WHERE key = 'gate/superseded-by'"])))

(defn- serves-target
  "The serving target for one active run given its attrs and `parent-of` sources:
  its subagent gate (`gate/step`), else the first `parent-of` source that is not
  its `spawned-by` provenance. This is the pre-cutover `run-for-target`
  heuristic, transcribed so the edge is stamped for exactly the runs that served
  before. nil when the run has no derivable target (e.g. a lone `spawned-by`
  placement)."
  [attrs parents]
  (or (get attrs "gate/step")
      (let [spawned-by (get attrs "agent-run/spawned-by")]
        (first (remove #(= spawned-by %) parents)))))

(defn- add-edge!
  "Insert one edge, refusing a self-referential `serves`/`supersedes` edge
  (PROP-Aep-001.R3): the relations are acyclic, so a malformed derivation must
  fail loudly rather than corrupt traversal. Idempotent — a re-run ignores an
  edge that already exists. Returns rows inserted (0 or 1)."
  [tx edge-type from to]
  (when (= from to)
    (throw (ex-info (str "Refusing a self-referential " edge-type " edge")
                    {:edge-type edge-type :strand from})))
  (-> (jdbc/execute-one!
       tx
       ["INSERT OR IGNORE INTO strand_edges (from_strand_id, to_strand_id, edge_type) VALUES (?, ?, ?)"
        from to edge-type])
      :next.jdbc/update-count))

(defn- set-attr!
  "Set an attribute only when absent (idempotent, and never clobbering a value a
  live succession already wrote). Returns rows inserted (0 or 1)."
  [tx strand-id k v]
  (-> (jdbc/execute-one!
       tx
       ["INSERT OR IGNORE INTO attributes (strand_id, key, value) VALUES (?, ?, ?)"
        strand-id k (json/write-str v)])
      :next.jdbc/update-count))

(defn- delete-active-attr!
  "Remove a retired attribute key across active strands; `keep-value` (optional)
  preserves rows whose JSON-encoded value equals it. Returns rows removed."
  ([tx k] (delete-active-attr! tx k nil))
  ([tx k keep-value]
   (let [scope "strand_id IN (SELECT id FROM strands WHERE state = 'active')"]
     (-> (if keep-value
           (jdbc/execute-one!
            tx
            [(str "DELETE FROM attributes WHERE key = ? AND value <> ? AND " scope) k keep-value])
           (jdbc/execute-one!
            tx
            [(str "DELETE FROM attributes WHERE key = ? AND " scope) k]))
         :next.jdbc/update-count))))

(defn- delete-active-edges!
  "Remove a retired edge type originating from active strands. Returns rows removed."
  [tx edge-type]
  (-> (jdbc/execute-one!
       tx
       [(str "DELETE FROM strand_edges WHERE edge_type = ? "
             "AND from_strand_id IN (SELECT id FROM strands WHERE state = 'active')")
        edge-type])
      :next.jdbc/update-count))

(defn- serving-edges
  "Derive `serves` edges from the active runs and gates, split into the edges to
  stamp and the count skipped because their derived target no longer exists. A
  run carrying `agent-run/serves \"false\"` is a read-only helper and contributes
  none; a serving run contributes an edge to its `serves-target`; an active gate
  contributes its current `gate/run`. Edges are de-duplicated so the current
  subagent run's edge — derivable from both its own `gate/step` and the gate's
  `gate/run` — is stamped once. An edge whose target is not in `strands` (a stale
  run whose target was removed) is dropped rather than stamped dangling, and
  counted so the coordinator sees the debris."
  [attrs-by-id parents-by-id strands]
  (let [run-edges (keep (fn [[id attrs]]
                          (when (and (contains? attrs "agent-run/run")
                                     (not= "false" (get attrs "agent-run/serves")))
                            (when-let [target (serves-target attrs (get parents-by-id id []))]
                              [id target])))
                        attrs-by-id)
        gate-edges (keep (fn [[id attrs]]
                           (when-let [run (get attrs "gate/run")]
                             [run id]))
                         attrs-by-id)
        {live true stale false} (group-by (fn [[_ target]] (contains? strands target))
                                          (distinct (concat run-edges gate-edges)))]
    {:edges (vec live) :skipped-missing-target (count stale)}))

(defn stamp!
  "Apply the whole cutover to `ds` (a next.jdbc datasource) in one transaction:
  derive every edge and lineage fact from the pre-cutover markers, then retire
  those markers on active strands. Returns a map of per-change counts plus
  :total; a :total of 0 means the world was already stamped, so a re-run is a
  no-op (PROP-Aep-001.C12.1, DW1)."
  [ds]
  (jdbc/with-transaction [tx ds]
    (let [read-keys ["agent-run/run" "agent-run/serves" "agent-run/spawned-by"
                     "gate/step" "gate/run"]
          active (active-ids tx)
          attrs-by-id (active-attrs tx active read-keys)
          run-ids (into [] (keep (fn [[id attrs]]
                                   (when (contains? attrs "agent-run/run") id)))
                        attrs-by-id)
          parents-by-id (parents-by-run tx run-ids)
          strands (strand-ids tx)
          {serves :edges serves-skipped :skipped-missing-target}
          (serving-edges attrs-by-id parents-by-id strands)
          serves-n (reduce (fn [n [run target]] (+ n (add-edge! tx "serves" run target))) 0 serves)
          ;; lineage: only an active successor is backfilled (mid-lineage active
          ;; runs, PROP-Aep-001.C12.1); a closed predecessor stays untouched.
          lineage (filter (fn [[_ succ]] (contains? active succ)) (superseded-links tx))
          [supersedes-n supersedes-attr-n]
          (reduce (fn [[en an] [pred succ]]
                    [(+ en (add-edge! tx "supersedes" succ pred))
                     (+ an (set-attr! tx succ "agent-run/supersedes" pred))])
                  [0 0]
                  lineage)
          changes {:serves-edges serves-n
                   :supersedes-edges supersedes-n
                   :supersedes-attrs supersedes-attr-n
                   ;; helper `false` markers are kept (see ns docstring); only
                   ;; serving runs' boolean is retired.
                   :agent-run-serves-removed (delete-active-attr! tx "agent-run/serves" helper-marker)
                   :gate-run-removed (delete-active-attr! tx "gate/run")
                   :gate-step-removed (delete-active-attr! tx "gate/step")
                   :gate-superseded-by-removed (delete-active-attr! tx "gate/superseded-by")
                   :delegates-removed (delete-active-edges! tx "delegates")}]
      ;; :serves-skipped-missing-target is diagnostic, not a mutation: it is kept
      ;; out of :total so an idempotent re-run (which re-skips the same debris)
      ;; still totals 0.
      (assoc changes
             :total (reduce + 0 (vals changes))
             :serves-skipped-missing-target serves-skipped))))

(defn- resolve-workspace-db
  "Resolve a workspace's live SQLite path from `mill weaver status`. The live db
  lives under the weaver state dir, not workspace-local data/, so we ask mill for
  database_path rather than assuming a location. Fails loudly if mill errors or
  reports no path."
  [workspace]
  (let [{:keys [exit out err]}
        (shell/sh "mill" "weaver" "status" "--json" "--workspace" workspace)]
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
  discovery, no workspace-local data/ assumption (PROP-Aep-001.C12.2)."
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
      (println "Usage: cutover.agent-engine-primitives --db <path> | --workspace <dir>")
      (println summary)
      (System/exit 0))
    (try
      (let [db-path (resolve-db-path options)
            ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})
            result (stamp! ds)]
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
