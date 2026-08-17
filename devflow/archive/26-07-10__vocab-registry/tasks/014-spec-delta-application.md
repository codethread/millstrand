# Task 14: apply SPEC-Vr-001/SPEC-Vr-002 to root specs; mark deltas

**Document ID:** `TASK-Vr-014` **Slice:** `PLAN-Vr-001.S9` **Harness:** worker **Type:** AFK **Depends on:** none (doc-only; lands with the set)

## TASK-Vr-014.P1 Scope

Type: AFK

Apply the feature's durable root-spec changes and flip the delta statuses (`PLAN-Vr-001.CM2`, `PROP-Vr-001.C10`, `C11`). Two deltas are applied (`SPEC-Vr-001` strand-model, `SPEC-Vr-002` alpha-surface); two are no-change dispositions kept for delta-set completeness (`SPEC-Vr-003` cli, `SPEC-Vr-004` daemon-runtime).

**Owned files (disjoint):**

- `devflow/specs/strand-model.md`
- `devflow/specs/alpha-surface.md`
- `devflow/feat/vocab-registry/specs/strand-model.delta.md`
- `devflow/feat/vocab-registry/specs/alpha-surface.delta.md`
- `devflow/feat/vocab-registry/specs/cli.delta.md`
- `devflow/feat/vocab-registry/specs/daemon-runtime.delta.md`

## TASK-Vr-014.P2 Must implement exactly

- **TASK-Vr-014.MI1:** Apply `SPEC-Vr-001.CC1` to the `strand-model.md` attribute-namespace prose: it gains a concrete referent — name `skein.api.vocab.alpha` as the runtime registry that records ownership, and note the third-party-prefix rule is backed by its duplicate-owner install failure. Locate the target by the delta's Old fragment; apply exactly per its Old/New fragments.
- **TASK-Vr-014.MI2:** Apply `SPEC-Vr-001.CC2` to the `strand-model.md` relations advisory-catalog paragraph: it gains one sentence noting `vocab.alpha` reflects the edge catalog as owned `:edge` declarations. Locate by the delta's Old fragment; apply exactly per it. The `SPEC-001.P5` shipped declared-acyclic enumeration stays untouched — unlike `SPEC-Np-001.CC1`, this feature declares no relation.
- **TASK-Vr-014.MI3:** Apply `SPEC-Vr-002.CC1` to the `alpha-surface.md` enumerated blessed set: add `vocab` (alphabetical) plus the extended parenthetical. Locate by the delta's Old fragment; apply exactly per its Old/New fragments.
- **TASK-Vr-014.MI4:** Flip `SPEC-Vr-001` and `SPEC-Vr-002` Status to Merged; confirm `SPEC-Vr-003` (cli, No change) and `SPEC-Vr-004` (daemon-runtime, No change) remain the recorded no-change dispositions (`PLAN-Vr-001.CM2`). Each applied delta fragment must verify against the edited root spec (Old fragment present before, New fragment present after).

## TASK-Vr-014.P3 Done when

- **TASK-Vr-014.DW1:** `strand-model.md` names `skein.api.vocab.alpha` as the ownership registry and states the catalog reflection; `alpha-surface.md` enumerates `vocab`; each delta fragment verified against the edited root spec; `SPEC-Vr-001`/`SPEC-Vr-002` marked Merged; `SPEC-Vr-003`/`SPEC-Vr-004` remain no-change (`PLAN-Vr-001.S9`, `DW5`).
- **TASK-Vr-014.DW2:** `make docs-check` at zero findings.

## TASK-Vr-014.P4 Out of scope

- **TASK-Vr-014.OS1:** The registry code (Task 1 owns it; `PROP-Vr-001.C11` records why the `relations.alpha` reflection needs no `db.clj`/storage-semantics change).
- **TASK-Vr-014.OS2:** Any other root-spec change — `strand-model.md` and `alpha-surface.md` change only where the deltas' fragments say; no `cli.md`/`daemon-runtime.md` edit (`PLAN-Vr-001.CM2`, `CM4`).

## TASK-Vr-014.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-014.P6 References

- **TASK-Vr-014.REF1:** `PLAN-Vr-001.S9`, `PLAN-Vr-001.CM2`, `PLAN-Vr-001.CM4`, `PLAN-Vr-001.AA13`.
- **TASK-Vr-014.REF2:** `SPEC-Vr-001.CC1`/`CC2`, `SPEC-Vr-002.CC1` (the exact editing instructions); `PROP-Vr-001.C10`, `C11`.
