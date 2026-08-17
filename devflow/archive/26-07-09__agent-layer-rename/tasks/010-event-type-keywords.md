# Task 10: event-type keyword rename (`:agent-run/engine`, `:gate/engine`)

**Document ID:** `TASK-Alr-010` **Phase:** `PLAN-Alr-001.PH2` (e) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-006, TASK-Alr-007

## TASK-Alr-010.P1 Scope

Rename the two engine event-type keywords so they follow the namespace rename: `:shuttle/engine`→`:agent-run/engine` and `:treadle/engine`→`:gate/engine`, via `events/register-handler!` and every publish/subscribe site (`PLAN-Alr-001.A5`, brief "Event-type keywords" row). These are **not** durable attributes — the cutover script never touches them; they are listed only so the source sweep is exhaustive. **Why this task serializes after Tasks 6 and 7 rather than fanning out with them:** the two event kws live in `agent_run.clj` (Task 6's file) and `subagent.clj` (Task 7's file); serializing avoids a same-file collision with two mutators (`PLAN-Alr-001.TC2` — never two mutators in the same file scope).

**Owned files (the two `events/register-handler!` call sites and subscribers only):**

- the `:shuttle/engine` registration/publish sites in `spools/agent-run/src/skein/spools/agent_run.clj`.
- the `:treadle/engine` registration/publish sites in `spools/agent-run/src/skein/spools/executors/subagent.clj`.
- any subscriber matching these event kws (grep tree-wide; excl. `devflow/archive/*`).

## TASK-Alr-010.P2 Must implement exactly

- **TASK-Alr-010.MI1:** Rename the event kw at `events/register-handler!` and every publish/subscribe call: `:shuttle/engine`→`:agent-run/engine`, `:treadle/engine`→`:gate/engine`.
- **TASK-Alr-010.MI2:** Scope strictly to the event-type keyword literals — do not re-touch the attribute strings Tasks 6/7 already swapped, and do not corrupt unrelated `shuttle/`/`treadle/` symbols with no rename-table row (`PLAN-Alr-001.A1`).
- **TASK-Alr-010.MI3:** Update any test asserting the old event-type keyword.

## TASK-Alr-010.P3 Validation / Done when

- **TASK-Alr-010.DW1:** Cold focused slice gate green: `clojure -M:test skein.agent-run-test skein.executors.subagent-test` (plus any event-bus suite asserting these kws). `make test-warm` iterates only.
- **TASK-Alr-010.DW2:** `make fmt-check lint` pass for the touched namespaces.
- **TASK-Alr-010.DW3:** `grep -n` confirms no `:shuttle/engine` or `:treadle/engine` survive outside `devflow/archive/*`; the class-2 attribute sweep of Tasks 6/7 is untouched.

## TASK-Alr-010.P4 Out of scope

- **TASK-Alr-010.OS1:** Durable attribute strings (Tasks 6–9) and the cutover script (Task 19 — these event kws are explicitly out of its scope).
- **TASK-Alr-010.OS2:** Docs/config/spec surfaces (PH3+).

## TASK-Alr-010.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Alr-010.P6 References

- **TASK-Alr-010.REF1:** `PLAN-Alr-001.PH2`, `PLAN-Alr-001.A5/TC2`.
- **TASK-Alr-010.REF2:** brief "Event-type keywords" table (`PLAN-Alr-001.TC1`).
