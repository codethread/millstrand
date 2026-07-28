# Lifecycle authoring forms replace module reconciliation callbacks

**Document ID:** `RFC-Laf-001` **Status:** Proposed **Date:** 2026-07-28 **Related:** [ADR-002](../adrs/0002-no-inline-module-lifecycle-macro.md) (rejected inline callback sugar), [ADR-003](../adrs/0003-spool-activation-lifecycle.md) (one activation path and the reconcile contract), [ADR-004](../adrs/0004-def-spool-convention.md) (`def spool` entry-point convention), [SPEC-003](../specs/repl-api.md) (extension API), [SPEC-004](../specs/daemon-runtime.md) (module refresh), [writing shared spools](../../docs/spools/writing-shared-spools.md)

## RFC-Laf-001.P1 Summary

Replace the public module `:reconcile` callback with declarative lifecycle authoring forms. The initial surface has forms for transition reactions, owned resources, and desired-state convergence. Skein collects these declarations while loading the module source, validates them before publication, and runs them through one shared lifecycle engine after contribution publication.

This is a breaking change. A public `(def spool {:reconcile 'reconcile})` is rejected after the cutover. There is no compatibility wrapper, legacy callback adapter, or deprecation period. Spool authors move each responsibility in a monolithic reconciler into the authoring form that states its lifecycle.

`def spool` also supports `:contribute`. Existing contribution authoring forms already cover much of that callback's role, but removing `:contribute` is outside this RFC. A separate RFC will decide that break. The intended end state is recorded here: extension namespaces contain authoring forms, not a `def spool` entry-point map, and every extension capability follows one visible, greppable authoring pattern.

“Pure authoring forms” means the forms are declarative and side-effect free while the module source is collected. Functions named by those declarations may perform effects when the lifecycle engine invokes them.

## RFC-Laf-001.P2 Motivation

The current reconciler is one function behind two pieces of bookkeeping:

```clojure
(defn reconcile
  [{:keys [runtime] :as ctx}]
  (case (get-in ctx [:module/contribution :status])
    :applied
    (do
      (register-handler! runtime)
      (start-worker-pool! runtime)
      (start-monitor! runtime)
      {:reconciled :applied})

    :removed
    (do
      (unregister-handler! runtime)
      (stop-monitor! runtime)
      (stop-worker-pool! runtime)
      {:reconciled :removed})

    (throw (ex-info "Unsupported module contribution status" ...))))

(def spool
  {:contribute 'contribute
   :reconcile 'reconcile})
```

The `spool` var does not explain the lifecycle. It points to another var which repeats the coordinator's status dispatch and hides every resource boundary inside one body. A reader must jump through the declaration, inspect both branches, pair each setup with its teardown, and infer ordering and failure behavior.

The callback boundary also makes partial progress opaque. If handler registration and worker startup succeed but monitor startup throws, Skein records one degraded reconcile result. It cannot report which effects are live, preserve successful unchanged effects by policy, or retry from the failed effect. Each spool must invent that bookkeeping in runtime state and make every preceding action safe to repeat.

The design was reasonable while lifecycle behavior was small and uncommon: one callback left effect policy to the owning domain. The in-tree reconciler census now shows repeated shapes:

- singleton setup and teardown, such as event handlers, hooks, notifiers, and worker pools;
- process-lifetime seeds with an applied action and an explicit no-op removal;
- desired-state convergence, where an effective registry is compared with runtime-owned live state;
- repeated extraction and validation of `[:module/contribution :status]`;
- multiple effects sequenced inside one callback, with partial failure visible only as one module-level error.

These are stable enough to name. Keeping them inside an unrestricted callback now costs more than the smaller public surface saves.

## RFC-Laf-001.P3 Discussion that led here

The design started with a narrower question: does every reconciler need a `case` over `:applied` and `:removed`? The answer exposed three separate lifecycle shapes.

First, some resources genuinely have different setup and teardown operations. Chime registers its engine handler and mutation barrier on application and unregisters both on removal. This resembles a scoped effect whose cleanup belongs beside its setup.

