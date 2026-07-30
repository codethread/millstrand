# Authoring forms program proposal

**Document ID:** `PROP-Auf-001`
**Status:** Approved
**Approved:** 2026-07-29
**Last Updated:** 2026-07-29
**Related RFCs:** [`RFC-Saf-001`](../../rfcs/2026-07-28-spool-authoring-forms.md) (spool authoring forms), [`RFC-Laf-001`](../../rfcs/2026-07-28-lifecycle-authoring-forms.md) (lifecycle authoring forms) — the source decision records this proposal merges and advances; kept unchanged as the intent history
**Related decisions:** [ADR-002](../../adrs/0002-no-inline-module-lifecycle-macro.md) (rejected inline callback sugar), [ADR-003](../../adrs/0003-spool-activation-lifecycle.md) (one activation path and the reconcile contract), [ADR-004](../../adrs/0004-def-spool-convention.md) (`def spool` entry-point convention), [RFC-020: readability macros](../../rfcs/2026-07-08-skein-readability-macros.md)
**Related root specs:** [`repl-api.md`](../../specs/repl-api.md) (C17c/C17d/C19), [`daemon-runtime.md`](../../specs/daemon-runtime.md) (C45/C46/C46b/C46c/C74a), [`alpha-surface.md`](../../specs/alpha-surface.md)
**Related proposals:** [`PROP-Sld-001`](../spool-lifecycle-docs/proposal.md) (documents the surface this program removes), [`PROP-Dsp-001`](../uwnzl-def-spool-convention/proposal.md) (the `def spool` convention whose endpoint this supersedes), [`rrvnn`](../rrvnn-intree-installer-removal/proposal.md), [`9snqu`](../9snqu-siblings-rollout/proposal.md), [`rtnfv`](../rtnfv-consumer-cutover/proposal.md), [`fbr4m`](../fbr4m-core-reconcile-image/proposal.md), [`ifenn`](../ifenn-chime-engine-parity/proposal.md)

> One document by design: the user directed that this program have a single proposal to aid coordination. It merges the two source RFCs — which proposed the two halves of one change as separate documents and deferred to each other — and advances beyond them where the source audits and later rulings produced new data; P1 lists the departures. The source RFCs stay in `devflow/rfcs/`, unchanged, as the record of original intent.

## PROP-Auf-001.P1 Summary

Skein should replace both public entry points of the module convention

```clojure
(def spool
  {:contribute 'contribute
   :reconcile 'reconcile})
```

with named top-level authoring forms. A spool declares each capability where it is defined:

```clojure
(defop help ...)
(defquery mine ...)
(defpattern release ...)
(defworkflow land ...)
(defjob nvd-scan ...)

(defresource monitor ...)
(defreaction activation-notice ...)
```

The forms are not a second publication model. Contribution forms are author-facing syntax for producing the same owner-complete contribution data that `:contribute` produces today; lifecycle forms are declarations that one shared coordinator engine executes where each module's `:reconcile` callback runs today. The runtime keeps one normalized contract per half: validate, stage, collide, override, publish for contributions; apply, preserve, replace, converge, remove for lifecycle effects.

The end state has no `def spool` var at all. An extension namespace is ordinary Clojure definitions plus explicit Skein authoring forms, and every capability follows one visible, greppable authoring pattern.

The two halves are at different maturity. The contribution replacement is proposed for acceptance here: the collector, the normalized contribution contract, and most of the forms already exist, and the remaining work is parity (overrides, image replay, kind bootstrapping, generated entries) plus promotion into shipped API surface. The lifecycle replacement proposes candidate forms and an engine but must pass the feasibility gates in P16.2 through a bounded spike before its names and shapes are accepted.

The cutover is a breaking change under TEN-000@1, and the user has accepted that break. It is minimized by staging, not by compatibility machinery: first ship the complete authoring surface while the old keys still resolve, then migrate every first-party and sibling spool onto the forms, then remove the old grammar in one final break. No alias, silent fallback, or compatibility shim ships, and the final grammar rejects an old `spool` map loudly with an error naming the replacement forms. P15 owns the stages.

"Pure authoring forms" in this proposal means capability and lifecycle declaration are expressed only through declarative, side-effect-free top-level forms rather than arbitrary runtime callbacks. It does not mean every form must be a macro or that macro expansion itself is side-effect free; functions named by lifecycle declarations may perform effects when the engine invokes them, and pure factory functions may sit underneath the contribution forms. The public semantic boundary is the declaration data the forms produce.

This is one coordinated program and deliberately one document. It is the scope authority for the kanban feature cards cut after sign-off: the program is far too large for a single feature, and slicing into cards and their tasks happens against P19's constraints. The audited baseline in P2.4 grounds the scope: 19 modules across this repo, its `.skein` workspace, and three pinned sibling repos still carry the old grammar, and eight spec clauses plus three ADRs govern the surface.

Where this proposal departs from the source RFCs, new data governs:

- **PROP-Auf-001.D1 — One document.** The RFCs' mutual deference is resolved here rather than by a third RFC (user direction, 2026-07-28).
- **PROP-Auf-001.D2 — A migration window exists.** RFC-Saf-001.REC8 said no released version accepts both grammars; landing order makes that impossible. A bounded window on skein-src main in which both grammars load is acknowledged in P15.S2 — no compatibility machinery ships and docs teach only the new grammar.
- **PROP-Auf-001.D3 — The break is authorized outright.** skein-src will not stamp a v1 marker (user ruling, 2026-07-28), so TEN-000@1 governs the whole program; sibling entry-point removals ship as recorded breaks under the installer-retirement precedent even though the siblings carry their own markers.
- **PROP-Auf-001.D4 — The census is concrete.** The RFCs' thematic censuses are replaced by the verified inventory in P2.4.
- **PROP-Auf-001.D5 — The governing-records gate is wider.** ADR-002, SPEC-003.C17c, and SPEC-004.C45/C46c join the amendment list, SPEC-004.C46b needs an explicit disposition, and SPEC-004.C74a is a concrete amendment target (P16.2).

## PROP-Auf-001.P2 Problem

### PROP-Auf-001.P2.1 Contribution: one idea, two grammars

Skein currently presents two ways to express one idea. The callback style collects every entry into a single return value:

```clojure
(defn contribute [_ctx]
  {:queries {:entries {"mine" [:= [:attr :owner] "ct"]}}})

(def spool
  {:contribute 'contribute})
```

The authoring-form style puts the declaration beside the definition:

```clojure
(defquery mine
  "Work owned by ct."
  {:usage "strand ready --query mine"}
  [:= [:attr :owner] "ct"])
```

Both eventually mean the same thing: this module owner contributes a named entry to a registry kind. The callback is therefore not a distinct domain concept. It is an assembly mechanism exposed as public authoring syntax, and that mechanism has several costs:

- **PROP-Auf-001.P2.1.1 — It centralizes unrelated declarations.** An op's handler and contract may be defined hundreds of lines from the map entry that publishes it. Names and implementation symbols are repeated, so they can drift.
- **PROP-Auf-001.P2.1.2 — It exposes publication representation.** Authors must know about `:entries`, `:overrides`, contribution kind keys, and the precise nesting of the runtime's normalized partition.
- **PROP-Auf-001.P2.1.3 — It creates two extension grammars.** Some capabilities read as `defop` or `defworkflow`; others appear as data in `contribute`. A reader must search for both patterns to discover what a spool adds.
- **PROP-Auf-001.P2.1.4 — It weakens local validation.** A malformed entry assembled in one large callback is naturally reported at contribution evaluation or staging. An authoring form can validate the declaration at the named source form and retain the name in its error data.
- **PROP-Auf-001.P2.1.5 — It makes ergonomic helpers look secondary.** The current forms already drive the same collector and publication kernel, but the presence of the general callback makes them appear to be optional convenience wrappers over the "real" API.
- **PROP-Auf-001.P2.1.6 — It encourages runtime-dependent declaration logic.** Because `contribute` receives the runtime and module context, it can choose capabilities dynamically. That conflicts with the intended model: a spool's declarations are static source facts, while runtime effects belong to lifecycle handling.

