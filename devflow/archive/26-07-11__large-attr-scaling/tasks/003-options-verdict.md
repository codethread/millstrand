# Task 3: residual-options assessment + verdict (PH3 / S3)

**Document ID:** `TASK-LargeAttrScaling-003` **Slice:** `PLAN-LargeAttrScaling-001.PH3` Residual-options assessment + verdict (S3) **Harness:** build (opus) **Type:** AFK **Branch:** `large-attr-scaling` **Worktree:** `/Users/ct/dev/projects/skein-src__large-attr-scaling`

Read first: `devflow/feat/large-attr-scaling/large-attr-scaling.plan.md` (`PLAN-LargeAttrScaling-001`, esp. `PH3`, `A7`, `TC5`, `Q4`) and `devflow/feat/large-attr-scaling/proposal.md` (`PROP-LargeAttrScaling-001.G3`, `S3`, `NG1`, `Q4`, and the settled storage in `PROP-EavAttrStorage-001.P1`). Read Task 2's numbers sections in `assessment-report.md` first — the assessment reasons over _those_ numbers, nothing else.

## TASK-LargeAttrScaling-003.P1 Scope

Type: AFK

Add the assessment + verdict sections to `assessment-report.md`, reasoning over the `S2` numbers Task 2 recorded. Judgment slice: bounded synthesis ending in exactly one concrete verdict. **No code, no harness, no storage change** (`NG2`).

**Owned files:**

- `devflow/feat/large-attr-scaling/assessment-report.md` — the assessment and verdict sections only. Do not touch Task 2's numbers sections except to reference them, and do not add the `## Re-running the harness` note (Task 4).

## TASK-LargeAttrScaling-003.P2 Must implement exactly

- **TASK-LargeAttrScaling-003.MI1:** Assess residual read-path mitigations for the `F2` paths (full-fidelity point reads over archived rows, text-search `LIKE` scans, payload inlining) and the large-list assembly cost (relates to card `ncso4`), reasoning **only** from the `S2` numbers (`A7`, `G3`).
- **TASK-LargeAttrScaling-003.MI2:** Keep options **bounded to EAV+archive and TEN-007** (`TC5`, `NG1`): the rejected side-table / transparent hot offload / artifacts-as-a-second-concept / event-sourcing / fixed-column alternatives stay rejected and are not reopened; no option may make a consumer above `skein.core.*` depend on the physical shape of attribute storage (TEN-007). Note that the assembly-path partial index the residual question reaches for — `idx_attributes_strand_hot` (`(strand_id) WHERE archived = 0`) — **already ships**; assess whether it suffices, do not re-add it (`A7`, `TC5`).
- **TASK-LargeAttrScaling-003.MI3:** Resolve `Q4` **from the measured `LIKE`-over-archived numbers** (`A7`): decide whether text-search seeing archived rows is a correctness concern worth a follow-up in its own right, or purely a cost question. Do not pre-assert it — cite the numbers.
- **TASK-LargeAttrScaling-003.MI4:** Land **one concrete verdict**: a named follow-up feature or an explicit no-action recommendation, with the measured evidence behind the call (`PH3`, `G3`).
- **TASK-LargeAttrScaling-003.MI5:** Apply the `docs-style` skill to the prose so it stays plain and factual.

## TASK-LargeAttrScaling-003.P3 Done when

- **TASK-LargeAttrScaling-003.DW1:** The report carries an assessment section grounded in the `S2` numbers and one concrete, evidence-backed verdict (named follow-up feature or explicit no-action).
- **TASK-LargeAttrScaling-003.DW2:** `Q4` is decided from the measured text-search numbers, not asserted.
- **TASK-LargeAttrScaling-003.DW3:** `git status --short` touches only `assessment-report.md` (`V7`).

## TASK-LargeAttrScaling-003.P4 Out of scope

- **TASK-LargeAttrScaling-003.OS1:** Building any recommended mitigation — the verdict _recommends_, it does not _build_ (`NG2`, `R5`). Any change is a future feature with its own proposal.
- **TASK-LargeAttrScaling-003.OS2:** Re-running or re-numbering the harness (Task 2 owns the numbers); the `## Re-running the harness` note and inert-gate sweep (Task 4).
- **TASK-LargeAttrScaling-003.OS3:** Reopening the settled storage direction (`NG1`).

## TASK-LargeAttrScaling-003.P5 Commit

- One atomic commit for this slice on branch `large-attr-scaling`, conventional message, why-focused, **no push**. This slice and Task 4 share `assessment-report.md`; commit only your assessment/verdict additions. Never `--no-verify`.

## TASK-LargeAttrScaling-003.P6 References

- **TASK-LargeAttrScaling-003.REF1:** `PLAN-LargeAttrScaling-001.PH3`, `A7`, `TC5`, `Q4`.
- **TASK-LargeAttrScaling-003.REF2:** `PROP-LargeAttrScaling-001.G3`, `S3`, `NG1`, `Q4`; `PROP-EavAttrStorage-001.P1` (settled storage, rejected alternatives); TEN-007.

## TASK-LargeAttrScaling-003.P7 Worker contract

- Set `--attr status=implemented` only when DW1–DW3 hold; never close this strand; never mutate sibling or parent strands; commit only your own slice.
- Never start/stop/restart or reload the canonical weaver. This slice is prose only — no world, no run.
