# Lifecycle authoring forms replace module reconciliation callbacks

**Document ID:** `RFC-Laf-001` **Status:** Proposed **Date:** 2026-07-28 **Related:** [ADR-002](../adrs/0002-no-inline-module-lifecycle-macro.md) (rejected inline callback sugar), [ADR-003](../adrs/0003-spool-activation-lifecycle.md) (one activation path and the reconcile contract), [ADR-004](../adrs/0004-def-spool-convention.md) (`def spool` entry-point convention), [SPEC-003](../specs/repl-api.md) (extension API), [SPEC-004](../specs/daemon-runtime.md) (module refresh), [writing shared spools](../../docs/spools/writing-shared-spools.md)

## RFC-Laf-001.P1 Summary

Explore replacing the public module `:reconcile` callback with declarative lifecycle authoring forms. Candidate forms cover transition reactions, owned resources, and desired-state reconciliation. Skein could collect these declarations while loading the module source, validate them before publication, and run them through one shared lifecycle engine after contribution publication.

If the feasibility work passes the gates in P15 and P17, the resulting feature is a breaking change. A public `(def spool {:reconcile 'reconcile})` would be rejected after the cutover, with no compatibility wrapper, legacy callback adapter, or deprecation period. This RFC does not yet accept the candidate forms or authorize that cutover.

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

- **RFC-Laf-001.G1:** Determine whether public `:reconcile` callbacks and the `def spool` bookkeeping required only to point at them can be replaced without losing current behavior.
- **RFC-Laf-001.G2:** Express the current reconcile behaviors through declarative, source-visible authoring forms.
- **RFC-Laf-001.G3:** Keep setup and teardown for an owned resource in one block.
- **RFC-Laf-001.G4:** Test whether effect identity, ordering, retained handles, whole-boundary retry, and teardown outcomes can become visible to `plan`, `status`, refresh results, and tests.
- **RFC-Laf-001.G5:** Preserve printable declaration data. Lifecycle callables remain fully qualified symbols resolved through the spool-aware classloader; declarations never hold closures.
- **RFC-Laf-001.G6:** Preserve publication order: validate and atomically publish contributions before applying lifecycle effects.
- **RFC-Laf-001.G7:** Fail loudly at the declaration or named effect boundary with the module key, effect id, effect kind, callable, and phase.
- **RFC-Laf-001.G8:** Establish authoring forms as the one extension-authoring pattern and leave room for new lifecycle forms when repeated behavior earns one.
- **RFC-Laf-001.G9:** Make the migration a clean break under TEN-000@1. Removed syntax has no alias or compatibility path.

## RFC-Laf-001.P5 Non-goals

- **RFC-Laf-001.NG1:** This RFC does not remove `def spool :contribute`. A follow-up RFC will decide how every remaining explicit contribution maps to authoring forms and whether new contribution forms are needed.
- **RFC-Laf-001.NG2:** No general workflow or arbitrary effect DSL. Candidate forms are limited to behavior found in current reconciler implementations.
- **RFC-Laf-001.NG3:** No automatic rollback. External effects may be irreversible, and a rollback claim would be false for notifications, subprocess actions, or remote registrations.
- **RFC-Laf-001.NG4:** No durable replay or exactly-once guarantee. Lifecycle state supports resumption within the running weaver, in line with PHILOSOPHY's resumability rule.
- **RFC-Laf-001.NG5:** No closure-valued callables or runtime `eval`. ADR-002's classloader and provenance constraints still apply.
- **RFC-Laf-001.NG6:** No attempt to turn every registered entry into a resource. Owner-partitioned contribution data remains distinct from live handles.
- **RFC-Laf-001.NG7:** No cross-module effect dependency graph in the first version. Existing module `:after` edges order modules; effect dependencies order effects within one module.

## RFC-Laf-001.P6 Candidate authoring surface

The examples use provisional names under `skein.api.lifecycle.alpha`. Neither that namespace nor the three-form split is decided here. The forms make the discussion concrete enough to test against current reconcilers; P14 and P15 retain smaller surfaces and existing reconcile vocabulary as live alternatives.

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

An absent transition is an explicit no-op in the declaration grammar, not a missing callback error. A healthy reaction with an identical lifecycle declaration is preserved when unrelated contribution data changes; it runs again after its own declaration changes or a remove/reapply cycle. A degraded reaction may be retried under P8.1. Authors who require repeat suppression across those transitions must model the idempotency key in their domain or runtime-owned spool state. The form does not promise exactly once.

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

