# Task 10: apply SPEC-Aep-001 to strand-model.md; mark deltas

**Document ID:** `TASK-Aep-010` **Slice:** `PLAN-Aep-001.S10` **Harness:** worker **Type:** AFK **Depends on:** none (doc-only; lands with the set)

## TASK-Aep-010.P1 Scope

Type: AFK

Apply the feature's durable root-spec changes and flip the delta statuses (`PLAN-Aep-001.CM2/CM4`).

**Owned files (disjoint):**

- `devflow/specs/strand-model.md`
- `devflow/specs/daemon-runtime.md`
- `devflow/feat/agent-engine-primitives/specs/strand-model.delta.md`
- `devflow/feat/agent-engine-primitives/specs/alpha-surface.delta.md`
- `devflow/feat/agent-engine-primitives/specs/daemon-runtime.delta.md`

## TASK-Aep-010.P2 Must implement exactly

- **TASK-Aep-010.MI1:** Apply `SPEC-Aep-001.CC1`: add `serves` to the shipped declared-acyclic enumeration in `strand-model.md` (line 48), exactly per the delta's Old/New fragments.
- **TASK-Aep-010.MI2:** Apply `SPEC-Aep-001.CC2`: insert the delta's verbatim `serves` contract paragraph (engine-owned operational edge; `parent-of` is structural only) immediately after the acyclic-declaration paragraph and before the self-edge paragraph.
- **TASK-Aep-010.MI3:** Apply `SPEC-Aep-003.CC1`: replace the `SPEC-004.C92` storage-location sentence in `daemon-runtime.md` exactly per that delta's Old/New fragments (staleness correction — the live db is under the weaver state directory, not the workspace).
- **TASK-Aep-010.MI4:** Flip `SPEC-Aep-001` and `SPEC-Aep-003` Status to Merged; confirm `SPEC-Aep-002` (alpha-surface) remains the recorded no-change disposition.

## TASK-Aep-010.P3 Done when

- **TASK-Aep-010.DW1:** `strand-model.md` names `serves` acyclic and states the `serves`/`parent-of` distinction; `daemon-runtime.md` `C92` states the state-dir storage location; each delta fragment verified against the edited root spec; `SPEC-Aep-001` and `SPEC-Aep-003` marked Merged.
- **TASK-Aep-010.DW2:** `make docs-check` at zero findings.

## TASK-Aep-010.P4 Out of scope

- **TASK-Aep-010.OS1:** The relations catalog code (Task 1 owns it; `SPEC-Aep-001.P3` records why it is not a doc edit).
- **TASK-Aep-010.OS2:** Any other root-spec change — in `daemon-runtime.md` only the `C92` sentence moves (`PLAN-Aep-001.CM4`: the gate-link conditional stays unfired).

## TASK-Aep-010.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-010.P6 References

- **TASK-Aep-010.REF1:** `PLAN-Aep-001.S10`; `SPEC-Aep-001` (the exact editing instructions); `PROP-Aep-001.C11`.
