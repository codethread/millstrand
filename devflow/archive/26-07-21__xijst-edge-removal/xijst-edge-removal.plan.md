# Exact edge removal Plan

**Document ID:** `PLAN-Xer-001` **Feature:** `xijst-edge-removal` **Proposal:** [proposal.md](./proposal.md) (`PROP-Xer-001`) **RFC:** none **Root specs:** [strand-model.md](../../specs/strand-model.md) (`SPEC-001`) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) (`DELTA-Xer-001`) **Status:** Reviewed **Last Updated:** 2026-07-21 **Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version: `PLAN-Xer-001` for v1, `PLAN-Xer-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID (`PLAN-Xer-001.P1`) so references are globally grepable.

## PLAN-Xer-001.P1 Goal and scope

Add one closed batch edge op, `{:op :remove :from :to :type}`, to the transactional batch graph mutation primitive, and convert every edge outcome to a uniform before/after transition shared by the result, the pre-commit hook, and the `:batch/applied` event. Removal is exact-identity, closed-shape, and fails loudly on absence; it adds no new graph invariant. See [PROP-Xer-001](./proposal.md) for why the gap matters and [DELTA-Xer-001](./specs/strand-model.delta.md) for the settled contract. Scope is the batch primitive only — the edge outcome construction in `apply-batch-in-transaction!`, not the create-path `:strand/edge-ops` outcome in `add-strand-batch!`, which keeps its existing shape.

## PLAN-Xer-001.P2 Approach

- **PLAN-Xer-001.A1 — op-specific grammar in `normalize-batch-payload!`:** The edge-validation branch currently rejects any `:op` that is not `:upsert`. Split validation per op. A `:remove` entry must be a non-nil map with exactly `:op`, `:from`, `:to`, and `:type` (reuse `require-no-unknown-keys!` against a remove-specific key set, so `:attributes` and any unknown key reject), both endpoints valid batch refs, and a valid `::specs/edge-type`. `:upsert` keeps its current checks including optional `:attributes`. The closed-op switch stays fail-loud for anything else.

- **PLAN-Xer-001.A2 — pre-bound-ref restriction in `apply-batch-in-transaction!`:** Its endpoint-resolution loop already rejects unknown and burned refs against `known-refs` (bound plus created). Add, for `:remove` ops only, that both endpoints are in `bound-refs` (the top-level `:refs` keys), never a created ref. Upsert continues to resolve against `known-refs`.

- **PLAN-Xer-001.A3 — remove execution and the ordered state machine:** The edge reducer in `apply-batch-in-transaction!` maps over `edges` in submitted order inside the existing `jdbc/with-transaction`; that ordering is already correct and needs no phase split. For `:remove`, resolve `from-id`/`to-id` from `final-refs`, load the exact storage-shaped row, and delete it. Absence — no row for `(from, to, type)` — throws `ex-info` with `ex-data` exactly `{:from submitted-from :to submitted-to :from-id resolved-from-id :to-id resolved-to-id :type submitted-type}`, which rolls the transaction back. Test that exact map for absent, wrong-direction, and wrong-type removes, and assert no mutation in each case. Extend the core-private `delete-edge!` (which returns the pre-delete row) or its `edge-row` lookup for this; make the absent-row case throw rather than return `nil`. Contention (WAL/`IMMEDIATE`/busy-timeout) propagates unchanged and is never translated to absence.

- **PLAN-Xer-001.A4 — uniform transition outcome:** Replace the upsert-only `{:op :from :to :type :edge ...}` outcome with a transition of exactly `:op`, `:from`, `:to`, `:type`, `:before`, and `:after`. In the db layer, upsert `:before` is the actual storage-shaped pre-image row (loaded before the write, `nil` when the edge is new) and `:after` is the written storage-shaped row; a replacement upsert must load its real pre-image, not derive it from the submitted op. For remove, `:before` is the removed row and `:after` is `nil`. Each non-nil db image has exactly `:from_strand_id`, `:to_strand_id`, `:edge_type`, and raw-JSON-text `:attributes`; do not add core decoding. `skein.api.batch.alpha/apply!` already applies `access/normalize` to the complete core result before hook, return, and event, so its public images have decoded-map `:attributes`. This is the only shape change; there is no `:edge` alias.

- **PLAN-Xer-001.A5 — public normalization, hook, and event:** `skein.api.batch.alpha/apply!` applies `access/normalize` to the complete db result before it builds the pre-commit `:batch/edge-ops` and post-commit `:batch/edges` channels. The normalized transition shape therefore reaches the returned result, pre-commit gate, and `:batch/applied` event with no new plumbing. The public `::normalized-payload` and `::result` specs name and close those payload and result shapes; `require-normalized-payload!` validates the grammar authority's output before the transaction, and `require-batch-result!` validates the normalized engine output before hooks, events, and return. Both guards fail loudly on impossible seam drift without weakening `skein.core.db/normalize-batch-payload!` as the grammar authority. The `apply!` docstring states the two-op grammar, the exact public transition shape, submitted refs versus durable row ids, the replacement-upsert pre-image, and the equal ordered result/hook/event vectors with no `:edge` alias.

## PLAN-Xer-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Xer-001.AA1 | `src/skein/core/db.clj` | `:remove` grammar in `normalize-batch-payload!`; pre-bound-ref check and remove execution in `apply-batch-in-transaction!`; edge outcomes become before/after transitions; a fail-loud exact-row delete built from `delete-edge!`/`edge-row` |
| PLAN-Xer-001.AA2 | `test/skein/core/db_test.clj` | Remove happy path; exact `ex-data` and no mutation for absent/wrong-direction/wrong-type removes; closed-shape and pre-bound-ref rejection; ordered same-source `serves` swap and DAG reversal; atomic multi-remove; transition key/order assertions and raw storage-shaped images on `:edges` |
| PLAN-Xer-001.AA3 | `test/skein/core/contract_props_test.clj` | Extend the coherent-batch edge generator to emit `:remove` ops (or add a focused property) so batch spec-validity still matches db acceptance with the new op |
| PLAN-Xer-001.AA4 | `src/skein/api/batch/alpha.clj` | `apply!` docstring states the two-op grammar and transition shape; public `::normalized-payload` and `::result` specs close the normalized payload/result seams, and `require-normalized-payload!`/`require-batch-result!` fail loudly before the transaction and before hooks, events, and return |
| PLAN-Xer-001.AA5 | `test/skein/weaver_test.clj` | Batch edge tests assert decoded-map transition images reach result, `:batch/edge-ops`, and `:batch/edges` equally, including a remove and a hook veto with no mutation and no event |
| PLAN-Xer-001.AA6 | `test/skein/api/batch/alpha_test.clj` | Surface-level loud rejection of a malformed `:remove` (extra key, `:attributes`, missing endpoint) |
| PLAN-Xer-001.AA7 | `docs/api/batch.api.md` | Regenerated from the `apply!` docstring by `make api-docs` |
| PLAN-Xer-001.AA8 | `devflow/specs/strand-model.md` | Merge `DELTA-Xer-001` into `SPEC-001.P6` at ship |
| PLAN-Xer-001.AA9 | `docs/reference.md`, `docs/tutorial.md` | One-line `:remove` mention beside each existing `:op :upsert` example |

## PLAN-Xer-001.P4 Contract and migration impact

- **PLAN-Xer-001.CM1:** No schema change — removal deletes from the existing `strand_edges` table, and the new grammar is validation only. No migration.
- **PLAN-Xer-001.CM2:** Breaking outcome-shape change: the upsert `:edge` outcome key is replaced by `:before`/`:after` with no alias (`TEN-000@1`, `DELTA-Xer-001.D4`). Any in-tree or pinned-spool reader of the old `:edge` key breaks loudly. Cross-channel equality assertions (`(:edges result)` vs `:batch/edges`) survive unchanged; only readers that dig into `:edge` need updating. `make spool-suite-gate` is the external-spool detection gate.
- **PLAN-Xer-001.CM3:** The durable contract change lives in `DELTA-Xer-001` (`SPEC-001.P6`); `alpha-surface.md` needs no delta because `batch` is already blessed and this accretes within its subnamespace.

## PLAN-Xer-001.P5 Implementation phases

### PLAN-Xer-001.PH1 Core op, state machine, and uniform outcome

Outcome: `{:op :remove :from :to :type}` is a supported batch edge op in `skein.core.db` — exact-identity, closed-shape, top-level-pre-bound-ref, fail-loud on clean absence, ordered in the one transaction — and every edge outcome is the before/after transition with no `:edge` alias. Cold `clojure -M:test skein.core.db-test skein.core.contract-props-test` green, covering `PROP-Xer-001`'s proof obligations PO1–PO7 at the db tier: monotone single removal; exact `ex-data` and no mutation for absent, wrong-direction, and wrong-type removes; the remaining shape matrix; ordered same-source `serves` swap and DAG reversal in the legal and reversed order; repeated-identity cases; atomic multi-op; and transition key/order assertions with raw images. This is one vertical slice: the op and the outcome shape are one coherent contract, and shipping the op without the shared transition would leave a half-shape.

### PLAN-Xer-001.PH2 Surface docstring, observation channels, and api docs

Outcome: the `apply!` docstring states the two-op grammar and public normalized transition shape; `skein.weaver-test` and `skein.api.batch.alpha-test` pin decoded-map transition images in the result, the `:batch/apply-before-commit` hook (`:batch/edge-ops`), and the `:batch/applied` event (`:batch/edges`) as equal ordered vectors, that a hook can veto a removal with no mutation and no event, and that a malformed `:remove` rejects loudly at the public surface. `make api-docs` regenerates `docs/api/batch.api.md` cleanly. Cold `clojure -M:test skein.weaver-test skein.api.batch.alpha-test` green.

### PLAN-Xer-001.PH3 Spec promotion and user docs

Outcome: `DELTA-Xer-001` is merged into `SPEC-001.P6` (both `CC1` and `CC2`), the delta marked Merged, and the devflow README index updated if it lists the feature. `docs/reference.md` and `docs/tutorial.md` gain a one-line `:remove` mention beside each `:op :upsert` example. `make docs-check` clean; the human-prose edits pass the `docs-style` sweep.

## PLAN-Xer-001.P6 Validation strategy

- **PLAN-Xer-001.V1:** Per-slice cold focused runs are the Done-when gate: `clojure -M:test skein.core.db-test skein.core.contract-props-test` for PH1, `clojure -M:test skein.weaver-test skein.api.batch.alpha-test` for PH2. Warm output never satisfies a gate.
- **PLAN-Xer-001.V2:** Queue-acceptance gates from `PROP-Xer-001.P7`: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test`; `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` held at zero findings; `make api-docs` clean regen with `git status --short` showing only the expected `docs/api/batch.api.md` change and no generated SQLite or runtime-metadata artifacts.
- **PLAN-Xer-001.V3:** `make spool-suite-gate` doubles as the CM2 external-spool probe for readers of the dropped `:edge` key.

