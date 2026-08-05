# Core Reconcile Contract + Image Activation Plan

**Document ID:** `PLAN-Cri-001` **Feature:** `fbr4m-core-reconcile-image` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md), [repl-api.md](../../specs/repl-api.md) **Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Reviewed **Last Updated:** 2026-08-05 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `PLAN-Cri-001.P1`.

## PLAN-Cri-001.P1 Goal and scope

Ship ADR-003's two core changes: the reconcile applied/removed contract as normative spec text with coordinator-level proof, and `{:load :image}` as a validated member of the closed module declaration grammar. See [proposal.md](./proposal.md).

## PLAN-Cri-001.P2 Approach

- **PLAN-Cri-001.A1 (grammar, module_graph):** Add `:load` to `declaration-keys`. In `normalize-declaration`, after the existing source-target checks: refuse a `:load` value outside `#{:image}` naming the allowed set; refuse `:load :image` with a `:file` target (image mode accepts only `:ns`); refuse `:load :image` without `:contribute` (no source evaluation, so no authoring-form collection exists). Each refusal is an `ex-info` carrying `:module/key`, the offending value, and the allowed alternatives (TEN-003). The normalized declaration retains `:load :image` as printable data.
- **PLAN-Cri-001.A2 (evaluation, module_refresh):** `evaluate-module` branches before source loading: an image declaration skips `with-contribution-collection`/`load-source!` entirely, requires `(find-ns ns)` — failing as that module's `:failed` outcome with `:module/key`, the namespace, and the load-it-first remedy — resolves the declared `:contribute` fn as today, and reports `:source/status :image` with no `:source/stamp`. `collection-context`/`load-source!` stay untouched for non-image declarations; nothing else in the pipeline changes (unchanged-skip, stage-publications, reconcile, `refresh! {:only}` all compose over the outcome shape).
- **PLAN-Cri-001.A3 (contract text):** Insert SPEC-004.C46b (reconcile contract) beside C46 per DELTA-Cri-001.CC1; extend the C45/C46 grammar language per DELTA-Cri-001.CC2; update SPEC-003 P5 helper prose per DELTA-Cri-002 — the P6 example stays untouched (production `:spools`-guarded declaration; image mode is the bare-runtime variant per ADR-003.P7); extend the `module!` docstring (grammar + the one-sentence reconcile contract) and the `::module-declaration` spec's optional `:load` key. The deltas' Merged status becomes true in this same branch: the root-spec merges land in PH2 before review.
- **PLAN-Cri-001.A4 (tests):** In `skein.core.weaver.modules-test`: grammar refusal cases asserting ex-data (`:module/key`, offending value, allowed alternatives); image activation on a bare runtime publishing the `:contribute` contribution with `:source/status :image`, no `:source/stamp`, and no source load (asserted by capturing the namespace-load ledger before/after and by the absence of `:collection/reload?` in the source result — timing-independent); unloaded-namespace `:failed` outcome (asserting the actionable ex-data, with the top-level result `:partial`); `refresh! {:only}` over an image module staying loadless AND skipping reconcile on the unchanged contribution (reconcile-call count unchanged); explicit `plan`/`status` assertions (`:dry-run?` plan outcome and `module-status` both report `:source/status :image`, the declaration keeps `:load :image` as data, and no contribution-source stamp is recorded); a source-loading module redeclared as `:load :image` dropping its previously recorded source stamp; removal-path reconcile receiving `:module/contribution :status :removed` and applied-path `:applied` (extend `module-reconcile` to record the received status); unchanged contribution skipping reconcile (already asserted — keep). In `skein.api.runtime.alpha-test`: `module!` accepts an image declaration and returns a conforming `::module-result`; spec accepts normalized `:load`.

## PLAN-Cri-001.P3 Affected areas

| ID               | Area                                          | Expected change                                        |
| ---------------- | --------------------------------------------- | ------------------------------------------------------ |
| PLAN-Cri-001.AA1 | `src/skein/core/weaver/module_graph.clj`      | `:load` key validation in the closed grammar           |
| PLAN-Cri-001.AA2 | `src/skein/core/weaver/module_refresh.clj`    | Image branch in `evaluate-module`                      |
| PLAN-Cri-001.AA3 | `src/skein/api/runtime/alpha.clj`             | `module!` docstring; `::module-declaration` `:load`    |
| PLAN-Cri-001.AA4 | `devflow/specs/daemon-runtime.md`             | New C46b; C45/C46 grammar admission                    |
| PLAN-Cri-001.AA5 | `devflow/specs/repl-api.md`                   | P5 helper prose + `:ns`-target paragraph gain `:load :image`; P6 example unchanged |
| PLAN-Cri-001.AA6 | `test/skein/core/weaver/modules_test.clj`                  | Grammar, image-activation, reconcile-contract tests    |
| PLAN-Cri-001.AA7 | `test/skein/api/runtime/alpha_test.clj`       | API-tier acceptance of the image declaration           |
| PLAN-Cri-001.AA8 | `docs/api/runtime.api.md`                     | Regenerated via `make api-docs` (docstring change)     |

## PLAN-Cri-001.P4 Contract and migration impact

- **PLAN-Cri-001.CM1:** Pure grammar addition — every existing declaration is untouched byte-for-byte; no data migration; no canonical-world action needed at this feature (the in-tree feature rrvnn is the first consumer).

## PLAN-Cri-001.P5 Implementation phases

### PLAN-Cri-001.PH1 Grammar + evaluation + tests

Outcome: `:load :image` validated and activated per A1/A2, reconcile-status delivery proven, `clojure -M:test skein.core.weaver.modules-test skein.api.runtime.alpha-test` green cold.

### PLAN-Cri-001.PH2 Spec merge + docs + quality gates

Outcome: root specs carry C46b and the grammar admission; deltas marked Merged; `make api-docs` regenerated; `make fmt-check lint reflect-check docs-check` green.

### PLAN-Cri-001.PH3 Land

Outcome: full locked suite at queue acceptance, `clojure -M:smoke`, `(cd cli && go test ./...)`, `make spool-suite-gate`; branch landed via `strand land`; card note records the exact shipped grammar for feature rrvnn.

## PLAN-Cri-001.P6 Validation strategy

- **PLAN-Cri-001.V1:** Per-slice gate: `clojure -M:test skein.core.weaver.modules-test skein.api.runtime.alpha-test` cold. Queue acceptance: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`, smoke, go tests, spool-suite-gate, quality gates, clean `git status --short`.
- **PLAN-Cri-001.V2:** No-source-load evidence is structural (load ledger delta + absence of reload markers), never wall-clock timing.

## PLAN-Cri-001.P7 Risks and open questions

- **PLAN-Cri-001.R1:** The image branch must not regress the existing no-reachable-source arm of `load-source!` (`module_refresh.clj:358`) — image mode is a separate explicit branch; the implicit arm keeps its semantics.
- **PLAN-Cri-001.R2:** `evaluate-module`'s `source-status` derivation keys off `(:file return)`; the image branch bypasses that derivation entirely rather than feeding it a synthetic return.

## PLAN-Cri-001.P8 Task context

- **PLAN-Cri-001.TC1:** Small two-slice feature, worked directly (no AFK task queue). ADR-003 P4/P6 are the binding design record.

## PLAN-Cri-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
