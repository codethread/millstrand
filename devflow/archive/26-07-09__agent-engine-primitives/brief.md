# Brief: agent-run engine primitives — serves relation and run lineage

Kanban: card `ah5vu`, feature 2 of epic `kaans` (agent-layer redesign). Source: coordinator design session 2026-07-09; F1 (`agent-layer-rename`, card `26o9g`) landed on main as `c79abb6` with the canonical world cut over to the renamed vocabulary.

## Problem

F1 renamed the surfaces; this feature changes the behavior. Two structural debts remain in `skein.spools.agent-run` and its consumers:

1. **Serving is encoded two ways, both wrong.** A run "serving" a strand is expressed by overloading `parent-of` (polluting structure-only traversals: loom, status, next-steps) plus the `agent-run/serves` boolean (`"false"` marks helpers). Consumers must combine an edge and an attr to answer "which run serves strand X".
2. **Run succession is three unrelated mechanisms.** Crash-respawn, resume, and deliberate supersession each preserve different subsets of target, `depends-on` edges, provenance, and attrs. `executors.subagent` compensates with `gate/run` stamp machinery, `gate/superseded-by` bookkeeping, a superseded-in-stall-predicate workaround, and a documented "retry is not the recovery verb" footgun.

## Scope (the contract)

In `skein.spools.agent-run` (post-F1 names):

1. **`serves` relation**: engine-owned edge run→target replacing both the `parent-of` overload and the `agent-run/serves` boolean. A serving run _is_ a run with a `serves` edge; helpers (recon spawns, reviewers, panel seats) attach without one. Delegation guards count `serves` edges. `parent-of` stays as structural placement (runs still render beneath their task); what is removed is the _interpretation_ — no reader infers serving from `parent-of` anymore.
2. **Lineage**: engine-owned deliberate supersession joining crash-respawn and resume as one family. A `supersede-and-respawn` primitive preserves target, `depends-on` edges, provenance, and attrs; a `supersedes` edge plus `agent-run/supersedes` attr record the chain; the engine answers "current run serving strand X".
3. **`delegation/retry` becomes a thin policy wrapper** over the engine primitive (keeps `--fresh`, fix-body-first guidance, serving-run selection).
4. **`executors.subagent` recovery rewired onto lineage**: delete the `gate/run` stamp machinery, `gate/superseded-by` compensation, the superseded-in-stall-predicate workaround, and the retry-on-gate footgun; delete the "retry is not the recovery verb" warning prose from the subagent executor doc; `agent retry` on a gate-serving run recovers the gate with no extra re-link step. The `stalled-gates` query is rewritten over serves+lineage.

Update `devflow/specs/strand-model.md` and the relations catalog (`skein.api.relations.alpha`) for the new relations; the alpha-surface index is unaffected (agent-run/delegation are repo-local userland per SPEC-005.C4).

## Migration

F1 landed and cut over separately, so this feature carries its own small cutover: stamp `serves` edges (and any lineage attrs) onto the handful of active runs in the canonical world at landing, using the same rehearse-on-a-copy-then-live ceremony as F1 (script under `scripts/cutover/`, rehearsal against a copied canonical SQLite in a disposable world, restart is a hard stop requiring explicit user sign-off, per the ceremony).

## Related

- `xwhe7` (refinement lane): checked at proposal time — unaffected (`PROP-Aep-001.C14`). It is a gate-delivery-policy question, stays a separate card against `kaans`, and must not be entangled with this feature's landing.
- F3 (`7azzl`), F4 (`41pna`), F5 (`2mp13`) follow in the epic.
