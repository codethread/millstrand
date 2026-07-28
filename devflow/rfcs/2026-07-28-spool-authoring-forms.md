# Replace `:contribute` with authoring forms

**Document ID:** `RFC-Saf-001` **Status:** Open **Date:** 2026-07-28 **Related:** [ADR-003: spool activation lifecycle](../adrs/0003-spool-activation-lifecycle.md), [ADR-004: `def spool` convention](../adrs/0004-def-spool-convention.md), [RFC-020: readability macros](2026-07-08-skein-readability-macros.md), [writing shared spools](../../docs/spools/writing-shared-spools.md), forthcoming RFC on replacing `:reconcile`

## RFC-Saf-001.P1 Summary

Skein should replace the monolithic `(def spool {:contribute 'contribute})` contribution path with a complete family of top-level authoring forms. A spool should declare each capability where it is defined:

```clojure
(defop help ...)
(defquery mine ...)
(defpattern release ...)
(defworkflow land ...)
(defjob nvd-scan ...)
```

These forms are not a second publication model. They are the author-facing syntax for producing the same owner-complete contribution data that `:contribute` produces today. The runtime should continue to validate, stage, collide, override, publish, and remove entries through one normalized contribution contract.

This is a breaking replacement, not a compatibility layer. Once the authoring surface covers the full contribution contract, `:contribute` should cease to be an accepted spool entry point. First-party and downstream spools must migrate rather than retain two equivalent grammars.

This RFC does not remove `:reconcile`. A separate RFC is being raised for that decision. During the interval between the two changes, a module with live effects may still expose `(def spool {:reconcile 'reconcile})`; a module with declarations only needs no `spool` var. If both RFCs are accepted, the final surface has no `(def spool {:contribute ... :reconcile ...})` convention at all: Skein extensions are authored through named top-level forms.

## RFC-Saf-001.P2 Problem

Skein currently presents two ways to express one idea.

The callback style collects every entry into a single return value:

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

Both eventually mean the same thing: this module owner contributes a named entry to a registry kind. The callback is therefore not a distinct domain concept. It is an assembly mechanism exposed as public authoring syntax.

That mechanism has several costs:

- **RFC-Saf-001.P2.1 — It centralizes unrelated declarations.** An op's handler and contract may be defined hundreds of lines from the map entry that publishes it. Names and implementation symbols are repeated, so they can drift.
- **RFC-Saf-001.P2.2 — It exposes publication representation.** Authors must know about `:entries`, `:overrides`, contribution kind keys, and the precise nesting of the runtime's normalized partition.
- **RFC-Saf-001.P2.3 — It creates two extension grammars.** Some capabilities read as `defop` or `defworkflow`; others appear as data in `contribute`. A reader must search for both patterns to discover what a spool adds.
- **RFC-Saf-001.P2.4 — It weakens local validation.** A malformed entry assembled in one large callback is naturally reported at contribution evaluation or staging. An authoring form can validate the declaration at the named source form and retain the name in its error data.
- **RFC-Saf-001.P2.5 — It makes ergonomic helpers look secondary.** The current forms already drive the same collector and publication kernel, but the presence of the general callback makes them appear to be optional convenience wrappers over the “real” API.
- **RFC-Saf-001.P2.6 — It encourages runtime-dependent declaration logic.** Because `contribute` receives the runtime and module context, it can choose capabilities dynamically. That conflicts with the intended model: a spool's declarations are static source facts, while runtime effects belong to lifecycle handling.

The split also leaves gaps in the authoring forms themselves. Today `collect-entry!` can record explicit override intent, but the workspace-local `defop` form cannot express it. Source evaluation can collect top-level forms, but image loading deliberately evaluates no source and therefore relies on the retained `:contribute` callback. Some domain spools use `contribute` to establish a registry kind before dependent entries publish. These are real differences in the current implementation, but none requires `:contribute` as a permanent authoring abstraction. They identify the behavior a complete authoring-form design must preserve.

The current surface is also split by ownership. `defop`, `defquery`, `defpattern`, and `defrule` are prototypes in this repository's `.skein` workspace, not shipped `skein.api.*` forms, and they call an internal collector. `defworkflow` and `defjob` are shipped by their domain spools and already call the blessed `skein.api.runtime.alpha/collect-entry!`. A complete replacement therefore includes promoting supported forms for core kinds into shipped API surface while domain-specific forms remain owned by their spools. This RFC decides that scope; the feature proposal decides exact namespaces and signatures.

