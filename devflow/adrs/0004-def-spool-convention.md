# Convention-resolved spool declarations: `def spool` supersedes the exported-base-declaration datum

> **Superseded on 2026-07-30 by PROP-Auf-001.** The public `def spool` convention, `::spool` grammar, and callback dispatch were removed after all selected modules migrated to contribution and lifecycle authoring forms. Public `spool` vars now fail with replacement-form guidance.

**Document ID:** `ADR-004` **Status:** Superseded **Date:** 2026-07-24 **Amended:** 2026-07-24 (pre-v1 sibling cutover authorized under TEN-000@1) **Upholds:** [`TEN-000@1`](../TENETS.md) (alpha software: remove, never shim); [`TEN-003`](../TENETS.md) (FAIL LOUDLY); [`TEN-004`](../TENETS.md) (Less is More); [`TEN-007`](../TENETS.md) (deep-module discipline — a simple interface over an implementation that absorbs the complexity). **Supersedes:** the ADR-003.P7 exported-base-declaration amendment (the exported `module` datum plus its cold-start literal-mirror parity test). **Related:** the def-spool design record — [`PROP-Dsp-001`](../feat/uwnzl-def-spool-convention/proposal.md), [`PLAN-Dsp-001`](../feat/uwnzl-def-spool-convention/uwnzl-def-spool-convention.plan.md), [`DELTA-Dsp-001`](../feat/uwnzl-def-spool-convention/specs/repl-api.delta.md), [`DELTA-Dsp-002`](../feat/uwnzl-def-spool-convention/specs/daemon-runtime.delta.md); [ADR-002](0002-no-inline-module-lifecycle-macro.md) (the printable-declaration invariant, honored here); [ADR-003](0003-spool-activation-lifecycle.md) (the one activation path this builds on); [SPEC-003 repl-api](../specs/repl-api.md) (`::module-opts`, `::spool`, C19); [SPEC-004 daemon-runtime](../specs/daemon-runtime.md) (C45/C46/C46b); epic strand `uwnzl`, Phase A plan `w6z1g`.

> This ADR records why a module's activation entry points move out of its `module!` declaration and into a `(def spool …)` var the refresh coordinator resolves by convention, and why that supersedes the exported-datum pattern ADR-003.P7 settled a day earlier. The decision came out of a live design session with the user immediately after epic `waq0l` landed; the binding rulings are transcribed in `PROP-Dsp-001.P2`. The proposal and both feature deltas passed a cold-read adversarial probe (`PROP-Dsp-001.P9`) before this record was written.

## ADR-004.P1 Context

Epic `waq0l` (ADR-003) retired `install!` and made `(runtime/module! runtime key opts)` the one activation path. The replacement is honest but spreads one module's activation contract across three places that must agree by hand:

1. The spool namespace defines `contribute`/`reconcile` and _also_ exports a `(def module {:ns … :contribute … :reconcile …})` datum.
2. Every consumer's `module!` call copies the `:contribute`/`:reconcile` triple into its opts.
3. Cold-start config cannot deref the datum — spool sources are not loadable when `.millstrand/init.clj` collects declarations — so it mirrors each triple as a literal map, policed by a parity test.

The ADR-003.P7 exported-base-declaration amendment named that literal mirror "recorded, tested duplication, not a second source of truth." It is still duplication, and the user's ruling was to remove the burden rather than test it: "they have three functions and then a testing layer to understand. We should provide deep-modules and swallow the complexity" (`PROP-Dsp-001.R1`). TEN-007 already names deep-module discipline as the house style, and TEN-003's own escape clause licenses solving ergonomic friction "in other ways like … better api design."

The user also settled that a naming convention _is_ API, not a fail-loud violation: "whether the namespace declares the correct def name, or the module! call writes the correct keys, they are both a form of contract users read from the docs" (`PROP-Dsp-001.R3`). The loudness does not relax; it moves inside the deep module.

## ADR-004.P2 Decision A — a module's entry points live in a public `def spool` var

A module's `:contribute`/`:reconcile` entry points move out of the `module!` declaration and into a public `(def spool {…})` var in the module's namespace. The refresh coordinator resolves that var at **every** module evaluation from the module's loaded namespace, so a consumer names only a source target and world policy:

```clojure
;; in the spool namespace — the single authored declaration
(def spool
  {:contribute 'contribute
   :reconcile 'reconcile})

;; in consuming config — source target and world policy, nothing mirrored
(runtime/module! runtime :acme/priority
  {:ns 'acme.priority.alpha
   :spools ['acme/priority]
   :required? true})
```

The shape (`DELTA-Dsp-001.CC2`, registered as `skein.api.spool.alpha/::spool`): a map with optional `:contribute` and `:reconcile` **symbols**, at least one present, and no `:ns` key — the namespace is implicit in where the var lives, so the old datum's `:ns` key is dropped in the rename. Entry points are symbols, never fn values: ADR-002.O1 requires declarations to stay printable, diffable data so `plan`, `status`, and shadow-by-redeclare work without evaluating anything, and a closure voids all three. Unqualified symbols are permitted for ceremony's sake and are qualified by the coordinator against the declaring namespace at resolution time, so the published declaration stays fully qualified printable data and ADR-002's invariant holds at the publication boundary.

