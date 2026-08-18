# Task 8: `review/*` + `panel/*` + `note/*` per-key split

**Document ID:** `TASK-Alr-008`

**Phase:** `PLAN-Alr-001.PH2` (c) **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-005

## TASK-Alr-008.P1 Scope

Rewrite token **class 2** for the delegation spool's three attribute families. This is a **per-key split**, not a suffix swap: the single `shuttle/*` prefix fans into three concept namespaces — `review/*`, `panel/*`, `note/*` — per the exact brief rows (`PLAN-Alr-001.AA4`, brief "Review / Panel / Note attributes" tables). Source and suite together; disjoint from run/gate/workflow.

**Owned files (disjoint from sibling PH2 tasks):**

- `spools/delegation/src/skein/spools/delegation.clj`.
- `test/skein/delegation_test.clj`.

## TASK-Alr-008.P2 Must implement exactly

- **TASK-Alr-008.MI1:** Review family: `shuttle/review-target`→`review/target`, `shuttle/review-pass`→`review/pass`, `shuttle/review-roster`→`review/roster`, `shuttle/review-focus`→`review/focus`, `shuttle/review-synthesis`→`review/synthesis`.
- **TASK-Alr-008.MI2:** Panel family: `shuttle/panel-seat`→`panel/seat`, `shuttle/panel-turn`→`panel/turn`, `shuttle/fresh-prompt`→`panel/fresh-prompt`, `shuttle/role`→`panel/role` (the panel-board role marker, value `"panel"` — value unchanged).
- **TASK-Alr-008.MI3:** Note family: `shuttle/note-for`→`note/for`, `shuttle/note`→`note/text`, `shuttle/note-by`→`note/by`, `shuttle/round`→`note/round`, `shuttle/at`→`note/at`. Note the `shuttle/note`→`note/**text**` and `shuttle/round`→`note/**round**` remaps are not mechanical suffix swaps — read each row.
- **TASK-Alr-008.MI4:** Flip the matching attr assertions in `delegation_test.clj`. Do **not** rename any run/gate attr (other tasks) and do **not** touch the frozen `strand agent`/`agent-plan` surface (`PLAN-Alr-001.CM4`).

## TASK-Alr-008.P3 Validation / Done when

- **TASK-Alr-008.DW1:** Cold focused slice gate green: `clojure -M:test skein.delegation-test`. `make test-warm` iterates only.
- **TASK-Alr-008.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-008.DW3:** `grep -n` confirms no `shuttle/review-`, `shuttle/panel-`, `shuttle/note`, `shuttle/round`, `shuttle/at`, `shuttle/fresh-prompt`, `shuttle/role` survivors in owned files outside `devflow/archive/*`.

## TASK-Alr-008.P4 Out of scope

- **TASK-Alr-008.OS1:** Run attrs (Task 6), gate attrs (Task 7), workflow (Task 9), event kws (Task 10).
- **TASK-Alr-008.OS2:** The `serves` edge / any behavior; the frozen trained-vocabulary surface.

## TASK-Alr-008.P5 Commit

- Atomic single commit (source + suite), devflow message, **no push**.

## TASK-Alr-008.P6 References

- **TASK-Alr-008.REF1:** `PLAN-Alr-001.PH2`, `PLAN-Alr-001.AA4`.
- **TASK-Alr-008.REF2:** brief "Review / Panel / Note attributes" tables (`PLAN-Alr-001.TC1`).
