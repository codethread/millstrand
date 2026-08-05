# Core Reconcile Contract + Image-Module Activation Proposal

**Document ID:** `PROP-Cri-001` **Last Updated:** 2026-07-23 **Related RFCs:** None **Related ADRs:** [ADR-003](../../adrs/0003-spool-activation-lifecycle.md) (P4 decision B, P6 decision D) **Related root specs:** [SPEC-003 repl-api](../../specs/repl-api.md) (P5, P6), [SPEC-004 daemon-runtime](../../specs/daemon-runtime.md) (C45, C46, C94)

## PROP-Cri-001.P1 Problem

ADR-003 settled two core decisions that the install! retirement needs shipped in skein-src before any fixture conversion can start:

1. **Decision D (reconcile contract).** Every module `:reconcile` fn receives a context whose `:module/contribution` carries `:status` `:applied` or `:removed`, but no spec text or docstring states what a reconciler must do with it. Two of three first-party patterns got it wrong (batteries re-registers unconditionally and degrades removal; chime had no registration at all until `2af05d7`) — the gap that hid a live production outage.

2. **Decision B (image activation, adopted).** Converting ~76 fixture activations from `install!` to `module!` costs ~+2.4s per suite run because activation reloads classpath source that is already loaded in the JVM image. The declaration grammar needs `{:load :image}` so a declaration can trust the live image, with the loud failure modes ADR-003.P4 fixes.

## PROP-Cri-001.P2 Goals

- **PROP-Cri-001.G1:** The reconcile applied/removed contract is normative spec text plus `module!` docstring prose, and a coordinator-level test proves a removal-path reconcile receives `:status :removed` (and an applied-path reconcile `:applied`; an unchanged contribution skips reconcile).
- **PROP-Cri-001.G2:** `{:load :image}` is a validated key of the closed module declaration grammar: `:ns`-target only, requires explicit `:contribute`, refused loudly otherwise with the module key, offending value, and allowed alternatives in the error.
- **PROP-Cri-001.G3:** An image-mode activation performs no source load during collection, and a declared-but-unloaded namespace fails loudly at evaluation as that module's `:failed` outcome.
- **PROP-Cri-001.G4:** `plan`/`status` output states an image module as image-owned with no source stamp (`:source/status :image`, no `:source/stamp`), keeping the declaration printable data.
- **PROP-Cri-001.G5:** The grammar change lands as root-spec deltas (SPEC-003 P5/P6 prose; SPEC-004 C45/C46 territory), merged on land.

## PROP-Cri-001.P3 Non-goals

- **PROP-Cri-001.NG1:** Converting in-tree spools or test fixtures — feature rrvnn consumes the grammar this feature ships.
- **PROP-Cri-001.NG2:** JVM-wide source-stamp fast path — rejected in ADR-003.P4; do not attempt.
- **PROP-Cri-001.NG3:** Kernel-level reconcile enforcement callbacks — CC13 forbids generic resource/effect callbacks in the publication kernel. The existing behavior (a throw during reconcile degrades the module to `:degraded`) is already the loud channel for a reconciler that re-registers into a duplicate check on removal; this feature documents it and proves the status delivery, nothing more.
- **PROP-Cri-001.NG4:** New lint-conventions rules for reconciler source shape — a source-text heuristic for "branches on status" is fragile and dishonest next to the runtime channel in NG3.

## PROP-Cri-001.P4 Proposed scope

- **PROP-Cri-001.S1 (grammar):** `module-graph/declaration-keys` gains `:load`. `normalize-declaration` validates it closed: the only accepted value is `:image` (anything else is refused naming the allowed set); `:load :image` with a `:file` target is refused (image mode accepts only `:ns`); `:load :image` without `:contribute` is refused (no source evaluation happens, so no authoring-form collection can exist). Every refusal is an `ex-info` carrying `:module/key`, the offending value, and the allowed alternatives (TEN-003, ADR-003.P4). These throw out of `module!` on the declare path, exactly like today's grammar refusals.
- **PROP-Cri-001.S2 (evaluation):** `evaluate-module` takes an image branch before any collection scope or `load-source!` call: when the declaration carries `:load :image`, it performs no source load and no contribution-collection scope at all; the contribution comes from the declared `:contribute` fn as today, and the outcome carries `:source/status :image` and no `:source/stamp`. `load-source!` itself is untouched — it stays the non-image path, including its implicit no-reachable-source arm. The ADR's "refusal" of an unloaded namespace maps to the established per-module evaluation-failure channel, exactly like a missing synced source: the module reports `[:modules key :status] = :failed` (top-level result normally `:partial`), never a top-level `:refused` and never a throw out of `module!`; `:required?` semantics are unchanged (its escalation covers prerequisite problems per SPEC-004's module-refresh C94, not evaluation failures). The `:failed` outcome's error data satisfies ADR-003.P4's message discipline: the module key, the offending namespace value, and the allowed alternative (the namespace must be loaded/required before an image module activates). Non-image declarations are byte-for-byte untouched.
- **PROP-Cri-001.S3 (honesty):** `plan` and `status` need no new plumbing: the per-module outcome (`:source/status :image`, absent stamp) and the introspectable declaration (`:load :image` in the graph) are the honest signals, mirroring CC14's caveat style. Verified by test, not assumed.
- **PROP-Cri-001.S4 (reconcile contract text):** SPEC-004 gains a clause beside C46 stating the contract: the coordinator invokes `:reconcile` only for `:applied` and `:removed` contribution statuses and skips unchanged contributions; a reconciler branches — applied ensures live resources and registrations, removed tears them down; running registration effects on `:removed` is a defect; a status outside the set (reachable only by direct call) fails loudly naming the received status, the allowed set, the module, and the reconciler it happened in (ADR-003.P6). A reconcile throw degrades that module's outcome to `:degraded` (existing `reconcile-one` behavior — `reconcile-one` needs no code change; it is the coordinator contract and test seam, now contractual). The `skein.api.runtime.alpha/module!` docstring carries the same contract in one sentence.
- **PROP-Cri-001.S5 (spec deltas):** SPEC-003 P5's `module!` helper prose and P6 example gain `:load :image`; the `::module-declaration` result spec accepts the optional normalized `:load` key. Deltas authored under `devflow/feat/fbr4m-core-reconcile-image/specs/` and merged into the root specs in this branch (repo discipline: root specs updated with the behavior change). The module-refresh C94 clause is cited, not changed; note daemon-runtime.md carries two distinct clauses numbered C94 (a storage clause and the module-refresh clause), so the delta identifies its target by text, not number alone.
- **PROP-Cri-001.S6 (tests):** In `skein.core.weaver.modules-test` (coordinator tier) and `skein.api.runtime.alpha-test` (API tier): grammar refusals (bad `:load` value, `:file` + `:load :image`, missing `:contribute`) with actionable ex-data; image activation on a bare runtime publishes the contribution without any source load (asserted via the namespace-load ledger / `:collection/reload?` evidence, not wall clock); unloaded namespace refusal; `plan`/`status` show `:source/status :image` and no stamp; `refresh! {:only}` re-evaluates an image module without loading source; reconcile contract delivery (removal-path ctx `:status :removed`, applied-path `:applied`, unchanged skips).

## PROP-Cri-001.P5 Open questions

- **PROP-Cri-001.Q1:** None — ADR-003 settled the design; this proposal only fixes the mechanical landing sites.