## PLAN-Xer-001.P7 Risks and open questions

- **PLAN-Xer-001.R1:** The `:edge` → `:before`/`:after` shape change is breaking. Mitigation: in-tree callers are updated in PH1/PH2, and `make spool-suite-gate` catches pinned-spool readers; a hit is a coordinated spool fix, not a weakened contract (`TEN-000@1`).
- **PLAN-Xer-001.R2:** Scope creep into the `add-strand-batch!` create-path, whose `:strand/edge-ops` outcome also carries an `:edge` key. Mitigation: that path is explicitly out of scope (`PLAN-Xer-001.P1`); do not touch it, and confirm its tests (e.g. `skein.weaver-test` weave assertions) stay green untouched.
- **PLAN-Xer-001.Q1:** None blocking task generation. The contract questions are resolved in `DELTA-Xer-001.Q1` / `PROP-Xer-001.P9`.

## PLAN-Xer-001.P8 Task context

- **PLAN-Xer-001.TC1:** Read this plan plus `DELTA-Xer-001` and `PROP-Xer-001` before code. The proof obligations `PROP-Xer-001.PO1-PO7` and the ordered cases `PROP-Xer-001.T1` (`serves` swap) and `PROP-Xer-001.T2` (DAG reversal) are the behavior checklist.
- **PLAN-Xer-001.TC2:** The db pre-image for a replacement upsert must be the actual storage-shaped row loaded before the write, not derived from the submitted op (`PROP-Xer-001.C4`). Load `:before` before mutating; `apply!` normalizes the completed result at the public boundary.
- **PLAN-Xer-001.TC3:** Absence is a clean post-lock lookup finding no row; contention exceptions propagate unchanged and are never absence (`PROP-Xer-001.C2`, `DELTA-Xer-001.D1`). No ignore-missing flag exists.
- **PLAN-Xer-001.TC4:** Removal applies no insertion-only check; deleting a row only shrinks the edge set, so self-edge, DAG, single-`serves`, and duplicate-identity guards do not run on remove, and removing a raw invalid edge (a raw self-edge) is allowed.
- **PLAN-Xer-001.TC5:** Do not build any C9-declined surface: no CLI verb, `:edge/removed` event, standalone helper, generic replace/rewire op, nested stages, variadic `apply!`, graph projector, tombstone, or connectivity/readiness invariant. Reopening one is a new decision, not an implementation choice.
- **PLAN-Xer-001.TC6:** Do not edit implementation code as part of the design slice; this plan and the delta are the artifacts. Implementation is the tasked work that follows.

