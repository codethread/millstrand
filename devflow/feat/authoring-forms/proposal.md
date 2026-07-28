# Authoring forms program proposal

**Document ID:** `PROP-Auf-001`
**Last Updated:** 2026-07-28
**Related RFCs:** [`RFC-Auf-001`](../../rfcs/2026-07-28-authoring-forms.md) — the merged decision record this program executes; it owns the design, alternatives, and cutover policy
**Related root specs:** [`devflow/specs/repl-api.md`](../../specs/repl-api.md) (C17c/C17d/C19), [`devflow/specs/daemon-runtime.md`](../../specs/daemon-runtime.md) (C45/C46/C46b/C46c/C74a), [`devflow/specs/alpha-surface.md`](../../specs/alpha-surface.md)
**Related proposals:** [`PROP-Sld-001`](../spool-lifecycle-docs/proposal.md) (documents the surface this program removes), [`PROP-Dsp-001`](../uwnzl-def-spool-convention/proposal.md) (the `def spool` convention whose endpoint this supersedes), [`rrvnn`](../rrvnn-intree-installer-removal/proposal.md), [`9snqu`](../9snqu-siblings-rollout/proposal.md), [`rtnfv`](../rtnfv-consumer-cutover/proposal.md), [`fbr4m`](../fbr4m-core-reconcile-image/proposal.md), [`ifenn`](../ifenn-chime-engine-parity/proposal.md)

## PROP-Auf-001.P1 Problem

Skein presents two extension grammars for one idea (authoring forms beside `:contribute` maps) and hides every module's lifecycle behind one `:reconcile` callback, all pointed at by a `def spool` var that exists only as bookkeeping. `RFC-Auf-001` records the decision: replace both entry points with named top-level authoring forms, remove the `def spool` convention entirely, and take the TEN-000@1 break the user has accepted — minimized by ordering (ship the forms, migrate everything, then remove) rather than by compatibility machinery.

That change is a program, not a feature. The audit below counts 19 modules across this repo, its `.skein` workspace, and three pinned sibling repos still on the old grammar; eight spec clauses and three ADRs govern the surface; the lifecycle half is gated on a feasibility spike; and an active v1-stamp card constrains when the break may land. Without a program frame, feature slicing would either stall on the lifecycle unknowns or land the break before the ecosystem can follow. This proposal is the scope authority for the kanban feature cards that will implement the program; slicing into features and tasks happens after sign-off, against this document.

## PROP-Auf-001.P2 Audited baseline

Verified against source on 2026-07-28 (three delegated audits; findings as run results under the proposal-stage orient step, run ids `eu5fj`, `rtu5a`, `wye1w`).

**Surface.** `defop`/`defquery`/`defpattern`/`defrule` are repo-local prototypes in `.skein/spools/macros/` calling the internal `skein.core.weaver.module-refresh/collect-entry!`; `defop` cannot express override intent. The blessed `skein.api.runtime.alpha/collect-entry!` (alpha.clj:351) does accept `{:override? true}`, and `defjob`/`defworkflow` already ride it. Image mode (`:load :image`) evaluates no source and requires a resolvable `:contribute` (module_refresh.clj:440); collected forms and `:contribute` are already mutually exclusive (SPEC-004.C46/C46c). Kinds are declared via `skein.api.registry.alpha/declare-kind!`; Cron, Workflow, and Chime bootstrap their kinds inside `contribute`. The devflow sibling spool is live proof of the end state in source mode — no `spool` var, contribution purely via collected `defworkflow` — but nothing yet proves image replay.