The split also leaves gaps in the authoring forms themselves. Today `collect-entry!` can record explicit override intent, but the workspace-local `defop` form cannot express it. Source evaluation can collect top-level forms, but image loading deliberately evaluates no source and therefore relies on the retained `:contribute` callback. Some domain spools use `contribute` to establish a registry kind before dependent entries publish. These are real differences in the current implementation, but none requires `:contribute` as a permanent authoring abstraction. They identify the behavior a complete authoring-form design must preserve.

The current surface is also split by ownership. `defop`, `defquery`, `defpattern`, and `defrule` are prototypes in this repository's `.skein` workspace, not shipped `skein.api.*` forms, and they call an internal collector. `defworkflow` and `defjob` are shipped by their domain spools and already call the blessed `skein.api.runtime.alpha/collect-entry!`. A complete replacement therefore includes promoting supported forms for core kinds into shipped API surface while domain-specific forms remain owned by their spools. This proposal decides that scope; the implementing feature's plan decides exact namespaces and signatures.

### PROP-Auf-001.P2.2 Lifecycle: one callback, hidden boundaries

The current reconciler is one function behind the same bookkeeping:

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

The lifecycle design question started narrower: does every reconciler need a `case` over `:applied` and `:removed`? The answer exposed three separate shapes. Some resources genuinely have different setup and teardown operations: Chime registers its engine handler and mutation barrier on application and unregisters both on removal, a scoped effect whose cleanup belongs beside its setup. Some domains already reconcile effective desired state: Cron reads the newly published effective job registry, compares it with runtime-owned scheduled jobs, starts or replaces changed jobs, and cancels absent jobs; the same convergence body works after application and removal because publication happens before lifecycle reconciliation. And some actions leave no owned resource: seeding process-lifetime vocabulary or emitting an activation notice may have no removal operation. These are transition reactions, not resources pretending to have empty cleanup.

The comparison with React is useful but not exact. A React effect groups setup with cleanup, while React's renderer also reconciles a desired tree with a host tree. Skein needs both ideas: a resource declaration keeps acquisition and release in one authoring block; a convergence declaration makes actual live state approach the effective registry; a reaction declaration handles a transition that leaves no resource handle. Once these boundaries are declared separately, Skein can see partial progress. That creates framework policy questions the callback previously left implicit: ordering, retry, cleanup after partial application, replacement, and reporting. Those questions already exist; the monolithic callback answers them independently and often accidentally in each spool. This proposal moves the common answers into one engine and leaves domain logic in named functions.

### PROP-Auf-001.P2.3 The entry-point map is bookkeeping

Both keys of `def spool` point at callbacks that restate runtime mechanics: `contribute` mirrors the normalized publication representation, `reconcile` mirrors the coordinator's transition dispatch. The var itself exists only to point at them. Once each capability and each lifecycle boundary is declared where it is defined, the map has no remaining job, and retaining it would retain the indirection both halves remove.

### PROP-Auf-001.P2.4 Audited baseline

Verified against source on 2026-07-28: three delegated audits (run ids `eu5fj`, `rtu5a`, `wye1w`, results retained under the proposal-stage orient step) plus a sol-med fact-check pass (review notes `bp5vo`, `fwk7j`).

**Surface.** `defop`/`defquery`/`defpattern`/`defrule` are repo-local prototypes in `.skein/spools/macros/` calling the internal `skein.core.weaver.module-refresh/collect-entry!`; `defop` cannot express override intent. The blessed `skein.api.runtime.alpha/collect-entry!` (alpha.clj:351) does accept `{:override? true}`, and `defjob`/`defworkflow` already ride it. Image mode (`:load :image`) evaluates no source and requires a resolvable `:contribute` (module_refresh.clj:440); collected forms and `:contribute` are already mutually exclusive (SPEC-004.C46/C46c). Kinds are declared via `skein.api.registry.alpha/declare-kind!`; Cron, Workflow, and Chime bootstrap their kinds inside `contribute`. The devflow sibling spool is live proof of the end state in source mode — no `spool` var, contribution purely via collected `defworkflow` — but nothing yet proves image replay.

**Census.** Inclusion rule: modules in the selected source universe — this repo's `spools/`, its tracked `.skein` config, and the pinned sibling roots in `.skein/spools.edn`. 19 modules carry a `spool` var: 13 here (batteries, cron, chime, workflow, workflow-cli, shell executor, code executor, guild, unsafe-text-search, plus the `.skein` kanban-tracker, harnesses, reviewers, and module-adapters) and 6 sibling (kanban, kanban-peering, delegation, agent-run, subagent executor, bench). 17 declare `:contribute` and 17 declare `:reconcile` (per-module verification in review note `bp5vo`). Irregulars the lifecycle design must carry: the shell executor's worker pool is a runtime-lifetime resource (survives module removal, closes at runtime stop); Guild resets and republishes runtime-owned declarations on both transitions; three `.skein` modules are unconditional singleton setters (kanban tracker, harness contracts, help transform) whose removal branch does not undo the binding; batteries, workflow, workflow-cli, and kanban have no-op removals (process-lifetime seeds).

**Context.** The active feature chain `fbr4m`/`rdrw9` → `rrvnn`/`9snqu` → `rtnfv` is converting installer-era activation onto the current `def spool` convention — this program supersedes that endpoint but depends on those migrations landing first, so the universe it breaks is uniform. `ifenn-chime-engine-parity` moves Chime registration into reconcile, which the lifecycle spike then uses as its atomic-cluster case. `spool-lifecycle-docs` (PROP-Sld-001) documents the very contract this program removes; its output becomes a migration source and must be rewritten in the removal stage. User direction (2026-07-28) settles the regime question: skein-src will not stamp v1, so the program executes wholly under TEN-000@1's alpha authority, sibling spools included; sibling migration releases still record their breaks per release (the installer-retirement precedent) rather than shipping as silent accretion. The v1-stamp card (`b3v1r`) predates this ruling; its disposition is user-owned.

## PROP-Auf-001.P3 Mental model

### PROP-Auf-001.P3.1 The contribution contract

Skein owns a registry of capability kinds. The core runtime always exposes `:ops`, `:queries`, `:patterns`, `:hooks`, and `:events`. Domain spools can expose further kinds, such as workflow definitions, workflow executors, cron jobs, or chime rules.

A module owns a complete partition in each kind it contributes. Internally, the contribution has this shape:

```clojure
{kind-id
 {:entries
  {entry-key entry-value}

  :overrides
  #{entry-key}}}
```

The current callback accepts either a short partition, `{kind-id {entry-key entry-value}}`, or the long partition shown above. The outer value must be a map, every kind ID must be a keyword, `:entries` must be a map, and `:overrides` must be a set containing only keys present in `:entries`. The long partition is closed to those two keys. Normalization expands the short form and a missing `:overrides` to the long shape.

The owner is the module key, not another field in this map. Republishing an owner's partition replaces its previous partition. Omitting an entry removes that owner's old entry; omitting a kind removes that owner's old partition for the kind. Registry-defined schemas validate entry keys and values. The publication kernel owns collision detection, explicit overrides, effective-value calculation, and atomic publication.

Authoring forms should produce this normalized intermediate representation without asking authors to write it. In that sense, the forms are first-class syntax over the contribution contract:

