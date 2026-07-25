# Convention-Resolved Spool Declarations (`def spool`) Plan

**Document ID:** `PLAN-Dsp-001` **Feature:** `uwnzl-def-spool-convention` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [repl-api.md](../../specs/repl-api.md) (SPEC-003, `::module-opts`/C19/C19a), [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004, C45/C46/C46b) **ADRs:** [ADR-002](../../adrs/0002-no-inline-module-lifecycle-macro.md) (printable-declaration invariant), [ADR-003](../../adrs/0003-spool-activation-lifecycle.md) (activation lifecycle; a successor decision records the supersession) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md) (`DELTA-Dsp-001`), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`DELTA-Dsp-002`), [specs/repl-api-phase-c.delta.md](./specs/repl-api-phase-c.delta.md) (`DELTA-Dsp-003`), [specs/daemon-runtime-phase-c.delta.md](./specs/daemon-runtime-phase-c.delta.md) (`DELTA-Dsp-004`) **Status:** Active **Last Updated:** 2026-07-24 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PLAN-Dsp-001` for v1 and `PLAN-Dsp-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PLAN-Dsp-001.P1`, so references are globally grepable and do not clash across documents.

## PLAN-Dsp-001.P1 Goal and scope

A module's activation entry points move out of its declaration and into a `(def spool …)` var the refresh coordinator resolves by convention, so a spool author states `:contribute`/`:reconcile` once in the namespace and every consumer names only a source target and world policy. This is an ergonomics and ownership shift, not a runtime behaviour change: the whole point (PROP-Dsp-001.R4, delegated-authority ruling in kanban note `wnxuv`) is that contribute/reconcile characteristics, reload semantics, staging, dependency ordering, status branching, and removal teardown are identical before and after. The proposal (`PROP-Dsp-001`) and both feature deltas passed cold-read and TerraMed review; this plan is the strategy bridge between them and the live task graph tracked on strand `w6z1g`.

## PLAN-Dsp-001.P2 Approach

- **PLAN-Dsp-001.A1 (convention resolution, one seam):** The coordinator resolves each module's entry points from the public `spool` var in its loaded namespace at **every** module evaluation, so the `:unchanged` fast path, targeted refreshes, classpath bindings, and `reload-code!` all resolve identically (`DELTA-Dsp-002.CC1`). Only the _source_ of the `:contribute`/`:reconcile` pair changes; everything else in refresh, publication, and reconcile stays put.
- **PLAN-Dsp-001.A2 (single validation source):** `s/def ::spool` lives in `skein.api.spool.alpha` and the runtime's loud enforcement validates over that same spec, so the author-facing surface and the enforcement path cannot drift (`DELTA-Dsp-001.CC2`, `DELTA-Dsp-002.CC5`). Entry points are symbols, not fn values (ADR-002.O1); unqualified symbols are coordinator-qualified so the published declaration stays printable data.
- **PLAN-Dsp-001.A3 (transitional per-key precedence):** Phase A keeps the legacy `:contribute`/`:reconcile` grammar keys and lets an explicitly declared key win per key over the `spool` var, silently and documented as transitional (`DELTA-Dsp-002.CC2`). This is precedence, not conflict, because the pinned sibling suites still declare explicit keys for in-tree namespaces during the window and a hard conflict would fail the gate the moment those namespaces gain `spool` vars.
- **PLAN-Dsp-001.A4 (retained resolved state):** Because declarations stop carrying `:reconcile`, removal-by-omission would lose its reconciler. The coordinator retains each module's last-good resolved entry-point set in runtime state and reconciles the `:removed` teardown through it, and exposes the resolved set additively alongside the authored graph in `status`/`plan`/refresh (`DELTA-Dsp-002.CC4`, G2a). This is the load-bearing new seam.
- **PLAN-Dsp-001.A5 (atomic landing, three phases):** In-tree conversion ships in the same branch as the resolution change (Phase A must be atomic), siblings convert and release next (Phase B), and grammar-key removal plus pin cutover come last (Phase C). Landing order is fixed by two constraints: `.skein/init.clj` must stay valid against pinned sibling releases at every land, and `make spool-suite-gate` runs pinned sibling suites that still carry explicit keys for in-tree namespaces until Phase B.

## PLAN-Dsp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dsp-001.AA1 | `src/skein/core/weaver` (`module_refresh.clj`, `module_graph.clj`) | Convention resolution, image-mode `spool` lookup, retained last-good state, enumerated loud failures |
| PLAN-Dsp-001.AA2 | `skein.api.runtime.alpha`, `skein.api.spool.alpha` | `::module-opts` keys optional in Phase A; new `::spool` spec; docstring reservation of the public `spool` name |
| PLAN-Dsp-001.AA3 | `scripts/quality` + conventions ratchet | Repository lint rejecting malformed/incidental public `spool` vars in module-loadable namespaces |
| PLAN-Dsp-001.AA4 | In-tree `spools/*`, `.skein/*.clj`, `.skein/init.clj` | `def module` → `def spool` (drop `:ns`), file-module `spool` vars, in-tree init triples dropped, parity test narrowed |
| PLAN-Dsp-001.AA5 | Test helpers and fixtures (`test_support.clj`, coordinator tests) | `activate-spool!` takes and requires a namespace symbol; regression matrix for the new seams |
| PLAN-Dsp-001.AA6 | Root specs, ADR-003 successor, `docs/spools/*`, generated API docs | Truthful per-phase contract updates, recorded C19 exception, docs of the transitional window |
| PLAN-Dsp-001.AA7 | Sibling repos (devflow, kanban, agent-harness) — Phase B | `spool` exports, `module` deletions, own-surface conversion, pre-v1 breaking markers, release exceptions naming the compatible Phase A commit |

## PLAN-Dsp-001.P4 Contract and migration impact

- **PLAN-Dsp-001.CM1 (grammar break, recorded not shimmed):** Phase C removes `:contribute`/`:reconcile` from `::module-opts` under TEN-000@1 — no aliases, no shims. Withdrawing accepted input keys breaks SPEC-003.C19's accretion promise for `skein.api.runtime.alpha`, so `DELTA-Dsp-001.CC5` stages the exact C19 exception wording now and promotes it into the root spec only when Phase C lands. The coordinator approved that wording under the user's delegated sign-off authority in note `dp90p`; Phase A does not break C19 because the keys are still accepted.
- **PLAN-Dsp-001.CM2 (per-phase spec truthfulness):** Each phase's land updates the root specs to describe that phase's actual behaviour. Phase A's deltas document the convention and the transitional acceptance of the old keys as a recorded pending-removal obligation; Phase C's delta completes the removal. No land ships a knowingly false root spec.
- **PLAN-Dsp-001.CM3 (pre-v1 compatibility exception, no forced restart):** No schema or persisted-config migration. The canonical world consumes siblings at pinned SHAs, so nothing changes there until the Phase C pin bump and refresh. By user direction on 2026-07-24, the sibling releases may break against pre-Phase-A Skein under TEN-000@1 and publish before Skein v1. They do not add a false `:skein/min` floor. Each release exception names Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` as the first compatible Skein commit, and Phase C pins the exact reviewed marker SHAs. This feature still does not create the Skein v1 stamp.

## PLAN-Dsp-001.P5 Implementation phases

### PLAN-Dsp-001.PH-A Phase A — skein-src core and in-tree (atomic, one branch)

Outcome: this checkout resolves `spool` by convention with the legacy keys still accepted at per-key precedence, in-tree spools and config are converted, and the root contracts describe Phase A truthfully. Phase A is internally split into disjoint file scopes that merge atomically into `codex/uwnzl-def-spool-convention`:

- **Core** (`5yfrq`): coordinator resolution, retained last-good state and additive status exposure, image-mode `spool` lookup, the `::spool` spec home, and the enumerated loud failures, with the tightened S1 regression matrix. Blocks runtime-dependent in-tree and records work.
- **Lint** (`c5c42`): the repository `spool`-var convention rule and ratchet tests over the existing conventions scan seam. Its structural shape came from the reviewed proposal and feature delta, so its disjoint implementation ran in parallel with core and is already coordinator-verified.
- **In-tree** (`vqwbt`): the seven `def module` → `def spool` renames, five file-module `spool` vars, in-tree `init.clj` triple drops (sibling triples retained for Phase C), `activate-spool!` signature change and fixture sweep, and truthful parity-test narrowing. Rebases onto the verified core commit so focused tests exercise the real convention.
- **Records** (`m47vr`): ADR-003 successor, truthful Phase A root-spec merges, the recorded C19 exception, `docs/spools/*` updates, and `make api-docs`. Runs after code integration.
- **Accept** (`z12za`): coordinator-only atomic verification and adversarial review across the integrated worktree before land.

### PLAN-Dsp-001.PH-B Phase B — coordinated pre-v1 sibling releases

Outcome: after Phase A lands, devflow.spool, kanban.spool, and agent-harness.spool each export `(def spool …)`, delete the `module` export, convert their own consuming surfaces (workspace config, fixtures, activation helpers, docs), and cut new `v<int>` markers with `release-exception.md` records per each repo's precedent. The records name Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` as the first compatible Skein commit. No `:skein/min` floor is added while no Skein marker can state that truthfully. Sibling suites hard-code `../skein-src` (waq0l note `5bae1`), so every release gate runs against landed Phase A.

### PLAN-Dsp-001.PH-C Phase C — pin and grammar cutover

Outcome: the transitional window closes. Bump sibling pins, drop the remaining sibling-backed triples from `.skein/init.clj`, sweep the remaining core tests and fixtures that generate explicit entry-point keys, remove `:contribute`/`:reconcile` from `::module-opts`, delete the narrowed parity test, land the Phase C spec delta with the promoted C19 exception, refresh the canonical world, and verify live — the close-out shape feature `rtnfv` used for epic `waq0l`. The epic DONE-WHEN grep gates (`git grep '(def module'` in `spools/` empty; no `:contribute`/`:reconcile` in `.skein/init.clj`) hold from here. Depends on Phase B released.

## PLAN-Dsp-001.P6 Validation strategy

- **PLAN-Dsp-001.V1 (invariance is the primary proof):** The regression matrix must show runtime semantics unchanged, not merely that resolution works. Required Phase A gates from the proposal S1: a precedence-window matrix (explicit wins per key; absent fields come from `spool`; a complete legacy declaration works with no `spool` var); a true `:load :image` path set up with only a preloaded namespace, covering both success and missing-`spool` failure with no source collection and no injected callable; and a last-good sequence where entry points resolve, a later evaluation fails, `reload-code!` runs, and the module is then removed by omission — proving the prior reconciler runs exactly once with `:removed` and that teardown, ordering, and exposed last-good status survive.
- **PLAN-Dsp-001.V2 (per-slice gates):** Each Phase A slice carries its own cold focused gate (coordinator tests for core, `make lint-conventions` for lint, focused spool suites plus `make spool-suite-gate` for in-tree, `make api-docs`/`make docs-check` for records). The pinned sibling suite gate must stay green throughout Phase A — it is what makes the precedence rule non-negotiable.
- **PLAN-Dsp-001.V3 (atomic acceptance):** Phase A queue acceptance (`z12za`) reruns each task gate in the integrated worktree, then `make build`, cold focused tests, `(cd cli && go test ./...)`, `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, `make spool-suite-gate`, and the flock-held full Clojure suite, and confirms no generated SQLite or runtime-metadata artifacts and no unowned files. Registered change-review and complex-patch-review findings must be fixed and resubmitted before close.
- **PLAN-Dsp-001.V4 (Phase C grep gates):** The epic DONE-WHEN greps and a green canonical-world refresh with live verification gate the cutover.

## PLAN-Dsp-001.P7 Risks and open questions

- **PLAN-Dsp-001.R1 (Var-`ifn?` trap):** A Clojure Var is itself `ifn?` regardless of what it holds, so entry-point validation must deref the var and test the root value, or a malformed `spool` resolves silently (archaeology `tmzb0`, risk R1).
- **PLAN-Dsp-001.R2 (removal teardown regression):** Without the retained resolved set (A4), dropping `:reconcile` from declarations makes removal-by-omission teardown disappear silently — the exact parity-bug class ADR-003.P6 exists to prevent. The existing removal test still uses declared keys and will not catch it, so a `spool`-var removal variant is mandatory.
- **PLAN-Dsp-001.R3 (over-broad forms conflict):** The forms-vs-`:contribute` loud conflict must be scoped to `:contribute` only; a `:reconcile`-only `spool` var must still compose with collected authoring forms (the `module_adapters`/`kanban_tracker` pattern), which is valid production behaviour today.
- **PLAN-Dsp-001.R4 (status-shape breakage):** The resolved-entry-point exposure must be a new additive field, never a mutation of the authored `:modules` graph, or downstream status readers break (archaeology `niyif`, risk R7).
- **PLAN-Dsp-001.Q1 (settled — C19 exception wording):** The coordinator approved the exact SPEC-003.C19 exception in `DELTA-Dsp-001.CC5` under the user's delegated sign-off authority (note `dp90p`). It remains staged until Phase C because the legacy keys are still accepted in Phase A.

## PLAN-Dsp-001.P8 Task context

- **PLAN-Dsp-001.TC1 (detailed tracker):** `w6z1g` is the live execution plan; it owns sequencing, per-file scope, and acceptance criteria. This plan stays at strategy level and does not mirror its checklists.
- **PLAN-Dsp-001.TC2 (cold-resume IDs):**
  - Kanban card `uwnzl`, kanban task `vwa06` (its notes hold the full record: proposal review run `fgjlu`/task `6ykzo`, TerraMed reviews `8rfx9`/`74h9n`, delegated-authority ruling `wnxuv`, and the three Opus archaeology notes `tmzb0`/`xkpij`/`niyif` from read-only run `1uorm`).
  - Tracked Phase A plan `w6z1g` with children `5yfrq` (core), `c5c42` (lint), `vqwbt` (in-tree), `m47vr` (records/specs), `z12za` (accept).
  - Integration branch `codex/uwnzl-def-spool-convention`; worktrees `codex/uwnzl-phase-a-{core,lint,specs,intree}`.
  - Live tracked runs at last coordinator note `14ioi`: `hvg4g` (core), `s9xhz` (lint), `mo25j` (spec deltas). Core runs under story workflow `uwnzl-phase-a-core-story` at behavior step `u80vl` with a TerraMed reviewer.
- **PLAN-Dsp-001.TC3 (authority and boundaries):** Coordinator authority is delegated for phased implementation, tracked runs, reviews, merges, releases, rebuilds, and refreshes; skein-src `v1` must not be stamped (ruling `wnxuv`). Tests exercise the real image path, not workaround-only spellings; `:load :image` stays unless a clearly test-only spelling is materially better without widening scope.
- **PLAN-Dsp-001.TC4 (pre-v1 break authorized):** On 2026-07-24 the user removed card `b3v1r` as a dependency of kanban task `l5lwo` and authorized the coordinated sibling break under TEN-000@1. Phase B may prepare and publish the convention-dependent markers now. It must not add a false `:skein/min` floor or create the Skein v1 stamp. Exact reviewed marker SHAs and release-exception records bridge compatibility until Phase C pins them and a later Skein marker can express a floor.

## PLAN-Dsp-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Dsp-001.DN1 Plan authored — 2026-07-24

- Written against `PROP-Dsp-001`, `DELTA-Dsp-001`/`DELTA-Dsp-002`, and archaeology `tmzb0`/`xkpij`/`niyif`, with `w6z1g` as the detailed tracker. Status set to Active because Phase A task runs are already live (`hvg4g`, `s9xhz`, `mo25j`).

### PLAN-Dsp-001.DN2 Pre-v1 cutover authorized — 2026-07-24

- The user declined to stamp Skein v1 yet and authorized the sibling and core breaks under TEN-000@1. Task 6 now records that HITL decision instead of waiting for `b3v1r`. Tasks 7 and 8 may run in order without the stamp. Sibling releases carry no `:skein/min` floor; each release exception names Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` as the first compatible Skein commit. Phase C pins the exact reviewed release SHAs before removing the legacy module keys.

### PLAN-Dsp-001.DN3 Phase B published; Phase C pins these markers — 2026-07-24

Phase B is complete (coordinator record: kanban note `5ib4p`). The releases were published in dependency order against tested Skein `8812c39da8b5a1fb61b39f47e32a88223bfaf8e3`, which descends from the Phase A merge `343f886880092bc38ed3e0522eca2d95a7cf04bc` that every release exception names as the first compatible Skein commit. Phase C pins exactly these peeled commits:

| Family | Marker | Annotated tag object | Peeled commit | Previous pin |
| --- | --- | --- | --- | --- |
| `codethread/kanban` | `v10` | `21b5ed1e6c9fa4bac20cc5fc2c3e4ae0c6404c51` | `14390f448cac93fb045d36bcecaf49f11f0a4de2` | `v9` at `46c4101befafeb2f5b3958a83c0677abc2608eda` |
| `codethread/devflow` | `v6` | `ebdba87d0672425d24fd474452fd4f9a4c1bc93a` | `7e86aa9d65546b7ca795202411e464bde5b39baf` | `v5` at `98ecdd8a2fe15e4deebc83ec94596337162b46a1` |
| `ct.spools/agent-run` roots | `v14` | `41543d6982f8a4bd996c891d5ae91458933f3a32` | `089d1b15a197b70f0257cbfc16ab85b5f0985b37` | `v13` at `35655ca2b68559e14668b78610388e94ed652efa` |

- No release carries a `:skein/min` floor: no Skein marker contains the Phase A commit, so none could state the requirement truthfully. Each release's `release-exception.md` carries it instead, and Phase C does not create the Skein v1 stamp either (`PLAN-Dsp-001.TC3`, `PLAN-Dsp-001.TC4`).
- Release gates at publication: devflow 24/162, kanban 77/437, agent-harness 221/1357, all green with format and lints clean. The compatibility alarms matched their records — devflow `v5` and kanban `v9` fail only on the removed `module` vars, agent-harness `v13` stayed green at 220/1349.
- Two Phase B corrections shaped what Phase C can assume. A subagent-executor test-helper fallback in the agent-harness candidate would have passed Phase A and failed Phase C once the grammar keys go, so `ct.spools.executors.subagent` gained a `spool` var and the fallback went (kanban note `ieosb`). Devflow and agent-harness also stopped declaring explicit entry-point keys for kanban by pinning the reviewed `v10` candidate in release order (note `qdwsy`). Both mean the pinned sibling suites carry no explicit entry-point keys into `make spool-suite-gate` after the pin bump.

### PLAN-Dsp-001.DN4 Phase C contract merge — 2026-07-24

- The Phase C contract deltas are `DELTA-Dsp-003` (REPL/API) and `DELTA-Dsp-004` (Weaver Runtime). The `::module-opts` entry-point keys are gone from SPEC-003.P5, SPEC-003.C17d's name reservation is unconditional, and the C19 exception approved in note `dp90p` is merged into SPEC-003.C19 verbatim — this is the land where C19's unqualified accretion promise would otherwise have become false (`PLAN-Dsp-001.CM1`, `PLAN-Dsp-001.CM2`). SPEC-004.C45/C46 lost the per-key precedence rule, the complete-declaration bypass, and image mode's explicit-`:contribute` alternative.
- Runtime characteristics are unchanged by this merge and were not intended to change: contribution, publication, reconcile status branching, retained last-good resolved entry points, removal-by-omission teardown, source stamps and the `:unchanged` fast path, ordering, and `reload-code!` all behave as they did in Phase A (`PROP-Dsp-001.NG1`, `ADR-004.P6`). The removal is at the declaration boundary; the coordinator resolves the same `spool` var it already resolved for absent fields.

### PLAN-Dsp-001.DN5 Phase C review corrections — 2026-07-24

Complex-patch review `de9b5b9d` over `8812c39..17ca3e6` (dumps `uu66x`, `0tygw`, `qjldv`; synthesis `zv7dw`) found five items. The correction narrows every fresh declaration seam, keeps one retained-state reader for restart-free removal teardown, adds no new resolution branch, and verifies that all six pinned sibling namespaces carry a `spool` var matching the literals init.clj dropped. The corrections are tracked as three disjoint tasks under strand `ah9rh`:

| Finding | Correction task |
| --- | --- |
| 1 — a freshly authored declaration could still install entry points through `declare-module!`/startup collection, bypassing the `::module-opts` refusal | `h19rz` (core) |
| 4 — a removed key failed with generic spec prose, or with "must be fully qualified" for the common unqualified form, instead of naming the key and the `def spool` remedy | `uh65v` (api diagnostic) |
| 2, 3, 5 — contract and test-coverage corrections below | `wrzkr` (contracts and config) |

- Finding 2: `DELTA-Dsp-004`'s summary and D1 said no declaration could reach the coordinator with an entry point, while D3 required pre-cutover graph data to stay readable and the code relies on that branch for restart-free removal teardown. Both are now qualified to newly authored declarations, and new `DELTA-Dsp-004.D3a` records the removal trigger: the retained reader goes when no supported live weaver generation predates the cutover, not before, because it is what runs a pre-cutover module's `:removed` reconcile (`PLAN-Dsp-001.R2`).
- Finding 3: SPEC-004.C46 promised an image-mode message naming both remedies while the two failure branches each named one. C46 now states plainly that both branches carry the same message; `h19rz` lands that message and asserts it in the unloaded and loaded-missing-contribution tests.
- Finding 5: retiring the sibling parity test left no in-repo check that a pinned sibling still exposes a usable entry-point declaration, so a pin bump would have surfaced as a coordination-world startup failure. `init-modules-resolve-entry-points-by-convention` now runs inside a started config runtime and requires a valid public `spool` var carrying `:contribute` from every `:ns` module expected to contribute — the six in-tree spools and all six pinned sibling namespaces — with the macro namespaces and workspace `:file` modules classified as forms-only.
- The `.skein/init.clj` header no longer claims every module resolves entry points from a `spool` var; `attention.clj`, `nvd_scan.clj`, `config.clj`, `analytics.clj` and the macro namespaces contribute only collected authoring forms and declare none.
