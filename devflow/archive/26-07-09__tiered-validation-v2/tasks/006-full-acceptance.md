# Task 6: Full acceptance (final slice)

**Document ID:** `TASK-Ttv-006`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH6, `P6` V2/V4, `R4`). **Depends on Tasks 4 and 5** (transitively on 1–3). This is the only slice that runs the full locked suite.

## TASK-Ttv-006.P1 Scope

Type: AFK

Validate the whole feature under the tier it ships: one full locked suite run, go tests, smoke, and the quality gates, with a clean worktree (PLAN-Ttv-001.PH6). Fix only what the gates surface — no new feature scope.

## TASK-Ttv-006.P2 Must implement exactly

- **TASK-Ttv-006.MI1:** Run the full locked suite exactly once, proving `skein.warm-test` and the runner refactor pass under full parallel load (PLAN-Ttv-001.V2): `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test`. Bare `flock` (nix on PATH); never `/opt/homebrew/opt/util-linux/bin/flock`.
- **TASK-Ttv-006.MI2:** Run `(cd cli && go test ./...)` — expected inert, run to confirm no regression; and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **TASK-Ttv-006.MI3:** Run `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check lint reflect-check docs-check` at zero findings, and confirm `make api-docs` leaves no diff.
- **TASK-Ttv-006.MI4:** Confirm `git status --short` shows no generated SQLite/runtime artifacts and no warm files (`.test-repl-port`/`.test-repl.pid`).

## TASK-Ttv-006.P3 Done when

- **TASK-Ttv-006.DW1:** The full locked suite (all parent parallel + serial + add-libs shards) is green under `flock -w 3600 /tmp/skein-test.lock`.
- **TASK-Ttv-006.DW2:** `(cd cli && go test ./...)` and `clojure -M:smoke` are green.
- **TASK-Ttv-006.DW3:** `make fmt-check lint reflect-check docs-check` pass at zero findings, `make api-docs` leaves no diff, and `git status --short` is clean of generated SQLite, runtime-metadata, and warm files.

## TASK-Ttv-006.P4 Out of scope

- **TASK-Ttv-006.OS1:** Any implementation change owned by Tasks 1–5; this slice only runs the gates and fixes what they surface.
- **TASK-Ttv-006.OS2:** Landing/merge (coordinator-only) and any CI change (PROP-Ttv-001.NG3).

## TASK-Ttv-006.P5 References

- **TASK-Ttv-006.REF1:** PLAN-Ttv-001.PH6, `V2`, `V4`, `R4`; PROP-Ttv-001.G5.
- **TASK-Ttv-006.REF2:** `Makefile` gates; `test/skein/warm_test.clj`, `test/skein/test_runner.clj`.
