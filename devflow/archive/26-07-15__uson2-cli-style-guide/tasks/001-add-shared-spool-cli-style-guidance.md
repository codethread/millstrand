# Task 1: Add shared-spool CLI style guidance

**Document ID:** `TASK-Ucs-001` **Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable.

## TASK-Ucs-001.P1 Scope

Type: AFK

Execution seat: `sol-high` (agent-facing documentation policy).

Add the approved advisory CLI style section beside the discovery section in `docs/spools/writing-shared-spools.md`. This task owns only that file.

Commit policy: commit only this task's owned change on the current feature branch. Use a HEREDOC commit message. Do not amend or include unrelated changes.

## TASK-Ucs-001.P2 Must implement exactly

- **TASK-Ucs-001.MI1:** State the role-based verb sets from `PROP-Ucs-001.S1`: entity lifecycle, workflow steps, and processes.
- **TASK-Ucs-001.MI2:** State the shared flag vocabulary: `--by`; attribute-named `--owner`, `--branch`, `--worktree`, and `--feature`; seconds-first unit-suffixed durations such as `--timeout-secs`; and `--outcome` for closing state.
- **TASK-Ucs-001.MI3:** Recommend `list` for live filterable work and plural nouns such as `harnesses`, `suites`, or `backends` for fixed catalogs.
- **TASK-Ucs-001.MI4:** Recommend one op with declared subcommands for a cohesive multi-verb domain while keeping single-purpose projections and config-registered ops flat.
- **TASK-Ucs-001.MI5:** Make payload-reference support the sole MUST: every text-bearing flag or positional uses the declared parser so whole-value `:stdin` and `:payload/<name>` references resolve.
- **TASK-Ucs-001.MI6:** Mark naming advice as advisory and fix-on-touch. Do not prescribe rename-only churn or compatibility aliases.
- **TASK-Ucs-001.MI7:** Link to the authoritative discovery-tier section in `docs/reference.md`; do not restate `help`, `about`, or `prime`.
- **TASK-Ucs-001.MI8:** Apply the repository docs-style procedure to the new prose.

## TASK-Ucs-001.P3 Done when

- **TASK-Ucs-001.DW1:** Each approved brief point appears once, with naming advice clearly advisory and the payload-reference rule clearly mandatory.
- **TASK-Ucs-001.DW2:** The discovery contract is linked rather than copied.
- **TASK-Ucs-001.DW3:** The docs-style sweep is clean: no unjustified tell hits, no prose line beyond column 180, and headings use sentence case.
- **TASK-Ucs-001.DW4:** `make docs-check` passes.
- **TASK-Ucs-001.DW5:** `git status --short` shows only the intended documentation change before commit and no runtime metadata afterward.

## TASK-Ucs-001.P4 Out of scope

- **TASK-Ucs-001.OS1:** Parser, API, spool, test, generated API documentation, and root-spec changes.
- **TASK-Ucs-001.OS2:** Changes to existing command names or compatibility aliases.
- **TASK-Ucs-001.OS3:** External `kanban.spool` work or pin movement; card `m5u47` owns that work.

## TASK-Ucs-001.P5 References

- **TASK-Ucs-001.REF1:** `PLAN-Ucs-001.S1`, A1, P6, and P8 in [../uson2-cli-style-guide.plan.md](../uson2-cli-style-guide.plan.md).
- **TASK-Ucs-001.REF2:** `PROP-Ucs-001.S1`, G1, G2, G5, and NG1-NG6 in [../proposal.md](../proposal.md).
- **TASK-Ucs-001.REF3:** Existing discovery guidance and target file: `docs/spools/writing-shared-spools.md`.
- **TASK-Ucs-001.REF4:** Authoritative discovery tiers: `docs/reference.md`, "Discovery tiers".
- **TASK-Ucs-001.REF5:** Repository prose gate: `.agents/skills/docs-style/SKILL.md`.
