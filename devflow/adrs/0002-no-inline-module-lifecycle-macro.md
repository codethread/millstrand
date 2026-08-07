# Do not add a `use-module` macro for inline lifecycle fns

> **Superseded for authoring on 2026-07-30 by PROP-Auf-001.** Contribution and lifecycle callbacks were removed after the selected source universe migrated to authoring forms. This ADR remains the historical record for why opaque inline callback bodies were rejected.

**Document ID:** `ADR-002`
**Status:** Rejected
**Date:** 2026-07-23
**Upholds:** [`TEN-004`](../TENETS.md) (Less is More); [`TEN-003`](../TENETS.md) (FAIL LOUDLY); [`TEN-000@1`](../TENETS.md) (this is alpha software).
**Related:** the module lifecycle surface `module!`/`refresh!`/`plan`/`status` in `skein.api.runtime.alpha` (DELTA-OlrRepl-001, DELTA-OlrDrt-001); the declaration grammar in `skein.core.weaver.module-graph/normalize-declaration`; the reconcile/contribute path in `skein.core.weaver.module-refresh`; the local-notifier convention in `.millstrand/init.local.clj` + `.millstrand/notifier_local.clj`; spool classloader semantics in `docs/spools/customisation.md`.

> This ADR records why we prototyped, verified, and then declined a `use-module`
> macro that would let a stable module's `:contribute`/`:reconcile` be authored
> inline at the declaration site instead of in a separate source file. The macro
> works; the reasons to keep it out are the edge cases it can't reach and the
> gotchas it would normalize. The exploration is preserved here so the choice is
> repeatable and a returning reader learns *why* rather than re-running the spike.

## ADR-002.P1 Context

A stable module is declared with `(module! runtime key opts)`. The lifecycle splits cleanly:

- **`:contribute`** — a fn (or the source's authoring forms) that produces registry *data* the coordinator publishes.
- **`:reconcile`** — a fn that applies live effects and, on removal, tears them down; it is the reconciler, not a fire-and-forget effect.

Both `:contribute` and `:reconcile` are named as **fully qualified symbols**, and `normalize-declaration` rejects anything else. That is deliberate: the published declaration must stay printable, diffable data so `plan` (dry-run), `status` (offline join), and `init.local.clj` shadow-by-redeclare all work without evaluating anything. A closure in the declaration would void all three.

The friction that opened the arc is the machine-local notifier. It is two files for one setter:

- `.millstrand/init.local.clj` — the `module!` declaration, gated `:after [:skein/spools-chime]`.
- `.millstrand/notifier_local.clj` — a `:file` source whose *only* job is to house `notifier-local/reconcile`, a one-line `(chime/set-notifier! …)`.

The question: could a small effect-only module be authored inline in one file, the way Emacs `use-package` folds a package's config and hooks into a single form?

## ADR-002.P2 Options considered

- **ADR-002.O1 — Store a closure in the declaration.** Let `:reconcile (fn …)` be an actual function value in `opts`. **Rejected on sight:** a closure is opaque — not printable, not diffable, no provenance — so it breaks `plan`, `status`, and shadow. This is the invariant `normalize-declaration` exists to protect.
- **ADR-002.O2 — A `use-module` desugaring macro (the spike).** A macro over `module!` that detects a literal `(fn …)` at `:contribute`/`:reconcile`, interns it as a stable top-level var (name derived from the module key, never a gensym, so reload redefines in place), and rewrites the declaration to reference that var *by symbol*. The published declaration stays data; the fn keeps a reload and provenance home — exactly what a hand-written `:file` module gets. Symbol callables and non-map opts pass straight through, so it is a strict superset of `module!`. **Built and verified (see P4); declined anyway.**
- **ADR-002.O3 — Quoted-form `:reconcile` body, evaluated at reconcile time.** The homoiconic `use-package` shape: carry the body as an unevaluated (printable, data-preserving) form and `eval` it at reconcile time in a namespace that first `require`s the spool. This is the *only* route that recovers a clean compile-time alias (`chime/set-notifier!`) in the body. **Rejected:** it pays runtime `eval`, forfeits compile-time checking, and — the decisive point — to load the spool correctly at eval time it must reproduce namespace setup through the right classloader, which is precisely what a `:file`/`:ns` module source already does. It converges on a worse copy of the source loader.
- **ADR-002.O4 — Inject `:after` spools into the fn's destructuring.** Have the macro/runtime hand resolved spool handles into the reconcile ctx, so the body destructures them instead of resolving by hand. **Rejected as specified:** `:after` holds *module keys* (`:skein/spools-chime`, `:harnesses`, `:config`), not namespaces, and a key may be a multi-namespace `:file` module — there is no runtime concept of a destructurable "spool handle" to inject. The workable degenerate (a `:resolve` clause naming specific vars the macro binds via `requiring-resolve` at call time) is real sugar, but it is sugar *over* `requiring-resolve` and inherits its two weaknesses (P4): no static checking, and a classloader blind spot.
- **ADR-002.O5 — Do nothing; keep the two-file `:file`-source convention.** Boring. The declaration stays in `init.local.clj`; spool-touching effects stay in a `:file` source with a normal `(:require …)`. **Chosen.**

## ADR-002.P3 Decision

**Do not add `use-module` (O2) or any inline-lifecycle sugar (O3, O4). Keep `module!` as-is: lifecycle fns are named symbols, and code that touches a spool lives in a `:file`/`:ns` source loaded by the module system. Simple and boring wins at this stage.**

The macro is not rejected because it fails — it passes end to end. It is rejected because it buys a narrow ergonomic win (fewer files for effect-only local glue) while normalizing gotchas that fail quietly, and because the deeper wins it gestures at require reworking `module!` itself, which the current pain does not justify.

## ADR-002.P4 Why — what the spike proved, and where it broke

The spike was built and validated so the decision rests on evidence, not intuition. It lifted inline fns to stable vars, kept the declaration as a printable symbol, applied through the real startup lifecycle, and passed `fmt`/`lint`/`reflect`/`conventions`. All of the following were run against a live weaver over a disposable workspace.

**It works — and that is not the question.** An inline `:reconcile` registered its side effect and the stored declaration read back as `user/use-module_demo_notifier_reconcile` (a symbol, not a closure), module outcome `:applied`. Reconcile bodies can be arbitrarily large; there is no code-size ceiling.

The reasons to decline are four boundaries the sugar cannot cross:

1. **A source target is still mandatory.** `module!` requires exactly one of `:ns`/`:file`; a declaration with neither fails loudly. So even a pure-effect inline module still points at some source file — the sugar removes the *separate fn file*, not the *source requirement*. "One form, zero other files" is not actually reached without a deeper `module!` change.

2. **The ergonomic `:contribute` path cannot inline.** An explicit `:contribute` fn lifts fine, but authoring forms (`defop`/`defquery`/`defpattern`/`defrule`) collect during source **load**. Those forms only work in a file the module system loads. To contribute inline you must hand-assemble the raw contribution map, losing the authoring forms that are the point.

3. **Spool access from an inline fn is a trap that fails at two different times.** The inline fn compiles at *collection* time, before any spool is synced onto the classpath:
   - A top-level `(require '[skein.spools.batteries :as b])` in `init.clj` → **`FileNotFoundException` at startup** (verified) — the spool root is not on the classpath during collection.
   - A compile-time alias inside the fn body → won't compile, for the same reason.
   - `((requiring-resolve 'skein.spools.batteries/foo) …)` inside the body → **works** (verified: resolved `true`, `:applied`), because the `:after`/`:spools` gate guarantees the spool is synced by reconcile time. But `requiring-resolve` loads through the base classloader; it succeeded here because batteries is a *source-root* spool on the classpath. A git-distributed spool under an isolated spool classloader may resolve to nothing or to a duplicate the module system did not load — the same hazard the docs flag for a bare `:reload`. The sugar cannot fix this; it is downstream of how the var loads.

4. **The `:file` source is the mechanism, not boilerplate.** Because the module loader loads a `:file`/`:ns` source *at the right lifecycle point, through the spool-aware classloader*, that source's plain `(:require [skein.spools.chime :as chime])` just works — clean compile-time alias, readable `chime/set-notifier!`, classloader-correct loading. Every attempt to move spool access inline (O3, O4) either can't get a static alias or reproduces this loader by hand. The two-file shape trades one extra file for exactly the property the inline shape keeps fighting to recover.

The honest boundary that emerged is not "small vs large blocks." It is **what the code touches**: inline fns are fine for effects over core `skein.api.*` and the runtime handle; the moment a spool's own namespace is involved — especially a git-distributed one — a `:file` source earns its keep.

## ADR-002.P5 Consequences — what we do instead

- **Keep `module!`'s grammar closed and its callables named.** Declarations stay printable data; `plan`/`status`/shadow keep working with no special-casing.
- **Local effect-only glue stays a `:file` module.** For the notifier and its kin, the `:file` source is the supported home: clean `require`, classloader-correct spool loading, static compilation, reload/provenance for free. The "extra" file is the seam that makes spool access correct.
- **Reach spools through a source `require` or a blessed runtime API**, never a bare `requiring-resolve` from an inline fn. `:after` + `:spools` gating remains load-bearing whenever a module depends on another.
- **No new public surface ships.** The spike is reverted; this record is the deliverable.

## ADR-002.P6 Revisit when

Reopen if the ergonomic pain recurs across *many* modules rather than the single local-notifier case, and only alongside a deliberate `module!` rework — not another surface macro. Two changes would make inline authoring genuinely first-class instead of a sugar with sharp edges:

1. **Make the source target optional for effect-only modules** (a module whose entire behavior is its reconcile, with no contribution and no external source), so "one form, zero other files" is actually reachable.
2. **A classloader-aware spool-handle injection** the runtime hands into the reconcile ctx, resolving `:spools`/`:after` roots through the same loader the module system uses — so inline access is correct for git-distributed spools, not just source-root ones.

Both are core-mechanics work on `module!` itself. Until the pain justifies that, the parked design asset is a desugaring macro plus these two runtime changes, not a bolt-on macro over today's grammar.

## ADR-002.P7 Lifecycle forms amendment

The lifecycle authoring feasibility spike accepted `defseed`, `defresource`, and `defreconcile`. This does not reverse the rejection of inline closure-valued module options. The forms collect printable declarations whose callables are fully qualified symbols, and the coordinator resolves those symbols through the selected spool loader before publication. Ordinary Clojure composition remains in named source functions. Runtime evaluation and closure-valued declarations remain rejected.
