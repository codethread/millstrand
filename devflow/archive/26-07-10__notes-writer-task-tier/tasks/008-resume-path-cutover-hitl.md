# Task 8: [HITL] pre-merge resume-path cutover for cards 3tgaj/1x2zz

**Document ID:** `TASK-Nwt-008` **Slice:** `PLAN-Nwt-001.PH3` (coordinator gate) · **Depends on:** Tasks 6 and 7 (the surfaces that drop handover projection). Status: done (cutover done 2026-07-10: 3tgaj resumes from task 84m3n; 1x2zz audited clean).

## TASK-Nwt-008.P1 Scope

Type: HITL

Coordinator-only gate: before the handover-projection removal (Tasks 6/7) merges, any card whose live resume path is its latest handover must gain an equivalent task/note resume point. As of 2026-07-10 that is cards `3tgaj` and `1x2zz`. This is a coordinator decision gated on the merge — never a worker task, never code (`PLAN-Nwt-001.CM5`, `R3`, `TC4`, `PROP-Nwt-001.NG4`).

## TASK-Nwt-008.P2 Must implement exactly

- **TASK-Nwt-008.MI1 (re-verify the card set):** The board accrues cards continuously; re-verify at merge time which cards' live resume path is a latest handover — do not assume the set is still exactly `3tgaj`/`1x2zz` (`PLAN-Nwt-001.R3`).
- **TASK-Nwt-008.MI2 (equivalent resume point):** For each such card, create/record an equivalent task/note resume point (a doing-task + its latest note, or an explicit resume note) BEFORE the projection drop lands, so no in-flight card is stranded (`PLAN-Nwt-001.CM5`, `R3`).
- **TASK-Nwt-008.MI3 (gate the merge):** Do not proceed with the handover-projection merge until every identified card carries its equivalent resume point. This is a hard pre-merge stop — a coordinator decision, not delegated.

## TASK-Nwt-008.P3 Done when

- **TASK-Nwt-008.DW1:** Every card whose resume path was a latest handover (re-verified at merge time) carries an equivalent task/note resume point, recorded before the projection drop merges (`PLAN-Nwt-001.CM5`, `R3`).
- **TASK-Nwt-008.DW2:** The cutover (card set, resume points created) is recorded as a coordinator note so the merge gate is auditable. No code or test gate — this is a data/coordination step (`PROP-Nwt-001.NG4`).

## TASK-Nwt-008.P4 Out of scope

- **TASK-Nwt-008.OS1:** Any code, test, or doc change (Tasks 6/7 own the removal).
- **TASK-Nwt-008.OS2:** Rewriting historical handover notes — they stay immutable and unprojected (`PROP-Nwt-001.NG4`).
- **TASK-Nwt-008.OS3:** Weaver restart — not required on data grounds (`PLAN-Nwt-001.CM4`).

## TASK-Nwt-008.P5 References

- **TASK-Nwt-008.REF1:** `PLAN-Nwt-001.CM5` (pre-merge resume-path cutover), `R3`, `TC4`, `CM4`, `PH3`.
- **TASK-Nwt-008.REF2:** `PROP-Nwt-001.NG4`; `DELTA-Nwt-001.J2`.
- **TASK-Nwt-008.REF3:** cards `3tgaj`, `1x2zz` (resume-path holders as of 2026-07-10 — re-verify at merge).
