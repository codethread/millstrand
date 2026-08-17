# Task 6: Root spec promotion + docs sweep + api docs

**Document ID:** `TASK-Lhc-006`

## TASK-Lhc-006.P1 Scope

Type: AFK

Promote DELTA-Lhc-001/002/003 into the root specs and sweep the human-facing docs. Prose follows the repo docs style (load the docs-style checklist).

## TASK-Lhc-006.P2 Must implement exactly

- **TASK-Lhc-006.MI1:** Merge each delta into its root spec (repl-api: C64/C65/C66/C67/C68 rewrite, C60b, C28, C63a/C63b batteries surface; daemon-runtime: C63a/C63b/C63d, C26b, C80, C108; cli: C33, C39, C44), mark deltas Merged, preserve clause IDs (append, don't renumber), update `devflow/README.md` index rows and Last Updated stamps.
- **TASK-Lhc-006.MI2:** Sweep `docs/reference.md` and `docs/spools/writing-shared-spools.md` (CLI style + authoring sections) for per-leaf classes, arity-N subcommands, and the composition idiom (flat `def` node blocks, reuse).
- **TASK-Lhc-006.MI3:** `make api-docs` regenerates `*.api.md`; commit the regenerated files.

## TASK-Lhc-006.P3 Done when

- **TASK-Lhc-006.DW1:** `make docs-check fmt-check lint` green; clean `git status --short` (generated api docs committed, nothing stray).
- **TASK-Lhc-006.DW2:** No remaining root-spec text asserts op-wide hook-class/deadline-class or the one-level cap (grep proof in worklog).

## TASK-Lhc-006.P4 Out of scope

- **TASK-Lhc-006.OS1:** Any source/behavior change; sibling spool docs (their own repos, Tasks 7–8).

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH4)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