The name is `spool`, not `module` (`PROP-Dsp-001.R9`): less likely to collide, and it reads as intent. Outside Phase A's complete-explicit transition path, a **public** var named `spool` in a module-loadable namespace _is_ that module's declaration; private `spool` vars are ignored, and a namespace never activated as a module is unaffected. A complete Phase A explicit declaration does not consult or validate the var; Phase C removes those explicit keys and makes the reservation unconditional. This reserves the public name as documented API, stated once in `skein.api.runtime.alpha`'s docstrings and the spool-authoring guide and enforced loudly by the runtime. A repository `lint-conventions` rule (`DELTA-Dsp-001.CC4`) rejects a malformed public `spool` var in this repository's module-loadable namespaces before merge; it is a repository guard, not a second runtime contract, and cannot protect external consumers — runtime validation is authoritative everywhere.

Validation has one source. `s/def ::spool` lives in `skein.api.spool.alpha`, and the runtime's own loud enforcement validates over that same spec, so the author-facing surface and the enforcement path cannot drift (`PROP-Dsp-001.G6`). Authors validate a candidate declaration with plain `s/valid?`/`s/explain-data`. When the runtime checks a resolved entry point it derefs the var and tests the held value, because a Clojure Var is itself `ifn?` regardless of what it holds, so testing the var could not give the promised error.

**This supersedes ADR-003.P7's exported-base-declaration amendment.** The exported `module` datum, the assoc-the-world consumer pattern, and the cold-start literal-mirror parity test are all replaced by the one authored `spool` var. Where P7 accepted recorded duplication because config could not deref the datum, the coordinator now resolves the var from the loaded namespace at evaluation time, so there is nothing to mirror.

## ADR-004.P3 Decision B — image mode resolves `spool`; the explicit-`:contribute` requirement is gone

ADR-003.P4 decision B required a `:load :image` module to carry an explicit `:contribute`, because image mode skips source evaluation and so collects no authoring forms. ADR-003.P9 flagged the first revisit trigger: "if explicit-`:contribute` becomes the common friction … revisit whether collection can honestly serve image-owned namespaces." The convention resolves it. Image evaluation resolves `<ns>/spool` from the already-loaded JVM image, so the declaration-time `:required :contribute` refusal (`module_graph.clj` `normalize-declaration`, mirrored in the `::module-opts` spec) is deleted. The `:file`-target refusal and the `:load`-value check stay declaration-time refusals.

A `:load :image` module whose namespace is unloaded fails at evaluation. A loaded image namespace with no public `spool` var may still use an explicit Phase A `:contribute`; when neither source supplies contribution, the module is a loud evaluation-time `:failed` outcome on the per-module evaluation-failure channel, never a top-level `:refused` and never a throw out of `module!`, with error data naming `:module/key`, the offending `:ns`, and `:load :image`.

Image mode never loads the declared target namespace's source: that namespace must already be present in the JVM image. Entry-point symbols retain the ordinary G2 resolution contract, so a deliberately fully qualified symbol naming a different, unloaded namespace may require that callable's namespace; this does not re-evaluate the image module's target source.

This is the one place where a refusal moves from declaration time to evaluation time, and it is named as such (`DELTA-Dsp-002.D1`). For ordinary modules nothing about failure locality changes: declared entry-point symbols already resolve at evaluation time after source load. Image mode is the exception because a namespace cannot be inspected for its `spool` var before it is loaded, so the check has no honest declaration-time form once the entry point lives in the image. The revisit trigger is resolved **without** relaxing any loud failure — an unloaded namespace, or a loaded namespace with no contribution from either the convention or the transitional explicit key, fails instead of defaulting silently (ADR-003.P9: "Do not revisit by relaxing the loud failures").

## ADR-004.P4 Decision C — the transitional per-key precedence window (Phase A)

The feature lands in three phases across skein-src and its sibling spool repos. `.millstrand/init.clj` must stay valid against pinned sibling releases at every land, and `make spool-suite-gate` runs pinned sibling suites whose fixtures still declare explicit entry-point keys for in-tree namespaces. That fixes the shape of the transition:

