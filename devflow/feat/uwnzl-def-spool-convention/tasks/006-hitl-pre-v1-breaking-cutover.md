# Task 6: [HITL] Authorize coordinated pre-v1 breaking cutover

**Document ID:** `TASK-Dsp-006`

## TASK-Dsp-006.P1 Scope

Type: HITL

Record the user's decision to publish convention-dependent sibling releases before Skein v1 under TEN-000@1. The cutover remains ordered and reviewed, but it no longer waits for card `b3v1r`. Because skein-src has no marker containing Phase A, the sibling releases must not add a false `:skein/min` floor.

## TASK-Dsp-006.P2 Must implement exactly

- **TASK-Dsp-006.MI1:** Confirm Phase A landed as `343f886880092bc38ed3e0522eca2d95a7cf04bc` and record the user's 2026-07-24 authorization to proceed without a Skein v1 stamp.
- **TASK-Dsp-006.MI2:** Amend the proposal, plan, and Tasks 7–8 so sibling markers may publish without `:skein/min`, with release exceptions naming the Phase A merge as the first compatible Skein commit.
- **TASK-Dsp-006.MI3:** Keep Skein v1 ownership outside this feature. Do not create, promote, or imply a Skein marker.

## TASK-Dsp-006.P3 Done when

- **TASK-Dsp-006.DW1:** The user authorization and compatible Phase A merge are recorded in the durable feature documents and on kanban task `l5lwo`.
- **TASK-Dsp-006.DW2:** Task 8 requires exact reviewed sibling marker SHAs and release exceptions, and forbids a false `:skein/min` floor.

## TASK-Dsp-006.P4 Out of scope

- **TASK-Dsp-006.OS1:** Creating or stamping Skein `v1` inside this feature. Card `b3v1r` remains independent.
- **TASK-Dsp-006.OS2:** Any sibling code change or marker publication (Tasks 7–8).

## TASK-Dsp-006.P5 References

- **TASK-Dsp-006.REF1:** `PLAN-Dsp-001.CM3`, `.PH-B`, `.TC4`; user direction recorded on kanban task `l5lwo` as note `xovsc`.
- **TASK-Dsp-006.REF2:** Kanban task `l5lwo`; waq0l note `5bae1` (sibling suites hard-code `../skein-src`).
