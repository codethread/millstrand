(ns skein.spools.workflow.internal.runs
  "The generic worker's run lifecycle: role-aware ready resolution, the shared
  run-result envelope, and the concurrency guard every run mutation crosses
  (PROP-Wcd-001.S2/S4).

  A worker drives a run through one verb per role. `complete` acts on an
  ordinary step, `choose` on a checkpoint, `defer` on a defer, and `advance` on
  either an ordinary step or checkpoint — so each verb
  first narrows the run's ready frontier to the items *it* could act on and
  only then asks whether the answer is unambiguous. That order is what makes a
  mixed frontier workable: a run with one ready step and one ready checkpoint is
  unambiguous for both verbs, and neither has to name a step id to say what it
  meant.

  Gates sit deliberately outside inference. A gate is an external wait point, so
  a worker closing one asserts that something outside this run happened; that
  assertion must be deliberate and attributed, which is why closing a gate takes
  an explicit step selector and an actor and is never the item a bare `complete`
  picks.

  Every mutation resolves twice: once before taking the run's guard, so the
  caller gets a role-specific failure without waiting behind another worker, and
  once inside it. If the compatible frontier moved in between, another worker
  wrote to this run and the request describes a state that no longer exists; it
  fails as `workflow/frontier-stale` carrying the current frontier rather than
  applying to whatever happens to be ready now. That failure is retryable and
  says so, which is what lets a client tell a lost race apart from bad input."
  (:require [skein.api.format.alpha :as fmt]
            [skein.api.spool.alpha :refer [fail!]]
            [skein.spools.workflow.internal.query :as query]
            [skein.spools.workflow.internal.util :as util]))

(declare frontier root-view selectable)

