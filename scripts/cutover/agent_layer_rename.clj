(ns cutover.agent-layer-rename
  "One-shot cutover that rewrites the durable attribute keys of *active* strands
  from the pre-rename shuttle/treadle vocabulary to the agent-run/gate/review/
  panel/note vocabulary, so the canonical world matches the renamed code at the
  user-signed weaver restart (PLAN-Alr-001.PH6/TC4/CM3, PROP-Alr-001.C1).

  The mapping is the brief's cutover-contract table read row-by-row, never a
  generic prefix rule: shuttle/* run attrs split into agent-run/review/panel/note
  families per key, so a blanket shuttle/*->agent-run/* rewrite would mis-map the
  review/panel/note keys. Only shuttle/handle.<k> is a genuine dynamic-suffix
  prefix rewrite. Event-type keywords (:agent-run/engine, :gate/engine) are event
  registrations, not durable attributes, so they never appear here.

  Scope is active strands only; closed/replaced strands are historical memory,
  not authority. A key absent from the table is left in the old vocabulary by
  design. The db target must be explicit (--db, or --workspace resolved through
  `mill weaver status`); the script refuses to guess a canonical world."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [clojure.data.json :as json]
   [next.jdbc :as jdbc]))

;; Exact per-key renames, transcribed row-by-row from the brief's cutover
;; contract. Ordering is irrelevant: every rewrite matches on the whole key, so
;; the note family (shuttle/note -> note/text) never collides with the run family
;; (shuttle/note-for is its own row under note/*).
(def exact-renames
  {;; Run attributes (millstrand.spools.agent-run): shuttle/... -> agent-run/...
   "shuttle/phase" "agent-run/phase"
   "shuttle/harness" "agent-run/harness"
   "shuttle/prompt" "agent-run/prompt"
   "shuttle/result" "agent-run/result"
   "shuttle/error" "agent-run/error"
   "shuttle/error-class" "agent-run/error-class"
   "shuttle/parse-error" "agent-run/parse-error"
   "shuttle/exit-code" "agent-run/exit-code"
   "shuttle/session-id" "agent-run/session-id"
   "shuttle/session" "agent-run/session"
   "shuttle/resumes" "agent-run/resumes"
   "shuttle/log" "agent-run/log"
   "shuttle/pid" "agent-run/pid"
   "shuttle/pid-started-at" "agent-run/pid-started-at"
   "shuttle/started-at" "agent-run/started-at"
   "shuttle/finished-at" "agent-run/finished-at"
   "shuttle/spawned-by" "agent-run/spawned-by"
   "shuttle/attempt" "agent-run/attempt"
   "shuttle/max-attempts" "agent-run/max-attempts"
   "shuttle/recovered-at" "agent-run/recovered-at"
   "shuttle/recovery-deferred-until" "agent-run/recovery-deferred-until"
   "shuttle/cwd" "agent-run/cwd"
   "shuttle/mode" "agent-run/mode"
   "shuttle/backend" "agent-run/backend"
   "shuttle/completion" "agent-run/completion"
   "shuttle/for" "agent-run/for"
   "shuttle/reap" "agent-run/reap"
   "shuttle/teardown-error" "agent-run/teardown-error"
   ;; Boolean markers renamed in F1 (dropped/reworked in F2, not here).
   "shuttle/run" "agent-run/run"
   "shuttle/serves" "agent-run/serves"
   ;; Review attributes (millstrand.spools.delegation): shuttle/review-... -> review/...
   "shuttle/review-target" "review/target"
   "shuttle/review-pass" "review/pass"
   "shuttle/review-roster" "review/roster"
   "shuttle/review-focus" "review/focus"
   "shuttle/review-synthesis" "review/synthesis"
   ;; Panel attributes: shuttle/... -> panel/...
   "shuttle/panel-seat" "panel/seat"
   "shuttle/panel-turn" "panel/turn"
   "shuttle/fresh-prompt" "panel/fresh-prompt"
   "shuttle/role" "panel/role"
   ;; Note attributes: shuttle/... -> note/...
   "shuttle/note-for" "note/for"
   "shuttle/note" "note/text"
   "shuttle/note-by" "note/by"
   "shuttle/round" "note/round"
   "shuttle/at" "note/at"
   ;; Gate attributes (millstrand.spools.executors.subagent): treadle/... -> gate/...
   "treadle/error" "gate/error"
   "treadle/delivered" "gate/delivered"
   "treadle/delivery-blocked" "gate/delivery-blocked"
   "treadle/run" "gate/run"
   "treadle/gate" "gate/step"
   "treadle/run-id" "gate/run-id"
   "treadle/superseded-by" "gate/superseded-by"
   ;; Workflow gate-outcome string (removes the note-record name collision).
   "workflow/notes" "workflow/outcome-notes"})

;; Dynamic per-handle keys are written as (str "shuttle/handle." k), so they can
;; carry any suffix and must be rewritten by prefix rather than exact match.
(def handle-old-prefix "shuttle/handle.")
(def handle-new-prefix "agent-run/handle.")

(def ^:private active-scope
  "strand_id IN (SELECT id FROM strands WHERE state = 'active')")

(defn- rewrite-exact!
  "Rewrite one exact key across active strands' attributes; returns rows changed."
  [tx old-key new-key]
  (-> (jdbc/execute-one!
       tx
       [(str "UPDATE attributes SET key = ? WHERE key = ? AND " active-scope)
        new-key old-key])
      :next.jdbc/update-count))

(defn- rewrite-handle-prefix!
  "Rewrite the dynamic shuttle/handle.<k> keys across active strands by prefix,
  preserving each per-handle suffix; returns rows changed."
  [tx]
  (-> (jdbc/execute-one!
       tx
       [(str "UPDATE attributes SET key = ? || substr(key, ?) "
             "WHERE key LIKE ? AND " active-scope)
        handle-new-prefix
        (inc (count handle-old-prefix))
        (str handle-old-prefix "%")]) ; % is literal, keys never embed a wildcard
      :next.jdbc/update-count))

(defn rewrite-keys!
  "Apply the whole cutover to `ds` (a next.jdbc datasource) in one transaction.
  Returns a map of {changed-key row-count} for every rewrite that touched a row,
  plus :total. An empty map (total 0) means the world was already migrated, so a
  re-run is a no-op."
  [ds]
  (jdbc/with-transaction [tx ds]
    (let [exact (into {}
                      (keep (fn [[old-key new-key]]
                              (let [n (rewrite-exact! tx old-key new-key)]
                                (when (pos? n) [old-key n]))))
                      exact-renames)
          handle-n (rewrite-handle-prefix! tx)
          changes (cond-> exact
                    (pos? handle-n) (assoc (str handle-old-prefix "<key>") handle-n))]
      (assoc changes :total (reduce + 0 (vals changes))))))

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
  discovery, no workspace-local data/ assumption."
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
      (println "Usage: cutover.agent-layer-rename --db <path> | --workspace <dir>")
      (println summary)
      (System/exit 0))
    (try
      (let [db-path (resolve-db-path options)
            ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})
            result (rewrite-keys! ds)]
        (println (str "Cutover complete against " db-path))
        (println (str "Rows rewritten: " (:total result)))
        (doseq [[k n] (sort-by key (dissoc result :total))]
          (println (format "  %-32s %d" k n)))
        (System/exit 0))
      (catch Exception e
        (binding [*out* *err*]
          (println "Cutover aborted:" (ex-message e))
          (when-let [data (ex-data e)] (println (pr-str data))))
        (System/exit 1)))))
