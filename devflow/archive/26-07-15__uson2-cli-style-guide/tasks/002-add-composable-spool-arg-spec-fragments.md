# Task 2: Add composable spool arg-spec fragments

**Document ID:** `TASK-Ucs-002`

**Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

## TASK-Ucs-002.P1 Scope

Type: AFK

Execution seat: `sol-med` (Alpha API-shape work).

Add four public plain-data arg-spec fragments to `skein.api.spool.alpha`, pin their shapes and composition in focused tests, and regenerate the spool Alpha API document. Owned files are `src/skein/api/spool/alpha.clj`, `test/skein/api/spool_test.clj`, and generated `docs/api/spool.api.md`.

Commit policy: commit only this task's owned changes on the current feature branch. Use a HEREDOC commit message. Do not amend or include unrelated changes.

## TASK-Ucs-002.P2 Must implement exactly

- **TASK-Ucs-002.MI1:** Define public documented values `note-surface`, `work-root`, `timeout-secs`, and `outcome` in `skein.api.spool.alpha`.
- **TASK-Ucs-002.MI2:** Make every value a partial declared arg-spec map composed through ordinary data merging. Keep the parser domain-neutral; add no parser behavior or new flag type.
- **TASK-Ucs-002.MI3:** Match the approved common shapes: note target/text plus `--by`, work-root `--feature`/`--owner`/`--branch`/`--worktree`, integer `--timeout-secs`, and closing `--outcome`.
- **TASK-Ucs-002.MI4:** Add focused tests that pin all four declarations and demonstrate at least one fragment composed with spool-owned flags or positionals without losing either side.
- **TASK-Ucs-002.MI5:** Give each public var a useful docstring and update the namespace surface description where needed.
- **TASK-Ucs-002.MI6:** Regenerate `docs/api/spool.api.md` from the source docstrings; do not hand-edit generated output.

## TASK-Ucs-002.P3 Done when

- **TASK-Ucs-002.DW1:** The four public vars have the approved names, plain-data shapes, and useful docstrings.
- **TASK-Ucs-002.DW2:** Tests pin each fragment and prove composition with a domain-owned declaration.
- **TASK-Ucs-002.DW3:** No parser source changed.
- **TASK-Ucs-002.DW4:** `clojure -M:test skein.api.spool-test` passes as a cold focused run.
- **TASK-Ucs-002.DW5:** `make api-docs` completes and `docs/api/spool.api.md` is current.
- **TASK-Ucs-002.DW6:** `make docs-check` passes.
- **TASK-Ucs-002.DW7:** `git status --short` shows only the owned changes before commit and no runtime metadata afterward.

## TASK-Ucs-002.P4 Out of scope

- **TASK-Ucs-002.OS1:** Parser changes, macros, fragment functions, or a new fragment namespace.
- **TASK-Ucs-002.OS2:** Migrating existing spool declarations to the fragments.
- **TASK-Ucs-002.OS3:** Operation-label behavior, style-guide prose, and root-spec promotion.
- **TASK-Ucs-002.OS4:** External `kanban.spool` work or pin movement; card `m5u47` owns that work.

## TASK-Ucs-002.P5 References

- **TASK-Ucs-002.REF1:** `PLAN-Ucs-001.S2`, A2, P6, and P8 in [../uson2-cli-style-guide.plan.md](../uson2-cli-style-guide.plan.md).
- **TASK-Ucs-002.REF2:** `PROP-Ucs-001.S2`, G3, and NG2 in [../proposal.md](../proposal.md).
- **TASK-Ucs-002.REF3:** Reviewed Alpha delta [../specs/alpha-surface.delta.md](../specs/alpha-surface.delta.md), especially `DELTA-Ucs-001.CC1`.
- **TASK-Ucs-002.REF4:** Existing API and test conventions: `src/skein/api/spool/alpha.clj` and `test/skein/api/spool_test.clj`.
