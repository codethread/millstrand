# Spool Activation Lifecycle ADR Proposal

**Document ID:** `PROP-Sal-001`
**Last Updated:** 2026-07-23
**Related RFCs:** None
**Related root specs:** [SPEC-003 repl-api](../../specs/repl-api.md) (C23, C23a), [SPEC-004 daemon-runtime](../../specs/daemon-runtime.md) (C74a)

## PROP-Sal-001.P1 Problem

Epic waq0l retires the legacy imperative spool `install!` entry points so the module lifecycle (`runtime/module!` + `:contribute`/`:reconcile`) is the one activation path for production, tests, and REPL alike. The owner-scoped-live-refresh design record (PROP-Olr-001 and its deltas, archived at `devflow/archive/26-07-22__owner-scoped-live-refresh/`) already settled most of the constraints, but those decisions are spread across an archived proposal, two spec deltas, gate-ruling strand notes (s19nn, s9dp0, mtl40), and a blind-probe design document. Later features in the epic — and future agents — need one durable, citable record so nothing is silently re-litigated, plus a ruling on the decision points the epic adds: singleton engine registration (A), image-module activation (B), completion of the installer-removal direction (C), and the reconcile applied/removed contract (D).

## PROP-Sal-001.P2 Goals

- **PROP-Sal-001.G1:** One accepted ADR (`devflow/adrs/0003-*.md`) records the already-decided constraints with citations, so downstream features cite the ADR instead of re-deriving from the archive.
- **PROP-Sal-001.G2:** Decisions A–D are taken and recorded with rationale and rejected alternatives, under the epic's pre-granted authority (no HITL pause).
- **PROP-Sal-001.G3:** The test-design conventions inherited from the gate riders (R5 and R6 of strand note s9dp0, R6 in the form amended by the gate-2 staleness ruling in strand note mtl40) and the exported-base-declaration pattern are recorded as the conversion conventions the in-tree and sibling features follow (scoped in S6).

## PROP-Sal-001.P3 Non-goals

- **PROP-Sal-001.NG1:** No code, spec-file, or grammar changes ship in this feature. Decision B's grammar amendment and decision D's spec delta are implemented by the core feature (card fbr4m); decision C's installer deletions are implemented by the in-tree and sibling features (cards rrvnn, 9snqu, kst0n).
- **PROP-Sal-001.NG2:** No re-opening of decisions the OLR record closed (REPL-as-declarative-source, kernel resource callbacks, per-key upsert, clear-and-replay).

## PROP-Sal-001.P4 Proposed scope

- **PROP-Sal-001.S1:** ADR-0003 following the ADR-002 format precedent: context, constraining prior decisions with citations, the four decisions with options and rationale, consequences for the remaining epic features, revisit conditions.
- **PROP-Sal-001.S2:** Decision A records the choice already shipped by the chime-engine-parity feature (reconcile-owned singleton registration, shell precedent, mtl40 sanction) and states the per-domain rule of thumb: identity-stable singleton live resources stay reconcile-owned with applied/removed branching; enumerable per-key entries go as contribution kinds with removal-by-omission.
- **PROP-Sal-001.S3:** Decision B rules on amending the closed module-declaration grammar (DELTA-OlrRepl-001.CC3) with an image-trusting load mode (working name `{:load :image}`): valid only alongside an `:ns` target (never `:file`), skip source load, require explicit `:contribute`, fail loudly when the namespace is not loaded; source-loading behavior for all other declarations is unchanged. Direction: adopt — the coordinator's no-reachable-source branch already trusts the live image, DELTA-OlrDrt-001.CC12 classifies classpath ownership structurally, and the measured recurring cost (~+2.4s suite, 13–14% focused on the 7 affected namespaces) is otherwise permanent. Rejected alternative recorded: JVM-wide source stamps (requires byte-exact load provenance that does not exist).
- **PROP-Sal-001.S4:** Decision C records that deleting spool installers completes PROP-Olr-001.G9/S7 after an explicit deferral: peers already had install-only removal (F17), while the in-tree reference-spool installers were deliberately left out of scope by the OLR docs task (PLAN-Olr-001.DN25: "Out of scope and deliberately untouched") so that task could hold its no-code-changes constraint. The epic executes the deferred removal; nothing in the record decided the installers should remain.
- **PROP-Sal-001.S5:** Decision D formalizes the reconcile contract implied by DELTA-OlrRepl-001.CC6 and the coordinator: reconcile is invoked only for `:applied` and `:removed` contribution statuses (unchanged contributions skip invocation entirely, `module_refresh.clj` `reconcile-one`), a reconciler branches on the status it receives, and unexpected statuses fail loudly. Shell models the branch; batteries degrades removal; chime had no registration at all. The ADR names where the spec text lands (SPEC-003/SPEC-004 delta plus the `module!` docstring — shipped by the core feature).
- **PROP-Sal-001.S6:** The ADR records the conversion conventions the in-tree and sibling features follow: gate riders R5 and R6 from note s9dp0 (R6 in the form amended by the mtl40 staleness ruling — structural classpath ownership shipped, the hardcoded-whitelist language is stale), the workflow-before-executor activation ordering (note 4q8cg), and the exported-base-declaration pattern (spool exports its declaration as data; production `init.clj` assocs `:spools` guards; tests pass the base datum).

## PROP-Sal-001.P5 Open questions

- **PROP-Sal-001.Q1:** None. User sign-off on decisions A/B is pre-granted under the epic waq0l AUTHORITY section; the ADR records the decisions and their rejected alternatives, and the card note carries the summary.