## RFC-Saf-001.P3 Mental model

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

“Pure authoring forms” in this RFC means that capability declaration is expressed only through those forms, rather than through an arbitrary runtime callback. It does not mean every form must be a macro or that macro expansion itself is side-effect free. Pure factory functions may sit underneath the forms, but the public semantic boundary is the declaration data they produce.

The original direct registration APIs remain a separate layer:

```clojure
(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

This still works because direct/REPL registration is an imperative, additive, top-precedence operation against one runtime. It is intentionally outside module refresh. It has no spool owner whose complete partition can be replaced, replayed, or removed by omission. Putting the same query in a spool therefore needs an authoring declaration such as `defquery`; that form supplies module lifecycle semantics, not merely a shorter spelling of `register-query!`.

## RFC-Saf-001.P4 Goals

- **RFC-Saf-001.G1:** Establish one visible grammar for Skein extensions: named, top-level authoring forms.
- **RFC-Saf-001.G2:** Preserve the complete contribution semantics available today, including owner-complete replacement, removal by omission, explicit override intent, open registry kinds, and registry-owned validation.
- **RFC-Saf-001.G3:** Keep ordinary source files Emacs-like and sequential. Authors write repeated forms such as `(defop one ...)` and `(defop two ...)`; they do not maintain a separate list of forms or a trailing manifest.
- **RFC-Saf-001.G4:** Make declarations easy to scan, grep, navigate, inspect, and reload. A capability's name, documentation, implementation, and publication contract should be co-located.
- **RFC-Saf-001.G5:** Make image loading and source loading observe the same declarations even though image mode does not evaluate source.
- **RFC-Saf-001.G6:** Permit future capability kinds to add purpose-built forms without widening the spool entry-point map.
- **RFC-Saf-001.G7:** Improve error locality and generated documentation by giving each kind a form that understands its complete schema.
- **RFC-Saf-001.G8:** Remove `:contribute` decisively once parity exists, following the repository's alpha breaking-change policy rather than preserving an indefinite dual surface.

## RFC-Saf-001.P5 Non-goals

- **RFC-Saf-001.NG1:** This RFC does not decide how to replace `:reconcile`, nor whether all live-resource lifecycle work can be expressed declaratively. That is the subject of a separate RFC.
- **RFC-Saf-001.NG2:** This RFC does not change the owner-partitioned registry kernel, contribution normalization, collision policy, publication atomicity, or removal-by-omission semantics.
- **RFC-Saf-001.NG3:** This RFC does not turn direct registry functions such as `register-query!` into declarations. They remain useful sharp tools for REPL work and runtime-owned imperative operations; they are not the spool authoring contract.
- **RFC-Saf-001.NG4:** This RFC does not require one generic macro for every kind. A common implementation protocol should support kind-specific forms with vocabulary and validation appropriate to their domain.
- **RFC-Saf-001.NG5:** This RFC does not preserve arbitrary runtime-dependent capability selection performed inside a `contribute` callback. Retaining such a callback under another name would retain the design this RFC removes.
- **RFC-Saf-001.NG6:** This RFC does not prescribe the private storage mechanism for replayable declarations. It specifies the observable source- and image-loading behavior that mechanism must provide.
- **RFC-Saf-001.NG7:** This RFC does not approve a final inventory of public macros, factories, or batch forms. Names in examples describe existing precedent or candidate syntax; every new public Var still requires a TEN-004 justification in the feature proposal.

## RFC-Saf-001.P6 Required semantics

### RFC-Saf-001.P6.1 Repeated forms are the normal source shape

Each declaration stands alone:

```clojure
(defop one ...)
(defop two ...)
(defquery mine ...)
```

No enclosing list is needed because each macro expansion can both define the ordinary Clojure Var or function and leave a replayable declaration record. The runtime assembles all records owned by the module into one owner-complete contribution before publication.

This distinction matters. The source is a sequence of definitions; the normalized contribution is a map. Authors should not have to mirror the runtime's batch representation in order to write sequential source.

### RFC-Saf-001.P6.2 Forms cover the entire contribution record

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

### RFC-Saf-001.P6.3 Forms are replayable in image mode

`:load :image` is an activation mode, not a test mode. It trusts an already-loaded namespace and does not evaluate its source. The current ephemeral collector therefore sees no authoring forms in image mode, while a `:contribute` symbol remains callable.

Removing `:contribute` requires authoring forms to leave declaration data in the loaded namespace. Image evaluation must be able to reconstruct the same owner-complete normalized contribution from that retained data without evaluating source and without invoking arbitrary spool code.

The retained representation may use metadata as part of its implementation, but Var or namespace metadata alone is insufficient because deleting a source form does not unmap its old Var during reload. A viable design therefore needs an epoch or cleanup mechanism, a generated namespace-owned manifest, a coordinator-owned snapshot, or another representation that can prove the current declaration set. This RFC does not choose among those viable designs. Whichever mechanism is chosen must satisfy five observable properties:

1. source collection and image replay produce equivalent normalized contributions;
2. declaration order does not alter the resulting partition except for the existing deterministic same-kind/same-key replacement rule;
3. removing a form from source removes its entry after the next source refresh rather than leaving stale declaration metadata;
4. ordinary code-only reloads do not publish a new partition outside module refresh;
5. an image namespace with no retained authoring record fails loudly, while an explicitly recorded empty declaration set remains distinguishable from missing or stale replay data.

Unit tests may source-load namespaces containing authoring forms today. The missing image behavior is therefore not an inability to test macros; it is a replay gap in one activation path.

### RFC-Saf-001.P6.4 Open kinds have an authoring path

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

This ordering requires new coordinator lifecycle machinery rather than a richer macro expansion. The RFC records that architectural cost so an infeasible kind-bootstrap story cannot hide behind “authoring sugar”; the feature proposal owns the exact phase and whether today's spool-state discovery seam survives.

### RFC-Saf-001.P6.5 Pure factories support generated declarations

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

The constructor and batch names above are illustrative rather than accepted public surface. The requirement is that generated declarations have one validated, replayable path; the feature proposal must choose and justify the smallest surface that provides it.

The batch form is not required around ordinary `defop` forms. Its purpose is to cross the compile-time authoring boundary for generated entries while retaining validation and replay. A bare top-level function call that merely returns a map cannot declare anything by itself; either an authoring form must collect the returned fragments or a macro must expand them into retained declarations.

### RFC-Saf-001.P6.6 Declarations are static source facts

The current callback receives `{:runtime ... :module/key ... :module/declaration ...}` and can compute a different partition from ambient runtime state. The replacement deliberately does not preserve that freedom.

A spool's capability set should be derivable from its source declarations and explicit authoring data. Runtime context may be used later to resolve or execute a published capability, but it must not silently decide whether the capability exists. Conditional declarations must be explicit data understood by a kind, or separate module declarations selected by the workspace. Arbitrary runtime-dependent selection would require a callback equivalent to `contribute` and defeat image replay, inspection, and static discovery.

## RFC-Saf-001.P7 Options

| ID | Summary | Advantages | Costs |
| -- | ------- | ---------- | ----- |
| RFC-Saf-001.O1 | Keep `:contribute` as the canonical general form and treat authoring forms as optional convenience. | No migration; arbitrary callback logic remains possible; image mode already works. | Preserves two grammars, monolithic assembly, weak static discovery, and the impression that forms are secondary. Every new form must coexist indefinitely with raw maps. |
| RFC-Saf-001.O2 | Improve authoring forms but retain `:contribute` as an escape hatch. | Most spools gain ergonomic forms while unusual spools retain full freedom. | The escape hatch becomes the permanent answer to every missing feature. Forms never become complete, tooling must inspect both paths, and runtime-dependent declaration logic remains part of the contract. |
| RFC-Saf-001.O3 | Replace `:contribute` with a complete, replayable authoring-form protocol. | One grep-friendly extension grammar; local validation and documentation; equivalent publication semantics; extensible to future kinds; image declarations become inspectable data. | Requires parity work for overrides, image replay, generated entries, and kind bootstrapping; forces a breaking migration; rejects arbitrary runtime-dependent contribution callbacks. |
| RFC-Saf-001.O4 | Replace `:contribute` with one required top-level data manifest. | Static and replayable; no macros required; close to the normalized representation. | Recreates the monolith under a new name, separates declarations from implementations, and makes authors understand representation keys. It solves callback dynamism but not authoring ergonomics or co-location. |

## RFC-Saf-001.P8 Recommendation

- **RFC-Saf-001.REC1:** Adopt **O3**. Authoring forms become the only spool contribution syntax, while the existing owner-partitioned contribution map remains an internal normalized representation.
- **RFC-Saf-001.REC2:** Make repeated top-level kind-specific forms the primary interface. Do not require users to wrap them in a list, a `do`, or a manifest.
- **RFC-Saf-001.REC3:** Define a small shared authoring protocol beneath the forms: validated declaration fragments, explicit override intent, replayable namespace-owned declaration data, and normalization to the existing contribution shape.
- **RFC-Saf-001.REC4:** Allow each capability domain to expose its own vocabulary. Promote supported forms for core kinds from repository-local prototypes into shipped API surface, while forms for domain kinds remain exported by the spool that owns them. `defop`, `defquery`, `defpattern`, `defworkflow`, `defjob`, and `defrule` should share publication semantics without being forced through one generic user-facing `defentry`.
- **RFC-Saf-001.REC5:** Support genuinely generated declarations through the smallest factory-backed or batch authoring surface that satisfies P6.5. The feature proposal must justify the exact public forms and functions; raw normalized contribution maps remain private to the publication boundary.
- **RFC-Saf-001.REC6:** Add a pre-publication authoring mechanism for open kind declarations so domain registries no longer need a `contribute` callback merely to establish their kind.
- **RFC-Saf-001.REC7:** Treat declaration sets as static source facts. Do not replace `contribute` with another arbitrary callback.
- **RFC-Saf-001.REC8:** Make the cutover breaking. After the complete forms and replay contract exist and first-party spools have moved, remove `:contribute` from `skein.api.spool.alpha/::spool`, reject old declarations loudly, and update the specs and authoring guide to describe only the new grammar. No released version accepts both grammars, and no alias, silent fallback, or compatibility shim ships. Coordinating core and sibling migrations before that breaking release is landing work, not a public compatibility window; its exact order belongs in the feature proposal.

## RFC-Saf-001.P9 Author experience

A declaration-only spool should read as ordinary definitions:

```clojure
(ns acme.delivery
  (:require [skein.api.ops.alpha :refer [defop]]
            [skein.api.patterns.alpha :refer [defpattern]]
            [skein.api.queries.alpha :refer [defquery]]))

