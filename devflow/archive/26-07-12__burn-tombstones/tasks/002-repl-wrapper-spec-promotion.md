# Task 2: repl recovery wrapper, SPEC-001 delta promotion, docs gates

**Document ID:** `TASK-Tomb-002`

## TASK-Tomb-002.P1 Scope

Type: AFK

Human-facing recovery surface and contract promotion, per [PLAN-Tomb-001](../burn-tombstones.plan.md) A3/PH2.

## TASK-Tomb-002.P2 Must implement exactly

- **TASK-Tomb-002.MI1:** `skein.repl` gains interactive wrappers over the task-1 core read fns (tombstones for a strand id; recent burns). These are **in-process only** — they do NOT ride the `daemon` dispatch (whose remote path needs an op in the api/client tables, which NG2 forbids adding). Instead: resolve the live runtime via `skein.api.current.alpha/runtime-or-nil`, get its datasource with `skein.core.weaver.access/ds`, and call the `skein.core.db` read fns directly. When no in-process runtime exists (connected-client REPL or none), throw with remediation text pointing at `mill weaver repl` — disaster recovery is an in-process weaver REPL activity, and the docstrings say so. No new behavior beyond convenience projection.
- **TASK-Tomb-002.MI2:** Merge DELTA-Tomb-001 (`devflow/feat/burn-tombstones/specs/strand-model.delta.md`) into `devflow/specs/strand-model.md`: P3 gains the tombstone-on-every-burn-path contract and forensic-not-undo semantics (including ref-assembly recovery caveats), P8 gains the `burn_history` persistence paragraph and read-tier boundary (core.db + repl only, no api/CLI), P10 drops deletion tombstones from the deferred list and adds still-deferred items (retention, restore op, programmatic surface). Update the root spec's Last Updated; mark the delta Status: Merged.
- **TASK-Tomb-002.MI3:** Verify the recovery round-trip manually in a disposable workspace (own `ws=$(mktemp -d)`, every expansion `${ws:?}`, never the canonical .skein world): create strands+edges, burn, read the tombstone from the REPL, assemble and replay a batch payload, confirm the new-id caveat. Record the transcript summary in the plan's Developer Notes (append-only).

## TASK-Tomb-002.P3 Done when

- **TASK-Tomb-002.DW1:** Cold focused run green on touched test namespaces (add repl-surface coverage if the namespace has an existing test home; otherwise db-level coverage from task 1 stands).
- **TASK-Tomb-002.DW2:** `make fmt-check lint reflect-check docs-check` clean.
- **TASK-Tomb-002.DW3:** Root spec and delta statuses updated as in MI2; `devflow/README.md` untouched unless index data changed.
- **TASK-Tomb-002.DW4:** Committed on branch `5ys8r-burn-tombstones`; worker stops at implemented+committed.

## TASK-Tomb-002.P4 Out of scope

- Wider docs sweep (docs/skein.md, spool docs) — separate feature card 2la9m.
- Any api.alpha/CLI surface, retention, restore op.

## TASK-Tomb-002.P5 References

- `src/skein/repl.clj` — existing wrapper idiom.
- `devflow/specs/strand-model.md`, the delta file, PLAN-Tomb-001.P6.
