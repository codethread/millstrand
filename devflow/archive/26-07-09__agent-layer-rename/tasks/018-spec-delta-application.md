# Task 18: apply the three spec deltas to root specs

**Document ID:** `TASK-Alr-018`

**Phase:** `PLAN-Alr-001.PH5` **Harness:** worker **Type:** AFK

**Depends on:** TASK-Alr-006, TASK-Alr-007, TASK-Alr-008, TASK-Alr-009, TASK-Alr-010

## TASK-Alr-018.P1 Scope

Promote the three feature deltas into the root specs after the sweep validates (`PLAN-Alr-001.PH5/CM2`): apply the token-swap and inventory-sync edits from `SPEC-Alr-001`, `SPEC-Alr-002`, `SPEC-Alr-003`, plus the two additive contract statements — the naming rule (`SPEC-Alr-001.CC2`) and the frozen trained-vocabulary surface (`SPEC-Alr-002.CC3`). `SPEC-Alr-003` is edit-only (its off-lane example is an edit, not an add). Mark all three deltas **Merged**.

**Owned files (disjoint):**

- `devflow/specs/strand-model.md` (`SPEC-001`), `devflow/specs/alpha-surface.md` (`SPEC-005`), `devflow/specs/daemon-runtime.md` (`SPEC-004`).
- `devflow/feat/agent-layer-rename/specs/{strand-model,alpha-surface,daemon-runtime}.delta.md` (Status → Merged).

## TASK-Alr-018.P2 Must implement exactly

- **TASK-Alr-018.MI1:** Apply each delta's old→new fragments to its root spec exactly as authored — the token swaps and inventory-sync edits across all three.
- **TASK-Alr-018.MI2:** Add the naming-rule statement (`SPEC-Alr-001.CC2`) to the strand-model spec and the frozen trained-vocabulary-surface statement (`SPEC-Alr-002.CC3`) to the alpha-surface spec; assign the next free `SPEC-005.Cn` id for the `CC3` addition at edit time.
- **TASK-Alr-018.MI3:** Apply the `SPEC-Alr-003` edits to the daemon-runtime spec (edit-only, no additive statement).
- **TASK-Alr-018.MI4:** Flip the Status line of all three `*.delta.md` files to **Merged**.

## TASK-Alr-018.P3 Validation / Done when

- **TASK-Alr-018.DW1:** `make docs-check` at zero findings.
- **TASK-Alr-018.DW2:** Each delta's old/new fragment is verified present in the edited root spec; the two additive statements carry live ids (`SPEC-Alr-001.CC2`, the assigned `SPEC-005.Cn`).
- **TASK-Alr-018.DW3:** All three deltas read Status: Merged.

## TASK-Alr-018.P4 Out of scope

- **TASK-Alr-018.OS1:** Any behavior contract change — this is token-swaps plus two additive statements only (`PLAN-Alr-001.CM2`).
- **TASK-Alr-018.OS2:** Source/doc/config edits (PH1–PH4).

## TASK-Alr-018.P5 Commit

- Atomic single commit (root specs + delta Status flips), devflow message, **no push**.

## TASK-Alr-018.P6 References

- **TASK-Alr-018.REF1:** `PLAN-Alr-001.PH5`, `PLAN-Alr-001.AA11/CM2`.
- **TASK-Alr-018.REF2:** `SPEC-Alr-001.CC2`, `SPEC-Alr-002.CC3`, `SPEC-Alr-003` (the delta docs).
