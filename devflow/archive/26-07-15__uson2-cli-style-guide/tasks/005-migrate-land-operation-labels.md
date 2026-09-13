# Task 5: Migrate land operation labels

**Document ID:** `TASK-Ucs-005`

**Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

## TASK-Ucs-005.P1 Scope

Type: AFK

Execution seat: `sol-low`.

Remove mismatching hand-written labels from land subcommand results so dispatch supplies `land <verb>`, while flat repo-config projections keep their handler labels. Owned files are `.skein/workflows.clj` and `test/skein/config_test.clj`.

Commit policy: commit only this task's owned changes on the current feature branch. Use a HEREDOC commit message. Do not amend or include unrelated changes.

## TASK-Ucs-005.P2 Must implement exactly

- **TASK-Ucs-005.MI1:** Remove hand-written `land-start`, `land-next`, `land-complete`, `land-choose`, `land-status`, and `land-break-lock` operation labels from land subcommand result construction.
- **TASK-Ucs-005.MI2:** Let registered-op dispatch supply `land start`, `land next`, `land complete`, `land choose`, `land status`, and `land break-lock`.
- **TASK-Ucs-005.MI3:** Preserve the already-canonical `land about` handler label. Equal-label tolerance keeps it valid; this slice removes only the mismatching `land-*` labels assigned by the plan.
- **TASK-Ucs-005.MI4:** Update all focused land operation-label assertions to the spaced canonical forms without changing workflow behavior.
- **TASK-Ucs-005.MI5:** Preserve handler-owned labels on flat repo-config projections because they have no selected subcommand.

## TASK-Ucs-005.P3 Done when

- **TASK-Ucs-005.DW1:** No land subcommand result emits a mismatching `land-*` label.
- **TASK-Ucs-005.DW2:** Registered land results carry `land <verb>` for every tested subcommand, and flat repo-config assertions remain handler-labelled.
- **TASK-Ucs-005.DW3:** `clojure -M:test skein.config-test` passes as a cold focused run.
- **TASK-Ucs-005.DW4:** `make docs-check` passes.
- **TASK-Ucs-005.DW5:** `git status --short` shows only the owned changes before commit and no runtime metadata afterward.

## TASK-Ucs-005.P4 Out of scope

- **TASK-Ucs-005.OS1:** Workflow semantics, merge-lock behavior, card transitions, or argument parsing.
- **TASK-Ucs-005.OS2:** Renaming flat repo-config projections or grouping them under subcommands.
- **TASK-Ucs-005.OS3:** Agent, roster, bench, or external kanban label cleanup.
- **TASK-Ucs-005.OS4:** External `kanban.spool` work or pin movement; card `m5u47` owns that work.
- **TASK-Ucs-005.OS5:** Root-spec promotion.

## TASK-Ucs-005.P5 References

- **TASK-Ucs-005.REF1:** `PLAN-Ucs-001.S5`, A4, P6, and TC5 in [../uson2-cli-style-guide.plan.md](../uson2-cli-style-guide.plan.md).
- **TASK-Ucs-005.REF2:** `PROP-Ucs-001.S3`, G4, G5, NG1, and NG6 in [../proposal.md](../proposal.md).
- **TASK-Ucs-005.REF3:** Land handler: `.skein/workflows.clj`.
- **TASK-Ucs-005.REF4:** Focused operation-label assertions: `test/skein/config_test.clj`.
