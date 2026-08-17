# Task 5: attr-scaling queue sweep to the tiers

**Document ID:** `TASK-Ttv-005`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH5, `AA10`, `TC5`) and PROP-Ttv-001.S4. **Depends on Task 3.** Sibling with Task 4; file scopes are disjoint (this slice edits only the attr-scaling task queue).

## TASK-Ttv-005.P1 Scope

Type: AFK

Sweep the pending `attr-scaling-ship-now` task Done-when blocks to the tiers: per-slice focused namespace runs, the full locked suite only in the validation-sweep task, and the stale `flock` path corrected everywhere (PLAN-Ttv-001.PH5, `AA10`).

## TASK-Ttv-005.P2 Must implement exactly

- **TASK-Ttv-005.MI1:** In `devflow/feat/attr-scaling-ship-now/tasks/001-l0a-pragmas.md`, `002-l1-lean-reads.md`, and `003-l0b-registry.md`, replace the full-suite Validation block with a **cold focused run** `clojure -M:test <ns...>` naming exactly the test namespaces that task's Scope touches (derive them from each task's owned files; do not invent scope). Remove the per-slice full-suite line from these three.
- **TASK-Ttv-005.MI2:** Keep the full locked suite only in `devflow/feat/attr-scaling-ship-now/tasks/005-validation-sweep.md`, and rewrite its command to bare `flock`: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` (PLAN-Ttv-001.TC5).
- **TASK-Ttv-005.MI3:** Correct every stale `/opt/homebrew/opt/util-linux/bin/flock` occurrence to bare `flock` across `devflow/feat/attr-scaling-ship-now/tasks/*.md`. Do not change the attr-scaling `index.yml`, ids, task titles, or any scope beyond Done-when validation blocks.

## TASK-Ttv-005.P3 Done when

- **TASK-Ttv-005.DW1:** `grep -rn "util-linux" devflow/feat/attr-scaling-ship-now/tasks/` returns nothing.
- **TASK-Ttv-005.DW2:** `grep -rln "flock -w 3600" devflow/feat/attr-scaling-ship-now/tasks/` lists only `005-validation-sweep.md`; tasks 001–003 now carry a focused `clojure -M:test <ns...>` Validation block naming their touched namespaces.
- **TASK-Ttv-005.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make docs-check` is green. (Markdown-only slice; no Clojure test namespace touched — the full suite is the PH6 acceptance slice, not run here.)

## TASK-Ttv-005.P4 Out of scope

- **TASK-Ttv-005.OS1:** Implementing or re-running any attr-scaling task; this is a Done-when prose sweep only.
- **TASK-Ttv-005.OS2:** The standing guidance surfaces (Task 4) and any file outside `devflow/feat/attr-scaling-ship-now/tasks/`.

## TASK-Ttv-005.P5 References

- **TASK-Ttv-005.REF1:** PLAN-Ttv-001.PH5, `AA10`, `TC5`; PROP-Ttv-001.S4.
- **TASK-Ttv-005.REF2:** `devflow/feat/attr-scaling-ship-now/tasks/{001,002,003,005}*.md`.
