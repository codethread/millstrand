# Task 5: strand agent spend subcommand (delegation.clj)

**Document ID:** `TASK-Ru-005`

**Slice:** `PLAN-Ru-001.S5` **Harness:** build **Type:** AFK

**Depends on:** TASK-Ru-004

## TASK-Ru-005.P1 Scope

Type: AFK

Wire `strand agent spend` — a new read subcommand on the existing `agent` op (`agent-arg-spec` and `agent-op`, find them with `rg -n "agent-arg-spec|defn agent-op" spools/delegation/src/skein/spools/delegation.clj`), alongside `ps`/`status`/`logs` — to the Task 4 aggregation fn (`PROP-Ru-001.C7`). Disjoint file from the spine; fans out after Task 4, which it calls (delegation already depends on agent-run; `PLAN-Ru-001.S5`, `A2`, `TC2`). No `cli/` Go change: this is arg-spec data on a weaver-registered op, so `make build` alone picks it up.

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj` (`agent-arg-spec` `:subcommands`, `agent-op` `case`, and the `strand agent about` manual)
- `test/skein/delegation_test.clj`

## TASK-Ru-005.P2 Must implement exactly

Per `PROP-Ru-001.C7`, `DW4`:

- **TASK-Ru-005.MI1:** Add a `"spend"` entry to `agent-arg-spec` `:subcommands` with `--harness`, `--since`, `--until`, and `--group-by` (`harness|day`) flags — all optional; no flags = all recorded runs grouped by harness.
- **TASK-Ru-005.MI2:** Add an `agent-op` `case` branch dispatching to the Task 4 aggregation fn in `skein.spools.agent-run`. JSON-only output (`PROP-Ru-001.C7`, the CLI-thin discipline).
- **TASK-Ru-005.MI3:** Add a `spend` entry to the `strand agent about` JSON manual describing the verb, its four flags, and the C7 output shape.
- **TASK-Ru-005.MI4:** New tests in `skein.delegation-test`: `strand agent spend` returns the C7 JSON; `--harness`/`--since`/`--until`/`--group-by day` narrow and rebucket; a run missing cost/tokens contributes `null`, not `0` (`PROP-Ru-001.R3`).

## TASK-Ru-005.P3 Done when

- **TASK-Ru-005.DW1:** `strand agent spend` returns per-run rows, per-harness and `--group-by day` groups, and totals; supports the three filters; derives `duration-ms` for every run including raw; never inflates missing figures to zero; `strand help agent`/`strand agent help` render the subcommand (generated, `SPEC-002.C39`; `PROP-Ru-001.DW4`).
- **TASK-Ru-005.DW2:** Iteration gate green: `clojure -M:test skein.delegation-test` — focused-runnable and **authoritative** for the subcommand wiring (`PLAN-Ru-001.A5`, `V1`). `(cd cli && go test ./...)` is deferred to Task 7.
- **TASK-Ru-005.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Ru-005.P4 Out of scope

- **TASK-Ru-005.OS1:** The aggregation logic (Task 4 owns the pure fn in `agent_run.clj`); this task only parses flags and routes to it.
- **TASK-Ru-005.OS2:** Any `cli/` Go dispatch change — none is owed (arg-spec data on an existing op).
- **TASK-Ru-005.OS3:** `api.md` regen and reference docs (Tasks 6/7).

## TASK-Ru-005.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-005.P6 References

- **TASK-Ru-005.REF1:** `PLAN-Ru-001.S5`, `AA5`, `V1`, `TC4` (`agent-arg-spec`/`agent-op` anchors).
- **TASK-Ru-005.REF2:** `PROP-Ru-001.C7` (subcommand home, flags, JSON shape), `DW4`; the landed Task 4 aggregation fn.