Second, some domains already reconcile effective desired state. Cron reads the newly published effective job registry, compares it with runtime-owned scheduled jobs, starts or replaces changed jobs, and cancels absent jobs. The same convergence body works after application and removal because publication happens before lifecycle reconciliation.

Third, some actions leave no owned resource. Seeding process-lifetime vocabulary or emitting an activation notice may have no removal operation. These are transition reactions, not resources pretending to have empty cleanup.

The comparison with React is useful but not exact. A React effect groups setup with cleanup, while React's renderer also reconciles a desired tree with a host tree. Skein needs both ideas:

- a resource declaration keeps acquisition and release in one authoring block;
- a convergence declaration makes actual live state approach the effective registry;
- a reaction declaration handles a transition that leaves no resource handle.

Once these boundaries are declared separately, Skein can see partial progress. That creates framework policy questions which the callback previously left implicit: ordering, retry, cleanup after partial application, replacement, and reporting. Those questions already exist. The monolithic callback answers them independently and often accidentally in each spool. This RFC moves the common answers into one engine and leaves domain logic in named functions.

## RFC-Laf-001.P4 Goals

- **RFC-Laf-001.G1:** Remove public `:reconcile` callbacks and the `def spool` bookkeeping required only to point at them.
- **RFC-Laf-001.G2:** Express the current reconcile behaviors through declarative, source-visible authoring forms.
- **RFC-Laf-001.G3:** Keep setup and teardown for an owned resource in one block.
- **RFC-Laf-001.G4:** Make effect identity, ordering, partial progress, retained handles, retry, and teardown outcomes visible to `plan`, `status`, refresh results, and tests.
- **RFC-Laf-001.G5:** Preserve printable declaration data. Lifecycle callables remain fully qualified symbols resolved through the spool-aware classloader; declarations never hold closures.
- **RFC-Laf-001.G6:** Preserve publication order: validate and atomically publish contributions before applying lifecycle effects.
- **RFC-Laf-001.G7:** Fail loudly at the declaration or named effect boundary with the module key, effect id, effect kind, callable, and phase.
- **RFC-Laf-001.G8:** Establish authoring forms as the one extension-authoring pattern and leave room for new lifecycle forms when repeated behavior earns one.
- **RFC-Laf-001.G9:** Make the migration a clean break under TEN-000@1. Removed syntax has no alias or compatibility path.

## RFC-Laf-001.P5 Non-goals

- **RFC-Laf-001.NG1:** This RFC does not remove `def spool :contribute`. A follow-up RFC will decide how every remaining explicit contribution maps to authoring forms and whether new contribution forms are needed.
- **RFC-Laf-001.NG2:** No general workflow or arbitrary effect DSL. The initial forms cover behavior already present in reconciler implementations.
- **RFC-Laf-001.NG3:** No automatic rollback. External effects may be irreversible, and a rollback claim would be false for notifications, subprocess actions, or remote registrations.
- **RFC-Laf-001.NG4:** No durable replay or exactly-once guarantee. Lifecycle state supports resumption within the running weaver, in line with PHILOSOPHY's resumability rule.
- **RFC-Laf-001.NG5:** No closure-valued callables or runtime `eval`. ADR-002's classloader and provenance constraints still apply.
- **RFC-Laf-001.NG6:** No attempt to turn every registered entry into a resource. Owner-partitioned contribution data remains distinct from live handles.
- **RFC-Laf-001.NG7:** No cross-module effect dependency graph in the first version. Existing module `:after` edges order modules; effect dependencies order effects within one module.

## RFC-Laf-001.P6 Proposed authoring surface

The examples use provisional names under `skein.api.lifecycle.alpha`. Final naming belongs to the feature proposal, but the three semantic categories are part of this RFC.

### RFC-Laf-001.P6.1 Transition reactions

`defreaction` declares an action tied to an applied or removed transition. It retains no resource handle.

```clojure
(ns acme.service
  (:require [skein.api.lifecycle.alpha :refer [defreaction]]))

(defreaction activation-notice
  "Tell the local operator that this module became active."
  {:on-applied 'acme.service/emit-activation-notice!})
```

A reaction may declare both transitions:

```clojure
(defreaction lifecycle-audit
  "Record module activation and removal in the external audit sink."
  {:on-applied 'acme.service/audit-activation!
   :on-removed 'acme.service/audit-removal!})
```

The functions receive a common lifecycle context:

```clojure
(defn emit-activation-notice!
  "Emit this module's activation notice."
  [{:keys [runtime module/key effect/id]}]
  (notify! runtime {:module key :effect id})
  {:emitted true})
```

An absent transition is an explicit no-op in the declaration grammar, not a missing callback error. Reactions run again after a changed contribution or a remove/reapply cycle. Authors who require repeat suppression must model the idempotency key in their domain or runtime-owned spool state. The form does not promise exactly once.

### RFC-Laf-001.P6.2 Owned resources

`defresource` declares paired acquisition and release:

```clojure
(ns acme.service
  (:require [skein.api.lifecycle.alpha :refer [defresource]]))

(defresource monitor
  "Run the service monitor while this module is active."
  {:open 'acme.service/start-monitor!
   :close 'acme.service/stop-monitor!})
```

The open function receives the lifecycle context and may return an arbitrary live handle:

```clojure
(defn start-monitor!
  "Start the service monitor and return its closeable handle."
  [{:keys [runtime]}]
  (monitor/start! runtime))
```

Skein retains the handle in runtime-owned lifecycle state under `[module-key effect-id]`. The close function receives it:

```clojure
(defn stop-monitor!
  "Stop the retained monitor."
  [{:keys [resource]}]
  (.close resource)
  {:closed true})
```

The handle is never contribution data and need not be data-first. Status, plan, and refresh results expose only a data-first resource descriptor and the data-first result returned by each lifecycle function. This preserves the current separation between printable declarations and live resources.

Several resources state their order explicitly:

```clojure
(defresource worker-pool
  "Run workers while this module is active."
  {:open 'acme.service/start-workers!
   :close 'acme.service/stop-workers!})

(defresource event-handler
  "Route graph events into the running worker pool."
  {:after #{:worker-pool}
   :open 'acme.service/register-handler!
   :close 'acme.service/unregister-handler!})
```

Application follows dependency order. Removal follows reverse dependency order, so the event source stops before its consumer.

### RFC-Laf-001.P6.3 Desired-state convergence

`defconvergence` declares how to read desired and actual state and make actual state converge:

```clojure
(ns acme.cron
  (:require [skein.api.lifecycle.alpha :refer [defconvergence]]))

(defconvergence scheduled-jobs
  "Make durable scheduler wakes match the effective Cron job registry."
  {:desired 'acme.cron/effective-jobs
   :actual 'acme.cron/running-jobs
   :converge 'acme.cron/converge-jobs!})
```

The lifecycle engine calls `:desired` and `:actual` after publication, then supplies both values to `:converge`:

```clojure
(defn converge-jobs!
  "Make running jobs match the effective declarations."
  [{:keys [runtime desired actual]}]
  (let [removed (remove (set (keys desired)) (keys actual))
        changed (for [[id job] desired
                      :when (not= job (get actual id))]
                  [id job])]
    (doseq [id removed]
      (unregister! runtime id))
    (doseq [[id job] changed]
      (register! runtime (assoc job :id id)))
    {:jobs (vec (sort (keys desired)))}))
```

The engine invokes the same convergence after applied and removed transitions. It does not impose a generic diff because domains differ on identity, replacement, ordering, and partial failure. A later keyed-collection form may earn its place if several domains repeat the same diff contract:

```clojure
(defcollection-resource scheduled-jobs
  {:desired 'acme.cron/effective-jobs
   :open 'acme.cron/start-job!
   :close 'acme.cron/stop-job!
   :fingerprint 'acme.cron/job-fingerprint})
```

That possible form is illustrative and not part of the first release.

## RFC-Laf-001.P7 Collected declaration shape

Lifecycle forms collect printable data under the owning module. A normalized module fragment is conceptually:

```clojure
{:lifecycle
 {:entries
  {:activation-notice
   {:kind :reaction
    :on-applied 'acme.service/emit-activation-notice!}

   :monitor
   {:kind :resource
    :open 'acme.service/start-monitor!
    :close 'acme.service/stop-monitor!}

   :scheduled-jobs
   {:kind :convergence
    :desired 'acme.cron/effective-jobs
    :actual 'acme.cron/running-jobs
    :converge 'acme.cron/converge-jobs!}}}}
```

This is lifecycle declaration data, not an ordinary shadowable registry kind. An effect belongs to exactly one module owner and disappears with that module. It has no defaults/workspace/direct precedence layers and cannot override another module's effect. Module ownership plus the effect id is its identity.

Each authoring form expands to collection through the same target-only module collector used by `defop`, `defquery`, and other forms. Evaluation outside the selected module source fails loudly. Duplicate effect ids in one module fail during collection. Unknown keys, cycles in `:after`, missing dependencies, non-symbol callables, unresolved vars, and vars whose root values are not functions fail before contribution publication.

## RFC-Laf-001.P8 Lifecycle engine

The coordinator's high-level order becomes:

```text
load module sources and collect contribution + lifecycle declarations
    ↓
validate contributions, lifecycle declarations, symbols, and effect DAGs
    ↓
stage and validate every affected owner contribution
    ↓
publish every contribution kind atomically
    ↓
diff previous and next lifecycle declarations
    ↓
apply, preserve, replace, converge, or remove effects
    ↓
record per-effect and aggregate module outcomes
```

The lifecycle engine is internal coordinator machinery. Removing the public reconciler does not remove reconciliation as a runtime phase.

### RFC-Laf-001.P8.1 Declaration transitions

For each `[module-key effect-id]`, the engine classifies:

| Previous | Next | Action |
| --- | --- | --- |
| absent | present | Apply the new effect. |
| identical | identical | Preserve its successful state; do not rerun it. |
| present | changed | Replace it according to its effect kind. |
| present | absent | Remove or clean up the retained old effect. |
| failed/degraded | identical | Retry the incomplete phase without rerunning completed unchanged dependencies. |

A reaction declaration change runs the next declaration's applied action after the prior declaration's optional removed action. A resource change closes the retained old handle before opening the new declaration. A convergence change invokes the new convergence against the post-publication desired state. Failure behavior follows P9.

Removal-by-omission never reloads a removed module's source. The coordinator therefore retains each applied effect's normalized declaration and resolved callable set, just as it currently retains the last-good reconciler. It also retains resource handles until close succeeds.

### RFC-Laf-001.P8.2 Context

Every lifecycle callable receives a common base context:

```clojure
{:runtime runtime
 :module/key module-key
 :module/declaration module-declaration
 :module/previous previous-module
 :effect/id effect-id
 :effect/kind effect-kind
 :effect/declaration declaration
 :effect/phase phase
 :refresh/result provisional-result}
```

Resource close adds `:resource`. Convergence adds `:desired` and `:actual`. Reaction functions receive no invented resource value.

The old `[:module/contribution :status]` dispatch is not part of the author-facing context. The engine already knows the precise effect transition and calls only the function for that phase.

## RFC-Laf-001.P9 Failure, retry, and teardown policy

The initial policy is deliberately small:

1. Apply effects in dependency order.
2. Stop applying a module's remaining effects after the first failure. Effects from later modules follow the existing module dependency and degraded-outcome rules.
3. Retain every successfully opened resource, completed reaction result, convergence result, and declaration.
4. On retry, preserve successful unchanged effects and resume from the failed effect.
5. Remove effects in reverse dependency order.
6. Attempt every independent removal even if one removal fails. A dependent is always attempted before its dependency.
7. Retain a resource handle and old close callable when close fails so teardown can be retried.
8. Report every effect outcome. A module is degraded while any effect is failed or has failed cleanup.
9. Never roll back a completed reaction or convergence action automatically.

This policy makes partial progress honest:

```text
worker-pool       applied
event-handler     applied
monitor           failed
initial-scan      not attempted
```