(defquery mine
  "Work owned by ct."
  {:usage "strand ready --query mine"}
  [:= [:attr :owner] "ct"])

(defop ship
  "Ship the selected release."
  {:arg-spec ship-arg-spec}
  [ctx]
  (ship! ctx))

(defpattern release
  "Create a release strand."
  {:input release-input}
  ...)
```

There is no `contribute` function and no `spool` var. Grepping for `defop`, `defquery`, or `defpattern` finds the extension points directly.

During the interim in which reconciliation remains callback-based, a spool with live resources may end with:

```clojure
(def spool
  {:reconcile 'reconcile})
```

That residual form belongs to the separate reconcile decision. It is not a reason to retain `:contribute`. If the reconcile RFC reaches the same authoring-form conclusion, this final map disappears and the namespace consists entirely of ordinary Clojure definitions plus explicit Skein authoring forms.

## RFC-Saf-001.P10 Consequences

- **RFC-Saf-001.C1 — One semantic path.** All spool capabilities reach publication as normalized owner-complete partitions. Macros, pure factories, and batch forms differ only in authoring ergonomics.
- **RFC-Saf-001.C2 — Better source quality.** Names, docs, handlers, schemas, and override intent are co-located. Forms can generate consistent Var metadata and discovery data, refuse unknown options, and report malformed declarations at the source construct.
- **RFC-Saf-001.C3 — Better inspection.** Tooling can enumerate retained declarations without executing an arbitrary contribution callback. The namespace itself becomes an index of its extension surface.
- **RFC-Saf-001.C4 — Better grep patterns.** A small family of `def*` forms becomes the single pattern for finding extension points. New domains can add a form without adding another key to `def spool`.
- **RFC-Saf-001.C5 — A real breaking migration.** Existing spools using `(def spool {:contribute ...})` must move every entry to an authoring form or factory-backed batch form. Spools that perform kind bootstrapping in `contribute` must move that responsibility to the pre-publication kind declaration mechanism. Runtime-dependent contribution selection must be redesigned as explicit declarations or rejected.
- **RFC-Saf-001.C6 — Replay becomes contractual.** Retained declaration data is no longer an optional macro implementation detail. Source mode, image mode, and module refresh must agree on one declaration set, including removal after source omission.
- **RFC-Saf-001.C7 — Direct registration remains distinct.** `(graph/register-query! runtime 'mine ...)` can remain useful for imperative REPL work. It does not gain owner-complete spool lifecycle semantics merely because an equivalent `defquery` exists.
- **RFC-Saf-001.C8 — `def spool` becomes transitional.** Acceptance of this RFC removes its `:contribute` key. The form may temporarily remain for `:reconcile`; the parallel reconcile RFC decides whether the convention disappears entirely.
- **RFC-Saf-001.C9 — Existing decisions need explicit amendment.** ADR-004 currently makes the public `spool` var the image-mode entry-point convention. The accepted outcome of this RFC must supersede that contribution portion while preserving ADR-003's owner-partition and image-no-source-evaluation contracts.

## RFC-Saf-001.P11 Acceptance conditions

The recommendation is ready to become the platform contract only if the feature proposal can demonstrate:

- **RFC-Saf-001.AC1:** every core and shipped domain contribution kind has a named authoring form or a documented factory-backed batch form, with core-kind forms available from shipped API surface rather than repository-local `.skein` code;
- **RFC-Saf-001.AC2:** explicit overrides are expressible without raw contribution maps;
- **RFC-Saf-001.AC3:** source and image activation produce equal normalized contributions for the same loaded namespace;
- **RFC-Saf-001.AC4:** removal by omission remains exact after source refresh, including after a declaration form is deleted;
- **RFC-Saf-001.AC5:** open domain kinds are established before dependent entries stage, without a contribution callback;
- **RFC-Saf-001.AC6:** first-party spools and pinned external spool suites no longer rely on `:contribute`;
- **RFC-Saf-001.AC7:** old `spool` maps containing `:contribute` fail with a direct migration error rather than being ignored;
- **RFC-Saf-001.AC8:** public specs, API docstrings, `devflow/UBIQUITOUS-LANGUAGE.md`, the spool authoring guide, and discovery surfaces describe one contribution grammar;
- **RFC-Saf-001.AC9:** image activation fails loudly when the loaded namespace has no retained authoring record, and distinguishes that failure from an explicitly recorded empty declaration set;
- **RFC-Saf-001.AC10:** the feature proposal inventories each proposed public authoring form or factory, justifies why direct registration or composition cannot supply its module lifecycle semantics, and assigns parity coverage for image replay, omission removal, overrides, and generated entries.

These are contract gates, not an implementation plan. Work slicing, ownership, and landing order belong in the later feature proposal.

## RFC-Saf-001.P12 Open questions

- **RFC-Saf-001.Q1:** Which viable retained representation best satisfies image replay and exact stale-declaration removal: metadata paired with an epoch or cleanup mechanism, a generated manifest Var, or a coordinator-owned snapshot associated with the loaded namespace?
- **RFC-Saf-001.Q2:** Should the shared factory protocol be public and generic, or should only kind-specific constructors such as `op/entry` and `query/entry` be public while normalization stays internal?
- **RFC-Saf-001.Q3:** What should the kind-declaration authoring form be called, and which parts of a registry backend can honestly be static data rather than runtime-owned initialization?
- **RFC-Saf-001.Q4:** Should generated batch forms define inspectable Vars for each generated entry, or is a retained declaration record with source provenance sufficient?
- **RFC-Saf-001.Q5:** Which authoring forms belong in core API namespaces and which should be exported by the domain spool that owns the kind?

## RFC-Saf-001.P13 Outcome

- **RFC-Saf-001.OUT1:** Pending explicit decision. The proposed ruling is O3 with REC1–REC8: replace `:contribute` with a complete family of replayable authoring forms, make the removal a deliberate breaking change, and leave `:reconcile` to its separate RFC.