The provisional `defconvergence` name declares how to read desired and actual state and make actual state converge:

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

For removal to be executable, an adopted desired-state form must retain either a final desired-state reader plus its reconcile callable or an explicit removal callable after the declaration disappears. The example does not choose between them. Merely omitting the declaration must never strand the external state it previously managed.

For ordinary desired-state changes, the engine could invoke the same callable after applied and removed contribution transitions. It should not impose a generic diff unless domains prove shared identity, replacement, ordering, and failure semantics. A later keyed-collection form may earn its place if several domains repeat the same diff contract:

```clojure
(defcollection-resource scheduled-jobs
  {:desired 'acme.cron/effective-jobs
   :open 'acme.cron/start-job!
   :close 'acme.cron/stop-job!
   :fingerprint 'acme.cron/job-fingerprint})
```

That possible form is illustrative and not part of the first release. The name `convergence` may itself be wrong: Skein's published word for this phase is **reconcile**, and a prototype must show whether desired-state convergence is a distinct authoring primitive or one constrained form of reconciliation before introducing new vocabulary.

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

Any shipped surface needs `clojure.spec` contracts for its declaration maps, callable contexts and results, normalized lifecycle data, plan/status/refresh projections, and closed status and phase values. Cross-entry uniqueness, dependency cycles, and other relationships that a local data spec cannot express remain explicit validators. Every failure projection must carry a common diagnostic envelope naming the module, effect, kind, callable, phase, offending value or input, and allowed alternatives when the boundary has a closed set. The examples below are sketches of those shapes, not their specifications.

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
| identical and healthy | identical | Preserve its successful state; do not rerun it. |
| present | changed | Replace it according to its effect kind. |
| present | absent | Remove or clean up the retained old effect. |
| identical but degraded | identical | Retry the effect from its declared boundary only if that boundary's contract makes whole-call retry safe. The engine cannot resume inside an opaque function. This deliberately differs from today's contribution-unchanged fast path and would require explicit amendments to SPEC-004.C46/C46b and ADR-003.P2's retained DELTA-OlrDrt-001.D4 constraint. |

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

## RFC-Laf-001.P9 Candidate failure, retry, and teardown policy

The following is a policy to prototype, not an accepted contract:

1. Apply effects in dependency order.
2. Stop applying a module's remaining effects after the first failure. Effects from later modules follow the existing module dependency and degraded-outcome rules.
3. Retain every successfully opened resource, completed reaction result, convergence result, and declaration.
4. On retry, preserve successful unchanged effects and call the failed effect again from its public boundary. There is no implied checkpoint or mid-function resume.
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

The next refresh could preserve the worker pool and handler, call the monitor's open boundary again, and reach the initial scan only after its dependencies succeed. That requires open to be retry-safe; the engine cannot infer progress inside it. If the module is removed first, the engine closes resources for which it retained declarations and handles.

An open function that throws has not returned a handle. Allowing such a function to leave external state behind would make cleanup impossible for the engine and is not an acceptable silent convention. A viable resource form must either require transactional acquisition or define a mandatory, validated partial-cleanup protocol whose retained state appears in status. The first release must not infer a handle from arbitrary exception data. A prototype must choose and prove one of those boundaries before `defresource` is accepted.

## RFC-Laf-001.P10 Results and inspection

The following projections are illustrative. The owning API, qualified keys, closed status vocabulary, and specs remain to be decided. `plan` could show the lifecycle declaration diff and intended phases without resolving actual state functions or performing effects:

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

Refresh results would include the same per-effect outcomes and retain the existing aggregate module status. Resource handles never appear in these data-first projections. Whatever namespace owns the lifecycle declarations must also own these projected keys, register their attribute vocabulary where applicable, and generate API documentation from the same public contract.

## RFC-Laf-001.P11 Worked migrations

### RFC-Laf-001.P11.1 Chime

Chime is a warning against splitting effects at the wrong boundary. Its current reconciler holds one monitor while it registers or unregisters the hook and handler and replaces the visible rule view. Three independently invoked effects could expose an intermediate state, so a lossless first migration keeps that atomic cluster together:

```clojure
(defresource engine-and-rule-view
  "Keep Chime's handler, mutation barrier, and visible rules atomic."
  {:open 'skein.spools.chime/open-engine-and-rule-view!
   :close 'skein.spools.chime/close-engine-and-rule-view!})
```

