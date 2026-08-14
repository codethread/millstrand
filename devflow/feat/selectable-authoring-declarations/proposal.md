# Selectable authoring declarations proposal

**Document ID:** `PROP-Sad-001`
**Status:** Approved
**Approved:** 2026-08-13
**Implementation reconciliation:** 2026-08-14
**Related RFCs:** [`RFC-Saf-001`](../../rfcs/2026-07-28-spool-authoring-forms.md), [`RFC-Laf-001`](../../rfcs/2026-07-28-lifecycle-authoring-forms.md)
**Related proposals:** [`PROP-Auf-001`](../../archive/26-08-07__authoring-forms/proposal.md), [`PROP-Rgs-001`](../../archive/26-08-07__registration-surfaces/proposal.md)
**Related root specs:** [`SPEC-003`](../../specs/repl-api.md), [`SPEC-004`](../../specs/daemon-runtime.md), [`SPEC-005`](../../specs/alpha-surface.md)
**Related brief:** [brief.md](./brief.md) (epic `z2yhh`, intake card `j2dj2`)
**Configuration identification:** `Sad` abbreviates selectable authoring declarations. A workspace-wide scan found no earlier `PROP-Sad` ID, so this feature takes `PROP-Sad-001`. Nested IDs carry the complete document ID.

Once approved this document is frozen. It records the intent agreed at sign-off. Later implementation change belongs in spec deltas, the plan, and code.

## PROP-Sad-001.P1 Problem

Millstrand authoring forms currently make two decisions in one expression: they define a Clojure Var and, while a module source collector is active, contribute a declaration to that module's owner partition. This works for a spool whose entry point intends to publish everything it defines. It does not work for a library that wants to offer several ops, queries, workflows, lifecycle effects, or domain declarations while leaving selection to each consumer.

A consumer can call the low-level registration APIs, but that forces it to reconstruct names, argument specifications, handler symbols, documentation, override intent, and kind-specific entry shapes that the library already authored. Those calls also write direct, weaver-lifetime registrations instead of durable consumer-owned module contributions. The missing operation is narrower: contribute this existing declaration Var as part of my module.

The authoring families are inconsistent at that boundary. Core forms define and collect together. `defop` defines a synthesized `<name>-op` Var rather than the supplied name. Chime's `defrule` and Workflow's `defexecutor` also synthesize handler names. Cron's `defjob` collects an entry without defining a declaration Var. Workflow happens to expose `static-definition`, and lifecycle exposes declaration constructors, but there is no ecosystem convention a library author or consumer can rely on across kinds.

The previous authoring-form program intentionally made `def*` mean durable module publication. That was the correct replacement for `def spool`, but it treated definition and selection as inseparable. This proposal supersedes that part of `PROP-Auf-001` and the `PROP-Rgs-001.G2` statement that every `def*` is necessarily module-owned and durable. It leaves the owner-partitioned publication kernel and the direct registration verb matrix intact.

## PROP-Sad-001.P2 Goals

- **PROP-Sad-001.G1:** Separate reusable declaration definition from consumer-owned module contribution for every supported authoring kind.
- **PROP-Sad-001.G2:** Establish one visible three-form convention: `def<kind>` defines, `use-<kind>!` contributes, and `def<kind>!` does both.
- **PROP-Sad-001.G3:** Let consumers select library declarations without restating their keys, argument vectors, bodies, specs, callables, documentation, or normalized registry entries.
- **PROP-Sad-001.G4:** Keep the declaration kind explicit at every selection site so source search, generated API docs, lint output, and failures teach the same vocabulary.
- **PROP-Sad-001.G5:** Give domain spools one supported way to define the same three-form family for open registry kinds.
- **PROP-Sad-001.G6:** Preserve owner-complete publication, override intent, image replay, code-only reload, removal by omission, lifecycle ordering, and each kind's existing binding moment.
- **PROP-Sad-001.G7:** Make the break directly under TEN-000@1 and migrate only the repositories the user authorized.

## PROP-Sad-001.P3 Non-goals