```text
top-level authoring forms
        │
        ▼
owner-complete normalized contribution
        │
        ▼
validate → stage → collide/override → publish
```

### PROP-Auf-001.P3.2 Lifecycle is a coordinator phase

Removing the public reconciler does not remove reconciliation as a runtime phase. The lifecycle engine is internal coordinator machinery: it collects the module's lifecycle declarations, diffs them against the previous declaration set after contribution publication, and applies, preserves, replaces, converges, or removes effects through the functions each declaration names. Lifecycle declarations are printable data, distinct from contribution entries: an effect belongs to exactly one module owner, has no precedence layers, and cannot override another module's effect. Publication order is preserved: contributions are validated and atomically published before any lifecycle effect runs.

### PROP-Auf-001.P3.3 Direct registration stays distinct

The original direct registration APIs remain a separate layer:

```clojure
(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

This still works because direct/REPL registration is an imperative, additive, top-precedence operation against one runtime. It is intentionally outside module refresh. It has no spool owner whose complete partition can be replaced, replayed, or removed by omission. Putting the same query in a spool therefore needs an authoring declaration such as `defquery`; that form supplies module lifecycle semantics, not merely a shorter spelling of `register-query!`.

## PROP-Auf-001.P4 Goals

- **PROP-Auf-001.G1:** Establish one visible grammar for Skein extensions — named, top-level authoring forms — and retire the `def spool` entry-point map entirely.
- **PROP-Auf-001.G2:** Preserve the complete contribution semantics available today, including owner-complete replacement, removal by omission, explicit override intent, open registry kinds, and registry-owned validation.
- **PROP-Auf-001.G3:** Express the current reconcile behaviors through declarative, source-visible lifecycle forms without losing behavior, keeping setup and teardown for an owned resource in one block.
- **PROP-Auf-001.G4:** Keep ordinary source files Emacs-like and sequential. Authors write repeated forms such as `(defop one ...)` and `(defop two ...)`; they do not maintain a separate list of forms or a trailing manifest.
- **PROP-Auf-001.G5:** Make declarations easy to scan, grep, navigate, inspect, and reload. A capability's name, documentation, implementation, and publication contract should be co-located, and so should a resource's acquisition and release.
- **PROP-Auf-001.G6:** Make image loading and source loading observe the same declarations even though image mode does not evaluate source.
- **PROP-Auf-001.G7:** Permit future capability kinds and lifecycle forms to be added without widening a spool entry-point map, with a high bar: a new form is earned by repeated behavior, not by novelty.
- **PROP-Auf-001.G8:** Improve error locality and generated documentation by giving each kind a form that understands its complete schema, and fail loudly at the declaration or named effect boundary with the module key, effect id, effect kind, callable, and phase.
- **PROP-Auf-001.G9:** Test whether effect identity, ordering, retained handles, whole-boundary retry, and teardown outcomes can become visible to `plan`, `status`, refresh results, and tests.
- **PROP-Auf-001.G10:** Preserve printable declaration data. Lifecycle callables remain fully qualified symbols resolved through the spool-aware classloader; declarations never hold closures.
- **PROP-Auf-001.G11:** Preserve publication order: validate and atomically publish contributions before applying lifecycle effects.
- **PROP-Auf-001.G12:** Make the cutover a clean break under TEN-000@1, staged as surface, migration, then removal, with no alias or compatibility path for the removed syntax.
- **PROP-Auf-001.G13:** Migrate the complete audited census (P2.4) — the `.skein` workspace modules and sibling releases included — with proven per-module parity: equal normalized contributions, exact removal-by-omission, lossless lifecycle behavior.

## PROP-Auf-001.P5 Non-goals

- **PROP-Auf-001.NG1:** This proposal does not change the owner-partitioned registry kernel, contribution normalization, collision policy, publication atomicity, or removal-by-omission semantics.
- **PROP-Auf-001.NG2:** This proposal does not turn direct registry functions such as `register-query!` into declarations. They remain useful sharp tools for REPL work and runtime-owned imperative operations; they are not the spool authoring contract.
- **PROP-Auf-001.NG3:** This proposal does not require one generic macro for every kind, on either half. A common implementation protocol should support kind-specific forms with vocabulary and validation appropriate to their domain.
- **PROP-Auf-001.NG4:** This proposal does not preserve arbitrary runtime-dependent capability selection performed inside a `contribute` callback. Retaining such a callback under another name would retain the design this proposal removes.
- **PROP-Auf-001.NG5:** This proposal does not prescribe the private storage mechanism for replayable declarations. It specifies the observable source- and image-loading behavior that mechanism must provide.
- **PROP-Auf-001.NG6:** This proposal does not approve a final inventory of public macros, factories, or batch forms. Names in examples describe existing precedent or candidate syntax; every new public Var still requires a TEN-004 justification in the implementing feature's plan.
- **PROP-Auf-001.NG7:** No general workflow or arbitrary effect DSL. Candidate lifecycle forms are limited to behavior found in current reconciler implementations.
- **PROP-Auf-001.NG8:** No automatic rollback. External effects may be irreversible, and a rollback claim would be false for notifications, subprocess actions, or remote registrations.
- **PROP-Auf-001.NG9:** No durable replay or exactly-once guarantee. Lifecycle state supports resumption within the running weaver, in line with PHILOSOPHY's resumability rule.
- **PROP-Auf-001.NG10:** No closure-valued callables or runtime `eval`. ADR-002's classloader and provenance constraints still apply.
- **PROP-Auf-001.NG11:** No attempt to turn every registered entry into a resource. Owner-partitioned contribution data remains distinct from live handles.
- **PROP-Auf-001.NG12:** No cross-module effect dependency graph in the first version. Existing module `:after` edges order modules; effect dependencies order effects within one module.
- **PROP-Auf-001.NG13:** No feature slicing or task detail in this document. Kanban feature cards are cut against it after sign-off, within P19's constraints; their plans own implementation strategy.
- **PROP-Auf-001.NG14:** No v1-stamp work. The user has ruled skein-src will not stamp v1; disposing of the stale v1-stamp card (`b3v1r`) and its epic is user-owned housekeeping outside this program.

## PROP-Auf-001.P6 Contribution authoring surface

### PROP-Auf-001.P6.1 Repeated forms are the normal source shape

Each declaration stands alone:

```clojure
(defop one ...)
(defop two ...)
(defquery mine ...)
```

No enclosing list is needed because each macro expansion can both define the ordinary Clojure Var or function and leave a replayable declaration record. The runtime assembles all records owned by the module into one owner-complete contribution before publication.

This distinction matters. The source is a sequence of definitions; the normalized contribution is a map. Authors should not have to mirror the runtime's batch representation in order to write sequential source.

The pattern is already live in production: the pinned devflow sibling spool ships no `spool` var at all and contributes solely through collected `defworkflow` forms under ordinary source loading. What it does not yet prove is image replay (P6.3).

### PROP-Auf-001.P6.2 Forms cover the entire contribution record

Every kind-specific authoring form must be able to express:

- the registry kind;
- the stable entry key;
- the complete entry value;
- explicit override intent;
- the information needed to define or reference the ordinary Clojure Var, function, or data value associated with the entry;
- documentation and kind-specific metadata needed by discovery surfaces.

For example, overriding an existing op must be possible directly at the form:

```clojure
(defop help
  "Workspace-specific help."
  {:arg-spec help-arg-spec
   :override? true}
  [ctx]
  (workspace-help ctx))
