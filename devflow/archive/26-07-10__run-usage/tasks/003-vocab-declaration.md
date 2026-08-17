# Task 3: vocab declaration growth — usage-attrs (agent_run.clj)

**Document ID:** `TASK-Ru-003` **Slice:** `PLAN-Ru-001.S3` **Harness:** build **Type:** AFK **Depends on:** TASK-Ru-002

## TASK-Ru-003.P1 Scope

Type: AFK

Declare the four usage keys through the F4 registry by extending the engine's existing `agent-run` declaration in `install!` (`rg -n "defn install!" spools/agent-run/src/skein/spools/agent_run.clj`) — the first extension of the F4 registry after its initial seed (`PROP-Ru-001.C6`, `Q2`). Logically independent of S1/S2 (the keys are advisory — the engine writes them regardless), but same file: serial after Task 2 (`PLAN-Ru-001.S3`, `A2`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj` (`install!` + a new `usage-attrs` def)
- `test/skein/agent_run_test.clj`

## TASK-Ru-003.P2 Must implement exactly

Per `PROP-Ru-001.C6`, `Q2`:

- **TASK-Ru-003.MI1:** Add a private `usage-attrs` set — `agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, `agent-run/usage-source` — kept **distinct** from the spawn-time `control-attrs` set: usage is written at _completion_, control is _reserved by spawn_ (`PROP-Ru-001.Q2`).
- **TASK-Ru-003.MI2:** Fold `usage-attrs` into the sorted `:keys` of the existing `agent-run` `vocab/declare!` call so one declaration lists both sets. The declaration stays owner `:skein/spools-shuttle` and idempotent for the same owner (survives `reload!`; `PROP-Ru-001.C6`).
- **TASK-Ru-003.MI3:** New test: the `agent-run` declaration's `:keys` lists the four usage keys.

## TASK-Ru-003.P3 Done when

- **TASK-Ru-003.DW1:** The `agent-run` declaration lists the four usage keys under owner `:skein/spools-shuttle`; `strand vocab` shows them under the `agent-run` namespace (proven end-to-end at Task 7; `PROP-Ru-001.DW3`).
- **TASK-Ru-003.DW2:** Iteration gate green: `clojure -M:test skein.delegation-test` (focused proxy; authoritative `skein.agent-run-test` shard deferred to Task 7, `PLAN-Ru-001.A5`). Do not run the full suite or the shard directly here.
- **TASK-Ru-003.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Ru-003.P4 Out of scope

- **TASK-Ru-003.OS1:** Any change to `control-attrs` or the spawn-time reservation set — usage is a distinct sibling set (`PROP-Ru-001.Q2`).
- **TASK-Ru-003.OS2:** The parse layer (Task 1), `finish-run!` (Task 2), the spend fn (Task 4), and the subcommand (Task 5).

## TASK-Ru-003.P5 Commit

- Atomic single commit, devflow message (why-focused), **no push**.

## TASK-Ru-003.P6 References

- **TASK-Ru-003.REF1:** `PLAN-Ru-001.S3`, `A2`, `AA3`, `TC4` (`install!`/`control-attrs` anchors).
- **TASK-Ru-003.REF2:** `PROP-Ru-001.C6` (declare through the F4 registry), `Q2` (sibling set, not `control-attrs`); the landed F4 `agent-run` seed the declaration extends.
