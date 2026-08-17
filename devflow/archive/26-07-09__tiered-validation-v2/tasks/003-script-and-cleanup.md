# Task 3: Script entry and worktree cleanup

**Document ID:** `TASK-Ttv-003`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH3, `A3`, `AA5`, `AA7`, `R1`, `R2`, `TC3`, `TC6`) and `docs/skein.md` before touching `.skein/workflows.clj`. **Depends on Task 2** (needs the `:test-repl` alias and `skein.test.warm`).

## TASK-Ttv-003.P1 Scope

Type: AFK

Wire the shell entry and lifecycle: `make test-warm` probes-or-boots the worktree's warm REPL, `make test-warm-stop` reaps it, and the land `cleanup` step stops it before `wktree remove`. All kills are by recorded PID (PLAN-Ttv-001.PH3, `R1`, `TC3`).

## TASK-Ttv-003.P2 Must implement exactly

- **TASK-Ttv-003.MI1:** Add a committed, formatted `scripts/test-warm` probe-or-boot script (PLAN-Ttv-001.A3): if `.test-repl-port` exists, probe the socket; reuse it if alive; if dead, kill the recorded PID from `.test-repl.pid` (by PID only — never `pkill -f`), delete the stale port/pid files, and boot fresh via the `:test-repl` alias. It then sends the `NS`-named namespaces through `skein.test.alpha/run-focused!` over the socket and prints the summary. The socket probe — not the port file — is the liveness truth (`R2`, `TC3`).
- **TASK-Ttv-003.MI2:** Add `Makefile` targets `test-warm` (`make test-warm NS="ns1 ns2"`, delegating to `scripts/test-warm`) and `test-warm-stop` (kills the recorded PID and removes the port/pid files). PID-only kills (PLAN-Ttv-001.AA5, `R1`).
- **TASK-Ttv-003.MI3:** Add a land `cleanup`-step instruction in `.skein/workflows.clj` (PLAN-Ttv-001.AA7): stop the worktree's warm test REPL by recorded PID (`make test-warm-stop`) before `wktree remove`. This is a coordination-world config change — smoke it in a disposable `--workspace` world first (`TC6`), never against the canonical `.skein` weaver.

## TASK-Ttv-003.P3 Done when

- **TASK-Ttv-003.DW1:** Exercise the script end-to-end in this worktree (the warm tooling is itself the deliverable under test here — this is not a warm result standing in for a cold slice gate): `make test-warm NS="skein.test.alpha-test"` boots the REPL and prints a green summary; a second `make test-warm NS="skein.test.alpha-test"` reuses the same PID (port file unchanged, no new JVM); `make test-warm-stop` kills the recorded PID and removes both files. Confirm no orphan JVM by the recorded PID afterward.
- **TASK-Ttv-003.DW2:** `make fmt-check` passes for `.skein/workflows.clj`; the shell `scripts/test-warm` is checked with `bash -n scripts/test-warm` (syntax) and runs as above.
- **TASK-Ttv-003.DW3:** The `.skein/workflows.clj` cleanup step is smoke-verified in a disposable `mktemp -d` `--workspace` world (never the canonical `.skein`); record the world path in a shell var guarded as `${ws:?}`.

## TASK-Ttv-003.P4 Out of scope

- **TASK-Ttv-003.OS1:** The socket server, alias, and `run-focused!` (Task 2 owns them).
- **TASK-Ttv-003.OS2:** Guidance-prose changes (Task 4) and the attr-scaling sweep (Task 5).
- **TASK-Ttv-003.OS3:** Any registry-visibility of live warm REPLs (PLAN-Ttv-001.R2 — deferred).

## TASK-Ttv-003.P5 References

- **TASK-Ttv-003.REF1:** PLAN-Ttv-001.PH3, `A3`, `AA5`, `AA7`, `R1`, `R2`, `TC3`, `TC6`.
- **TASK-Ttv-003.REF2:** `docs/skein.md` (config-change discipline), `.skein/workflows.clj`, `Makefile`.