- **Phase A (this land — skein-src core and in-tree):** the coordinator resolves `spool` by convention, and the grammar _temporarily_ still accepts `:contribute`/`:reconcile`. During the window an explicitly declared key wins **per key** over the `spool` var — silently, documented as transitional. Resolution fills only absent entry-point fields from the var; a complete legacy declaration does not consult or validate it and remains valid when its target namespace carries no `spool` var. This is **precedence, not conflict**: a hard "declared key + `spool` var" conflict would fail the pinned sibling suites the moment in-tree namespaces gain `spool` vars. In-tree spools rename to `def spool`; in-tree init.clj entries drop their triples.
- **Phase B (siblings):** after Phase A lands, devflow/kanban/agent-harness export `spool`, delete `module`, convert their own surfaces, and publish coordinated breaking markers under TEN-000@1. No `:skein/min` floor is added while Skein has no marker containing Phase A. Each release exception names Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` as the first compatible Skein commit.
- **Phase C (cutover):** bump pins, drop the remaining sibling triples, sweep the remaining core tests that generate explicit keys, and remove `:contribute`/`:reconcile` from `::module-opts` entirely — completing the removal under TEN-000@1.

The window is a landing-order necessity inside one epic, not a compatibility shim for external consumers. It closes in Phase C, and no release ships with the window open as its end state. Until a later Skein marker can express a truthful compatibility floor, exact sibling marker SHAs and their release-exception records carry the requirement.

## ADR-004.P5 Decision D — record the C19 break, do not shim it

Phase C removes `:contribute` and `:reconcile` from `skein.api.runtime.alpha`'s accepted `::module-opts` grammar. Withdrawing an accepted input key breaks SPEC-003.C19's accretion promise for that subnamespace — under C19's own definition, breaking rethinks move to a new subnamespace. Ruling `PROP-Dsp-001.R5` ("TEN-000@1 is still valid") points at recording an explicit exception instead: alpha software removes, it does not alias or shim.

The accretion-preserving alternative — a parallel `module!` under a new `skein.api.*.alpha` subnamespace differing only by dropping two keys — is exactly the alias/shim R5 forbids, and it would leave two live declaration grammars for the one activation path. It is rejected. What replaces the keys is documented API of equal standing: the `def spool` var. The contract users read from the docs is unchanged in kind, only in where it is authored.

The exact C19 exception wording is staged in `DELTA-Dsp-001.CC5` and approved under the user's delegated coordinator sign-off authority (note `dp90p`). It is **not** promoted into the root spec in Phase A: the keys are still accepted in Phase A, so SPEC-003.C19 is not yet false. The exception merges into SPEC-003.C19 only when Phase C completes the removal (`PLAN-Dsp-001.CM1`). No land ships a knowingly false root spec.

## ADR-004.P6 The invariant: runtime lifecycle semantics did not change

The whole point of the change is that it is an ergonomics and ownership shift, not a runtime behavior change (`PROP-Dsp-001.R4`, `NG1`). Contribute/reconcile characteristics, staging versus refresh collection, full-graph recollection from startup files, source stamps and the `:unchanged` fast path, residual/conflict/remedy reporting, root guards, dependency ordering, atomic owner-partition publication, effective-registry retraction, and the reconcile status-branching contract (SPEC-004.C46b, ADR-003.P6 decision D) are all unchanged. Only _where a module's entry-point pair comes from_ changes.

There is exactly one new seam, and it exists only to keep an existing behavior working. A module removed by omission is never re-evaluated — its source is not loaded on the removal path — and the coordinator today recovers its reconciler from the previously stored declaration. Once declarations stop carrying `:reconcile`, the coordinator must retain each module's **last successfully resolved entry-point set** in runtime state and reconcile the `:removed` teardown (SPEC-004.C46b) through that retained set (`DELTA-Dsp-002.CC4`). The retained set is last-good — after a failed evaluation the previous set is retained and the failure reported — and persists across `reload-code!`. `status`, `plan`, and refresh results expose the resolved entry points additively alongside the authored graph, never as a mutation of the authored `:modules` view. Without this seam, dropping `:reconcile` from declarations would make removal teardown silently disappear, which is the exact parity-bug class ADR-003.P6 exists to prevent.

## ADR-004.P7 Consequences

- The core slice adds coordinator resolution, the retained last-good state and additive status exposure, image-mode `spool` lookup, the `::spool` spec, and the enumerated loud failures.
- The in-tree slice renames the seven `def module` datums to `def spool` (dropping `:ns`), adds `spool` vars to the five entry-point-carrying file modules, drops the in-tree init.clj triples, narrows the parity test to the sibling-backed literals it still guards, and gives `activate-spool!` a namespace-symbol signature that requires the namespace itself.
- The records slice (this ADR, the SPEC-003/SPEC-004 Phase A deltas, and the `docs/spools/*` updates) describes Phase A truthfully and regenerates the API docs.
- Sibling and cutover work follows in Phases B and C per `PLAN-Dsp-001`.

## ADR-004.P8 Revisit when

- If a `:file` module's uniform `spool` name (Decision A applies it to non-spool workspace file modules too) proves confusing in practice, revisit whether file modules deserve a second var name. Uniformity — one name, one rule — was chosen over forking the convention; the misnomer is recorded consciously (`PROP-Dsp-001.Q4`).
- If stale `spool` vars after in-place reload cause a real incident, revisit var cleanup. Today the behavior keeps exact parity with today's symbol resolution — a deleted `contribute` fn resolves the same way from a stale image — and the pickup ladder in `docs/spools/customisation.md` is the documented remedy in both worlds. No var-cleanup machinery ships for a parity property that already exists.

## ADR-004.P9 Lifecycle migration amendment

`def spool` remains transitional while production modules still use its `:reconcile` entry. New lifecycle forms are collected beside contribution declarations and replay through the same source/image declaration record. A module cannot carry both the legacy reconciler and collected lifecycle declarations. The final authoring-forms cutover removes `def spool` only after contribution and lifecycle migrations leave no entry points in the selected source universe.
