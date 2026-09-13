# Task 2: finish-run! terminal-write seam (agent_run.clj)

**Document ID:** `TASK-Ru-002`

**Slice:** `PLAN-Ru-001.S2` **Harness:** build **Type:** AFK

**Depends on:** TASK-Ru-001

## TASK-Ru-002.P1 Scope

Type: AFK

Write the captured usage onto the terminal run record at the single `finish-run!` seam (find it with `rg -n "defn-? finish-run!" spools/agent-run/src/skein/spools/agent_run.clj`), on **both** branches that have parsed output — so a usage-limit failure still records its spend (`PROP-Ru-001.C5`, `G2`, `R2`). Serial after Task 1: same file, and it needs `:usage` on the parse result (`PLAN-Ru-001.S2`, `A2`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj` (`finish-run!` only)
- `test/skein/agent_run_test.clj`

## TASK-Ru-002.P2 Must implement exactly

Per `PROP-Ru-001.C5`, `G2`, `R2`, `R3`:

- **TASK-Ru-002.MI1:** Destructure `:usage` alongside `{:keys [result session-id parse-error error]}` in `finish-run!`.
- **TASK-Ru-002.MI2:** Render `:usage` to the C1 string-keyed `agent-run/*` attributes (`agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, `agent-run/usage-source`) writing **only reported dimensions** — a nil figure is an absent key, never a stored `0` (`PROP-Ru-001.R3`).
- **TASK-Ru-002.MI3:** Merge those attributes onto **both** terminal branches with parsed output: the `:else` done branch's `update-run!` map, and the `error` terminal-error branch's `mark-failed!` `extra` map (`PROP-Ru-001.C5`, `G2`).
- **TASK-Ru-002.MI4:** The non-zero-exit branch has no parsed output and stays usage-less — a crashed process produced no usage object; do not synthesize one.
- **TASK-Ru-002.MI5:** New tests: a completing pi-json run records `agent-run/cost-usd`/`tokens-total`/`tokens`/`usage-source`; a claude-json run records the same from its result object; a **pi terminal-error run still records its cost** via `mark-failed!`'s `extra` (`PROP-Ru-001.R2`, `DW2`, `PLAN-Ru-001.V3`); a raw run records none; a nil cost is an absent key, not a stored `0` (`PROP-Ru-001.R3`, `PLAN-Ru-001.V4`).

## TASK-Ru-002.P3 Done when

- **TASK-Ru-002.DW1:** Done and terminal-error runs with parsed usage write the C1 attributes; raw and non-zero-exit runs write none; no dimension is zero-filled (`PROP-Ru-001.DW1`, `DW2`).
- **TASK-Ru-002.DW2:** Iteration gate green: `clojure -M:test skein.delegation-test` (focused proxy; authoritative `skein.agent-run-test` shard deferred to Task 7, `PLAN-Ru-001.A5`). Do not run the full suite or the shard directly here.
- **TASK-Ru-002.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Ru-002.P4 Out of scope

- **TASK-Ru-002.OS1:** Changing the parse layer (Task 1 owns it) or the vocab declaration (Task 3).
- **TASK-Ru-002.OS2:** The spend read fn (Task 4) and the `strand agent spend` subcommand (Task 5).

## TASK-Ru-002.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-002.P6 References

- **TASK-Ru-002.REF1:** `PLAN-Ru-001.S2`, `A2`, `A3`, `AA2`, `V3`, `V4`, `TC4` (`finish-run!` anchor with its `update-run!` done branch and `mark-failed!` `extra` terminal-error branch).
- **TASK-Ru-002.REF2:** `PROP-Ru-001.C5` (both terminal branches), `G2` (one seam), `R2` (error-path capture), `R3` (no silent zero); the landed Task 1 `:usage` on the parse result.
