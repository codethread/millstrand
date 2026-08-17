# Finalize Alpha API Surface

**Document ID:** `TASK-001`

## TASK-001.P1 Scope

Type: AFK

Freeze the MVP namespace split, helper names, daemon operation names, and return shapes before implementation begins.

## TASK-001.P2 References

- **TASK-001.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-001.R2:** [Runtime Transformations PRD](../../../prd/runtime-transformations.md)
- **TASK-001.R3:** [Daemon delta](../specs/daemon-runtime.delta.md), [REPL delta](../specs/repl-api.delta.md), [CLI delta](../specs/cli.delta.md)
- **TASK-001.R4:** Current blessed helper pattern: `src/atom/libs/alpha.clj`

## TASK-001.P3 Implementation notes

- **TASK-001.I1:** Prefer a small split of `atom.graph.alpha` for query/id/graph helpers and `atom.views.alpha` for view registry helpers unless implementation evidence shows one namespace is clearly simpler.
- **TASK-001.I2:** Record final API choices in `runtime-transformation-primitives.plan.md` Developer Notes and adjust spec deltas if names or shapes differ from the current sketches.
- **TASK-001.I3:** Preserve these frozen contracts unless explicitly revising the plan:
    - `query-ids!` returns ids ordered like `list` query results.
    - `tasks-by-ids` returns normalized rows in first-occurrence input order, collapses duplicates, returns `[]` for empty input, and fails for missing ids.
    - `ancestor-root-ids` uses `parent-of` parent `from_task_id` -> child `to_task_id`, includes seeds as depth-zero candidates, and returns topmost matching ancestors.
    - `subgraph` returns `{:root-ids [...] :tasks [...] :edges [...]}` with the ordering rules from the daemon delta.
    - `register-view!` accepts a simple name and fully qualified function symbol; `view!` invokes daemon-side with `{:params params}`; `views` returns serializable entries.

## TASK-001.P4 Done when

- **TASK-001.D1:** The plan contains a Developer Notes entry naming the final namespace split and API surface.
- **TASK-001.D2:** Feature spec deltas and PRD examples are internally consistent with the final names.
- **TASK-001.D3:** No code implementation beyond small doc/spec edits is required for this task.