```

The exact form grammar belongs to the feature specification, but `:override? true` must normalize to:

```clojure
{:ops
 {:entries {"help" help-entry}
  :overrides #{"help"}}}
```

This closes a current incidental gap. `collect-entry!` already accepts `{:override? true}`; the current `defop` does not expose it. Without the option, defining `help` produces no override intent and publication correctly refuses the collision. The limitation belongs to the macro surface, not to the contribution model.

### PROP-Auf-001.P6.3 Forms are replayable in image mode

`:load :image` is an activation mode, not a test mode. It trusts an already-loaded namespace and does not evaluate its source. The current ephemeral collector therefore sees no authoring forms in image mode, while a `:contribute` symbol remains callable.

Removing `:contribute` requires authoring forms to leave declaration data in the loaded namespace. Image evaluation must be able to reconstruct the same owner-complete normalized contribution from that retained data without evaluating source and without invoking arbitrary spool code.

The retained representation may use metadata as part of its implementation, but Var or namespace metadata alone is insufficient because deleting a source form does not unmap its old Var during reload. A viable design therefore needs an epoch or cleanup mechanism, a generated namespace-owned manifest, a coordinator-owned snapshot, or another representation that can prove the current declaration set. This proposal does not choose among those viable designs. Whichever mechanism is chosen must satisfy five observable properties:

1. source collection and image replay produce equivalent normalized contributions;
2. declaration order does not alter the resulting partition except for the existing deterministic same-kind/same-key replacement rule;
3. removing a form from source removes its entry after the next source refresh rather than leaving stale declaration metadata;
4. ordinary code-only reloads do not publish a new partition outside module refresh;
5. an image namespace with no retained authoring record fails loudly, while an explicitly recorded empty declaration set remains distinguishable from missing or stale replay data.

Unit tests may source-load namespaces containing authoring forms today. The missing image behavior is therefore not an inability to test macros; it is a replay gap in one activation path.

### PROP-Auf-001.P6.4 Open kinds have an authoring path

Core kinds can ship forms such as `defop`, `defquery`, and `defpattern`. A domain spool must likewise be able to expose `defworkflow`, `defjob`, `defrule`, or a future kind-specific form without asking its users to return raw contribution maps.

Some current `contribute` callbacks also establish the registry handle that declares a domain kind. Cron, for example, materializes its job-kind registry before dependent job contributions stage. Removing `:contribute` across the platform therefore requires a declarative kind-definition form or equivalent pre-publication declaration, conceptually:

```clojure
(defkind jobs
  {:id :skein.spools.cron/jobs
   :registry ...})
```

`defkind` here names the role, not its final syntax. The essential contract is ordering:

```text
load and replay declarations
        ↓
realize registry handles and declare kinds
        ↓
discover publication backends
        ↓
validate, stage, and publish entries
        ↓
reconcile live effects
```

A new kind exposed directly by core needs no spool-owned bootstrapping because core already declares it. Its convenience macro is straightforward sugar over collection. The pre-publication requirement exists for open, runtime-owned domain kinds.

This ordering requires new coordinator lifecycle machinery rather than a richer macro expansion. This proposal records that architectural cost so an infeasible kind-bootstrap story cannot hide behind "authoring sugar"; the implementing feature's plan owns the exact phase and whether today's spool-state discovery seam survives.

### PROP-Auf-001.P6.5 Pure factories support generated declarations

Repeated macros are the default because most capability declarations are hand-authored and deserve a named source location. Programmatically generated declarations are still legitimate. One candidate is a pure constructor that returns a validated declaration fragment:

```clojure
(op/entry 'status
  {:doc "Show status."
   :arg-spec status-arg-spec
   :fn 'acme.ops/status-op})
```

Kind-specific macros can be thin syntax over these constructors plus declaration collection. A batch form may accept a sequence of fragments when entries genuinely arise from data:

```clojure
(defops
  (for [service services]
    (op/entry (service-op-name service)
      (service-op-spec service))))
```

The constructor and batch names above are illustrative rather than accepted public surface. The requirement is that generated declarations have one validated, replayable path; the implementing feature's plan must choose and justify the smallest surface that provides it.

The batch form is not required around ordinary `defop` forms. Its purpose is to cross the compile-time authoring boundary for generated entries while retaining validation and replay. A bare top-level function call that merely returns a map cannot declare anything by itself; either an authoring form must collect the returned fragments or a macro must expand them into retained declarations.

### PROP-Auf-001.P6.6 Declarations are static source facts

The current callback receives `{:runtime ... :module/key ... :module/declaration ...}` and can compute a different partition from ambient runtime state. The replacement deliberately does not preserve that freedom.

A spool's capability set should be derivable from its source declarations and explicit authoring data. Runtime context may be used later to resolve or execute a published capability, but it must not silently decide whether the capability exists. Conditional declarations must be explicit data understood by a kind, or separate module declarations selected by the workspace. Arbitrary runtime-dependent selection would require a callback equivalent to `contribute` and defeat image replay, inspection, and static discovery.

## PROP-Auf-001.P7 Lifecycle authoring surface

The examples use provisional names under `skein.api.lifecycle.alpha`. Neither that namespace nor the three-form split is decided here. The forms make the discussion concrete enough to test against current reconcilers; P13.2 and P16.2 retain smaller surfaces and existing reconcile vocabulary as live alternatives.

### PROP-Auf-001.P7.1 Transition reactions

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

An absent transition is an explicit no-op in the declaration grammar, not a missing callback error. A healthy reaction with an identical lifecycle declaration is preserved when unrelated contribution data changes; it runs again after its own declaration changes or a remove/reapply cycle. A degraded reaction may be retried under P8.2. Authors who require repeat suppression across those transitions must model the idempotency key in their domain or runtime-owned spool state. The form does not promise exactly once.

### PROP-Auf-001.P7.2 Owned resources

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

### PROP-Auf-001.P7.3 Desired-state convergence

The provisional `defconvergence` name declares how to read desired and actual state and make actual state converge:

```clojure
(ns acme.cron
  (:require [skein.api.lifecycle.alpha :refer [defconvergence]]))

(defconvergence scheduled-jobs
  "Make durable scheduler wakes match the effective Cron job registry."
  {:desired 'acme.cron/effective-jobs
   :actual 'acme.cron/running-jobs
   :converge 'acme.cron/converge-jobs!
   :on-removed 'acme.cron/remove-all-jobs!})
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

For removal to be executable, this candidate requires `:on-removed`. The coordinator retains the last-good declaration and resolved callable set, then invokes that removal callable when the declaration disappears. Merely omitting the declaration must never strand the external state it previously managed. A later design could prove that a retained desired-state reader plus an empty desired value is equally safe, but that optimization is not assumed here.

For ordinary desired-state changes, the engine could invoke the same callable after applied and removed contribution transitions. It should not impose a generic diff unless domains prove shared identity, replacement, ordering, and failure semantics. A later keyed-collection form may earn its place if several domains repeat the same diff contract:

```clojure
(defcollection-resource scheduled-jobs
  {:desired 'acme.cron/effective-jobs
   :open 'acme.cron/start-job!
   :close 'acme.cron/stop-job!
   :fingerprint 'acme.cron/job-fingerprint})
```

That possible form is illustrative and not part of the first release. The name `convergence` may itself be wrong: Skein's published word for this phase is **reconcile**, and a prototype must show whether desired-state convergence is a distinct authoring primitive or one constrained form of reconciliation before introducing new vocabulary.

## PROP-Auf-001.P8 Lifecycle declaration data and engine

### PROP-Auf-001.P8.1 Collected declaration shape

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

Any shipped surface needs `clojure.spec` contracts for its declaration maps, callable contexts and results, normalized lifecycle data, plan/status/refresh projections, and closed status and phase values. Cross-entry uniqueness, dependency cycles, and other relationships that a local data spec cannot express remain explicit validators. Every failure projection must carry a common diagnostic envelope naming the module, effect, kind, callable, phase, offending value or input, and allowed alternatives when the boundary has a closed set. The examples in this proposal are sketches of those shapes, not their specifications.

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

### PROP-Auf-001.P8.2 Declaration transitions

For each `[module-key effect-id]`, the engine classifies:

| Previous | Next | Action |
| --- | --- | --- |
| absent | present | Apply the new effect. |
| identical and healthy | identical | Preserve its successful state; do not rerun it. |
| present | changed | Replace it according to its effect kind. |
| present | absent | Remove through the retained old declaration. A resource closes its handle; a convergence invokes its required `:on-removed`; a reaction invokes `:on-removed` when it declared one. |
| identical but degraded | identical | Retry the effect from its declared boundary only if that boundary's contract makes whole-call retry safe. The engine cannot resume inside an opaque function. This deliberately differs from today's contribution-unchanged fast path and would require explicit amendments to SPEC-004.C46/C46b and ADR-003.P2's retained DELTA-OlrDrt-001.D4 constraint. |

A reaction declaration change runs the next declaration's applied action after the prior declaration's optional removed action. A resource change closes the retained old handle before opening the new declaration. A convergence change invokes the new convergence against the post-publication desired state. Failure behavior follows P9.

Removal-by-omission never reloads a removed module's source. The coordinator therefore retains each applied effect's normalized declaration and resolved callable set, just as it currently retains the last-good reconciler. It also retains resource handles until close succeeds.

### PROP-Auf-001.P8.3 Context

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

## PROP-Auf-001.P9 Candidate failure, retry, and teardown policy

The following is a policy to prototype, not an accepted contract:

1. Apply effects in dependency order.
2. Stop applying a module's remaining effects after the first failure. Effects from later modules follow the existing module dependency and degraded-outcome rules.
3. Retain every successfully opened resource, completed reaction result, convergence result, and declaration.
4. On retry, preserve successful unchanged effects and call the failed effect again from its public boundary. There is no implied checkpoint or mid-function resume.
5. Remove effects in reverse dependency order.
6. Attempt removals in reverse dependency order. When a dependent fails to close, do not close any dependency it may still use; mark that blocked cleanup explicitly. Continue cleanup only in independent subgraphs.
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

## PROP-Auf-001.P10 Results and inspection

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

## PROP-Auf-001.P11 Worked migrations

### PROP-Auf-001.P11.1 Chime

Chime is a warning against splitting effects at the wrong boundary. Its current reconciler holds one monitor while it registers or unregisters the hook and handler and replaces the visible rule view. Three independently invoked effects could expose an intermediate state, so a lossless first migration keeps that atomic cluster together:

```clojure
(defresource engine-and-rule-view
  "Keep Chime's handler, mutation barrier, and visible rules atomic."
  {:open 'skein.spools.chime/open-engine-and-rule-view!
   :close 'skein.spools.chime/close-engine-and-rule-view!})
```

This preserves behavior but gains no effect-level visibility inside the cluster. That is an honest limit of the proposal: authoring forms improve boundaries that already exist; they do not manufacture safe boundaries inside one lock. A prototype must prove that the resource form can carry this transition without changing Chime's concurrency behavior.

### PROP-Auf-001.P11.2 Cron

Cron becomes one convergence declaration. Its current diff body moves unchanged into the named convergence function:

```clojure
(defconvergence scheduled-jobs
  "Make durable scheduler wakes match effective Cron job declarations."
  {:desired 'skein.spools.cron/effective-jobs
   :actual 'skein.spools.cron/running-jobs
   :converge 'skein.spools.cron/converge-jobs!
   :on-removed 'skein.spools.cron/remove-all-jobs!})
```

There is no status switch for ordinary job-entry changes: publication changes the effective registry before convergence runs, so absent jobs are cancelled by the ordinary diff. Removing Cron's convergence declaration itself is a different transition; the coordinator uses the retained `:on-removed` callable to cancel every job previously managed by that declaration.

The trigger is not solved by this sketch. Cron's own lifecycle declaration can stay byte-identical while another module changes the effective Cron job kind. The lifecycle engine would need a declared dependency such as `:when-kinds #{:skein.spools.cron/jobs}`, or another validated way to rerun convergence when its desired input changes. Calling every convergence after every publication is a possible baseline but may be too broad. A prototype must establish a trigger contract before this form is considered feasible.

Cron also shows the two halves meeting in one spool: its kind bootstrapping moves to the pre-publication kind declaration (P6.4), its job entries are already `defjob` forms, and its reconciler becomes the convergence above. Nothing remains for a `spool` var to point at.

### PROP-Auf-001.P11.3 Process-lifetime seeds

Batteries glossary outcomes and workflow vocabulary currently use an applied action with an explicit no-op removal because their domains expose no retraction API:

```clojure
(defreaction glossary-outcomes
  "Seed Batteries' process-lifetime glossary outcomes."
  {:on-applied 'skein.spools.batteries/seed-glossary-outcomes!})
```

The absence of `:on-removed` records the no-op directly. No hand-written branch can accidentally re-register on removal.

### PROP-Auf-001.P11.4 Workspace singleton bindings

The current workspace tracker, help-transform, harness defaults, and local notifier reconcilers apply setters unconditionally. Under the lifecycle contract, removal should undo the binding. Their migration exposes whether each domain has a real unset/reset operation:

```clojure
(defresource tracker-binding
  "Bind devflow as the kanban tracker while this module is active."
  {:open 'kanban-tracker/bind-devflow!
   :close 'kanban-tracker/unbind-devflow!})
```

If a singleton API has no removal operation, the feature must add one or deliberately classify the binding as a process-lifetime reaction. The migration may not silently preserve today's unconditional-removal defect.

## PROP-Auf-001.P12 Author experience

A finished spool reads as ordinary definitions plus explicit Skein authoring forms:

```clojure
(ns acme.delivery
  (:require [skein.api.lifecycle.alpha :as lifecycle]
            [skein.api.skein.alpha :as skein]))

(skein/defquery mine
  "Work owned by ct."
  {:usage "strand ready --query mine"}
  [:= [:attr :owner] "ct"])

(skein/defop ship
  "Ship the selected release."
  {:arg-spec ship-arg-spec}
  [ctx]
  (ship! ctx))

(skein/defpattern release
  "Create a release strand."
  {:input release-input}
  ...)

(lifecycle/defresource monitor
  "Run the delivery monitor while this module is active."
  {:open 'acme.delivery/start-monitor!
   :close 'acme.delivery/stop-monitor!})
```

There is no `contribute` function, no `reconcile` function, and no `spool` var. Grepping for `defop`, `defquery`, `defpattern`, or `defresource` finds the extension points directly, and `defresource monitor` is both the searchable identity and the complete lifecycle declaration: setup and teardown cannot be separated into distant status branches, and a no-op removal is visible as an absent transition rather than hidden in a callback.

During the migration window (P15.S2), unmigrated spools still carry their old `spool` var; the window is released but transitional (P15.S2 owns its contract), and the landed final grammar has none.

## PROP-Auf-001.P13 Options

### PROP-Auf-001.P13.1 Contribution

| ID | Summary | Advantages | Costs |
| -- | ------- | ---------- | ----- |
| PROP-Auf-001.O1 | Keep `:contribute` as the canonical general form and treat authoring forms as optional convenience. | No migration; arbitrary callback logic remains possible; image mode already works. | Preserves two grammars, monolithic assembly, weak static discovery, and the impression that forms are secondary. Every new form must coexist indefinitely with raw maps. |
| PROP-Auf-001.O2 | Improve authoring forms but retain `:contribute` as an escape hatch. | Most spools gain ergonomic forms while unusual spools retain full freedom. | The escape hatch becomes the permanent answer to every missing feature. Forms never become complete, tooling must inspect both paths, and runtime-dependent declaration logic remains part of the contract. |
| PROP-Auf-001.O3 | Replace `:contribute` with a complete, replayable authoring-form protocol. | One grep-friendly extension grammar; local validation and documentation; equivalent publication semantics; extensible to future kinds; image declarations become inspectable data. | Requires parity work for overrides, image replay, generated entries, and kind bootstrapping; forces a breaking migration; rejects arbitrary runtime-dependent contribution callbacks. |
| PROP-Auf-001.O4 | Replace `:contribute` with one required top-level data manifest. | Static and replayable; no macros required; close to the normalized representation. | Recreates the monolith under a new name, separates declarations from implementations, and makes authors understand representation keys. It solves callback dynamism but not authoring ergonomics or co-location. |

### PROP-Auf-001.P13.2 Lifecycle

- **PROP-Auf-001.O5 — Keep the monolithic reconciler.** This preserves the smallest core and maximal domain freedom. It also preserves repeated status dispatch, opaque partial progress, bespoke retry, and setup/teardown drift. The current census provides enough repeated behavior to justify a shared surface.
- **PROP-Auf-001.O6 — Add only a status-validation helper.** A helper such as `(runtime/reconcile-status! ctx 'ns/reconcile)` removes error-map boilerplate but leaves resource boundaries and failure policy opaque. It treats the symptom rather than the callback's accumulated responsibilities.
- **PROP-Auf-001.O7 — Keep `:reconcile` as an escape hatch.** This weakens the migration and leaves two lifecycle models indefinitely. New spools would choose inconsistently, quality checks could not require the declarative shape, and the generic engine could not promise complete per-effect visibility. Rejected: the break is the mechanism that establishes one pattern.
- **PROP-Auf-001.O8 — One generic `defeffect`.** A single form with a `:kind` key is smaller but makes unlike lifecycle contracts branches of one open map grammar. Distinct forms give grep-visible intent, narrower specs, better errors, and room for kind-specific documentation. A shared internal normal form can still back them.
- **PROP-Auf-001.O9 — Automatic transaction and rollback.** Rollback cannot honestly cover external actions. The engine instead records partial progress, preserves handles, retries incomplete work, and attempts teardown. Domains may implement transactional acquisition where their boundary supports it.
- **PROP-Auf-001.O10 — Smaller or existing authoring surfaces.** TEN-004 requires each proposed form to earn a public name. A reaction could be a resource with no retained handle, or direct composition of an existing event/hook API; a separate `defreaction` earns its place only if one-way transition semantics and retry reporting cannot stay clear in that smaller shape. A resource cannot generally be replaced by `events/register-handler!` or `hooks/register-hook!`: those APIs own two particular registries, not arbitrary threads, subscriptions, notifiers, or external handles; without a declared boundary the coordinator cannot retain handles or report partial progress, so `defresource` is the strongest candidate for a distinct primitive. Desired-state convergence may be a constrained `defreconcile`, a mode of `defresource`, or a domain-owned function called by one of those forms; a separate `defconvergence` is not justified until Cron and another independent domain demonstrate shared trigger, retry, and removal semantics. A prototype should begin from the smallest surface that expresses Chime, Cron, Shell, a process-lifetime seed, and a workspace singleton, then add names only where the examples become less honest without them.

## PROP-Auf-001.P14 Recommendation

- **PROP-Auf-001.REC1:** Adopt **O3** for contributions. Authoring forms become the only spool contribution syntax, while the existing owner-partitioned contribution map remains an internal normalized representation.
- **PROP-Auf-001.REC2:** Make repeated top-level kind-specific forms the primary interface. Do not require users to wrap them in a list, a `do`, or a manifest.
- **PROP-Auf-001.REC3:** Define a small shared authoring protocol beneath the forms: validated declaration fragments, explicit override intent, replayable namespace-owned declaration data, and normalization to the existing contribution shape. Lifecycle forms collect through the same target-only module collector.
- **PROP-Auf-001.REC4:** Allow each capability domain to expose its own vocabulary. The five Skein core-kind forms live together in `skein.api.skein.alpha`, conventionally required `:as skein`; authors write `skein/defop`, `skein/defquery`, `skein/defpattern`, `skein/defhook`, and `skein/defhandler`. Their constructors and validation specs stay internal in `skein.core.contribution`. Forms for domain kinds remain exported by the spool that owns them, as `skein.spools.cron/defjob` and `skein.spools.workflow/defworkflow` already are. The forms share publication semantics without being forced through one generic user-facing `defentry`.
- **PROP-Auf-001.REC5:** Support genuinely generated declarations through the smallest factory-backed or batch authoring surface that satisfies P6.5. The implementing feature's plan must justify the exact public forms and functions; raw normalized contribution maps remain private to the publication boundary.
- **PROP-Auf-001.REC6:** Add a pre-publication authoring mechanism for open kind declarations so domain registries no longer need a `contribute` callback merely to establish their kind.
- **PROP-Auf-001.REC7:** Treat declaration sets as static source facts. Do not replace either callback with another arbitrary callback; in particular, a generic form that accepts a function returning an arbitrary multi-kind map would merely rename `:contribute` and fail the goal.
- **PROP-Auf-001.REC8:** For the lifecycle half, proceed to a bounded feasibility spike before accepting names or public shapes. Prototype the smallest lifecycle surface against Cron, Chime, the Shell executor, one process-lifetime seed, and one workspace singleton: together they cover desired-state reconciliation, atomic resource clusters, runtime-lifetime resources, one-way reactions, ordering, retained handles, and the current unconditional-removal defect. If the spike satisfies P16.2, return with the public specs, minimum-surface analysis, migration proof, and governing-record amendments needed to accept that half.
- **PROP-Auf-001.REC9:** Execute the cutover as the staged break in P15. After both surfaces are complete and every first-party and sibling spool has moved, remove `:contribute` and `:reconcile` from `skein.api.spool.alpha/::spool` and remove the `def spool` convention itself. Old declarations fail loudly with a migration error naming the replacement forms; no alias, silent fallback, or compatibility shim ships.

## PROP-Auf-001.P15 Staged cutover

TEN-000@1 permits the clean break, and the user has accepted it. The break is minimized by ordering, not by compatibility machinery: the authoring surface lands before anything migrates, and the removal lands after everything has.

This withdrawal breaks the `skein.api.*.alpha` accretion promise (SPEC-003.C19) where the entry-point grammar lives: `skein.api.spool.alpha`'s `::spool` shape (SPEC-003.C17c) and the coordinator resolution behind it (SPEC-004.C45/C46). As with the prior `def spool` cutover, whose exception the root spec already records beside SPEC-003.C19, the feature must record this exception explicitly rather than hide it behind a parallel alias namespace. Sequencing is settled by user direction (2026-07-28): skein-src will not stamp a v1 marker and stays alpha, so TEN-000@1 authorizes the break outright. The same ruling covers the sibling migrations — the siblings carry their own post-v1 markers, and their entry-point removals ship as recorded breaks rather than accretion (P15.S2).

- **PROP-Auf-001.S1 — Surface.** Ship the complete authoring surface while the old keys still resolve. For contributions: put the five core-kind forms in `skein.api.skein.alpha` and their declaration plumbing in `skein.core.contribution`; add explicit override intent, the replayable declaration record and image replay, the pre-publication kind declaration, and justified domain factory/batch paths for generated entries; keep domain forms with their owning spools; close the remaining expressiveness gaps (static entries, custom kinds, candidate validation, multi-namespace spools, empty kind-provider modules, provenance) without recreating an unrestricted module-wide callback; and make the forms testable through the production module path — macro expansion, collection, invalid declarations, duplicate and override behavior, activation, removal by omission, image mode, and plan/status projections all need public testing support. For lifecycle: run the REC8 spike, then implement the accepted forms, collection, validation, retained state, engine execution, and inspection.
- **PROP-Auf-001.S2 — Migration.** Convert every in-tree spool, the repo `.skein` config, examples, and tests to authoring forms; coordinate sibling spool releases and pin bumps so the selected source universe contains no `:contribute` or `:reconcile` declaration. Prove parity before the break: equal normalized contributions, exact removal by omission, and lossless lifecycle behavior for each migrated reconciler. Sibling migrations are developed and validated against skein-src main — the pinned spool-suite gate holds them green against this checkout — and ship as sibling marker releases consumed by pin bumps. Removing the old entry points from a sibling's published surface is a recorded break authorized by the ruling above, following the installer-retirement precedent with its per-release `bin/compat-alarm` evidence; no `:skein/min` floor machinery is involved because skein-src stamps no markers. The interval in which both grammars load on main is a migration window, not a supported authoring contract: docs and quality gates teach only the new grammar, no compatibility machinery ships, and within one module the grammars never mix — collected authoring forms and a `:contribute` entry point are mutually exclusive, already the shipped rule (SPEC-004.C46c), which the window keeps.
- **PROP-Auf-001.S3 — Removal.** Remove both keys and the public `def spool` convention from the `::spool` grammar and the convention resolver; delete callback resolution and retention once declaration retention covers removal-by-omission; update root specs, ADR lineage, the spool authoring guide, testing helpers, generated API docs, and quality checks; and reject a `def spool` var with an actionable error naming the replacement forms. No released version accepts the old grammar after this stage.

Work slicing, ownership, and landing order within each stage belong to the implementing feature's plan.

## PROP-Auf-001.P16 Acceptance conditions and feasibility gates

### PROP-Auf-001.P16.1 Contribution acceptance conditions

The contribution recommendation is ready to become the platform contract only if the implementing feature's plan can demonstrate:

- **PROP-Auf-001.AC1:** every core and shipped domain contribution kind has a named authoring form or a documented factory-backed batch form, with core-kind forms available from shipped API surface rather than repository-local `.skein` code;
- **PROP-Auf-001.AC2:** explicit overrides are expressible without raw contribution maps;
- **PROP-Auf-001.AC3:** source and image activation produce equal normalized contributions for the same loaded namespace;
- **PROP-Auf-001.AC4:** removal by omission remains exact after source refresh, including after a declaration form is deleted;
- **PROP-Auf-001.AC5:** open domain kinds are established before dependent entries stage, without a contribution callback;
- **PROP-Auf-001.AC6:** first-party spools and pinned external spool suites no longer rely on `:contribute`;
- **PROP-Auf-001.AC7:** after S3, old `spool` maps fail with a direct migration error rather than being ignored;
- **PROP-Auf-001.AC8:** public specs, API docstrings, `devflow/UBIQUITOUS-LANGUAGE.md`, the spool authoring guide, and discovery surfaces describe one contribution grammar;
- **PROP-Auf-001.AC9:** image activation fails loudly when the loaded namespace has no retained authoring record, and distinguishes that failure from an explicitly recorded empty declaration set;
- **PROP-Auf-001.AC10:** the implementing feature's plan inventories each proposed public authoring form or factory, justifies why direct registration or composition cannot supply its module lifecycle semantics, and assigns parity coverage for image replay, omission removal, overrides, and generated entries.

These are contract gates, not an implementation plan.

### PROP-Auf-001.P16.2 Lifecycle feasibility gates

The lifecycle half does not need every execution-policy detail settled to proceed to its spike, but the spike and the implementing feature's plan must satisfy these gates before the lifecycle forms are accepted or migration begins. None is presently known to make the direction impossible; cross-module triggers, retained runtime-stop cleanup, atomic clusters, and degraded-effect scheduling all have plausible implementation seams in the current coordinator. They remain claims to test, not settled design.

- **Lossless mapping:** every current in-tree, workspace, and pinned sibling reconciler maps losslessly to a proposed lifecycle form or identifies a concrete missing primitive. Guild deserves particular attention: it resets runtime-owned declarations and republishes them from one reconciler, and may expose a contribution/lifecycle boundary the three sketched forms do not cover.
- **Cross-module convergence triggers:** a convergence must rerun when its declared desired input changes even if its owning module declaration does not (the Cron case in P11.2). No trigger grammar is chosen yet.
- **Atomic lifecycle clusters:** forms must permit one effect to retain Chime's current single-lock transition (P11.1).
- **Runtime-lifetime resources:** the shell executor's worker pool survives module removal and closes at runtime stop. The proposed forms currently describe module lifetime only; a scope such as `:module` versus `:runtime`, or a separate form, must prove this behavior is expressible.
- **Retry outside contribution change:** retrying a degraded effect while its contribution is unchanged supersedes today's unchanged-skip rule (P8.2) and requires explicit amendments to SPEC-004.C46/C46b and ADR-003's retained constraints; a prototype must show how the coordinator schedules and reports the retry.
- **Replacement ordering:** when one effect id disappears and another appears over the same singleton, cleanup must precede acquisition or the new registration may collide with the old one. The engine needs a deterministic transition order or an explicit relationship.
- **Convergence removal:** removing a convergence declaration invokes its retained `:on-removed` contract and cannot leave its previously managed external state running. A prototype must prove retained resolution and removal ordering; it may propose a safe final-convergence alternative, but omission without cleanup is forbidden.
- **Convergence retry:** an opaque convergence function offers no checkpoints. The engine may rerun its whole boundary only under an idempotency or retry-safe contract; it cannot preserve progress inside the call.
- **Partial resource acquisition:** a thrown open call yields no handle. The resource form needs transactional acquisition or a mandatory, validated partial-cleanup protocol before it can claim leak-free teardown.
- **Coordinator-level behavior tests:** applied, unchanged, changed, removed, failed-open, failed-close, whole-boundary retry, dependency order, reverse teardown, and removal-by-omission all have coordinator-level tests. A failed dependent close blocks teardown of the dependencies it may still use while independent cleanup continues; a resource opened before a sibling failure is preserved on retry and closed on later module removal; a failed close retains enough state to retry cleanup.
- **Machine-checkable contracts:** public specs validate every declaration, callable context and result, normalized state, projection, phase, status, and diagnostic shape adopted by the feature. Prose examples are insufficient for a shipped boundary.
- **Publication and plan invariants:** contribution publication remains atomic and precedes lifecycle execution, and `plan` performs no lifecycle effect.
- **Minimum surface:** O10 records the required comparison with existing forms and composition. The three-form sketch is not yet justified as the minimum public API.
- **Reconcile terminology:** `defconvergence` may rename an existing published concept. It stays provisional until a prototype proves a distinct semantic boundary.
- **Vocabulary ownership:** the namespace owning lifecycle declarations, projected keys, status words, vocab publication, and generated API docs is unresolved and must be decided.
- **Governing records:** the implementing feature's plan enumerates the changes to ADR-002 (an explicit compatibility statement: lifecycle declarations stay printable symbol-valued data, no closures or runtime eval), ADR-003 Decisions A and D, ADR-004, SPEC-003.C17c/C17d/C19, SPEC-004.C45/C46/C46c, and SPEC-004.C74a (Chime's reconcile-owned handler contract becomes a concrete amendment and acceptance target, not a conceptual citation). It must also state SPEC-004.C46b's disposition explicitly: unchanged, split between coordinator mechanics and lifecycle-form semantics, or replaced. The existing "no generic effect callbacks in the registry kernel" boundary holds: the lifecycle engine may remain coordinator machinery rather than registry-kernel callbacks, but the distinction must be explicit.
- **Landed grammar:** the final grammar rejects the old `spool` var and names the replacement forms, and all in-tree and selected sibling sources have migrated before it lands.

## PROP-Auf-001.P17 Open questions

- **PROP-Auf-001.Q1:** Which viable retained representation best satisfies image replay and exact stale-declaration removal: metadata paired with an epoch or cleanup mechanism, a generated manifest Var, or a coordinator-owned snapshot associated with the loaded namespace?
- **PROP-Auf-001.Q2:** Resolved: normalized contribution data, core declaration constructors, and their specs stay internal in `skein.core.contribution`. The public core grammar is the five forms in `skein.api.skein.alpha`; domains justify their own factory or batch surface when generated authoring is a real use case.
- **PROP-Auf-001.Q3:** What should the kind-declaration authoring form be called, and which parts of a registry backend can honestly be static data rather than runtime-owned initialization?
- **PROP-Auf-001.Q4:** Should generated batch forms define inspectable Vars for each generated entry, or is a retained declaration record with source provenance sufficient?
- **PROP-Auf-001.Q5:** Which authoring forms belong in core API namespaces and which should be exported by the domain spool that owns the kind?
- **PROP-Auf-001.Q6:** Are `defreaction`, `defresource`, and `defconvergence` the right public names? The semantic split is proposed; naming should be tested against real migrations.
- **PROP-Auf-001.Q7:** Should reactions exist in the first release of the lifecycle surface, or should process-lifetime seeds use a narrower `defseed` form that communicates their deliberate lack of teardown?
- **PROP-Auf-001.Q8:** What exact declaration change forces resource replacement? Byte-identical normalized data is the baseline; doc-only or provenance-only changes may not warrant close/open.
- **PROP-Auf-001.Q9:** Should a failed resource open be allowed to return a cleanup handle through a sanctioned exception shape, or must partial acquisition always live in domain spool state?
- **PROP-Auf-001.Q10:** After one effect fails, should independent siblings continue applying? This proposal recommends stop-on-first-failure for application and attempt-all for removal; migration evidence may justify independent-subgraph continuation.
- **PROP-Auf-001.Q11:** Does `plan` resolve callable symbols, as current evaluation does, or show declaration diffs from collected data and leave resolution to refresh? It must remain side-effect free either way.
- **PROP-Auf-001.Q12:** How should code-only reload interact with retained open/close vars? The current reconciler is retained by symbol and re-resolved through the spool loader; lifecycle callables need an equally explicit generation and reload contract.
- **PROP-Auf-001.Q13:** Should convergence functions receive raw desired/actual values, or should the engine standardize a richer diff result? The first release should prefer raw values unless two migrations demonstrate a shared diff.
- **PROP-Auf-001.Q14:** Can a resource close depend on the old module contribution after publication removed it? The retained effect context may need the previous owner partition, not only `:module/previous`.
- **PROP-Auf-001.Q15:** How does a resource declare module lifetime versus weaver-runtime lifetime, and what happens on module removal for a runtime-lifetime resource?
- **PROP-Auf-001.Q16:** What publication fact triggers a convergence whose desired state is contributed by other modules?
- **PROP-Auf-001.Q17:** Is the candidate's required retained `:on-removed` callable the smallest honest convergence cleanup contract, or can a retained desired-state reader and an explicit empty desired value prove equivalent behavior?
- **PROP-Auf-001.Q18:** Which namespace owns the public specs, projected keys, status vocabulary, and generated API documentation for lifecycle declarations?
- **PROP-Auf-001.Q19:** Is desired-state convergence a distinct form, or should it retain the reconcile name and live behind a smaller generic lifecycle surface?
- **PROP-Auf-001.Q20:** Disposition of the overlapping in-flight features: does the `rrvnn`/`9snqu` chain complete its `def spool` conversions as the stepping stone (recommended: a uniform universe migrates mechanically), or do late items re-target directly to authoring forms? Decide when the first migration feature is cut.

## PROP-Auf-001.P18 Consequences

- **PROP-Auf-001.C1 — One semantic path per half.** All spool capabilities reach publication as normalized owner-complete partitions, and all lifecycle behavior reaches execution as declared effects through one engine. Macros, pure factories, and batch forms differ only in authoring ergonomics.
- **PROP-Auf-001.C2 — Better source quality.** Names, docs, handlers, schemas, override intent, and setup/teardown pairing are co-located. Forms can generate consistent Var metadata and discovery data, refuse unknown options, and report malformed declarations at the source construct.
- **PROP-Auf-001.C3 — Better inspection.** Tooling can enumerate retained declarations without executing an arbitrary callback, and refresh, `plan`, and `status` can report effect-level outcomes. The namespace itself becomes an index of its extension surface.
- **PROP-Auf-001.C4 — Better grep patterns.** A small family of `def*` forms becomes the single pattern for finding extension points. New domains can add a form without adding another key to a `spool` map.
- **PROP-Auf-001.C5 — A real breaking migration.** Existing spools must move every contribution entry to an authoring form or factory-backed batch form, and every reconciler to lifecycle declarations. Kind bootstrapping moves to the pre-publication kind declaration. Runtime-dependent contribution selection must be redesigned as explicit declarations or rejected. Singleton bindings without a removal operation must gain one or be deliberately classified as process-lifetime reactions.
- **PROP-Auf-001.C6 — Replay becomes contractual.** Retained declaration data is no longer an optional macro implementation detail. Source mode, image mode, and module refresh must agree on one declaration set, including removal after source omission.
- **PROP-Auf-001.C7 — Direct registration remains distinct.** `(graph/register-query! runtime 'mine ...)` remains useful for imperative REPL work. It does not gain owner-complete spool lifecycle semantics merely because an equivalent `defquery` exists.
- **PROP-Auf-001.C8 — The coordinator grows.** It owns an effect DAG, retained handles, per-effect state, transition classification, retry, and removal. That complexity is not new behavior; it is the common part of behavior currently repeated or omitted in spool callbacks. Centralizing it makes failures inspectable and policies consistent.
- **PROP-Auf-001.C9 — Tests use the production path.** Modules activate through the ordinary coordinator path and tests assert effect-level outcomes. Resource fixtures no longer call a public reconciler directly with fabricated contribution statuses, and test helpers can inspect lifecycle state without exposing handles.
- **PROP-Auf-001.C10 — Existing decisions need explicit amendment.** ADR-004's public `spool` var convention is superseded entirely; ADR-003's owner-partition and image-no-source-evaluation contracts are preserved and its retry-related constraints amended per P16.2; ADR-002's printable-declaration constraints are preserved and restated for lifecycle declarations. New forms keep a high bar afterward: one is added only when several modules repeat a declaration and execution contract the existing forms cannot express cleanly.

## PROP-Auf-001.P19 Program constraints for slicing

Constraints the post-sign-off feature breakdown must hold, stated once so cards need not rediscover them: the P15.S1 surface lands before any migration feature; the lifecycle spike (REC8) lands before any lifecycle-surface feature; no migration feature starts on a sibling until the forms it needs have landed on skein-src main (siblings validate against main through the pinned suite gate); the removal (P15.S3) is one feature and lands last, after the selected source universe is clean; features touching the same module own disjoint slices. Everything else — ordering within stages, worktrees, delegation — belongs to the cards and their plans.

## PROP-Auf-001.P20 Outcome

- **PROP-Auf-001.OUT1:** Approved by ct at the devflow checkpoint on 2026-07-29. The approved ruling is REC1–REC9: replace `:contribute` with a complete family of replayable contribution authoring forms, run the bounded lifecycle spike and accept lifecycle forms only through the P16.2 gates, and execute the staged TEN-000@1 cutover in P15 so the `def spool` convention is removed in one final break after every first-party and sibling spool has migrated.