This preserves behavior but gains no effect-level visibility inside the cluster. That is an honest limit of the proposal: authoring forms improve boundaries that already exist; they do not manufacture safe boundaries inside one lock. A prototype must prove that the resource form can carry this transition without changing Chime's concurrency behavior.

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

The trigger is not solved by this sketch. Cron's own lifecycle declaration can stay byte-identical while another module changes the effective Cron job kind. The lifecycle engine would need a declared dependency such as `:when-kinds #{:skein.spools.cron/jobs}`, or another validated way to rerun convergence when its desired input changes. Calling every convergence after every publication is a possible baseline but may be too broad. A prototype must establish a trigger contract before this form is considered feasible.

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

That is not the desired final surface. The current authoring forms are not yet one shipped `skein.api.*` surface: `defop`, `defquery`, `defpattern`, and `defrule` live in this repo's `.skein/spools/macros`; `defjob` and `defworkflow` correctly live in their shipped Cron and Workflow spool namespaces. A separate RFC must verify how to make authoring forms complete before proposing the `:contribute` break.

### RFC-Laf-001.P13.1 Unverified migration sketch

The following is a discussion sketch, not a design decision or a claim that the present APIs cover every contribution. Each step needs source archaeology, prototypes against real spools, and its own RFC:

1. Complete the generic declarative kind-and-entry mechanism over the primitives already present in `skein.api.registry.alpha` and `skein.api.runtime.alpha`. The APIs already declare owner-partitioned kinds and collect entries, but provider modules such as Cron and Workflow currently use `:contribute` to materialize their runtime-owned kind registries before dependent contributions stage. Removing the callback requires a declarative kind-bootstrap path.
2. Promote general authoring forms from `.skein/spools/macros` into shipped APIs. One possible layout colocates a form with the API whose entry it builds: `skein.api.weaver.alpha/defop`, `skein.api.graph.alpha/defquery`, `skein.api.patterns.alpha/defpattern`, `skein.api.events.alpha/defhandler`, and `skein.api.hooks.alpha/defhook`. A central `skein.api.macros.alpha` is another option. Namespace ownership has not been decided.
3. Keep domain forms with their shipped spools. `skein.spools.cron/defjob` and `skein.spools.workflow/defworkflow` are the right ownership direction: the blessed APIs supply collection and registry primitives, while the spool defines the meaning and validation of its entry.
4. Fill expressiveness gaps without recreating an unrestricted module-wide callback under another name. Static entries, custom kinds, overrides, candidate validation, programmatically generated entries, multi-namespace spools, empty kind-provider modules, provenance, image activation, and removal by omission all need a form or a deliberate boundary. A possible generic computed-entry form should remain scoped to one declared kind.
5. Make the promoted forms testable through the production module path. Macro expansion, collection, invalid declarations, duplicate and override behavior, activation, removal by omission, image mode, and plan/status projections need public testing support.
6. Migrate in-tree spools, `.skein` config, sibling spools, examples, and tests. Prove contribution and removal parity before changing the grammar.
7. Only after the selected source universe has moved, remove `:contribute`, delete callback resolution and retention, and reject the old form with an actionable authoring-form remedy.

This sequence may change once verified. In particular, a generic authoring form that accepts a function returning an arbitrary multi-kind map would merely rename `:contribute` and fail the goal.

The separate RFC will therefore:

- inventory every remaining explicit `:contribute` function;
- verify which generic kind-bootstrap and collection primitives are missing;
- decide whether existing authoring forms cover each owner-complete partition;
- decide which general forms become shipped `skein.api.*` surface and which domain forms stay in their owning spools;
- add forms only where a repeated contribution shape earns one;
- resolve multi-namespace and programmatically generated contribution cases;
- specify public testing support and prove migration parity;
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

### RFC-Laf-001.O6 Smaller or existing authoring surfaces

TEN-004 requires each proposed form to earn a public name:

- A reaction could be a resource with no retained handle or one optional removal function, or direct composition of an existing event/hook API. A separate `defreaction` earns its place only if one-way transition semantics and retry reporting cannot stay clear in that smaller shape.
- A resource cannot generally be replaced by `events/register-handler!` or `hooks/register-hook!`: those APIs own two particular registries, not arbitrary threads, subscriptions, notifiers, or external handles. Userland setup/teardown functions remain possible, but without a declared boundary the coordinator cannot retain handles or report partial progress. `defresource` is the strongest candidate for a distinct primitive.
- Desired-state convergence may be a constrained `defreconcile`, a mode of `defresource`, or a domain-owned function called by one of those forms. A separate `defconvergence` is not justified until Cron and another independent domain demonstrate shared trigger, retry, and removal semantics.

A single generic lifecycle form remains O4. A prototype should begin from the smallest surface that expresses Chime, Cron, Shell, a process-lifetime seed, and a workspace singleton, then add names only where the examples become less honest without them.

## RFC-Laf-001.P15 Feasibility gates and open questions

This RFC discusses whether replacing the callback is a worthwhile and feasible direction. It does not need to settle every execution-policy detail before acceptance. It does need to expose any boundary that could make the proposed replacement unable to preserve current behavior. Oracle review `rhmvw` identified the following gates:

- **Cross-module convergence triggers:** a convergence must rerun when its declared desired input changes even if its owning module declaration does not. P11.2 records the Cron case. No trigger grammar is chosen.
- **Atomic lifecycle clusters:** forms must permit one effect to retain Chime's current single-lock transition. P11.1 now sketches the cluster as one resource and records the visibility tradeoff.
- **Runtime-lifetime resources:** the shell executor's worker pool survives module removal and closes at runtime stop. The proposed forms currently describe module lifetime only. A scope such as `:module` versus `:runtime`, or a separate form, must prove this behavior is expressible.
- **Retry outside contribution change:** retrying a degraded effect while its contribution is unchanged supersedes today's unchanged-skip rule. P8.1 now states that contract change; a prototype must show how the coordinator schedules and reports the retry.
- **Replacement ordering:** when one effect id disappears and another appears over the same singleton, cleanup must precede acquisition or the new registration may collide with the old one. The engine needs a deterministic transition order or an explicit relationship.
- **Convergence removal:** removing the convergence declaration itself is different from removing entries in its desired registry. The form needs an explicit final-convergence or teardown contract.
- **Convergence retry:** an opaque convergence function offers no checkpoints. The engine may rerun its whole boundary only under an idempotency or retry-safe contract; it cannot preserve progress inside the call.
- **Partial resource acquisition:** a thrown open call yields no handle. The resource form needs transactional acquisition or a mandatory partial-cleanup protocol before it can claim leak-free teardown.
- **Machine-checkable contracts:** declaration, callable, normalized state, projection, phase, status, and diagnostic shapes need public specs and tests. Prose examples are insufficient for a shipped boundary.
- **Vocabulary ownership:** the namespace owning lifecycle declarations, projected keys, status words, vocab publication, and generated API docs is unresolved.
- **Minimum surface:** P14.O6 records the required comparison with existing forms and composition. The three-form sketch is not yet justified as the minimum public API.
- **Reconcile terminology:** `defconvergence` may rename an existing published concept. It stays provisional until a prototype proves a distinct semantic boundary.
- **Governing records:** a feature proposal must enumerate the changes to ADR-003 Decisions A and D, SPEC-003.C17d, SPEC-004.C46/C46b/C74a, and the existing “no generic effect callbacks in the registry kernel” boundary. A lifecycle engine may remain coordinator machinery rather than registry-kernel callbacks, but the distinction must be made explicit.
- **Guild-shaped behavior:** Guild resets runtime-owned declarations and republishes them from one reconciler. Its migration may expose a contribution/lifecycle boundary that the three sketched forms do not yet cover.

None is presently known to make the direction impossible. Cross-module triggers, retained runtime-stop cleanup, atomic clusters, and degraded-effect scheduling all have plausible implementation seams in the current coordinator. They remain claims to test, not settled design.