The next refresh preserves the worker pool and handler, retries the monitor, and reaches the initial scan only after its dependencies succeed. If the module is removed first, the engine closes the handler and pool using their retained declarations and handles, and attempts cleanup for any partially acquired monitor state that the failed open explicitly returned or recorded.

An open function that throws has not returned a handle. If acquisition can partially succeed, the domain must either make open transactional, record the partial handle in spool state before the risky step, or throw an `ex-info` carrying a sanctioned cleanup descriptor if the final API adopts one. The first release should not infer a handle from arbitrary exception data. This remains an open design point in P15.

## RFC-Laf-001.P10 Results and inspection

`plan` shows the lifecycle declaration diff and intended phases without resolving actual state functions or performing effects:

```clojure
{:module/key :acme/service
 :lifecycle
 {:apply [:worker-pool :event-handler :monitor]
  :preserve []
  :replace []
  :remove []}}
```

`status` joins retained runtime state:

```clojure
{:module/key :acme/service
 :lifecycle
 {:worker-pool {:kind :resource :status :applied}
  :event-handler {:kind :resource :status :applied}
  :monitor {:kind :resource
            :status :degraded
            :phase :open
            :error {...}}}}
```

Refresh results include the same per-effect outcomes and retain the existing aggregate module status. Resource handles never appear in these data-first projections.

## RFC-Laf-001.P11 Worked migrations

### RFC-Laf-001.P11.1 Chime

Chime's handler and mutation barrier are independent singleton resources, with the handler ordered after the barrier if that reflects the required observation boundary:

```clojure
(defresource registration-barrier
  "Reject mutations while Chime's effective rule view is transitioning."
  {:open 'skein.spools.chime/register-registration-barrier!
   :close 'skein.spools.chime/unregister-registration-barrier!})

(defresource engine
  "Dispatch graph events through Chime's effective rule view."
  {:after #{:registration-barrier}
   :open 'skein.spools.chime/register-engine!
   :close 'skein.spools.chime/unregister-engine!})

(defconvergence visible-rules
  "Make Chime's visible rule view match the effective rule registry."
  {:after #{:registration-barrier}
   :desired 'skein.spools.chime/effective-rules
   :actual 'skein.spools.chime/visible-rules
   :converge 'skein.spools.chime/converge-rule-view!})
```

The current one-lock atomicity across handler registration and visible-view publication must remain expressible. The likely answer is one resource or convergence function owning that atomic cluster, not splitting it merely because forms exist. Effect decomposition follows real failure and ownership boundaries; it is not a goal by itself.

### RFC-Laf-001.P11.2 Cron

Cron becomes one convergence declaration. Its current diff body moves unchanged into the named convergence function:

```clojure
(defconvergence scheduled-jobs
  "Make durable scheduler wakes match effective Cron job declarations."
  {:desired 'skein.spools.cron/effective-jobs
   :actual 'skein.spools.cron/running-jobs
   :converge 'skein.spools.cron/converge-jobs!})
```

There is no status switch. Removal changes the effective registry before this function runs, so absent jobs are cancelled by the ordinary diff.

### RFC-Laf-001.P11.3 Process-lifetime seeds

Batteries glossary outcomes and workflow vocabulary currently use an applied action with an explicit no-op removal because their domains expose no retraction API:

```clojure
(defreaction glossary-outcomes
  "Seed Batteries' process-lifetime glossary outcomes."
  {:on-applied 'skein.spools.batteries/seed-glossary-outcomes!})
```

The absence of `:on-removed` records the no-op directly. No hand-written branch can accidentally re-register on removal.

### RFC-Laf-001.P11.4 Workspace singleton bindings

The current workspace tracker, help-transform, harness defaults, and local notifier reconcilers apply setters unconditionally. Under the lifecycle contract, removal should undo the binding. Their migration exposes whether each domain has a real unset/reset operation:

```clojure
(defresource tracker-binding
  "Bind devflow as the kanban tracker while this module is active."
  {:open 'kanban-tracker/bind-devflow!
   :close 'kanban-tracker/unbind-devflow!})
```

If a singleton API has no removal operation, the feature must add one or deliberately classify the binding as a process-lifetime reaction. The migration may not silently preserve today's unconditional-removal defect.