**Census.** Inclusion rule: modules in the selected source universe — this repo's `spools/`, its tracked `.skein` config, and the pinned sibling roots in `.skein/spools.edn`. 19 modules carry a `spool` var: 13 here (batteries, cron, chime, workflow, workflow-cli, shell executor, code executor, guild, unsafe-text-search, plus the `.skein` kanban-tracker, harnesses, reviewers, and module-adapters) and 6 sibling (kanban, kanban-peering, delegation, agent-run, subagent executor, bench). 17 declare `:contribute` and 17 declare `:reconcile` (per-module verification in review note `bp5vo`). Irregulars the lifecycle design must carry: the shell executor's worker pool is a runtime-lifetime resource (survives module removal, closes at runtime stop); Guild resets and republishes runtime-owned declarations on both transitions; three `.skein` modules are unconditional singleton setters (kanban tracker, harness contracts, help transform) whose removal branch does not undo the binding; batteries, workflow, workflow-cli, and kanban have no-op removals (process-lifetime seeds).

**Context.** The active feature chain `fbr4m`/`rdrw9` → `rrvnn`/`9snqu` → `rtnfv` is converting installer-era activation onto the current `def spool` convention — this program supersedes that endpoint but depends on those migrations landing first, so the universe it breaks is uniform. `ifenn-chime-engine-parity` moves Chime registration into reconcile, which the lifecycle spike then uses as its atomic-cluster case. `spool-lifecycle-docs` (PROP-Sld-001) documents the very contract this program removes; its output becomes a migration source and must be rewritten in the removal stage. The release regime permits a clean pre-v1 break for Skein itself; after a Skein v1 stamp, removal would need a new name. The siblings are already past their own v1, so their migration releases are recorded breaks under the installer-retirement precedent (TEN-000@1, explicit per-release exception), not accretive releases. The v1-stamp card (`b3v1r`, p1, hitl) is active but explicitly waiting on user direction.

## PROP-Auf-001.P3 Goals

- **PROP-Auf-001.G1:** One shipped authoring grammar for contributions: core-kind forms promoted into `skein.api.*` surface with explicit overrides, contractual image replay, a pre-publication kind declaration, and a factory/batch path for generated entries (RFC-Auf-001.P6, AC1–AC10).
- **PROP-Auf-001.G2:** A lifecycle authoring surface accepted only through the bounded feasibility spike and its gates (RFC-Auf-001.REC8, P16.2), prototyped against Cron, Chime, the shell executor, a process-lifetime seed, and a workspace singleton.
- **PROP-Auf-001.G3:** Every module in the audited census migrated with proven parity — equal normalized contributions, exact removal-by-omission, lossless lifecycle behavior — including the `.skein` workspace modules and sibling releases with markers, pin bumps, floors, and per-release recorded-break compat-alarm evidence.
- **PROP-Auf-001.G4:** One final break: `:contribute`, `:reconcile`, and the `def spool` convention removed together, old declarations rejected loudly with errors naming the replacement forms, no shims or aliases (RFC-Auf-001.P15.S3).
- **PROP-Auf-001.G5:** The break sequenced against the v1 stamp: it lands before any Skein v1 marker stamps the accretion promise, or the stamp records the pending exception first.
- **PROP-Auf-001.G6:** Governing records amended, not orphaned: ADR-002/003/004, SPEC-003.C17c/C17d/C19, SPEC-004.C45/C46/C46b/C46c/C74a, `UBIQUITOUS-LANGUAGE.md`'s Contribute/Reconcile/`def spool` entries, the spool authoring guide, and PROP-Sld-001's documentation output.

## PROP-Auf-001.P4 Non-goals

- **PROP-Auf-001.NG1:** No re-litigation of the design. Alternatives, rejected options, and the cutover policy live in RFC-Auf-001; this proposal does not restate them.
- **PROP-Auf-001.NG2:** No registry-kernel changes: owner-partitioned contribution semantics, collision policy, publication atomicity, and removal-by-omission are preserved (RFC-Auf-001.NG1).
- **PROP-Auf-001.NG3:** No compatibility machinery: no alias or fallback, and no module mixing grammars. The bounded dual-grammar migration window exists only so sibling floors can reach the S1 marker (RFC-Auf-001.P15.S2); it is transitional, not a supported authoring contract.
- **PROP-Auf-001.NG4:** No feature slicing or task detail here. Kanban feature cards are cut against this document after sign-off; their plans own implementation strategy.
- **PROP-Auf-001.NG5:** No v1-stamp decision here. The stamp is user-owned (`b3v1r` is hitl); this program only records the sequencing constraint G5.

