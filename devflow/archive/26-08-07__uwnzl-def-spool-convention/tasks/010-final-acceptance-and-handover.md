# Task 10: Final acceptance gates and review handover

**Document ID:** `TASK-Dsp-010`

## TASK-Dsp-010.P1 Scope

Type: AFK

Run the final acceptance matrix over the Phase C cutover and prepare the branch for the coordinator landing workflow. This proves the whole feature is green end to end against bumped pins; it does not adopt anything new into the live world beyond the Phase C refresh already verified. Tracked as kanban task `nws2o`.

Run read-only acceptance in Task 9's integrated `/Users/ct/dev/projects/skein-src__uwnzl-phase-c` worktree on `codex/uwnzl-phase-c-cutover`. This task owns no code or documentation files; any failure returns to its owning Task 7, 8, or 9 branch and must be re-integrated before the acceptance matrix restarts.

## TASK-Dsp-010.P2 Must implement exactly

- **TASK-Dsp-010.MI1:** Rerun cold focused tests per slice, `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` against the bumped sibling pins.
- **TASK-Dsp-010.MI2:** Run `make spool-suite-gate` and the flock-held full Clojure suite once at queue acceptance: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`.
- **TASK-Dsp-010.MI3:** Confirm the epic DONE-WHEN greps still hold and `git status --short` shows no generated SQLite or runtime-metadata artifacts.
- **TASK-Dsp-010.MI4:** Confirm every reviewed delta is Merged in the root specs, the promoted C19 exception is present, and task/plan notes carry the validation evidence.
- **TASK-Dsp-010.MI5:** Prepare the branch for landing: record the exact main commit, dependency-ordered handoff, and any recovery runbook the coordinator needs.

## TASK-Dsp-010.P3 Done when

- **TASK-Dsp-010.DW1:** Every blocking gate is green with commands and results recorded on the doing task and the plan's Developer Notes.
- **TASK-Dsp-010.DW2:** The branch is clean, reviewed, and ready for coordinator landing with published sibling pins that resolve.
- **TASK-Dsp-010.DW3:** The epic DONE-WHEN gates are confirmed green.

## TASK-Dsp-010.P4 Out of scope

- **TASK-Dsp-010.OS1:** Running `make install` or restarting the canonical weaver.
- **TASK-Dsp-010.OS2:** Any code change; failures return to the owning task, they are not patched here.

## TASK-Dsp-010.P5 References

- **TASK-Dsp-010.REF1:** `PLAN-Dsp-001.V3`, `.V4`; CLAUDE.md validation and landing discipline; `strand land about`.
- **TASK-Dsp-010.REF2:** Kanban task `nws2o`; epic card `uwnzl` DONE-WHEN; Task 9 cutover worktree and branch.