## PLAN-Xer-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Xer-001.DN1 Task 1: core removal and transitions — 2026-07-21

- Commit `d3861da` implements the database slice. The cold focused gate passed with 68 tests and 370 assertions; Terra review `gd1yq` signed off with no findings. Core images deliberately remain storage-shaped for the existing public normalization boundary.

### PLAN-Xer-001.DN2 Task 2: public batch contract — 2026-07-21

- Commit `e89e327` documents and proves the normalized public transition path without new runtime plumbing. The cold focused gate passed with 92 tests and 934 assertions; regenerated API docs are reproducible, and Terra review `d0ici` signed off clean.

### PLAN-Xer-001.DN3 Task 3: spec promotion and user docs — 2026-07-21

- Commit `286f441` promoted `DELTA-Xer-001` and added user guidance. Terra review `r42by` found that the tutorial's placement could imply newly created refs were removable; `690bd36` made the pre-bound-ref rule explicit, and re-review `l71e9` signed off clean. `make docs-check` passes.

### PLAN-Xer-001.DN4 Post-plan review amendment — 2026-07-21

- Review amendment records the named public `::normalized-payload` and `::result` specs plus the fail-loud `require-normalized-payload!` and `require-batch-result!` seam guards in A5 and AA4. Task 2 remains an immutable historical contract; this amendment supersedes its narrower exact-implementation wording.