## PROP-Auf-001.P5 Proposed scope

- **PROP-Auf-001.S1 — Contribution authoring surface.** Promote the core-kind forms out of `.skein/spools/macros` into shipped API namespaces; add override intent, the replayable declaration record with image replay, the declarative kind declaration replacing contribute-time bootstrap (Cron, Workflow, Chime), the factory/batch path, and public testing support through the production module path.
- **PROP-Auf-001.S2 — Lifecycle spike, then surface.** Run the feasibility spike against the five named migrations; accept form names and shapes only through the RFC's gates; then ship the lifecycle engine, collection, validation, retained state, and inspection projections. The `.skein` singleton setters and no-op removals migrate to honest declarations (a binding gains a real unset or is deliberately classified process-lifetime — the current unconditional-removal defect may not be silently preserved).
- **PROP-Auf-001.S3 — Migration of the census.** All 21 modules: in-tree spools, `.skein` workspace config, and sibling releases (kanban, delegation/agent-run/bench; devflow is already form-only) as marker releases with pin bumps whose entry-point removals are recorded breaks (RFC-Auf-001.P15.S2); parity proven per module before the break; coordinated with (and after) the in-flight `rrvnn`/`9snqu`/`rtnfv` chain.
- **PROP-Auf-001.S4 — The removal.** `:contribute` and `:reconcile` leave the `::spool` grammar and the convention resolver; callback retention is deleted once declaration retention covers removal-by-omission; old `spool` vars fail with actionable migration errors; quality gates enforce the new grammar.
- **PROP-Auf-001.S5 — Records and docs.** RFC status transitions; ADR supersessions and amendments; root-spec deltas including the recorded SPEC-003.C19 accretion exception; `UBIQUITOUS-LANGUAGE.md`; `docs/spools/writing-shared-spools.md` and the PROP-Sld-001 material rewritten to teach only the new grammar.

## PROP-Auf-001.P6 Program constraints for slicing

Constraints the post-sign-off feature breakdown must hold, stated once so cards need not rediscover them: S1 lands before any migration feature; the S2 spike lands before any lifecycle-surface feature; no migration feature starts on a sibling until the forms it needs are in a tagged Skein release the sibling can pin; S4 is one feature and lands last, after the selected source universe is clean; features touching the same module own disjoint slices. Everything else — ordering within stages, worktrees, delegation — belongs to the cards and their plans.

## PROP-Auf-001.P7 Open questions

- **PROP-Auf-001.Q1:** v1-stamp sequencing (G5): does the user hold `b3v1r` until this program's break lands, or stamp v1 with a recorded pending exception? User decision; blocks scheduling of S4 only.
- **PROP-Auf-001.Q2:** The lifecycle surface itself: form names, minimum surface, and the P16.2 gate outcomes are unresolved until the S2 spike reports (RFC-Auf-001.Q6–Q19).
- **PROP-Auf-001.Q3:** Disposition of overlapping in-flight features: does `rrvnn`/`9snqu` complete its `def spool` conversions as the stepping stone (recommended: a uniform universe migrates mechanically), or do late items re-target directly to authoring forms? Decide when the first migration feature is cut.
- **PROP-Auf-001.Q4:** SPEC-004.C46b's disposition — unchanged, split between coordinator mechanics and lifecycle-form semantics, or replaced (RFC-Auf-001.P16.2).
- **PROP-Auf-001.Q5:** Namespace ownership for promoted forms — colocated per API (`skein.api.weaver.alpha/defop`, ...) or a central macros namespace (RFC-Auf-001.Q5); decide in the S1 feature's plan.
