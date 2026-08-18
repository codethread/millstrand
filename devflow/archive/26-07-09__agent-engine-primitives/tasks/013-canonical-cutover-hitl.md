# Task 13: canonical cutover at user-signed weaver restart

**Document ID:** `TASK-Aep-013`

**Slice:** `PROP-Aep-001.C12.3`–`C12.5` **Harness:** coordinator **Type:** HITL

**Depends on:** TASK-Aep-011, TASK-Aep-012 (and the branch landed on main via the land pipeline)

## TASK-Aep-013.P1 Scope

Type: HITL

Coordinator-only execution of the rehearsed cutover against the canonical `.skein` world. The weaver restart is a hard stop that needs fresh, explicit user sign-off for this feature: the F1 pre-authorization (`92awa`) covered F1 only and does not carry over (`PROP-Aep-001.C12.4`). Never delegated to a worker; never started autonomously.

## TASK-Aep-013.P2 Must implement exactly

- **TASK-Aep-013.MI1:** Precondition: F2 landed on main, Task 11's rehearsal recorded clean, Task 12 green on the landed head.
- **TASK-Aep-013.MI2:** Quiesce the board (`PROP-Aep-001.C12.3`): no in-flight delegated runs or open subagent gates mid-transition.
- **TASK-Aep-013.MI3:** Back up the live db (resolve via `./bin/mill weaver status --workspace <canonical>` → `database_path`; copy aside, F1 precedent), then run the stamping script against it with an explicit `--db` target.
- **TASK-Aep-013.MI4:** STOP: ask the user for explicit sign-off before restarting the canonical weaver (`PROP-Aep-001.C12.4`). Only after sign-off: restart, then run the C12.5 smoke — `strand agent status`, `strand ready --query stalled-gates`, `strand agent ps --for <a live task>`, `strand kanban board` render clean; `strand list --query agent-failures` returns without error.
- **TASK-Aep-013.MI5:** Record the cutover (backup path, row/edge counts, smoke results) as a note on card `ah5vu`; only then `kanban finish` is in play.

## TASK-Aep-013.P3 Done when

- **TASK-Aep-013.DW1:** Canonical world stamped; user-signed restart done; C12.5 smoke green; audit note on the card.

## TASK-Aep-013.P4 Out of scope

- **TASK-Aep-013.OS1:** Any code or doc change.

## TASK-Aep-013.P5 References

- **TASK-Aep-013.REF1:** `PROP-Aep-001.C12.3`–`C12.5`, `R2`; `PLAN-Aep-001.CM3`.
- **TASK-Aep-013.REF2:** F1 ceremony: `devflow/feat/agent-layer-rename/cutover-ceremony.md`.
