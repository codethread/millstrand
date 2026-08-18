# Task 3: Stamp dispatch-owned operation labels

**Document ID:** `TASK-Ucs-003`

**Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

## TASK-Ucs-003.P1 Scope

Type: AFK

Execution seat: `sol-med` (registered-operation API-shape work).

Make registered-op dispatch own canonical operation labels for declared-subcommand map results. Owned files are `src/skein/api/weaver/alpha.clj` and `test/skein/weaver_test.clj`.

Commit policy: commit only this task's owned changes on the current feature branch. Use a HEREDOC commit message. Do not amend or include unrelated changes.

## TASK-Ucs-003.P2 Must implement exactly

- **TASK-Ucs-003.MI1:** After the handler returns, when parsed args contain `:subcommand` and the result is a map, derive `"<op-name> <full-subcommand-path>"` from the canonical registered op name, parsed subcommand, and parsed nested `:action` when present.
- **TASK-Ucs-003.MI2:** Stamp an absent `:operation` key with the derived label. Use `contains?` so explicit nil is not treated as absence.
- **TASK-Ucs-003.MI3:** Preserve an existing label only when it exactly equals the derived label.
- **TASK-Ucs-003.MI4:** Fail loudly on every different existing value, including nil, and include expected and actual labels in exception data.
- **TASK-Ucs-003.MI5:** Leave flat arg-spec ops, raw-envelope ops, non-map results, thrown failures, streaming emitted items, and the dispatch help alias unchanged.
- **TASK-Ucs-003.MI6:** Add focused coverage for absent, equal, conflicting, and explicit-nil labels, a two-level command path, and every excluded path named in MI5.
- **TASK-Ucs-003.MI7:** Update the public `op!` docstring only if needed to describe the new result boundary.

## TASK-Ucs-003.P3 Done when

- **TASK-Ucs-003.DW1:** Dispatch stamps absent labels, preserves equal labels, and rejects disagreements with expected/actual evidence.
- **TASK-Ucs-003.DW2:** Tests show flat, raw, non-map, failure, stream emission, and help-alias behavior remains unchanged.
- **TASK-Ucs-003.DW3:** `clojure -M:test skein.weaver-test` passes as a cold focused run.
- **TASK-Ucs-003.DW4:** If a public docstring changes, `make api-docs` completes and generated API docs are current.
- **TASK-Ucs-003.DW5:** `make docs-check` passes.
- **TASK-Ucs-003.DW6:** `git status --short` shows only the owned changes before commit and no runtime metadata afterward.

## TASK-Ucs-003.P4 Out of scope

- **TASK-Ucs-003.OS1:** Parser behavior, inference for flat ops, result wrappers for non-map values, or mutation of emitted stream items.
- **TASK-Ucs-003.OS2:** Removing labels from agent, land, roster, bench, or external kanban handlers.
- **TASK-Ucs-003.OS3:** Compatibility aliases or exceptions for mismatching consumers.
- **TASK-Ucs-003.OS4:** Root-spec promotion; the reviewed delta remains the contract source until feature acceptance.

## TASK-Ucs-003.P5 References

- **TASK-Ucs-003.REF1:** `PLAN-Ucs-001.S3`, A3, P6, and TC3 in [../uson2-cli-style-guide.plan.md](../uson2-cli-style-guide.plan.md).
- **TASK-Ucs-003.REF2:** `PROP-Ucs-001.S3` and G4 in [../proposal.md](../proposal.md).
- **TASK-Ucs-003.REF3:** Reviewed runtime delta [../specs/daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), especially `DELTA-Ucs-002.CC1` and `CC2`.
- **TASK-Ucs-003.REF4:** Dispatch and focused tests: `src/skein/api/weaver/alpha.clj` and `test/skein/weaver_test.clj`.
