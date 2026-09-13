# Task 2: Warm REPL server, run-focused! entry, alias

**Document ID:** `TASK-Ttv-002`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH2, `A2`, `A4`, `AA2`–`AA4`, `AA6`, `TC2`, `TC4`, `R4`) and `devflow/feat/tiered-validation-v2/specs/repl-api.delta.md` (DELTA-Ttv-001.CC1/CC2/D2). **Depends on Task 1** (reuses its extracted focused core).

## TASK-Ttv-002.P1 Scope

Type: AFK

Ship the warm loop's in-process pieces: the blessed `run-focused!` entry that reuses the Task 1 core, the socket-REPL bootstrap with its port/pid files and idle watchdog, the `:test-repl` alias, and the gitignore entries — plus the `skein.warm-test` namespace that proves warm and cold reject identically (PLAN-Ttv-001.PH2, `TC2`).

## TASK-Ttv-002.P2 Must implement exactly

- **TASK-Ttv-002.MI1:** Accrete `run-focused!` onto `src/skein/test/alpha.clj` (DELTA-Ttv-001.CC1): take a namespace collection, reach the Task 1 runner core via `requiring-resolve` (never a static `:require` of the test-side runner — PLAN-Ttv-001.A4/`R4`), return the summary, no `System/exit`. Keep it reflection-clean (no new Java interop).
- **TASK-Ttv-002.MI2:** Add `test/skein/test/warm.clj` (`skein.test.warm`, DELTA-Ttv-001.D2): a `-main` that starts a `clojure.core.server` socket REPL on an ephemeral port, writes `.test-repl-port` and `.test-repl.pid` into the worktree root, resets a daemon idle timer on each client connection, and after 60 idle minutes deletes both files and exits.
- **TASK-Ttv-002.MI3:** Add the `:test-repl` alias to `deps.edn` (PLAN-Ttv-001.AA4) on the `:test` classpath, inheriting the `:test` extra-paths/deps and native-access JVM opt, with `:main-opts` running `skein.test.warm`.
- **TASK-Ttv-002.MI4:** Add `.test-repl-port` and `.test-repl.pid` to `.gitignore` (PLAN-Ttv-001.AA6) as per-worktree runtime files.
- **TASK-Ttv-002.MI5:** Add `test/skein/warm_test.clj` (`skein.warm-test`) and declare it in the runner's island set (`parallel-namespaces` in `test/skein/test_runner.clj`) so it is focus-eligible (PLAN-Ttv-001.TC2). It asserts: `run-focused!` returns a summary with no process exit, and a shard-only or undeclared namespace fails loudly identically to cold (reuses `validate-focused!`, not a second validator).

## TASK-Ttv-002.P3 Done when

- **TASK-Ttv-002.DW1:** `clojure -M:test skein.warm-test skein.test.alpha-test` is green (the new warm test plus the blessed-helper test), proving `run-focused!` reuse and identical rejection.
- **TASK-Ttv-002.DW2:** `make fmt-check lint reflect-check` pass — reflect-check must stay clean, confirming the `requiring-resolve` boundary does not leak the test-side runner onto the main classpath (PLAN-Ttv-001.R4).
- **TASK-Ttv-002.DW3:** `git status --short` shows no `.test-repl-port`/`.test-repl.pid` tracked (gitignored).

## TASK-Ttv-002.P4 Out of scope

- **TASK-Ttv-002.OS1:** `scripts/test-warm`, the Makefile targets, and the workflows.clj cleanup step (Task 3).
- **TASK-Ttv-002.OS2:** Any runner behaviour change beyond declaring `skein.warm-test`; the focused-core extraction is Task 1's.

## TASK-Ttv-002.P5 References

- **TASK-Ttv-002.REF1:** PLAN-Ttv-001.PH2, `A2`, `A4`, `AA2`–`AA4`, `AA6`, `TC2`, `TC4`, `R4`.
- **TASK-Ttv-002.REF2:** DELTA-Ttv-001.CC1/CC2/D2; `src/skein/test/alpha.clj`, `test/skein/test_runner.clj` island vectors (`:12`).