(def ^:private roles
  "The worker roles, each mapping its verb's compatibility rules to the stable
  reasons that verb reports.

  `:selectable?` is what an explicit step selector may name and `:inferable?`
  what a bare verb may pick. They differ for exactly one item: a gate is a step
  a worker may close by naming it, never one the engine picks for them."
  (let [ordinary? (fn [item] (not (contains? #{"checkpoint" "defer"} (:role item))))
        role? (fn [role] (fn [item] (= role (:role item))))]
    {:advance {:noun "advanceable item"
               :selectable? (fn [item]
                              (contains? #{"step" "checkpoint"} (:role item)))
               :inferable? (fn [item]
                             (and (contains? #{"step" "checkpoint"} (:role item))
                                  (not (:gate item))))
               :absent :workflow/ready-advance-absent
               :ambiguous :workflow/ready-advance-ambiguous
               :incompatible :workflow/ready-advance-incompatible}
     :step {:noun "step"
            :selectable? ordinary?
            :inferable? (fn [item] (and (ordinary? item) (not (:gate item))))
            :absent :workflow/ready-step-absent
            :ambiguous :workflow/ready-step-ambiguous
            :incompatible :workflow/ready-step-incompatible}
     :checkpoint {:noun "checkpoint"
                  :selectable? (role? "checkpoint")
                  :inferable? (role? "checkpoint")
                  :absent :workflow/ready-checkpoint-absent
                  :ambiguous :workflow/ready-checkpoint-ambiguous
                  :incompatible :workflow/ready-checkpoint-incompatible}
     :defer {:noun "defer"
             :selectable? (role? "defer")
             :inferable? (role? "defer")
             :absent :workflow/ready-defer-absent
             :ambiguous :workflow/ready-defer-ambiguous
             :incompatible :workflow/ready-defer-incompatible}}))

(def ^:private stale-guidance
  (fmt/reflow
   "|Another worker wrote to this run. Choose from the current ready frontier or
    |re-run workflow ready, then retry."))

(def ^:private ambiguous-guidance
  (fmt/reflow
   "|Re-run with --step naming one of the compatible items."))

(def ^:private gate-actor-guidance
  (fmt/reflow
   "|Re-run with --by naming who closed the gate."))

(def ^:private defer-guidance
  (fmt/reflow
   "|This ready item selects another workflow and its params. Use workflow defer
    |instead."))

(defn result
  "Return the shared run result for `run-id`, stamped `operation`.

  The one shape every run verb answers with: what was invoked, which run, the
  run's current root identity, its complete ready frontier, and whether it is
  done. A worker therefore never has to follow a mutation with a read to learn
  what it may do next, and an empty `:ready` is never ambiguous — `:done` says
  whether the run finished or merely stalled.

  The engine names the operation rather than letting the CLI stamp it, unlike
  the discovery projections: `::run-result` requires it, and a result builder
  that validated a shape it had not finished building would be validating the
  wrong thing. The worker grammar is the engine's to publish either way."
  [rt operation run-id]
  (let [root (root-view rt run-id)
        ready (frontier rt run-id)
        done (query/done-with-rt? rt run-id)]
    (util/require-shape! :skein.spools.workflow/run-result
                         {:operation operation
                          :run-id run-id
                          :root root
                          :ready ready
                          :done done}
                         :workflow/run-result-invalid
                         "Workflow run result is invalid"
                         {:run-id run-id})))

(defn attention-result
  "Return `attention` as the await result for `run-id`, stamped `operation`.

  The blocking read keeps the attention vocabulary the engine already publishes
  — its reason, ready frontier, done state, and the detail behind the reason —
  and adds only the two envelope keys every other verb on this surface carries,
  so one client parser handles the whole grammar."
  [operation run-id attention]
  (util/require-shape! :skein.spools.workflow/attention-result
                       (merge {:operation operation :run-id run-id} attention)
                       :workflow/attention-result-invalid
                       "Workflow attention result is invalid"
                       {:run-id run-id}))

(defn require-gate-actor!
  "Return gate `item` once `by` attributes a deliberate close of it.

  A gate records that something outside this run happened, and `by` is the whole
  record of who decided so. The engine requires it too; refusing here keeps the
  failure in the same role vocabulary as the rest of the verb."
  [run-id item by]
  (when (and (:gate item) (not (util/non-blank-string? by)))
    (fail! "Closing a workflow gate requires an actor"
           {:reason :workflow/gate-actor-required
            :run-id run-id
            :step (:id item)
            :gate (:gate item)
            :guidance gate-actor-guidance}))
  item)

(defn require-fresh-frontier!
  "Fail loudly unless `role`'s compatible items are the same in `before` and `after`.

  The other half of the run guard, called once the guard is held: a difference
  means another worker wrote to this run between the caller's resolution and its
  turn, so the request describes a state that no longer exists. Nothing has been
  mutated at that point, and the failure says so — a stale frontier is worth
  retrying, unlike an ambiguous or wrong-role request."
  [operation role run-id step before after]
  (when (not= (mapv :id (selectable role before)) (mapv :id (selectable role after)))
    (fail! "Workflow frontier changed before the mutation applied"
           (cond-> {:reason :workflow/frontier-stale
                    :operation operation
                    :run-id run-id
                    :ready after
                    :guidance stale-guidance}
             step (assoc :step step))))
  after)

;; --- frontier resolution ------------------------------------------------------

(defn frontier
  "Return `run-id`'s complete ready frontier as step views."
  [rt run-id]
  (query/ready-with-rt rt run-id {}))

(defn- selectable
  "Return the items of ready frontier `ready` an explicit `role` selector may name."
  [role ready]
  (filterv (:selectable? (roles role)) ready))

(defn require-run!
  "Return `request` once its run id names a run this weaver has poured.

  A run that never existed is a different failure from a frontier with nothing
  this verb could act on, and a worker repairs them differently: one is a wrong
  run id, the other a run that has moved on. Asking first is what keeps them
  apart, since an unknown run has an empty frontier like any other."
  [rt request]
  (root-view rt (:run-id request))
  request)

(defn- root-view
  "Return the identity of `run-id`'s current root.

  The active root while the run is live, and the last root it poured once the
  run has finished — a finished run still has a root to name, and a result whose
  root vanished at the final close would make the shape conditional on timing."
  [rt run-id]
  (let [root (or (query/current-root-with-rt rt run-id)
                 (last (query/run-molecule-roots rt run-id)))]
    (when-not root
      (fail! "Unknown workflow run" {:reason :workflow/run-unknown :run-id run-id}))
    {:id (:id root) :title (:title root) :state (:state root)}))

(defn resolve-target!
  "Return the item of ready frontier `ready` that `role`'s verb acts on.

  With `step`, the named item must be ready and compatible with this verb; a
  wrong-role selector fails with the items that would have been. Without one,
  exactly one inferable item must be ready: none and several are different
  failures, because they need different repairs."
  [role run-id ready step]
  (let [{:keys [noun inferable?] :as spec} (roles role)]
    (if step
      (let [item (or (first (filter #(= step (:id %)) ready))
                     (fail! "Requested workflow step is not ready"
                            {:reason :workflow/step-not-ready
                             :run-id run-id :step step :ready ready}))]
        (when-not ((:selectable? spec) item)
          (fail! (str "Requested workflow step is not a " noun)
                 (cond-> {:reason (:incompatible spec)
                          :run-id run-id :step step :role (:role item)
                          :compatible (selectable role ready)}
                   (and (= role :advance) (= "defer" (:role item)))
                   (assoc :guidance defer-guidance))))
        item)
      (let [items (filterv inferable? ready)]
        (case (count items)
          1 (first items)
          0 (fail! (str "No ready workflow " noun)
                   (cond-> {:reason (:absent spec) :run-id run-id :ready ready}
                     (and (= role :advance)
                          (some #(= "defer" (:role %)) ready))
                     (assoc :guidance defer-guidance)))
          (fail! (str "More than one workflow " noun " is ready")
                 {:reason (:ambiguous spec)
                  :run-id run-id
                  :compatible items
                  :ready ready
                  :guidance ambiguous-guidance}))))))
