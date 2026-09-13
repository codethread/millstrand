# Task 8: agent-harness.spool adoption (agent-run/delegation/bench) + release tag

**Document ID:** `TASK-Lhc-008`

## TASK-Lhc-008.P1 Scope

Type: AFK

Adopt per-leaf classes across all three roots of `~/dev/projects/agent-harness.spool` (pinned v11 in skein-src): `agent-run`, `delegation` (the `agent` op family), and `bench`. The motivating fix: `agent` today registers op-wide `:deadline-class :unbounded` for the sake of its awaiting verbs — under leaf classes, only the blocking leaves (`await`, `flow-await`-style verbs, interactive attach) declare `:unbounded`; every other verb gets its true class. Do not trust this task's prose for the verb inventory: derive the exact leaf list per root from the pinned v11 source (review confirmed there is no `attach` leaf) and produce a full verb-by-verb class matrix in the worklog before editing.

## TASK-Lhc-008.P2 Must implement exactly

- **TASK-Lhc-008.MI1:** Every op leaf in all three roots declares both classes in its arg-spec; no registration-opts classes remain; doc-only leaves included.
- **TASK-Lhc-008.MI2:** Handler `:subcommand` consumption updated to path vectors. Enumerate every positional-action grammar in the migration matrix; any grammar relying on the retired nested `:action` label amendment MUST migrate to real nesting here (DELTA-Lhc-002.CC5); purely-internal positionals that never fed the label may stay, marked so in the matrix.
- **TASK-Lhc-008.MI3:** Root docs/contracts updated (`strand about agent` prose source, bench manual) where they state op-wide classes.
- **TASK-Lhc-008.MI4:** All three roots' suites green against the feature checkout.
- **TASK-Lhc-008.MI5:** Release + wiring discipline:
- **WIRE:** Work in a clean isolated worktree of the spool repo (never the user's live checkout); the repo's deps hard-code `../skein-src`, so wire the feature checkout with an **uncommitted** local dev override (deps.edn `:local/root` override or the repo's documented mechanism) pointing at `/Users/ct/dev/projects/skein-src__8wwjk-leaf-hook-class`; prove no override lands in any commit (`git show --stat` in worklog).
- **CEREMONY:** Run the repo's `bin/compat-alarm` (or documented equivalent) against the previous tag, decide/record the Skein version floor, push commits to the default branch, push the annotated tag, and record the tag plus the peeled `refs/tags/vN^{}` commit SHA in the worklog note.

## TASK-Lhc-008.P3 Done when

- **TASK-Lhc-008.DW1:** All three roots' suites cold-green against the feature checkout; repo gates green.
- **TASK-Lhc-008.DW2:** `v12` annotated tag pushed; worklog carries tag + peeled SHA + compat-alarm outcome + class matrices.

## TASK-Lhc-008.P4 Out of scope

- **TASK-Lhc-008.OS1:** Any skein-src edit; other spool repos.

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH5)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
