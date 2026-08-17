# Task 5: Agent-run result consumption seam

**Document ID:** `TASK-Dcr-005`

## TASK-Dcr-005.P1 Scope

Type: AFK

Execution seat: `sol-med`

Implement `PLAN-Dcr-001.PH5`: check the successful agent-run result at the subagent-to-workflow consumption seam.

Owned files:

- `spools/agent-run/src/skein/spools/executors/subagent.clj`
- `test/skein/executors/subagent_test.clj`
- generated subagent API reference if its public docstrings change

## TASK-Dcr-005.P2 Must implement exactly

- **TASK-Dcr-005.MI1:** Check a successful non-blank `agent-run/result` against the shared `:string` shape immediately before the subagent executor calls `workflow/complete!`.
- **TASK-Dcr-005.MI2:** Route a mismatch through the existing loud delivery-error path and leave the workflow gate incomplete. Preserve valid delivery and all existing retry/error behavior.
- **TASK-Dcr-005.MI3:** Do not check producer persistence, arbitrary stored attributes, op responses, or unrelated workflow payloads.

## TASK-Dcr-005.P3 Done when

- **TASK-Dcr-005.DW1:** Tests prove valid result delivery completes the gate and invalid result delivery is loud, follows the existing error path, and does not complete the gate.
- **TASK-Dcr-005.DW2:** Cold focused gate passes: `clojure -M:test skein.executors.subagent-test`.
- **TASK-Dcr-005.DW3:** `make fmt-check lint reflect-check` passes. If public docstrings change, `make api-docs` and `make docs-check` also pass and generated changes are committed.

## TASK-Dcr-005.P4 Out of scope

- **TASK-Dcr-005.OS1:** Producer-side validation, stored-attribute normalization, serving-path checks, or another spool-to-spool seam.

## TASK-Dcr-005.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-005.P6 References

- **TASK-Dcr-005.REF1:** `PLAN-Dcr-001.A6`, `PH5`, `V3`, `TC3`.
- **TASK-Dcr-005.REF2:** `PROP-Dcr-001.P4.4`; `DELTA-Dcr-as-001.CC3`.
- **TASK-Dcr-005.REF3:** `spools/agent-run/src/skein/spools/executors/subagent.clj` delivery path.