- **PROP-Sad-001.NG1:** No generic `use!`. Selection always names its kind through `use-op!`, `use-query!`, `use-workflow!`, `use-rule!`, or the corresponding domain form.
- **PROP-Sad-001.NG2:** No compatibility alias, dual-semantics release, legacy namespace, runtime feature probe, deprecation period, or adapter that preserves the old publishing meaning of unbanged `def*`.
- **PROP-Sad-001.NG3:** No change to explicit-runtime or `millstrand.repl` `register/replace/unregister-*!` functions. They remain the imperative direct-owner surface.
- **PROP-Sad-001.NG4:** No declaration renaming at selection time. A different public key is a different declaration and needs an explicitly named wrapper.
- **PROP-Sad-001.NG5:** No bundle, declaration-set, wildcard-selection, namespace-selection, or automatic "use every declaration" form.
- **PROP-Sad-001.NG6:** No migration or patch to a spool repository outside Millstrand, Millhouse, agent-harness, devflow, and codethread. Consumers may select declarations from another library in their own authorized module without changing that library repository.
- **PROP-Sad-001.NG7:** No first-release lint policy that attempts to prove whether `def<kind>!` or `use-<kind>!` appears in a configured module source. The macro analysis needed for correct binding and arity checking remains in scope; location policy may follow later.
- **PROP-Sad-001.NG8:** No early weaver restart while repository releases or workspace pins still expose a mixed authoring API.

## PROP-Sad-001.P4 Proposed scope

- **PROP-Sad-001.S1:** Every declaration Var carries one validated, printable authoring descriptor owned by a shared Millstrand protocol. The descriptor identifies the declaration family and kind, public key, source Var, collection channel, normalized entry material, and any data required for retained image replay. The Var's root remains the natural Clojure value for its kind.
- **PROP-Sad-001.S2:** `def<kind>` defines an inert reusable declaration and never contributes it. It defines exactly the supplied name. Function-backed forms bind their handler at that name; value-backed forms bind their declaration data at that name. Forms that currently synthesize a public handler name or define no Var adopt this rule as part of the break.
- **PROP-Sad-001.S3:** `use-<kind>!` accepts one or more declaration Vars from its own family and contributes them to the current module source. Registry-family use forms take an optional leading use-options map closed to `:override?`; lifecycle use forms accept declaration Vars only. Passing a Var from another family, a Var without authoring metadata, an invalid descriptor, or the same effective key twice in one use form fails loudly at that form. Separate top-level registry use forms retain the current collector rule from `SPEC-003.C17`: repeating a kind and key replaces the earlier contribution deterministically, and the later selection's override intent is the one retained.
- **PROP-Sad-001.S4:** `def<kind>!` is the permanent define-and-contribute shorthand for workspace-owned declarations and spool entry points. Its definition contract and return value are the same as `def<kind>`: it returns the installed Var after selecting it once. Any selection-only option is routed to the generated `use-<kind>!` step rather than stored as library declaration intent; the selected-Var vector returned by a standalone use form is not the bang form's outward return value.
- **PROP-Sad-001.S5:** Selection is passive outside module collection, matching current authoring-form behavior. Direct REPL evaluation and `runtime/reload-code!` may define or redefine Vars without changing live owner partitions. Source collection and retained image replay publish the consumer module's complete selected partition, and omission remains removal.
- **PROP-Sad-001.S6:** `millstrand.api.authoring.alpha/defauthoring` is the domain-author utility. It defines a kind's `def<kind>`, `use-<kind>!`, and `def<kind>!` public macros over the shared descriptor and collector contract. Core and lifecycle forms use the same underlying contract; external domains own their typed names and declaration validation.
- **PROP-Sad-001.S7:** The core families are `defop`/`use-op!`/`defop!`, `defquery`/`use-query!`/`defquery!`, `defpattern`/`use-pattern!`/`defpattern!`, `defhook`/`use-hook!`/`defhook!`, `defhandler`/`use-handler!`/`defhandler!`, and `defbin`/`use-bin!`/`defbin!`.
- **PROP-Sad-001.S8:** The lifecycle families are `defseed`/`use-seed!`/`defseed!`, `defresource`/`use-resource!`/`defresource!`, and `defreconcile`/`use-reconcile!`/`defreconcile!`. Selecting a lifecycle declaration makes the consumer module its lifecycle owner without changing the callable's source provenance.
- **PROP-Sad-001.S9:** Millhouse applies the convention to Workflow's `workflow` and `executor` kinds, Chime's `rule` kind, Cron's `job` kind, and every core or lifecycle declaration in that repository. Its public domain surfaces become `defworkflow`/`use-workflow!`/`defworkflow!`, `defexecutor`/`use-executor!`/`defexecutor!`, `defrule`/`use-rule!`/`defrule!`, and `defjob`/`use-job!`/`defjob!`.
- **PROP-Sad-001.S9a:** Cron's post-break definition grammar is `(defjob name doc job)`, where `name` is an unqualified symbol and `doc` is the Var docstring. Its descriptor and normalized job value derive the registry id as `(keyword name)`. Selection is `(use-job! declaration-var ...)` or `(use-job! {:override? true} declaration-var ...)`. The bang grammar is `(defjob! name doc job)` or `(defjob! name doc {:override? true} job)`; the options map applies only to its generated selection. For example, `(defjob nvd-scan "Scan NVD." job)` defines the Var `nvd-scan` with registry id `:nvd-scan` and contributes nothing.
- **PROP-Sad-001.S10:** The migration universe is closed to five repositories: Millstrand, Millhouse, agent-harness, devflow, and codethread. Each migrates publishing entry points to bang forms and uses the split forms where it deliberately exposes selectable declarations. The selected workspace pins annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669` and activates its published `ct.spools.kanban` module. That module provides ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash`. Guild, peering operations, and lifecycle resources are out of scope for the Millstrand surface. A disposable fresh-generation acceptance test proves the selected v26 surface and retained-image replay before any real weaver restart. The v26 full repository suite is part of the final external spool gate. No Kanban source is changed in this repository, and no compatibility bridge is added.
- **PROP-Sad-001.S11:** Public specs, authoring tests, disposable-world integration tests, image replay tests, consumer proofs, generated API docs, guides, examples, and clj-kondo exports move to the new semantics. Tests prove that requiring a library defines Vars without publication, typed use publishes under the consumer owner, omission retracts the selection, and bang forms remain the compact publishing path.
- **PROP-Sad-001.S12:** The coordinated cutover publishes the changed authorized sibling repositories, moves the selected Millstrand workspace to their new coordinates, verifies that no authorized source still relies on unbanged publication, and only then restarts affected weavers. The new generation must load one coherent authoring API; restarting earlier is outside the authorized sequence.

