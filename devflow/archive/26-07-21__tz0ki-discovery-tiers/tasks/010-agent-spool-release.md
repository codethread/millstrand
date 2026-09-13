# Task 10: [HITL] Reviewed release/tag v8 of agent spool

**Document ID:** `TASK-Dtf-010`

## TASK-Dtf-010.P1 Scope

Type: HITL

[HITL] Cut and publish the reviewed `agent-harness.spool` release (new major tag v8) that carries the Task 9 adoption. A published release is an outward, hard-to-reverse action and carries a compatibility break, so it needs human sign-off before the tag is created/pushed.

## TASK-Dtf-010.P2 Must implement exactly

- **TASK-Dtf-010.MI1:** Confirm the Task 9 adoption is reviewed and green in `agent-harness.spool`, and that the v7→v8 compatibility break (PLAN-Dtf-001.CM3) is accepted and release-noted (published-name behavior change; major-tag boundary; new Skein API dependency floor).
- **TASK-Dtf-010.MI2:** Confirm no producer compatibility check/alarm silently blocks the tag; update it if one exists.
- **TASK-Dtf-010.MI3:** Create the annotated `v8` tag on the merged producer change and record the **peeled SHA** for the downstream coordinate bump (Task 11).

## TASK-Dtf-010.P3 Done when

- **TASK-Dtf-010.DW1:** The `v8` tag exists on `agent-harness.spool` with a recorded peeled SHA, and the compatibility boundary is documented.

## TASK-Dtf-010.P4 Out of scope

- **TASK-Dtf-010.OS1:** The downstream `.skein/spools.edn` bump and gate (Task 11).

## TASK-Dtf-010.P5 References

- **TASK-Dtf-010.REF1:** PLAN-Dtf-001.PH7/CM3; RFC-Dtf-001.C4; SPEC-003.C63a (`vN` tag → peeled SHA coordinate convention); the shared-spool release guidance.
