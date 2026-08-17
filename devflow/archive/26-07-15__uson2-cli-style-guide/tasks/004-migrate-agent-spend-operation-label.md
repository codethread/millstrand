# Task 4: Migrate agent spend operation label

**Document ID:** `TASK-Ucs-004` **Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

## TASK-Ucs-004.P1 Scope

Type: AFK

Execution seat: `sol-low`.

Remove the in-repo `agent spend` label mismatch so registered-op dispatch supplies `agent spend`, then update the direct and agent-run expectations. Owned files are `spools/agent-run/src/skein/spools/agent_run.clj`, `spools/delegation/src/skein/spools/delegation.clj`, `test/skein/delegation_test.clj`, and `test/skein/agent_run_test.clj`.

Commit policy: commit only this task's owned changes on the current feature branch. Use a HEREDOC commit message. Do not amend or include unrelated changes.

## TASK-Ucs-004.P2 Must implement exactly

- **TASK-Ucs-004.MI1:** Remove the hand-written `:operation "agent-spend"` from the spend result producer so dispatch owns the registered subcommand label.
- **TASK-Ucs-004.MI2:** Update the spend docstring and delegation operation metadata to describe the canonical `agent spend` result without claiming the producer stamps it.
- **TASK-Ucs-004.MI3:** Update focused delegation and agent-run assertions from `agent-spend` to `agent spend`; preserve all report filters, totals, groups, rows, and failure behavior.
- **TASK-Ucs-004.MI4:** Regenerate affected API docs only if public docstrings change.

## TASK-Ucs-004.P3 Done when

- **TASK-Ucs-004.DW1:** No agent spend producer emits the mismatching `agent-spend` label.
- **TASK-Ucs-004.DW2:** Both direct and registered agent-run consumers expect `agent spend` and all non-label assertions remain intact.
- **TASK-Ucs-004.DW3:** `clojure -M:test skein.delegation-test skein.agent-run-test` passes as a cold focused run.
- **TASK-Ucs-004.DW4:** If a public docstring changes, `make api-docs` completes and generated API docs are current.
- **TASK-Ucs-004.DW5:** `make docs-check` passes.
- **TASK-Ucs-004.DW6:** `git status --short` shows only the owned changes before commit and no runtime metadata afterward.

## TASK-Ucs-004.P4 Out of scope

- **TASK-Ucs-004.OS1:** Changes to spend aggregation, filters, persistence, or report shape beyond the operation label.
- **TASK-Ucs-004.OS2:** Cleanup of already-equal roster or bench labels; those remain fix-on-touch.
- **TASK-Ucs-004.OS3:** External `kanban.spool` changes or pin movement; card `m5u47` owns that work.
- **TASK-Ucs-004.OS4:** Land labels, parser behavior, and root-spec promotion.

## TASK-Ucs-004.P5 References

- **TASK-Ucs-004.REF1:** `PLAN-Ucs-001.S4`, A4, P6, and TC5 in [../uson2-cli-style-guide.plan.md](../uson2-cli-style-guide.plan.md).
- **TASK-Ucs-004.REF2:** `PROP-Ucs-001.S3`, G4, G5, and NG1 in [../proposal.md](../proposal.md).
- **TASK-Ucs-004.REF3:** Producer and operation metadata: `spools/agent-run/src/skein/spools/agent_run.clj` and `spools/delegation/src/skein/spools/delegation.clj`.
- **TASK-Ucs-004.REF4:** Focused consumers: `test/skein/delegation_test.clj` and `test/skein/agent_run_test.clj`.
