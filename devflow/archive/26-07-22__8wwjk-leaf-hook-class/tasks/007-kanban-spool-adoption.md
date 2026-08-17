# Task 7: kanban.spool adoption + release tag

**Document ID:** `TASK-Lhc-007`

## TASK-Lhc-007.P1 Scope

Type: AFK

Adopt per-leaf classes and real nesting in the kanban spool (`~/dev/projects/kanban.spool`, pinned v6 in skein-src). The known migration: `kanban` declares leaf classes on every verb (`about`/`board`/`card`/`next`/ `prime` `:read`; `add`/`claim`/`finish`/`note`/`priority`/`promote`/`reopen`/ `review`/`rework` `:mutating`; all `:standard`), and `kanban task` migrates from the positional `<action> add|list` hack to real nested subcommands (`task add <feature> ...` `:mutating`, `task list <feature>` `:read`) with its returns tree mirrored and handler dispatch reading the `:subcommand` path vector.

## TASK-Lhc-007.P2 Must implement exactly

- **TASK-Lhc-007.MI1:** Every leaf of every registered op declares both classes in the arg-spec — the `kanban` family AND the auxiliary ops `kanban-export`, `kanban-peers`, `kanban-send`, plus the guild `kanban.send.v1` registration (which must supply explicit classes to the new guild constructor contract from skein-src Task 3). Produce the full verb-by-verb class matrix in the worklog before editing; no registration-opts classes remain.
- **TASK-Lhc-007.MI2:** `task` nesting per above; the board/tracker projections and `print-board!` keep working (they join tasks by attributes, not argv).
- **TASK-Lhc-007.MI3:** Handler `:subcommand` consumption updated to path vectors throughout; docs (spool README/contract) updated.
- **TASK-Lhc-007.MI4:** Suite green against the feature checkout, including a live depth-N help assertion through `kanban task add` (`strand help kanban task add` slices to the leaf with populated classes).
- **TASK-Lhc-007.MI5:** Release + wiring discipline:
- **WIRE:** Work in a clean isolated worktree of the spool repo (never the user's live checkout); the repo's deps hard-code `../skein-src`, so wire the feature checkout with an **uncommitted** local dev override (deps.edn `:local/root` override or the repo's documented mechanism) pointing at `/Users/ct/dev/projects/skein-src__8wwjk-leaf-hook-class`; prove no override lands in any commit (`git show --stat` in worklog).
- **CEREMONY:** Run the repo's `bin/compat-alarm` (or documented equivalent) against the previous tag, decide/record the Skein version floor, push commits to the default branch, push the annotated tag, and record the tag plus the peeled `refs/tags/vN^{}` commit SHA in the worklog note.

## TASK-Lhc-007.P3 Done when

- **TASK-Lhc-007.DW1:** Spool suite cold-green against the feature checkout; repo's own lint/format gates green if present.
- **TASK-Lhc-007.DW2:** `v7` annotated tag pushed; worklog carries tag + peeled SHA + compat-alarm outcome + class matrix.

## TASK-Lhc-007.P4 Out of scope

- **TASK-Lhc-007.OS1:** Any skein-src edit (pin bumps are Task 9); other spool repos.

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH5)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
