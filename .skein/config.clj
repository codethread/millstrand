(ns config
  "Repo-local Skein runtime configuration for skein-src: named queries and the
  thin CLI op surface over the shipped spools.

  Thin glue only: `ct.spools.devflow` owns the feature lifecycle,
  `skein.spools.workflow` is its generic CLI, `ct.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern (all activated from
  init.clj). This file registers the `kanban-tree` board projection op, the
  `hitl` session op, and a few named queries. Sibling init.clj modules hold the
  rest of the repo policy: hand-authored workflows in workflows.clj, harness
  seats in harnesses.clj, chime attention rules in attention.clj, the NVD scan
  cron job in nvd_scan.clj, and reviewer rosters in reviewers.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skein.macros.ops :refer [defop]]
            [skein.macros.queries :refer [defquery]]
            [ct.spools.agent-run :as shuttle]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :refer [attr-get entity-projection]]
            [skein.api.weaver.alpha :as weaver]))

(def ^:private stamped-op-return
  {:type :map :required {:operation :string} :extra :json})

;; ---------------------------------------------------------------------------
;; Named queries
;; ---------------------------------------------------------------------------

(defquery feature-active-query
  "Parameterized query for all active strands carrying a feature attribute."
  {:usage "strand list --query feature-active --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]]})

(defquery feature-work-query
  "Parameterized query for active task/review strands in a feature."
  {:usage "strand ready --query feature-work --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-owner-work-query
  "Parameterized query for active task/review strands in a feature owned by one actor."
  {:usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
  {:params [:feature :owner]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:= [:attr :owner] [:param :owner]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-run-query
  "Parameterized query for the active strands of one workflow run/feature."
  {:usage "strand list --query feature-run --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :feature]]]})

(defquery workflow-runs-query
  "Query for active workflow roots (any family)."
  {:usage "strand list --query workflow-runs --limit 500"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]])

(defquery devflow-runs-query
  "Query for active devflow lifecycle roots."
  {:usage "strand list --query devflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]
   [:= [:attr "workflow/family"] "devflow"]])

(defquery work-query
  "Query for active actionable work, excluding workflow plumbing, agent run records, and inert kanban refinement cards."
  {:usage "strand ready --query work"}
  [:and
   [:= :state "active"]
   [:or [:missing [:attr "agent-run/run"]]
    [:not [:= [:attr "agent-run/run"] "true"]]]
   [:or [:missing [:attr "kanban/lane"]]
    [:not [:= [:attr "kanban/lane"] "refinement"]]]
   [:or
    [:missing [:attr "workflow/role"]]
    [:not [:in [:attr "workflow/role"] ["root" "digest" "procedure"]]]]])

;; ---------------------------------------------------------------------------
;; kanban-tree: epic -> feature -> task projection for the agent dashboard
;; ---------------------------------------------------------------------------

;; The kanban board links its three tiers (epic -> feature -> task) only through
;; parent-of edges — no tier carries its parent's id as an attribute — so the CLI
;; query surface, which filters flat strand rows by attribute, cannot join them.
;; This projection walks each tier with one batched single-hop `outgoing-edges`
;; call (no transitive subgraph, so note/agent-run descendants never leak in) and
;; derives task status exactly as `kanban card` does, letting a renderer build the
;; collapsible tree from a single poll rather than a round trip per feature. The
;; task-tier helpers below mirror the kanban spool's private `feature-tasks` /
;; `derive-task-status` / `tasks-with-status`, which are not part of its API.

(defn- kanban-card-type
  "Return a kanban card's type, defaulting to feature (cards predating the epic tier)."
  [strand]
  (or (attr-get strand :kanban/type) "feature"))

(defn- kanban-task?
  "Return true when strand carries the kanban task marker."
  [strand]
  (= "true" (attr-get strand :kanban/task)))

(defn- kanban-task-status
  "Derive a task's status from core graph state and the core `owner` attr only:
  `done` when closed, `blocked` while any dependency is unclosed, then
  `doing`/`ready` on whether an owner is stamped."
  [task dep-states]
  (cond
    (= "closed" (:state task)) "done"
    (some #(not= "closed" %) dep-states) "blocked"
    (some? (attr-get task :owner)) "doing"
    :else "ready"))

(defn- kanban-tasks-with-status
  "Return a map of task id -> compact task view decorated with derived status,
  batching the depends-on frontier so status resolves without a per-task round
  trip."
  [rt tasks]
  (let [dep-edges (graph/outgoing-edges rt (mapv :id tasks) "depends-on")
        target-state (into {}
                           (map (juxt :id :state))
                           (graph/strands-by-ids rt (into [] (map :to_strand_id) dep-edges)))
        deps-by-task (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
                               (update m from_strand_id (fnil conj []) to_strand_id))
                             {} dep-edges)]
    (into {}
          (map (fn [task]
                 [(:id task)
                  (cond-> {:id (:id task)
                           :title (:title task)
                           :state (:state task)
                           :status (kanban-task-status
                                    task
                                    (map target-state (get deps-by-task (:id task))))}
                    (attr-get task :owner) (assoc :owner (attr-get task :owner)))]))
          tasks)))

(defn- kanban-tree-projection
  "Join the kanban card tiers into cards carrying their parent epic id and their
  tasks with derived status. `all?` includes closed cards and tasks; otherwise
  only active cards and their non-closed tasks are returned."
  [rt all?]
  (let [all-cards (graph/strands-by-ids rt (graph/query-ids rt 'kanban-cards {}))
        cards (if all? (vec all-cards) (filterv #(= "active" (:state %)) all-cards))
        epics (filterv #(= "epic" (kanban-card-type %)) cards)
        features (filterv #(= "feature" (kanban-card-type %)) cards)
        feature-ids (set (map :id features))
        ;; epic -> feature: direct parent-of children that are feature cards on the board
        epic-of-feature (into {}
                              (comp (filter #(feature-ids (:to_strand_id %)))
                                    (map (juxt :to_strand_id :from_strand_id)))
                              (graph/outgoing-edges rt (mapv :id epics) "parent-of"))
        ;; feature -> task: direct parent-of children carrying the task marker
        task-edges (graph/outgoing-edges rt (mapv :id features) "parent-of")
        tasks (cond->> (filter kanban-task? (graph/strands-by-ids rt (mapv :to_strand_id task-edges)))
                (not all?) (filter #(not= "closed" (:state %)))
                :always vec)
        task-view (kanban-tasks-with-status rt tasks)
        tasks-of-feature (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
                                   (if-let [t (task-view to_strand_id)]
                                     (update m from_strand_id (fnil conj []) t)
                                     m))
                                 {} task-edges)]
    {:operation "kanban-tree"
     :cards (mapv (fn [s]
                    (assoc (entity-projection s)
                           :created_at (:created_at s)
                           :updated_at (:updated_at s)
                           :type (kanban-card-type s)
                           :epic (get epic-of-feature (:id s))
                           :tasks (vec (sort-by :id (get tasks-of-feature (:id s) [])))))
                  cards)}))

(defop kanban-tree
  "Return the kanban board as an epic -> feature -> task hierarchy in one call.

  Each card carries its `type` (epic/feature), the `epic` id it hangs under (nil
  for top-level cards), and its `tasks` with derived status, so a renderer builds
  the collapsible board without a round trip per feature. Read-only; mirrors the
  `kanban-cards` query's active-by-default scope, widened with `--all true`."
  {:returns stamped-op-return :arg-spec {:op "kanban-tree"
                                         :hook-class :read
                                         :deadline-class :standard
                                         :doc "Project the epic -> feature -> task kanban hierarchy with derived task status."
                                         :flags {:all {:type :boolean-token
                                                       :doc "Include closed cards and tasks: true or false (default false)."}}}}
  [ctx]
  (let [{:keys [all]} (:op/args ctx)]
    (kanban-tree-projection (current/runtime) (boolean all))))

;; ---------------------------------------------------------------------------
;; devflow ops: thin CLI wrappers over ct.spools.devflow
;; ---------------------------------------------------------------------------

(defn require-non-blank!
  "Return value when it is a non-blank string, otherwise fail with arg context.

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [arg value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value value})))
  value)

(defn parse-json-object-arg
  "Parse a CLI JSON-object argument into a keywordized map, failing loudly.

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [op raw]
  (let [value (json/read-str raw :key-fn keyword)]
    (when-not (map? value)
      (throw (ex-info (str op " JSON input must be an object")
                      {:input raw})))
    value))

;; The blessed arg-spec parser (skein.api.cli.alpha) binds positionals strictly
;; by order, so it cannot express the position-independent `step=<id>` selector
;; these ops accept, nor disambiguate the optional json-input/notes slots it can
;; sit among (docs/reference.md "Discovery tiers"). We therefore declare the fixed
;; positionals in each arg-spec — which drives generated `strand help <op>` — and
;; collect the optional tail into one variadic positional, then split `step=<id>`
;; out of that tail here. Fail-loud errors reference `strand help <op>` in their
;; data instead of a hand-written usage string.
(defn pop-step-selector
  "Split one optional `step=<id>` selector out of variadic tail tokens.

  `step=` is a whole token rather than a positional slot so it never collides
  with the other optional args (notes, JSON input) sharing the tail. Returns
  `[other-tokens step-id-or-nil]`, failing loudly on a duplicate selector or a
  blank id. Public: workflows.clj (loaded after this file) reuses it for the
  land op's tail convention."
  [op tail]
  (let [{steps true others false} (group-by #(str/starts-with? % "step=") tail)]
    (when (> (count steps) 1)
      (throw (ex-info (str op " accepts at most one step=<id> selector")
                      {:op op :help (str "strand help " op) :tail (vec tail)})))
    (when-let [step (first steps)]
      (require-non-blank! :step (subs step (count "step="))))
    [(vec others) (some-> (first steps) (subs (count "step=")))]))

;; ---------------------------------------------------------------------------
;; hitl: interactive human-in-the-loop working sessions
;; ---------------------------------------------------------------------------

(defn- hitl-prompt
  "Compose the session prompt: coordinator-supplied context plus the tracking
  contract that makes the session self-terminating (the session agent records
  notes and an outcome on the tracking strand, then closes it, which completes
  the run and tears down the multiplexer session)."
  [tracking-id context]
  (str "You are an INTERACTIVE HITL session: the user is attached to this"
       " terminal and you work through the task together, at their direction."
       " This is a working session, not a headless task — converse, propose,"
       " and act when they agree.\n\n"
       context
       "\n\n## Tracking contract (important)\n"
       "Your tracking strand is " tracking-id ". A coordinator agent reads it"
       " after this session to learn what happened — the user will not relay"
       " details.\n"
       "- Record each significant decision as a closed note child as you go:"
       " `strand add \"note: <decision>\" --state closed` then"
       " `strand update " tracking-id " --edge parent-of:<note-id>`.\n"
       "- When the user says you are done: write the outcome —"
       " `strand update " tracking-id " --attr outcome=\"<2-5 sentence summary:"
       " decisions, commits (shas), open questions>\"` — then close the strand:"
       " `strand update " tracking-id " --state closed`.\n"
       "- Closing " tracking-id " completes your run and tears down this"
       " session — make it your very last act, after the outcome attr is"
       " written and any final commit is made.\n"))

