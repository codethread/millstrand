# Task 10: apply SPEC-Np-001/SPEC-Np-002 to root specs; mark deltas

**Document ID:** `TASK-Np-010` **Slice:** `PLAN-Np-001.S10` **Harness:** worker **Type:** AFK **Depends on:** none (doc-only; lands with the set)

## TASK-Np-010.P1 Scope

Type: AFK

Apply the feature's durable root-spec changes and flip the delta statuses (`PLAN-Np-001.CM2`, `PROP-Np-001.C12`). Two deltas are applied (`SPEC-Np-001` strand-model, `SPEC-Np-002` alpha-surface); two are no-change dispositions kept for delta-set completeness (`SPEC-Np-003` cli, `SPEC-Np-004` daemon-runtime).

**Owned files (disjoint):**

- `devflow/specs/strand-model.md`
- `devflow/specs/alpha-surface.md`
- `devflow/feat/note-primitive/specs/strand-model.delta.md`
- `devflow/feat/note-primitive/specs/alpha-surface.delta.md`
- `devflow/feat/note-primitive/specs/cli.delta.md`
- `devflow/feat/note-primitive/specs/daemon-runtime.delta.md`

## TASK-Np-010.P2 Must implement exactly

- **TASK-Np-010.MI1:** Apply `SPEC-Np-001.CC1`: add `notes` to the shipped declared-acyclic enumeration in `strand-model.md` (`:48`), exactly per the delta's Old/New fragments.
- **TASK-Np-010.MI2:** Apply `SPEC-Np-001.CC2`: insert the delta's verbatim `notes` classification paragraph (core-owned operational relation, note → target, append-only memory) after `:50`, exactly per the delta.
- **TASK-Np-010.MI3:** Apply `SPEC-Np-002.CC1`: add the `notes` enumeration entry (`skein.api.notes.alpha`) plus the extended parenthetical in `alpha-surface.md` (`:12`), exactly per the delta's Old/New fragments.
- **TASK-Np-010.MI4:** Flip `SPEC-Np-001` and `SPEC-Np-002` Status to Merged; confirm `SPEC-Np-003` (cli, no change) and `SPEC-Np-004` (daemon-runtime, no change) remain the recorded no-change dispositions (`PLAN-Np-001.CM2`, `CM4`). Each applied delta fragment must verify against the edited root spec (Old fragment present before, New fragment present after).

## TASK-Np-010.P3 Done when

- **TASK-Np-010.DW1:** `strand-model.md` names `notes` acyclic and states the `notes` classification; `alpha-surface.md` enumerates `skein.api.notes.alpha`; each delta fragment verified against the edited root spec; `SPEC-Np-001`/`SPEC-Np-002` marked Merged; `SPEC-Np-003`/`SPEC-Np-004` remain no-change.
- **TASK-Np-010.DW2:** `make docs-check` at zero findings.

## TASK-Np-010.P4 Out of scope

- **TASK-Np-010.OS1:** The relations catalog code (Task 1 owns it; `PROP-Np-001.C12` records why the catalog is not a spec edit).
- **TASK-Np-010.OS2:** Any other root-spec change — `strand-model.md` and `alpha-surface.md` change only where the deltas' fragments say; no `cli.md`/`daemon-runtime.md` edit (`PLAN-Np-001.CM4`).

## TASK-Np-010.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-010.P6 References

- **TASK-Np-010.REF1:** `PLAN-Np-001.S10`, `PLAN-Np-001.CM2/CM4`, `PLAN-Np-001.AA10`.
- **TASK-Np-010.REF2:** `SPEC-Np-001`/`SPEC-Np-002` (the exact editing instructions); `PROP-Np-001.C9`, `C12`.
