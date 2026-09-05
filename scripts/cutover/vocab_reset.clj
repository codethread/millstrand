(ns cutover.vocab-reset
  "One-shot cutover that rewrites the durable attributes of *active* strands from
  the pre-reset vocabulary to the words the vocabulary-reset branch published, so
  a live world matches the renamed code at the user-signed weaver restart
  (SPEC-005.C11, the shared-spool vocabulary rule's cutover clause).

  Scope is active strands only: closed and replaced strands are historical
  memory, not authority, so their attributes stay exactly as they were written.
  A key absent from `migrations` is left in the old vocabulary by design.

  The migration table is data — one map per durable-attribute migration — so a
  later slice appends an entry instead of editing the engine. Three shapes:

    {:migration :rename-key   :from \"old/key\" :to \"new/key\"}
    {:migration :rename-value :key \"k\" :from \"old\" :to \"new\"}
    {:migration :drop-key     :key \"k\"}

  The table is a closed registry, and `validate-table!` is its boundary: every
  entry is checked against its variant's spec before the rewrite opens a
  transaction, so an unknown migration word, a misspelled guard key, or a
  non-string value fails naming the offending entry rather than reaching SQL
  construction. An entry carries its variant's own fields, an optional `:note`,
  and at most one value guard beside any sibling guard — nothing else.

  Each entry may narrow its rows with guards, of two readings. Value guards —
  `:when-value` / `:unless-value` / `:when-value-in` — match the row's own JSON
  value, and are what let one overloaded key split two ways: `bench/run` carries
  a root id on judge strands but a plain \"true\" marker elsewhere, and only the
  former becomes `bench/run-id`. `:when-value-in` is the enumerated form, and
  says something stronger than one half of a split: it names the whole
  vocabulary the key may carry on an active strand, so a value outside the set
  is drift rather than the other half. `kanban/status` becomes `kanban/lane`
  only for the four board lanes; an active card stamped with a finished card's
  outcome is a card the new board itself refuses to place, so the cutover
  reports it and fails rather than renaming it into a lane it never sat in.
  Sibling guards ask about the strand around the row instead: `:when-sibling`
  requires another attribute at a given value, and `:when-sibling-key` requires
  only that the strand carry a key at all.

  Table order matters only where an entry reads a key another entry moves: the
  plan-root `kind` rename reads `workflow` \"agent-plan\", so it is listed before
  the entry that drops that key, and the `kanban/devflow` drop reads `kanban/run`
  before the rename carries that key to `kanban/run-id` — an order that also
  keeps the two roads to `kanban/run-id` from meeting on one strand. Every other
  pair is independent.

  Idempotent by construction: each statement matches the *old* shape, so a
  re-run against a migrated world matches nothing and reports a zero total. A
  strand carrying both the old and the new key is an inconsistent world rather
  than a migrated one — the attributes primary key (strand_id, key) cannot hold
  both after the rewrite — so the pre-flight refuses it loudly instead of
  surfacing a raw SQLite constraint violation.

  Rows a guard declined are reported, in two readings the exit code keeps apart.
  A sibling guard's decline is advisory (exit 0): it says the strand was shaped
  unlike what the entry expected, which is as often correct as not, and only the
  operator can tell which — so the row is named rather than migrated blind or
  dropped in silence. A `:when-value-in` decline is drift (exit 1): the
  enumeration is a claim about the key's whole vocabulary, so the value is one
  the new code has no word for, and a run that leaves it behind is not a cutover
  any automation may read as done. A `:when-value` / `:unless-value` decline is
  not reported at all: those rows are the deliberate other half of a split,
  still spelled the way this table intends to leave them.

  Drift fails after the commit rather than aborting the transaction, because the
  drift row is no obstacle to the rewrite: it keeps its old key, which is what
  its guard chose, and every other strand migrates cleanly around it. Aborting
  would hold a whole world hostage to a judgement only a human can make about
  one card, and would abort identically on every re-run. Committing keeps both
  claims above true — the world is migrated as far as the table reaches, and a
  re-run reports a zero total and the same drift, so the exit code stays 1 until
  the operator resolves the row itself.

  The db target must be explicit (--db, or --workspace resolved through
  `mill weaver status`); the script refuses to guess a canonical world. A live
  world's db lives under the weaver state directory, not workspace-local
  `data/`.

  Run it with scripts/ on the classpath:
    clojure -Sdeps '{:extra-paths [\"scripts\"]}' -M -m cutover.vocab-reset \\
      --db <path>

  WHEN: once per world, at merge/reload time, by the user — not by an agent and
  not against a world an agent owns. The canonical `.skein` world is migrated as
  a hand step: land the branch, stop the weaver, run this against the world's db,
  then start the weaver again on the renamed code. Rehearse on a copy of the db
  first: the rewrite is one transaction, so an *aborted* run leaves the world as
  it was, but a drift failure commits — the copy is where you find out which
  strands you will have to resolve by hand before the real one."
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [next.jdbc :as jdbc]))

(def migrations
  "Every durable-attribute migration the vocabulary reset shipped, in table form.

  Transcribed per rename from the branch's mutation slices rather than derived
  from a prefix rule: the old vocabulary split one prefix across several new
  owners (`review/*` became `panel/*` only for the blackboard/pass/synthesis
  keys, while `review/roster` and `review/focus` kept their word), so a blanket
  prefix rewrite would mis-map its neighbours.

  The kanban entries come from the same transcription applied to the sibling
  spools' own adoption of the reset vocabulary (kanban.spool 505e873), read at
  the pins this repo now builds against rather than from this repo's history.
  The devflow spool renamed no durable attribute in that slice, so it adds no
  entry here."
  [;; workflow: the run's shape is its form, and role names the graph position.
   {:migration :rename-key :from "workflow/phase" :to "workflow/form"
    :note "phase collided with agent-run/phase's lifecycle reading"}
   {:migration :rename-value :key "workflow/role" :from "molecule" :to "root"
    :note "the molecule metaphor lost to the plain graph noun"}
   {:migration :drop-key :key "workflow/wisp"
    :note "derivable from workflow/form = wisp; a second name for one concept"}
   {:migration :drop-key :key "workflow/hitl"
    :note "derivable from workflow/checkpoint-kind = human"}
   {:migration :rename-key :from "gate/run-id" :to "workflow/run-id"
    :note "the workflow run owns the id; the gate only pointed at it"}
   {:migration :rename-key :from "shell/error" :to "gate/error"
    :note "one failure stamp for both gate kinds, shell and subagent"}

   ;; agent-run: the run completes on its target, it is not merely 'for' it.
   {:migration :rename-key :from "agent-run/for" :to "agent-run/completes-on"}
   {:migration :rename-key :from "panel/fresh-prompt" :to "agent-run/fresh-prompt"
    :note "prompt freshness is the run's concern, not the panel's"}

   ;; delegation: the panel primitive owns the board it reviews against.
   {:migration :rename-key :from "review/target" :to "panel/blackboard"}
   {:migration :rename-key :from "review/pass" :to "panel/pass"}
   {:migration :rename-key :from "review/synthesis" :to "panel/synthesis"}
   {:migration :drop-key :key "panel/role" :when-value "panel"
    :note "the blackboard's self-marker; its kind already says what it is"}

   ;; Bare keys on delegated task strands, namespaced onto their owning spool.
   ;; Guarded by kind: a bare `harness` elsewhere is not agent-run's word.
   {:migration :rename-key :from "harness" :to "agent-run/harness"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "cwd" :to "agent-run/cwd"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "max-attempts" :to "agent-run/max-attempts"
    :when-sibling {"kind" "task"}}
   {:migration :rename-key :from "backend" :to "agent-run/backend"
    :when-sibling {"kind" "task"}}

   ;; The plan root stopped carrying its workflow name as a separate marker:
   ;; kind says what it is. This pair is order-dependent (see the ns docstring).
   {:migration :rename-value :key "kind" :from "plan" :to "agent-plan"
    :when-sibling {"workflow" "agent-plan"}}
   {:migration :drop-key :key "workflow" :when-value "agent-plan"
    :note "dropped from roots and children; kind carries the plan's identity"}

   ;; ephemeral: a bare key squatted the global namespace. This rename lands on
   ;; a parallel branch, so the entry is carried here from the slice's contract
   ;; rather than from this checkout's history.
   {:migration :rename-key :from "ephemeral" :to "ephemeral/entry"}

   ;; bench: the harness is the registered agent def, and `bench/run` was
   ;; overloaded — a root id on judge strands, a bare "true" marker on run
   ;; roots. Only the id-carrying rows become bench/run-id.
   {:migration :rename-key :from "bench/agent" :to "bench/harness"}
   {:migration :rename-key :from "bench/run" :to "bench/run-id"
    :unless-value "true"
    :note "marker rows keep bench/run; judge rows carried the root id"}
   {:migration :drop-key :key "bench/aborted" :when-value "true"
    :note "redundant with bench/error = aborted on a closed strand"}

   ;; kanban: the board split one status word in two — the lane an active card
   ;; sits in, and the outcome a finished one ended on. Only the lane half is in
   ;; scope, because an outcome is stamped as the card closes and closed cards
   ;; are historical memory; the four lanes are therefore the whole vocabulary
   ;; an active card may carry, and the new board reads anything else as drift.
   {:migration :rename-key :from "kanban/status" :to "kanban/lane"
    :when-value-in #{"refinement" "pending" "claimed" "in_review"}
    :note "the board owns placement; outcomes stay on the closed cards they end"}

   ;; The card's run binding, and the alias that preceded it. `kanban/devflow`
   ;; was already deprecated to a fallback the old spool read only where
   ;; `kanban/run` was absent, so the two entries follow that same reading:
   ;; where kanban/run answers, the alias is a value the code had already
   ;; stopped consulting and goes; where the alias is the sole copy, it *is* the
   ;; binding and takes the new word. Order-dependent (see the ns docstring).
   {:migration :drop-key :key "kanban/devflow" :when-sibling-key "kanban/run"
    :note "the deprecated alias, on a card whose canonical key already answers"}
   {:migration :rename-key :from "kanban/run" :to "kanban/run-id"
    :note "the id is the run's own; the tracker seam joins on kanban/run-id"}
   {:migration :rename-key :from "kanban/devflow" :to "kanban/run-id"
    :note "sole-copy rows: the alias held the run the old fallback reader used"}])

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(s/def ::migration #{:rename-key :rename-value :drop-key})
(s/def ::from non-blank-string?)
(s/def ::to non-blank-string?)
(s/def ::key non-blank-string?)
(s/def ::note string?)
(s/def ::when-value non-blank-string?)
(s/def ::unless-value non-blank-string?)
(s/def ::when-value-in (s/coll-of non-blank-string? :kind set? :min-count 1))
(s/def ::when-sibling (s/map-of non-blank-string? non-blank-string? :count 1))
(s/def ::when-sibling-key non-blank-string?)

(def ^:private guard-keys
  "The guard keys any entry may carry, whatever its variant."
  #{:when-value :unless-value :when-value-in :when-sibling :when-sibling-key})

(def ^:private variant-keys
  "The fields each migration variant declares, beside the guards and `:note`."
  {:rename-key #{:migration :from :to}
   :rename-value #{:migration :key :from :to}
   :drop-key #{:migration :key}})

(defn- known-keys?
  "True when `entry` carries no key beyond the ones its variant declares.

  What closes the registry: a misspelled `:when-vlaue` is a guard the engine
  silently would not apply, so it must be an error at the table's boundary
  rather than a rewrite that quietly ran unguarded."
  [entry]
  (let [allowed (into guard-keys (conj (get variant-keys (:migration entry) #{})
                                       :note))]
    (every? allowed (keys entry))))

(defn- at-most-one-value-guard?
  "True when `entry` carries no more than one guard against the row's own value.

  Two would compose into a contradiction the SQL would silently honour as a
  match on neither."
  [entry]
  (>= 1 (count (filterv (partial contains? entry)
                        [:when-value :unless-value :when-value-in]))))

(defmulti ^:private entry-shape
  "Dispatch a migration entry to its variant's closed spec."
  :migration)

(defmethod entry-shape :rename-key [_]
  (s/and (s/keys :req-un [::migration ::from ::to]
                 :opt-un [::note ::when-value ::unless-value ::when-value-in
                          ::when-sibling ::when-sibling-key])
         known-keys?))

(defmethod entry-shape :rename-value [_]
  (s/and (s/keys :req-un [::migration ::key ::from ::to]
                 :opt-un [::note ::when-value ::unless-value ::when-value-in
                          ::when-sibling ::when-sibling-key])
         known-keys?))

(defmethod entry-shape :drop-key [_]
  (s/and (s/keys :req-un [::migration ::key]
                 :opt-un [::note ::when-value ::unless-value ::when-value-in
                          ::when-sibling ::when-sibling-key])
         known-keys?))

(s/def ::entry (s/and map? (s/multi-spec entry-shape :migration)
                      at-most-one-value-guard?))
(s/def ::table (s/coll-of ::entry :kind vector? :min-count 1))

(defn validate-table!
  "Return `table` if every entry satisfies the registry contract, else throw.

  The rewrite calls this before it opens its transaction: a later slice appends
  entries to a table the engine reads as data, and an entry the engine cannot
  read must fail as itself — named, with the spec's reading of what is wrong —
  rather than as a SQL error partway through a world's migration."
  [table]
  (if-let [entry (and (coll? table)
                      (first (remove (partial s/valid? ::entry) table)))]
    (throw (ex-info (str "Malformed migration entry: " (s/explain-str ::entry entry))
                    {:entry entry}))
    (when-not (s/valid? ::table table)
      (throw (ex-info (str "Malformed migration table: " (s/explain-str ::table table))
                      {:table table}))))
  table)

(def ^:private active-scope
  "SQL predicate narrowing a statement to attributes of active strands."
  "strand_id IN (SELECT id FROM strands WHERE state = 'active')")

(defn- sibling-clause
  "Return `[sql params]` requiring the strand to carry `k` at `v`, or nil."
  [when-sibling]
  (when when-sibling
    (let [[k v] (first when-sibling)]
      [(str " AND strand_id IN (SELECT strand_id FROM attributes"
            " WHERE key = ? AND value = ?)")
       [k (json/write-str v)]])))

(defn- sibling-key-clause
  "Return `[sql params]` requiring the strand to carry `k` at any value, or nil."
  [when-sibling-key]
  (when when-sibling-key
    [(str " AND strand_id IN (SELECT strand_id FROM attributes WHERE key = ?)")
     [when-sibling-key]]))

(defn- value-clause
  "Return `[sql params]` matching (or excluding) the row's own JSON value, or nil."
  [{:keys [when-value unless-value when-value-in]}]
  (cond
    when-value [" AND value = ?" [(json/write-str when-value)]]
    unless-value [" AND value <> ?" [(json/write-str unless-value)]]
    when-value-in [(str " AND value IN ("
                        (str/join ", " (repeat (count when-value-in) "?"))
                        ")")
                   (mapv json/write-str (sort when-value-in))]
    :else nil))

(defn- guards
  "Return `[sql params]` for an entry's row guards, composed in table order."
  [entry]
  (let [clauses (keep identity [(value-clause entry)
                                (sibling-clause (:when-sibling entry))
                                (sibling-key-clause (:when-sibling-key entry))])]
    [(str/join (map first clauses))
     (into [] (mapcat second) clauses)]))

(defn- decline-reading
  "Return how an entry's declined rows read: `:drift`, `:advisory`, or nil.

  A `:when-value-in` guard enumerates the whole vocabulary its key may carry, so
  a row it declined holds a value the new code has no word for: definite drift,
  and the cutover's failure. A sibling guard's decline says only that the strand
  was shaped unlike what the entry expected, which the operator may well read as
  correct: advisory. A `:when-value` / `:unless-value` decline is neither, and a
  `:rename-value` entry declines nothing legible — its source key survives the
  rewrite, so a row still carrying it is evidence of nothing."
  [{:keys [migration] :as entry}]
  (when (#{:rename-key :drop-key} migration)
    (cond
      (contains? entry :when-value-in) :drift
      (some (partial contains? entry) [:when-sibling :when-sibling-key]) :advisory)))

(defn- source-key
  "Return the attribute key an entry's rows carry before it runs."
  [{:keys [migration from key]}]
  (if (= :rename-key migration) from key))

(defn- label
  "Return the human-readable name this entry reports itself under."
  [{:keys [migration from to key] :as entry}]
  (let [guarded (when (decline-reading entry) " (guarded)")]
    (case migration
      :rename-key (str from " -> " to guarded)
      :rename-value (str key ": " from " -> " to)
      :drop-key (str "drop " key guarded))))

(defn- apply-entry!
  "Apply one migration entry within `tx`; return the number of rows changed."
  [tx {:keys [migration from to key] :as entry}]
  (let [[guard-sql guard-params] (guards entry)]
    (-> (jdbc/execute-one!
         tx
         (case migration
           :rename-key
           (into [(str "UPDATE attributes SET key = ? WHERE key = ? AND "
                       active-scope guard-sql)
                  to from]
                 guard-params)

           :rename-value
           (into [(str "UPDATE attributes SET value = ? WHERE key = ? AND value = ? AND "
                       active-scope guard-sql)
                  (json/write-str to) key (json/write-str from)]
                 guard-params)

           :drop-key
           (into [(str "DELETE FROM attributes WHERE key = ? AND " active-scope guard-sql)
                  key]
                 guard-params)

           (throw (ex-info "Unknown migration shape" {:entry entry}))))
        :next.jdbc/update-count)))

(defn- collisions
  "Return the active strands already carrying both sides of a `:rename-key`.

  A row here means the world is inconsistent rather than half-migrated: the
  rewrite cannot land both keys under the attributes primary key, so the caller
  must abort loudly with the evidence instead of letting SQLite raise."
  [tx]
  (into []
        (mapcat (fn [{:keys [from to] :as entry}]
                  (let [[guard-sql guard-params] (guards entry)]
                    (->> (jdbc/execute!
                          tx
                          (into [(str "SELECT strand_id FROM attributes WHERE key = ? AND "
                                      active-scope guard-sql
                                      " AND strand_id IN (SELECT strand_id FROM attributes"
                                      " WHERE key = ?)")
                                 from]
                                (concat guard-params [to])))
                         (map (fn [row]
                                {:strand-id (:attributes/strand_id row)
                                 :migration (label entry)}))))))
        (filter (comp #{:rename-key} :migration) migrations)))

(defn- declined-rows
  "Return `{label [strand-id]}` for the entries whose declines read as `reading`.

  Called after the rewrite, so a row still carrying an entry's source key is by
  construction one its guard declined — the migrated rows have moved on. A row a
  later entry claimed under another name is not named here, because it no longer
  carries the key.

  The advisory half is the guards that narrow a key to the rows whose shape
  earns the rewrite: `harness` is agent-run's word on a delegated task and
  nobody else's. A row such a guard skipped is either correctly none of our
  business or a strand shaped unexpectedly, and only the operator can tell
  which. The drift half is
  `kanban/status`, a lane only where it holds one: the guard names the whole
  vocabulary, so a declined row is a strand the new code cannot read."
  [tx reading]
  (into {}
        (keep (fn [entry]
                (let [ids (->> (jdbc/execute!
                                tx
                                [(str "SELECT strand_id FROM attributes WHERE key = ? AND "
                                      active-scope)
                                 (source-key entry)])
                               (mapv :attributes/strand_id))]
                  (when (seq ids) [(label entry) ids]))))
        (filter (comp #{reading} decline-reading) migrations)))

(defn rewrite!
  "Apply every migration to `ds` (a next.jdbc datasource) in one transaction.

  Returns `{:changes {label row-count} :total n :left-behind {label [strand-id]}
  :drift {label [strand-id]}}`. `:changes` lists only the entries that touched a
  row, so a zero total means the world was already migrated and a re-run is a
  no-op. `:left-behind` names the rows a sibling guard declined, for the
  operator to read; `:drift` names the rows left outside a vocabulary a guard
  enumerates in full, which `report` turns into a nonzero exit.

  Throws when the migration table is malformed, or when an active strand carries
  both sides of a rename — neither reaches SQL."
  [ds]
  (validate-table! migrations)
  (jdbc/with-transaction [tx ds]
    (when-let [found (seq (collisions tx))]
      (throw (ex-info (str "Refusing to migrate: " (count found)
                           " active strand(s) carry both the old and the new key")
                      {:collisions found})))
    (let [changes (into {}
                        (keep (fn [entry]
                                (let [n (apply-entry! tx entry)]
                                  (when (pos? n) [(label entry) n]))))
                        migrations)]
      {:changes changes
       :total (reduce + 0 (vals changes))
       :left-behind (declined-rows tx :advisory)
       :drift (declined-rows tx :drift)})))

(defn report
  "Print the cutover's outcome against `db-path`; return the process exit code.

  Drift exits 1, naming each entry and the strands still under its old key: the
  rewrite committed, so the world is migrated as far as the table reaches, but a
  strand the new code has no word for is left behind and no automation may read
  the run as a finished cutover. A sibling guard's decline prints in its own
  section and keeps the exit at 0 — it is a reading for the operator, not a
  failure."
  [db-path {:keys [changes total left-behind drift]}]
  (println (str (if (seq drift) "Cutover incomplete against " "Cutover complete against ")
                db-path))
  (println (str "Rows rewritten: " total))
  (doseq [[k n] (sort-by key changes)]
    (println (format "  %-48s %d" k n)))
  (when (seq left-behind)
    (println)
    (println "Left in the old vocabulary — a guard declined these rows:")
    (doseq [[k ids] (sort-by key left-behind)]
      (println (format "  %-48s %s" k (str/join " " ids)))))
  (when (seq drift)
    (println)
    (println "Drift — these active strands hold a value outside the vocabulary their entry enumerates:")
    (doseq [[k ids] (sort-by key drift)]
      (println (format "  %-48s %s" k (str/join " " ids))))
    (println)
    (println "The rewrite committed, but these rows keep the old key: resolve each strand by hand."))
  (if (seq drift) 1 0))

(defn- resolve-workspace-db
  "Resolve a workspace's live SQLite path from `mill weaver status`.

  The live db lives under the weaver state dir, not workspace-local `data/`, so
  we ask mill for database_path rather than assuming a location. Fails loudly if
  mill errors or reports no path."
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
  "Resolve the explicit db target from parsed options.

  Refuses to run without an explicit target and refuses an ambiguous pair — no
  implicit canonical-world discovery, no workspace-local `data/` assumption."
  [{:keys [db workspace]}]
  (cond
    (and db workspace)
    (throw (ex-info "Pass exactly one of --db or --workspace, not both"
                    {:db db :workspace workspace}))
    db db
    workspace (resolve-workspace-db workspace)
    :else
    (throw (ex-info "Refusing to run without an explicit db target: pass --db <path> or --workspace <dir>"
                    {}))))

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
      (println "Usage: cutover.vocab-reset --db <path> | --workspace <dir>")
      (println summary)
      (System/exit 0))
    (try
      (let [db-path (resolve-db-path options)
            ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})]
        (System/exit (report db-path (rewrite! ds))))
      (catch Exception e
        (binding [*out* *err*]
          (println "Cutover aborted:" (ex-message e))
          (when-let [data (ex-data e)] (println (pr-str data))))
        (System/exit 1)))))
