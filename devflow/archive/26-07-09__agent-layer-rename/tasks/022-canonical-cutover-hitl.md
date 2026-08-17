# Task 22: canonical-world cutover at user-signed weaver restart

**Document ID:** `TASK-Alr-022` **Phase:** `PLAN-Alr-001.PH6` (canonical execution) **Harness:** — (coordinator, interactive) **Type:** HITL (`hitl: true`) **Depends on:** TASK-Alr-020, TASK-Alr-021

## TASK-Alr-022.P1 Scope

Execute the rehearsed cutover against the **canonical `.skein` world** and bring its weaver up on the renamed code. This is **not a delegable worker task** — it is coordinator-run and gated on explicit user sign-off, because it rewrites the live coordination world's active strands and requires a weaver restart that tears down live shuttle/agent-run runs and the registries sibling agents depend on. **`hitl: true`** — stop and hand to the human at the restart; never choose it for the user.

**Why HITL (say it in the body per contract):** restarting the canonical weaver is a repo hard rule requiring explicit user sign-off (it destroys live runs and registries); the cutover mutates durable data in the shared world irreversibly. The user must be present at the restart (`PROP-Alr-001.C4/DW4`, `PLAN-Alr-001.PH6` gate, CLAUDE.md weaver-restart rule).

## TASK-Alr-022.P2 Must implement exactly (coordinator runbook, per Task 20's ceremony doc)

- **TASK-Alr-022.MI1:** Precondition: Task 21 acceptance is green (code landed) and Task 20's rehearsal passed against a SQLite **copy**. Do not proceed otherwise.
- **TASK-Alr-022.MI2:** Quiet the board (no in-flight runs mid-write), then run the Task 19 cutover script against the canonical world's **live** db — resolve its path from `mill weaver status --workspace <canonical>` (`database_path`, under the weaver state dir, not workspace-local `data/`) — following the Task 20 ceremony doc exactly.
- **TASK-Alr-022.MI3:** **HARD STOP — user sign-off:** hand to the user for the canonical weaver restart. The agent does not restart the canonical weaver.
- **TASK-Alr-022.MI4:** After the user-signed restart, run the `PROP-Alr-001.C5` post-restart smoke on the new keys with the runnable commands — `strand agent status`, `strand ready --query stalled-gates`, `strand kanban board` — and confirm clean.

## TASK-Alr-022.P3 Validation / Done when

- **TASK-Alr-022.DW1:** The user has signed off and performed the weaver restart; post-restart smoke is clean on the renamed keys.
- **TASK-Alr-022.DW2:** The canonical world's active strands carry the new vocabulary; no table-mapped old key remains active.

## TASK-Alr-022.P4 Out of scope

- **TASK-Alr-022.OS1:** Authoring/editing the script or ceremony doc (Tasks 19/20).
- **TASK-Alr-022.OS2:** Any code rename (PH1–PH6) or the acceptance battery (Task 21).

## TASK-Alr-022.P5 Commit

- No commit — this is a live-world data + restart ceremony, not a code change.

## TASK-Alr-022.P6 References

- **TASK-Alr-022.REF1:** `PLAN-Alr-001.PH6` gate ("the canonical cutover is coordinator-run after explicit user sign-off, not a worker task"), `PROP-Alr-001.C4/C5/DW4`.
- **TASK-Alr-022.REF2:** CLAUDE.md "Never restart a running weaver … requires explicit user sign-off"; Task 20 ceremony doc.
