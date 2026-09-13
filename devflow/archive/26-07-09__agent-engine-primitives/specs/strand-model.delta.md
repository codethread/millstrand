# Strand Model delta for agent-engine-primitives

**Document ID:** `SPEC-Aep-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Aep-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-09

## SPEC-Aep-001.P1 Summary

This is the F2 behavioral delta for the relation vocabulary. F2 makes `serves` the single engine-owned encoding for "which run serves strand X" (`PROP-Aep-001.C1`), returning `parent-of` to structure-only. The strand model's Edges section (`SPEC-001.P5`) names the shipped declared-acyclic relation set in one sentence (line 48) and the advisory catalog in another (line 52). This delta ADDS `serves` to the shipped declared-acyclic set and ADDS one contract sentence introducing `serves` as an engine-owned operational relation while clarifying that `parent-of` carries structural hierarchy only. `supersedes` already ships declared-acyclic (line 48) and needs no change; run succession (the `supersede-and-respawn` lineage family, `PROP-Aep-001.C4`–`C6`) is spool-level behavior documented in the agent-run spool docs (`PROP-Aep-001.C10`), not the strand model, so no lineage statement is added here. These are exact editing instructions applied at implementation time.

## SPEC-Aep-001.P2 Contract changes

- **SPEC-Aep-001.CC1** (edit, `SPEC-001.P5`, line 48): add `serves` to the shipped declared-acyclic enumeration. This is the doc mirror of adding `"serves"` to `shipped-acyclic-relations` in `src/skein/core/db.clj` (`PROP-Aep-001.C1` "declared acyclic"; `PROP-Aep-001.C5` records `supersedes` as already shipped-acyclic there). Token addition only; the surrounding acyclicity contract (self-edge prohibition, cycle rejection, userland extension) is unchanged.

    Old:

    ```text
    A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, and `supersedes` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.
    ```

    New:

    ```text
    A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, `supersedes`, and `serves` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.
    ```

- **SPEC-Aep-001.CC2** (ADD, `SPEC-001.P5`, after line 48): add one contract sentence naming `serves` as an engine-owned operational relation and stating the `parent-of` structural-only clarification (`PROP-Aep-001.C11` bullet 1). Insert as a new paragraph immediately after the acyclic-declaration paragraph edited by `SPEC-Aep-001.CC1` (line 48) and before the self-edge paragraph (line 50). Verbatim text to add:

    > The `serves` relation is an engine-owned operational edge from a run to the strand whose own work that run carries out (run `--serves-->` served-target); it is the single durable encoding of that delegation and is declared acyclic. `parent-of` expresses structural hierarchy and placement only — a reader never infers serving from a `parent-of` edge — so a run placed structurally beneath a strand and a run serving that strand are recorded by distinct relations.

    Rationale for placement: `SPEC-001.P5` is the Edges section that governs the named relation vocabulary and its acyclicity, so the `serves`-vs-`parent-of` distinction belongs beside the acyclic declaration rather than in a spool doc. The behavioral consumers of the distinction (delegation guards, gate recovery, run-summary `:for`) stay in the repo-local userland spool docs (`PROP-Aep-001.C10`, `SPEC-005.C4`).

## SPEC-Aep-001.P3 Companion (code, not a doc edit here)

- The advisory catalog `src/skein/api/relations/alpha.clj` gains a matching `serves` operational entry (`:family :operational`, `:direction "run --serves--> served-target"`, `:declared-acyclic? true`), and its `test/skein/relations_test.clj` catalog-set assertion updates with it (`PROP-Aep-001.C11` bullet 2). The catalog is source-visible alpha code, not part of this root-spec index; `SPEC-001.P5` line 52 already describes the catalog generically (it enumerates no entries), so adding one entry changes no strand-model contract text and is carried in the implementation plan, not this delta.

## SPEC-Aep-001.P4 Flagged (out of scope for F2)

- **SPEC-Aep-001.F1:** No lineage/run-succession statement is added to the strand model. `supersedes` already ships declared-acyclic and is already in the catalog; the `supersede-and-respawn` primitive, the `agent-run/supersedes` attr mirror, and the "current run serving X" resolution rule (`PROP-Aep-001.C4`–`C6`) are agent-run engine behavior whose contract is the agent-run spool README/cookbook (`PROP-Aep-001.C10`), classified repo-local userland by `SPEC-005.C4`. The strand model names the relations; it does not describe how the engine writes them.
