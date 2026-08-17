# Task 2: Batteries sweep — remaining ops, renderer, contract docs

**Document ID:** `TASK-Lhc-002`

## TASK-Lhc-002.P1 Scope

Type: AFK

Declare per-leaf `:hook-class`/`:deadline-class` in the arg-spec of **every** batteries op other than `spool` (done in Task 1): add/update/show/supersede/ burn/list/ready/subgraph/weave/query/pattern/vocab/note/notes and any other registered batteries op. Mechanical adoption of the Task 1 mechanism; classes match each op's current registered behavior (`query`/`pattern`/`vocab`/`list`/ `ready`/`show`/`subgraph`/`notes` are reads; mutation ops keep `:mutating`; all `:standard` unless currently `:unbounded`).

## TASK-Lhc-002.P2 Must implement exactly

- **TASK-Lhc-002.MI1:** Every batteries op's arg-spec leaves carry both classes (doc-only leaves included, e.g. `query list`); registration/contribution entries stop passing op-level classes for arg-spec ops (raw-envelope ops, if any, keep registration classes per DELTA-Lhc-002.CC1).
- **TASK-Lhc-002.MI2:** The batteries op-contribution constructors (`op-contribution-entry` and peers) carry the node classes through the publication shape unchanged — no re-created defaults.
- **TASK-Lhc-002.MI3:** `spools/batteries.md` and `spools/README.md` updated for per-leaf classes and the `spool status` fold (DELTA-Lhc-001.CC8).
- **TASK-Lhc-002.MI4:** Owner tests assert leaf classes for a representative read leaf and mutating leaf, and existing hook-class assertions (`batteries_test.clj:113` region) move to the node shape.

## TASK-Lhc-002.P3 Done when

- **TASK-Lhc-002.DW1:** Cold `clojure -M:test` green on the batteries owner test namespaces.
- **TASK-Lhc-002.DW2:** `make api-docs` when docstrings changed; `make fmt-check lint reflect-check docs-check` green; clean `git status --short`.

## TASK-Lhc-002.P4 Out of scope / ownership

- **TASK-Lhc-002.OS1:** No edits to Task 1's core mechanism files, the smoke suite (Task 1 owns it), `.skein/` (Task 4), or enforcement (Task 5). Batteries op definitions + owner tests are owned here post-Task 1; the `spool` op and renderer regions stay as Task 1 left them.
- Owns: `spools/batteries/src/skein/spools/batteries.clj` (op definitions + contribution constructors; not the `spool` op or renderer regions), `test/skein/spools/batteries_test.clj`, `spools/batteries.md`, `spools/README.md`.

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH2a)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
