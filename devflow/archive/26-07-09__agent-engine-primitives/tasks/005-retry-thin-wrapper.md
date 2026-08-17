# Task 5: op-retry as thin wrapper over the primitive (delegation.clj)

**Document ID:** `TASK-Aep-005` **Slice:** `PLAN-Aep-001.S5` **Harness:** build **Type:** AFK **Depends on:** TASK-Aep-003 (the primitive), TASK-Aep-004 (same file — sequential after the serving rewrite)

## TASK-Aep-005.P1 Scope

Type: AFK

`op-retry` keeps its policy and sheds its mechanism (`PROP-Aep-001.C7`). Second sequential slice in `delegation.clj`.

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj`

## TASK-Aep-005.P2 Must implement exactly

- **TASK-Aep-005.MI1:** Shed the hand-rolled supersession in `op-retry` (`delegation.clj:1736`): the `agent-run/phase "superseded"` close (`:1776`), the `depends-on` re-read (`:1780`), the served-target re-derivation (`:1779`), and the re-spawn (`:1795`) all move behind `supersede-and-respawn!`. The wrapper computes prompt/harness/continuity (`--fresh` → `:fresh`)/`:carry-attrs` and hands the predecessor id to the primitive.
- **TASK-Aep-005.MI2:** Keep every policy behavior: task-vs-run-id resolution; serving-run selection against `serves`-edge runs (a retry-by-task resolves against serving runs only, so a failed reviewer/recon helper never shadows the real delegation failure); the multiple-failed-serving-runs ambiguity guard; the resume-classed-failure-refuses-plain-retry loud stop; the fix-body-first prompt rebuild (`prompt-for-task` from the task's current body); `--harness`/`--cwd`/`--prompt` overrides; the `:carry-attrs` set of spool-owned structural attrs (`review/*`, `panel/*`).
- **TASK-Aep-005.MI3:** No re-link step anywhere: because the primitive moves the `serves` edge to the successor, a retry of a serving run IS the recovery — the target's current serving run (`PROP-Aep-001.C5`) is the fresh one.

## TASK-Aep-005.P3 Done when

- **TASK-Aep-005.DW1:** `op-retry` writes no supersession machinery of its own; all succession goes through `supersede-and-respawn!`; retry policy (`--fresh`, ambiguity guard, fix-body-first, overrides, carry-attrs) preserved.
- **TASK-Aep-005.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green.
- **TASK-Aep-005.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Aep-005.P4 Out of scope

- **TASK-Aep-005.OS1:** Subagent executor recovery and `stalled-gates` (Task 6).
- **TASK-Aep-005.OS2:** README/cookbook prose (Task 8).

## TASK-Aep-005.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-005.P6 References

- **TASK-Aep-005.REF1:** `PLAN-Aep-001.S5`.
- **TASK-Aep-005.REF2:** `PROP-Aep-001.C7` (stays/sheds inventory), `C4`/`C5` (the primitive and the resolution rule).