## RFC-Laf-001.P12 Breaking migration

This change intentionally withdraws the accepted `:reconcile` key from `skein.api.spool.alpha/::spool`.

The cutover sequence is:

1. Implement lifecycle collection, validation, retained state, execution, inspection, and tests.
2. Convert every in-tree and `.skein` reconciler to lifecycle forms.
3. Coordinate sibling spool releases and pin bumps so the selected source universe contains no `:reconcile` declarations.
4. Remove `:reconcile` from the `::spool` grammar and convention resolver.
5. Delete reconciler retention and dispatch once lifecycle retention covers removal-by-omission.
6. Update root specs, ADR lineage, spool authoring docs, testing helpers, generated API docs, and quality checks.
7. Reject a `def spool` containing `:reconcile` with an actionable error naming the replacement lifecycle forms.

There is no dual-running phase in a released source universe. During development, a private migration branch may temporarily understand both shapes to convert and test the tree, but the landed contract accepts only lifecycle forms. A module may not declare both a reconciler and lifecycle forms.

This withdrawal breaks the `skein.api.spool.alpha` accretion promise in SPEC-003.C19. As with the prior `def spool` cutover, the feature must record the exception explicitly rather than hide it behind a parallel alias namespace. TEN-000@1 permits the clean break, and the user's direction requires it.

## RFC-Laf-001.P13 The `:contribute` follow-up

After this RFC, a namespace may temporarily still contain:

```clojure
(def spool
  {:contribute 'contribute})
```

That is not the desired final surface. Ops, queries, patterns, workflows, rules, jobs, and other registered kinds already have or can gain authoring forms. A separate RFC will:

- inventory every remaining explicit `:contribute` function;
- decide whether existing authoring forms cover each owner-complete partition;
- add forms only where a repeated contribution shape earns one;
- resolve multi-namespace and programmatically generated contribution cases;
- remove `:contribute` and then remove the public `def spool` convention itself.

Keeping that decision separate prevents this RFC from mixing live-resource lifecycle policy with contribution authoring and owner-partition semantics. The two changes share an end state:

```clojure
(ns acme.extension
  (:require [skein.api.ops.alpha :refer [defop]]
            [skein.api.lifecycle.alpha :refer [defresource]]))

(defop ...)
(defresource ...)
```

There is no entry-point manifest. Source-visible authoring forms are the extension.

## RFC-Laf-001.P14 Alternatives

### RFC-Laf-001.O1 Keep the monolithic reconciler

This preserves the smallest core and maximal domain freedom. It also preserves repeated status dispatch, opaque partial progress, bespoke retry, and setup/teardown drift. The current census provides enough repeated behavior to justify a shared surface.

### RFC-Laf-001.O2 Add only a status-validation helper

A helper such as `(runtime/reconcile-status! ctx 'ns/reconcile)` removes error-map boilerplate but leaves resource boundaries and failure policy opaque. It treats the symptom rather than the callback's accumulated responsibilities.

### RFC-Laf-001.O3 Keep `:reconcile` as an escape hatch

This weakens the migration and leaves two lifecycle models indefinitely. New spools would choose inconsistently, quality checks could not require the declarative shape, and the generic engine could not promise complete per-effect visibility. Rejected: the break is the mechanism that establishes one pattern.

### RFC-Laf-001.O4 One generic `defeffect`

```clojure
(defeffect monitor
  {:kind :resource
   :open 'acme/start!
   :close 'acme/stop!})
```

One form is smaller but makes unlike lifecycle contracts branches of one open map grammar. Distinct forms give grep-visible intent, narrower specs, better errors, and room for kind-specific documentation. A shared internal normal form can still back them.

### RFC-Laf-001.O5 Automatic transaction and rollback

Rollback cannot honestly cover external actions. The engine instead records partial progress, preserves handles, retries incomplete work, and attempts teardown. Domains may implement transactional acquisition where their boundary supports it.

## RFC-Laf-001.P15 Open questions