(defop hitl
  "Open an interactive HITL working session for a human + agent pair.

  Usage: `strand hitl <parent-id> <title> --context <text> [--cwd <dir>]
  [--harness <name>] [--backend <name>]`. Creates a tracking strand under
  `parent-id` (a kanban card, plan, or work root), composes the required
  `--context` brief with the tracking contract, and spawns an interactive
  multiplexer run serving the tracking strand (default harness `hitl-fable`,
  backend `tmux`). The session ends when the session agent closes the tracking
  strand after writing its outcome; the coordinator then reads the tracking
  strand for notes and outcome. Returns the tracking id and pending run
  summary — `strand agent ps` carries the session name and attach command once
  the session is live."
  {:returns stamped-op-return :arg-spec {:op "hitl"
                                         :hook-class :mutating
                                         :deadline-class :standard
                                         :doc "Open an interactive HITL session: tracking strand + multiplexer run."
                                         :positionals [{:name :parent-id
                                                        :type :string
                                                        :required? true
                                                        :doc "Strand to hang the tracking strand under (kanban card, plan, or work root)."}
                                                       {:name :title
                                                        :type :string
                                                        :required? true
                                                        :doc "Short session title."}]
                                         :flags {:context {:type :string
                                                           :doc "Required session brief: the situation, artifacts, findings, and what to work through together."}
                                                 :cwd {:type :string
                                                       :doc "Working directory for the session (defaults to the workspace root)."}
                                                 :harness {:type :string
                                                           :doc "Interactive-capable harness (prompt-via :arg TUI, e.g. hitl-fable — the default). Headless harnesses like build die in a pane."}
                                                 :backend {:type :string
                                                           :doc "Multiplexer backend (default tmux)."}}}}
  [ctx]
  (let [{:keys [parent-id title context cwd harness backend]} (:op/args ctx)
        rt (current/runtime)]
    (require-non-blank! :context context)
    (when-not (weaver/show rt parent-id)
      (throw (ex-info "hitl parent strand not found" {:parent parent-id})))
    (let [tracking (weaver/add! rt {:title (str "HITL: " title)
                                    :attributes {"hitl" "true"
                                                 "body" (str "Tracking strand for the interactive HITL session \"" title "\"."
                                                             " The session agent appends closed note children for decisions,"
                                                             " writes a final outcome attr, then closes this strand to end its"
                                                             " run and tear down the session.")}})]
      (weaver/update! rt parent-id {:edges [{:type "parent-of" :to (:id tracking)}]})
      (let [run (shuttle/spawn-run! {:harness (or harness "hitl-fable")
                                     :prompt (hitl-prompt (:id tracking) context)
                                     :title (str "HITL: " title)
                                     :parent (:id tracking)
                                     :cwd cwd
                                     :mode :interactive
                                     :backend (or backend "tmux")})]
        {:operation "hitl"
         :tracking (:id tracking)
         :parent parent-id
         :run (shuttle/run-summary run)
         :next "strand agent ps shows the attach command once the session is live; the session ends when the tracking strand closes."}))))
