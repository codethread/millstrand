# Reload-spool fingerprint refresh proposal

**Document ID:** `PROP-Rsf-001` **Last Updated:** 2026-07-19 **Related RFCs:** None **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004.C46, SPEC-004.C44c, SPEC-004.C44d)

## PROP-Rsf-001.P1 Problem

The documented hot-bump flow — `reload-spool!` makes a bumped spool source live, then a targeted re-`use!` or a full `reload!` re-registers (docs/spools/customisation.md) — is permanently broken after any source edit to a loaded root. `reload-synced-spool!` load-files the fresh sources but never refreshes the generation fingerprint baseline, so SPEC-004.C44c's redefinition classification stays outstanding even though loaded code now matches disk exactly. Every subsequent `sync!` — and, since the reload-preflight fix, every `reload!` — refuses with `:non-additive-sync-diff` until a weaver restart, turning the flow that exists to avoid restarts into a restart generator. Separately, the recorded `:pending-generation` from any refusal persists forever (SPEC-004.C44d), so even a config restored to a syncable shape keeps advertising a restart that is no longer needed.

## PROP-Rsf-001.P2 Goals

- **PROP-Rsf-001.G1:** A completed hot bump converges: after `reload-spool!` succeeds for a root, that root stops classifying as a redefinition, and `sync!`/`reload!` pass again without a restart.
- **PROP-Rsf-001.G2:** A partially failed `reload-spool!` does not converge: the baseline stays put, and the outstanding redefinition keeps refusing, because a half-reloaded root genuinely diverges from disk.
- **PROP-Rsf-001.G3:** A `sync!` that succeeds with zero per-root failures clears a stale `:pending-generation` record — only such a pass classifies every previously loaded root, so it proves the on-disk config no longer carries any refused class sync classifies. A sync that merely succeeds around per-root `:failed` roots proves nothing about them (a broken root can temporarily hide a repoint, redefinition, or Maven bump) and leaves the record standing.

## PROP-Rsf-001.P3 Non-goals

- **PROP-Rsf-001.NG1:** Relaxing C44c classification itself. Redefinitions, removals, repoints, and Maven bumps refuse exactly as today; only the baselines and the stale-record lifecycle change.
- **PROP-Rsf-001.NG2:** Clearing pending from the read-only reload preflight; its one permitted mutation stays the C44d record on refusal.
- **PROP-Rsf-001.NG3:** Any change to `reload-spool!`'s no-registration contract or load ordering.

## PROP-Rsf-001.P4 Proposed scope

- **PROP-Rsf-001.S1:** A completed `reload-spool!` stops the root classifying as non-additive; a failed or partial reload changes nothing.
- **PROP-Rsf-001.S2:** A `sync!` succeeding with zero per-root failures clears the recorded `:pending-generation`; any other outcome leaves it standing. SPEC-004.C44d and C46 state both lifecycles.

## PROP-Rsf-001.P5 Open questions

None.