- **RFC-Laf-001.Q1:** Are `defreaction`, `defresource`, and `defconvergence` the right public names? The semantic split is proposed; naming should be tested against real migrations.
- **RFC-Laf-001.Q2:** Should reactions exist in v1, or should process-lifetime seeds use a narrower `defseed` form that communicates their deliberate lack of teardown?
- **RFC-Laf-001.Q3:** What exact declaration change forces resource replacement? Byte-identical normalized data is the baseline; doc-only or provenance-only changes may not warrant close/open.
- **RFC-Laf-001.Q4:** Should a failed resource open be allowed to return a cleanup handle through a sanctioned exception shape, or must partial acquisition always live in domain spool state?
- **RFC-Laf-001.Q5:** After one effect fails, should independent siblings continue applying? This RFC recommends stop-on-first-failure for application and attempt-all for removal; migration evidence may justify independent-subgraph continuation.
- **RFC-Laf-001.Q6:** Does `plan` resolve callable symbols, as current evaluation does, or show declaration diffs from collected data and leave resolution to refresh? It must remain side-effect free either way.
- **RFC-Laf-001.Q7:** How should code-only reload interact with retained open/close vars? The current reconciler is retained by symbol and re-resolved through the spool loader; lifecycle callables need an equally explicit generation and reload contract.
- **RFC-Laf-001.Q8:** Should convergence functions receive raw desired/actual values, or should the engine standardize a richer diff result? The first release should prefer raw values unless two migrations demonstrate a shared diff.
- **RFC-Laf-001.Q9:** Can a resource close depend on the old module contribution after publication removed it? The retained effect context may need the previous owner partition, not only `:module/previous`.

## RFC-Laf-001.P16 Consequences

Spool source becomes easier to scan. `defresource monitor` is both the searchable identity and the complete lifecycle declaration. Setup and teardown cannot be separated into distant status branches. A no-op removal is visible as an absent transition rather than hidden in a callback.

The coordinator becomes more complex. It owns an effect DAG, retained handles, per-effect state, transition classification, retry, and removal. That complexity is not new behavior; it is the common part of behavior currently repeated or omitted in spool callbacks. Centralizing it makes failures inspectable and policies consistent.

Tests can activate modules through the production path and assert effect-level outcomes. Resource fixtures no longer need to call a public reconciler directly with fabricated contribution statuses. Test helpers can inspect lifecycle state without exposing handles.

New lifecycle forms have a high bar. They are added only when several modules repeat a declaration and execution contract that the existing forms cannot express cleanly. The point is one authoring pattern, not a growing catalogue of synonyms.

The separate contribution RFC remains required to complete the end state. Until then, `def spool` survives only for explicit `:contribute` users.

## RFC-Laf-001.P17 Acceptance criteria

- Every current in-tree, workspace, and pinned sibling reconciler has a lossless mapping to a proposed lifecycle form or identifies a concrete missing primitive.
- Applied, unchanged, changed, removed, failed-open, failed-close, retry, dependency order, reverse teardown, and removal-by-omission behavior have coordinator-level tests.
- Refresh and status report effect-level outcomes without exposing live handles.
- A resource successfully opened before a sibling failure is preserved on retry and closed on later module removal.
- A failed close retains enough state to retry cleanup.
- Contribution publication remains atomic and precedes lifecycle execution.
- `plan` performs no lifecycle effect.
- The landed grammar rejects `def spool :reconcile` and names the replacement forms.
- All in-tree and selected sibling sources have migrated before the breaking grammar lands.
- Root specs and authoring docs state that lifecycle behavior is authored through forms.
- A follow-up RFC for removing `def spool :contribute` is filed and linked, without making its undecided mechanics part of this change.

## RFC-Laf-001.P18 Recommendation

Proceed with the breaking replacement of public module reconcilers by lifecycle authoring forms. Prototype the engine against Cron, Chime, one process-lifetime seed, and one workspace singleton before fixing the final names. Those four migrations cover convergence, paired resources, one-way reactions, ordering, retained handles, and the current removal defect.

Do not retain `:reconcile` as an escape hatch. Do not remove `:contribute` in this feature. Record and pursue the separate contribution-authoring RFC so the final extension surface is made entirely of authoring forms and `def spool` disappears.