- **RFC-Laf-001.Q1:** Are `defreaction`, `defresource`, and `defconvergence` the right public names? The semantic split is proposed; naming should be tested against real migrations.
- **RFC-Laf-001.Q2:** Should reactions exist in v1, or should process-lifetime seeds use a narrower `defseed` form that communicates their deliberate lack of teardown?
- **RFC-Laf-001.Q3:** What exact declaration change forces resource replacement? Byte-identical normalized data is the baseline; doc-only or provenance-only changes may not warrant close/open.
- **RFC-Laf-001.Q4:** Should a failed resource open be allowed to return a cleanup handle through a sanctioned exception shape, or must partial acquisition always live in domain spool state?
- **RFC-Laf-001.Q5:** After one effect fails, should independent siblings continue applying? This RFC recommends stop-on-first-failure for application and attempt-all for removal; migration evidence may justify independent-subgraph continuation.
- **RFC-Laf-001.Q6:** Does `plan` resolve callable symbols, as current evaluation does, or show declaration diffs from collected data and leave resolution to refresh? It must remain side-effect free either way.
- **RFC-Laf-001.Q7:** How should code-only reload interact with retained open/close vars? The current reconciler is retained by symbol and re-resolved through the spool loader; lifecycle callables need an equally explicit generation and reload contract.
- **RFC-Laf-001.Q8:** Should convergence functions receive raw desired/actual values, or should the engine standardize a richer diff result? The first release should prefer raw values unless two migrations demonstrate a shared diff.
- **RFC-Laf-001.Q9:** Can a resource close depend on the old module contribution after publication removed it? The retained effect context may need the previous owner partition, not only `:module/previous`.
- **RFC-Laf-001.Q10:** How does a resource declare module lifetime versus weaver-runtime lifetime, and what happens on module removal for a runtime-lifetime resource?
- **RFC-Laf-001.Q11:** What publication fact triggers a convergence whose desired state is contributed by other modules?
- **RFC-Laf-001.Q12:** When an effect declaration is removed, how does a convergence express final cleanup if its desired-state reader is no longer part of the next declaration?
- **RFC-Laf-001.Q13:** Which namespace owns the public specs, projected keys, status vocabulary, and generated API documentation for lifecycle declarations?
- **RFC-Laf-001.Q14:** Is desired-state convergence a distinct form, or should it retain the reconcile name and live behind a smaller generic lifecycle surface?

## RFC-Laf-001.P16 Consequences

Spool source becomes easier to scan. `defresource monitor` is both the searchable identity and the complete lifecycle declaration. Setup and teardown cannot be separated into distant status branches. A no-op removal is visible as an absent transition rather than hidden in a callback.

The coordinator becomes more complex. It owns an effect DAG, retained handles, per-effect state, transition classification, retry, and removal. That complexity is not new behavior; it is the common part of behavior currently repeated or omitted in spool callbacks. Centralizing it makes failures inspectable and policies consistent.

Tests can activate modules through the production path and assert effect-level outcomes. Resource fixtures no longer need to call a public reconciler directly with fabricated contribution statuses. Test helpers can inspect lifecycle state without exposing handles.

New lifecycle forms have a high bar. They are added only when several modules repeat a declaration and execution contract that the existing forms cannot express cleanly. The point is one authoring pattern, not a growing catalogue of synonyms.

The separate contribution RFC remains required to complete the end state. Until then, `def spool` survives only for explicit `:contribute` users.

## RFC-Laf-001.P17 Gates before accepting the breaking design

Landing this Proposed RFC records the investigation; it does not satisfy these gates. A later spike and feature proposal must satisfy them before implementation or migration begins:

- Every current in-tree, workspace, and pinned sibling reconciler has a lossless mapping to a proposed lifecycle form or identifies a concrete missing primitive.
- Applied, unchanged, changed, removed, failed-open, failed-close, whole-boundary retry, dependency order, reverse teardown, and removal-by-omission behavior have coordinator-level tests.
- Public specs validate every declaration, callable context/result, normalized state, projection, phase, status, and diagnostic shape adopted by the feature.
- A resource open contract prevents untracked partial acquisition through transactional behavior or a validated cleanup protocol.
- A convergence adopted by the feature defines its trigger, whole-call retry requirement, and declaration-removal cleanup.
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

Proceed to a bounded feasibility spike, not directly to the breaking replacement. Prototype the smallest lifecycle surface against Cron, Chime, the Shell executor, one process-lifetime seed, and one workspace singleton before choosing names or accepting public shapes. Those migrations cover desired-state reconciliation, atomic resource clusters, runtime-lifetime resources, one-way reactions, ordering, retained handles, and the current removal defect.

If the spike satisfies P17, return with the public specs, minimum-surface analysis, migration proof, and governing-record amendments needed to accept the break. At that point, do not retain `:reconcile` as an escape hatch. Do not remove `:contribute` in the same feature. Record and pursue the separate contribution-authoring RFC so the eventual extension surface can be made entirely of authoring forms and `def spool` can disappear.
