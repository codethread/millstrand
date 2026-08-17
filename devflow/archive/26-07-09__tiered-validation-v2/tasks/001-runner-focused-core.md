# Task 1: Runner focused-core extraction

**Document ID:** `TASK-Ttv-001`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH1, `A5`, `AA1`, `TC2`, `TC7`).

## TASK-Ttv-001.P1 Scope

Type: AFK

Extract a non-exiting focused core from `test/skein/test_runner.clj` so warm and cold can share one validated code path (PLAN-Ttv-001.PH1, `A5`). Behaviour-preserving refactor only: cold focused mode and the full suite stay byte-for-byte unchanged. No new surface.

## TASK-Ttv-001.P2 Must implement exactly

- **TASK-Ttv-001.MI1:** In `test/skein/test_runner.clj`, extract the focused core out of `run-focused` (`:246`): a function that calls `validate-focused!` (`:228`) and runs the requested namespaces in-process (serial filter then parallel filter, declaration order, the existing `run-serial` path at `:252-253`) and **returns the summary** — no `System/exit`.
- **TASK-Ttv-001.MI2:** Keep `run-focused` as the exiting `-main` wrapper: it calls the new core and then does the `System/exit` on fail/error count (`:258`). The `-main` dispatch (`:274`) is otherwise untouched.
- **TASK-Ttv-001.MI3:** Do not change `validate-focused!`'s rules, the island vectors (`parallel-namespaces` `:12`, `serial-namespaces` `:34`, the add-libs shards), or the full-suite path (`run-all`/`run-parallel`/`run-serial`). This is a pure extraction.

## TASK-Ttv-001.P3 Done when

- **TASK-Ttv-001.DW1:** A cold focused run through the extracted core is green, spanning a serial and a parallel namespace so both filter branches execute: `clojure -M:test skein.test.alpha-test skein.repl-test`.
- **TASK-Ttv-001.DW2:** `make fmt-check lint reflect-check` pass for the touched namespace.
- **TASK-Ttv-001.DW3:** `git diff` shows the only behaviour change is the returned-summary extraction; island vectors and `validate-focused!` rules are unchanged. The full-suite byte-for-byte proof is deferred to the PH6 acceptance slice (Task 6), not run here.

## TASK-Ttv-001.P4 Out of scope

- **TASK-Ttv-001.OS1:** `skein.test.alpha/run-focused!`, the warm server, and the new test namespace (Task 2).
- **TASK-Ttv-001.OS2:** Shard-focused runner selection (PLAN-Ttv-001.A5/`TC7`) — do not add it while touching the runner.

## TASK-Ttv-001.P5 References

- **TASK-Ttv-001.REF1:** PLAN-Ttv-001.PH1, `A5`, `AA1`, `TC2`.
- **TASK-Ttv-001.REF2:** `test/skein/test_runner.clj:228,246,258,270`.