## PROP-Sad-001.P5 Examples

- **PROP-Sad-001.E1:** A library offers declarations without changing a consumer's weaver.

```clojure
(ns cool.lib
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [millhouse.spools.workflow :as workflow]))

(millstrand/defquery blah-query
  "Return blah strands."
  {}
  [:= [:attr :kind] "blah"])

(millstrand/defop blah
  "Operate on blah strands."
  {:arg-spec blah-arg-spec}
  [ctx]
  (run-blah ctx))

(workflow/defworkflow blah-workflow
  "Run the blah workflow."
  {:entrypoints #{:start}}
  (workflow/workflow ...))
```

- **PROP-Sad-001.E2:** A consumer module selects only the declarations it wants, with the declaration kind visible at each call site.

```clojure
(ns me
  (:require [cool.lib :as lib]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millhouse.spools.workflow :as workflow]))

(millstrand/use-query! lib/blah-query)
(millstrand/use-op! lib/blah)
(workflow/use-workflow! lib/blah-workflow)
```

- **PROP-Sad-001.E3:** A workspace module defines and selects its own declaration in one form.

```clojure
(millstrand/defop! report
  "Render the workspace report."
  {:arg-spec report-arg-spec}
  [ctx]
  (render-report ctx))
```

- **PROP-Sad-001.E4:** Override intent belongs to the consumer's selection.

```clojure
(millstrand/use-op! {:override? true} lib/blah)
```

- **PROP-Sad-001.E5:** A domain spool publishes the same three-part vocabulary rather than inventing its own selection convention.

```clojure
;; chime.api
(authoring/defauthoring rule ...)

;; chime.chimes
(chime/defrule some-chime-rule ...)

;; consumer module
(chime/use-rule! chimes/some-chime-rule)
(chime/defrule! my-custom-rule ...)
```

- **PROP-Sad-001.E6:** Cron declarations use the same named-Var model even though the old form was keyed directly by a keyword.

```clojure
;; library
(cron/defjob nvd-scan
  "Scan NVD on a fixed interval."
  {:interval-ms 86400000
   :handler 'security.jobs/scan-nvd})

;; consumer module
(cron/use-job! jobs/nvd-scan)

;; local definition and selection with explicit override intent
(cron/defjob! nvd-scan
  "Scan NVD on the workspace schedule."
  {:override? true}
  {:interval-ms 43200000
   :handler 'workspace.security/scan-nvd})
```

## PROP-Sad-001.P6 Open questions

- **PROP-Sad-001.Q1:** None. The form names, definition/selection split, absence of generic `use!`, direct-break policy, authorized repository set, and delayed weaver restart were settled with the user during intake. Exact descriptor schemas and `defauthoring` macro-expansion contracts belong in the feature spec deltas without reopening those decisions.

## Implementation reconciliation — 2026-08-14

The earlier unchanged Kanban v24 outcome at peeled SHA `87f61bc2750e7026f3650235907db25f19b1536e` and the proposal-time v25 target at peeled SHA `a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e` are superseded. The final outcome in `PROP-Sad-001.S10` is annotated Kanban v26 at peeled SHA `cd6eab928408faf7101af612c2e199796852d669`. The v24 and v25 targets remain historical traceability only; neither is an active pin or acceptance target. The former v24 Guild-suite blocker is superseded by the published v26 gate and is not an active acceptance constraint.
