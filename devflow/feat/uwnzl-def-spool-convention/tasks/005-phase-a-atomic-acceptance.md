# Task 5: Phase A atomic acceptance and adversarial review

**Document ID:** `TASK-Dsp-005`

## TASK-Dsp-005.P1 Scope

Type: AFK

Coordinator-only acceptance once every Phase A branch is integrated into `codex/uwnzl-def-spool-convention`. Rerun each slice gate in the integrated worktree, run the full acceptance matrix, and hold for registered reviews before land. Tracked on strand `z12za`.

## TASK-Dsp-005.P2 Must implement exactly

- **TASK-Dsp-005.MI1:** Inspect every commit and diff across the integrated worktree, then rerun each task's own validation gate there.
- **TASK-Dsp-005.MI2:** Run `make build`, cold focused tests, `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check`.
- **TASK-Dsp-005.MI3:** Run `make spool-suite-gate` (pinned sibling suites still carry explicit in-tree keys), then the flock-held full Clojure suite once at queue acceptance: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`.
- **TASK-Dsp-005.MI4:** Confirm `git status --short` shows no generated SQLite or runtime-metadata artifacts and no unowned files.
- **TASK-Dsp-005.MI5:** Route the integrated worktree through registered change-review and complex-patch-review; fix and resubmit every finding before close.

## TASK-Dsp-005.P3 Done when

- **TASK-Dsp-005.DW1:** Every gate in MI2–MI4 is green with commands and results recorded on the doing task and the plan's Developer Notes.
- **TASK-Dsp-005.DW2:** All review findings are resolved and resubmitted.
- **TASK-Dsp-005.DW3:** The invariance proof (`PLAN-Dsp-001.V1`) holds in the integrated worktree, and the branch is ready to land. Phase A may land here; sibling release work remains outside this task.

## TASK-Dsp-005.P4 Out of scope

- **TASK-Dsp-005.OS1:** Do not run `make install` or restart the canonical weaver.
- **TASK-Dsp-005.OS2:** The pre-v1 cutover decision (Task 6) and any sibling release (Tasks 7–8).

## TASK-Dsp-005.P5 References

- **TASK-Dsp-005.REF1:** `PLAN-Dsp-001.V3`; CLAUDE.md validation and landing discipline; `strand land about`.
- **TASK-Dsp-005.REF2:** Strand `z12za`; integration branch `codex/uwnzl-def-spool-convention`; kanban task `vwa06`.
