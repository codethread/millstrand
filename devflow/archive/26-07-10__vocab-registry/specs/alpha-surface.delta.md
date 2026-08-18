# Alpha Surface delta for vocab-registry

**Document ID:** `SPEC-Vr-002` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Vr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged — applied at `PLAN-Vr-001.S9` **Last Updated:** 2026-07-10

## SPEC-Vr-002.P1 Summary

F4 introduces one new blessed spool-facing namespace, `skein.api.vocab.alpha` (the runtime vocabulary registry — `declare!`, `declarations`, `declaration`, `PROP-Vr-001.C2`–`C4`), so this is a real alpha-surface delta, exactly the disposition `skein.api.notes.alpha` took in F3 (`SPEC-Np-002`, `PROP-Np-001.C12`). The tier line `SPEC-005.C2` enumerates the blessed `skein.api.*.alpha` namespaces by name (line 12); this delta ADDS `vocab` to that enumeration and extends the "where each is specified" parenthetical so the sentence stays true for the new namespace (`PROP-Vr-001.C11`). No tier boundary moves and no other clause changes: the `strand vocab` verb is a batteries op in-contract via `spools/batteries.md` (`SPEC-005.C3`), the selvage/carder consumers are classpath reference spools in-contract via their docs (`SPEC-005.C3`), the edge seed reads the already-blessed `relations.alpha` and changes nothing there, and there is no `skein.core.*` change (the registry is runtime spool-state, not storage init — contrast `notes`, which needed a `db.clj` relation declaration). These are exact editing instructions applied at implementation time.

## SPEC-Vr-002.P2 Contract changes

- **SPEC-Vr-002.CC1** (edit, `SPEC-005.C2`, line 12): add `vocab` to the enumerated blessed set (alphabetical, between `views` and `weaver`) and extend the specification parenthetical so `vocab` points at its contract home. `skein.api.vocab.alpha` follows the `skein.api.relations.alpha`/`skein.api.notes.alpha` precedent — a graph-vocabulary alpha namespace whose behavioral contract is the strand model, not a `SPEC-003.P4` explicit-runtime helper entry — so it is cited the same way, through this parenthetical exception rather than a `repl-api.md` addition (see `SPEC-Vr-002.F1`). The registry's contract home is `SPEC-001`: the `SPEC-001.P4` ownership prose that names it and the `SPEC-001.P5` catalog-reflection sentence, both added by `SPEC-Vr-001` (`CC1`/`CC2`), so `SPEC-001` is the citation.

    Old:

    ```text
    - **SPEC-005.C2:** The blessed spool-facing API is every `skein.api.*.alpha` namespace — currently `batch`, `cli`, `current`, `events`, `format`, `graph`, `hooks`, `notes`, `patterns`, `peers`, `relations`, `runtime`, `scheduler`, `views`, `weaver` — plus `skein.test.alpha`, `skein.userland.alpha`, and the human-facing `skein.repl` helpers. Each is specified in SPEC-003/SPEC-004 (relations in SPEC-001.P5, notes in SPEC-001) and follows accretion-based compatibility within its subnamespace.
    ```

    New:

    ```text
    - **SPEC-005.C2:** The blessed spool-facing API is every `skein.api.*.alpha` namespace — currently `batch`, `cli`, `current`, `events`, `format`, `graph`, `hooks`, `notes`, `patterns`, `peers`, `relations`, `runtime`, `scheduler`, `views`, `vocab`, `weaver` — plus `skein.test.alpha`, `skein.userland.alpha`, and the human-facing `skein.repl` helpers. Each is specified in SPEC-003/SPEC-004 (relations in SPEC-001.P5, notes in SPEC-001, vocab in SPEC-001) and follows accretion-based compatibility within its subnamespace.
    ```

## SPEC-Vr-002.P3 Dispositions (no change)

- The edge seed (`PROP-Vr-001.C5`) touches only reads of the already-blessed `skein.api.relations.alpha` (`SPEC-005.C2`); nothing in `relations.alpha` changes, so its `relations_test` catalog-set assertion is untouched. Applied as a code fact in the implementation plan, not here (mirrors `SPEC-Np-002.P3`).
- `strand vocab` is a batteries op, in-contract through `spools/batteries.md` (`SPEC-005.C3`), not this index (`PROP-Vr-001.C6`, `C11`).
- The selvage cross-check helper (`PROP-Vr-001.C7`) and the carder undeclared-namespace section (`PROP-Vr-001.C8`) are classpath reference spools in-contract via `spools/selvage.md`/`spools/carder.md` (`SPEC-005.C3`); each surface accretes one helper/section within its own doc's cadence.
- No `skein.core.*` change. The registry is runtime spool-state (`skein.api.runtime.alpha/spool-state`), not storage init, so there is no `db.clj` delta and no `SPEC-005.C5` internal-storage surface to move (`PROP-Vr-001.C11` final bullet).

## SPEC-Vr-002.P4 Flagged (out of scope for F4)

- **SPEC-Vr-002.F1:** No `repl-api.md` (`SPEC-003`) delta for `declare!`/`declarations`/`declaration`. `vocab` deliberately follows the `relations`/`notes` pattern: its contract is the strand model (the `SPEC-001.P4` ownership prose plus the `SPEC-001.P5` catalog-reflection sentence, added by `SPEC-Vr-001`) together with the namespace docstring and `spools/batteries.md` for the CLI verb, per `PROP-Vr-001.C11`, which scopes the namespace's disposition to this `SPEC-005.C2` enumeration edit and adds no `SPEC-003` entry. If a maintainer later wants an explicit in-process `declare!`/`declarations` signature contract, `SPEC-003.P4` is its home; the signed proposal does not ask for one, and this plan does not widen it.
- **SPEC-Vr-002.F2:** No tier boundary moves. `skein.api.vocab.alpha` is a shipped blessed namespace from the Skein checkout (like `relations`/`notes`), not a reference spool or repo-local userland spool, so `SPEC-005.C3`/`C4` are unchanged and no reference-spool doc is introduced for it (its consumers' docs already exist and merely accrete).
