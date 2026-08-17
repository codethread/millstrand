# Weaver Runtime delta for reload-spool fingerprint refresh

**Document ID:** `DELTA-Rsf-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Draft **Last Updated:** 2026-07-19

## DELTA-Rsf-001.P1 Summary

Two lifecycle amendments: a completed `reload-spool!` converges the redefinition baseline, and a sync succeeding with zero per-root failures clears the recorded pending generation. SPEC-004.C46 and SPEC-004.C44d gain the contract; C44c classification is untouched.

## DELTA-Rsf-001.P2 Contract changes

- **DELTA-Rsf-001.CC1:** Amend SPEC-004.C46: once every namespace of the root loads, `reload-spool!` records the root's fresh generation fingerprint — loaded-namespace entries hash the exact bytes compiled, so a file racing the reload can only make the baseline conservative (a later sync refuses, never silently accepts) — and the completed hot bump stops classifying as a SPEC-004.C44c redefinition and later `sync!`/`reload!` calls pass without a restart. A failed or partial reload records nothing — a half-reloaded root genuinely diverges from disk, and the outstanding refusal is the truthful signal.
- **DELTA-Rsf-001.CC2:** Amend SPEC-004.C44d: the recorded `:pending-generation` stays visible through `syncs` and later sync results until a `sync!` succeeds with zero per-root failures — only such a pass classifies every previously loaded root, so it proves no refused class sync classifies remains on disk and clears the record — or the weaver process is replaced. A sync succeeding around per-root `:failed` roots proves nothing about them (a broken root can temporarily hide a repoint, redefinition, or Maven bump) and leaves the record standing.

## DELTA-Rsf-001.P3 Design decisions

### DELTA-Rsf-001.D1 Converge the baseline, not the classification

- **Decision:** Keep C44c refusing exactly as today; make the completed hot bump update the fingerprint baseline it is judged against, and let a provably clean sync retire the stale restart advertisement.
- **Rationale:** The refusal was correct while loaded code diverged from disk; after `reload-spool!` completes, they agree, so the baseline is what is stale — not the rule. Requiring zero per-root failures for the pending clear closes the only evasion path (an unclassifiable root).
- **Rejected:** Relaxing redefinition classification (weakens the generation model); clearing pending from the read-only reload preflight (its one permitted mutation stays the refusal record); clearing on any successful sync (fails the hidden-class scenario above).

## DELTA-Rsf-001.P4 Open questions

None.
