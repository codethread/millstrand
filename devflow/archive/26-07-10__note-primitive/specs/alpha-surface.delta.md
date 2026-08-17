# Alpha Surface delta for note-primitive

**Document ID:** `SPEC-Np-002` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Np-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-10

## SPEC-Np-002.P1 Summary

F3 introduces one new blessed spool-facing namespace, `skein.api.notes.alpha` (the note primitive `note!`/`notes`, `PROP-Np-001.C4`), so this is a real alpha-surface delta — unlike F2's `SPEC-Aep-002`, which was a no-change disposition. The tier line `SPEC-005.C2` enumerates the blessed `skein.api.*.alpha` namespaces by name (line 12); this delta ADDS `notes` to that enumeration and extends the "where each is specified" parenthetical so the sentence stays true for the new namespace (`PROP-Np-001.C12`). No tier boundary moves and no other clause changes: the batteries verbs are in-contract via `spools/batteries.md` (`SPEC-005.C3`), the kanban/delegation writers are repo-local userland (`SPEC-005.C4`), and the `db.clj` relation declaration is `skein.core.*` internal storage init surfaced through the strand-model spec and the catalog (`SPEC-Np-001`). These are exact editing instructions applied at implementation time.

## SPEC-Np-002.P2 Contract changes

- **SPEC-Np-002.CC1** (edit, `SPEC-005.C2`, line 12): add `notes` to the enumerated blessed set (alphabetical, between `hooks` and `patterns`) and extend the specification parenthetical so `notes` points at its contract home. `skein.api.notes.alpha` follows the `skein.api.relations.alpha` precedent — a graph-vocabulary alpha namespace whose behavioral contract is the strand model, not a `SPEC-003.P4` explicit-runtime helper entry — so it is cited the same way `relations` is, through this parenthetical exception rather than a `repl-api.md` addition (see `SPEC-Np-002.P4.F1`). The `notes` relation is `SPEC-001.P5` (added by `SPEC-Np-001.CC1`/`CC2`) and the `note/*` attribute vocabulary it writes is the `SPEC-001.P4` roster (line 34), so `SPEC-001` is the citation.

    Old:

    ```text
    - **SPEC-005.C2:** The blessed spool-facing API is every `skein.api.*.alpha` namespace — currently `batch`, `cli`, `current`, `events`, `format`, `graph`, `hooks`, `patterns`, `peers`, `relations`, `runtime`, `scheduler`, `views`, `weaver` — plus `skein.test.alpha`, `skein.userland.alpha`, and the human-facing `skein.repl` helpers. Each is specified in SPEC-003/SPEC-004 (relations in SPEC-001.P5) and follows accretion-based compatibility within its subnamespace.
    ```

    New:

    ```text
    - **SPEC-005.C2:** The blessed spool-facing API is every `skein.api.*.alpha` namespace — currently `batch`, `cli`, `current`, `events`, `format`, `graph`, `hooks`, `notes`, `patterns`, `peers`, `relations`, `runtime`, `scheduler`, `views`, `weaver` — plus `skein.test.alpha`, `skein.userland.alpha`, and the human-facing `skein.repl` helpers. Each is specified in SPEC-003/SPEC-004 (relations in SPEC-001.P5, notes in SPEC-001) and follows accretion-based compatibility within its subnamespace.
    ```

## SPEC-Np-002.P3 Dispositions (no change)

- The `notes`-relation catalog entry (`PROP-Np-001.C2`) touches `skein.api.relations.alpha`, already blessed alpha surface (`SPEC-005.C2`); its `relations_test` catalog-set assertion updates with it. Source code carrying accretion-based compatibility within its own subnamespace — `SPEC-005` indexes the catalog namespace but enumerates none of its entries, so adding one entry moves no `SPEC-005` contract text. Applied in the implementation plan, not here (mirrors `SPEC-Aep-002.P2`).
- `strand note` / `strand notes` are batteries ops, in-contract through `spools/batteries.md` (`SPEC-005.C3`), not this index (`PROP-Np-001.C5`, `C12`).
- The kanban and delegation note-writer migrations (`PROP-Np-001.C6`/`C7`) are repo-local approved userland (`SPEC-005.C4`); their READMEs/docs are their own contracts.
- The `"notes"` addition to `shipped-acyclic-relations` in `db.clj` (`PROP-Np-001.C1`) is `skein.core.*` internal storage init (`SPEC-005.C5`) — no alpha-surface entry; it is surfaced through the strand-model spec (`SPEC-Np-001`) and the catalog.

## SPEC-Np-002.P4 Flagged (out of scope for F3)

- **SPEC-Np-002.F1:** No `repl-api.md` (`SPEC-003`) delta for `note!`/`notes`. Every other `skein.api.*.alpha` namespace carries a per-function contract in `SPEC-003.P4`/`P4a`/`P5` except `relations` (contract in `SPEC-001.P5`, cited via the `SPEC-005.C2` parenthetical), `cli` (`SPEC-003.P5b`), and `weaver` (`SPEC-004`). `notes` deliberately follows the `relations` pattern: its contract is the strand model (relation + `note/*` roster) plus the batteries doc for the CLI verbs, per `PROP-Np-001.C12`, which scopes the namespace's disposition to the `SPEC-005.C2` enumeration edit and adds no `SPEC-003` entry. If a maintainer later wants an explicit in-process `note!`/`notes` signature contract, `SPEC-003.P4` is its home; the proposal (signed off) does not ask for one, and this plan does not widen it.
- **SPEC-Np-002.F2:** No tier boundary moves. `skein.api.notes.alpha` is a shipped blessed namespace from the Skein checkout (like `relations`), not a reference spool or repo-local userland spool, so `SPEC-005.C3`/`C4` are unchanged and no reference-spool doc is introduced for it.
