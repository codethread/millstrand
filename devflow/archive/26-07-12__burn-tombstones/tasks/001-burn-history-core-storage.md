# Task 1: burn_history storage, capture in deletion tx, core read fns, db tests

**Document ID:** `TASK-Tomb-001`

## TASK-Tomb-001.P1 Scope

Type: AFK

Core storage for burn tombstones in `skein.core.db`, per [PLAN-Tomb-001](../burn-tombstones.plan.md) A1/A2 and [DELTA-Tomb-001](../specs/strand-model.delta.md).

## TASK-Tomb-001.P2 Must implement exactly

- **TASK-Tomb-001.MI1:** Add a `burn_history` table to the `schema-sql` vector in `src/skein/core/db.clj` (`CREATE TABLE IF NOT EXISTS`, following the `scheduler_history` style): integer autoincrement pk, burned strand id, strand core fields (title, state, created_at, updated_at), attributes JSON (full map including archived rows, with archived keys distinguishable), incident edges JSON (each edge: from, to, type, attributes — decoded via the existing JSON helpers, not embedded as raw text), and a recorded_at timestamp defaulting to `datetime('now')`. JSON columns get `CHECK (json_valid(...))`. Index on the burned strand id.
- **TASK-Tomb-001.MI2:** Inside `delete-strands!` (the single deletion choke point used by `burn-by-ids!` and the batch burn path), before the deletes and within the caller's open transaction, capture each existing strand's row, full attribute map, and incident edges, and insert one `burn_history` row per burned strand. Ids that do not exist at capture time inside the tx are skipped (no tombstone, no error) — validation stays the callers' job. Attribute and edge JSON shapes must map onto the batch-mutation payload's `:strands` entry fields and `:edges` upsert entries (see SPEC-001.P6) so recovery assembly is mechanical.
- **TASK-Tomb-001.MI3:** Public read fns in `skein.core.db`: `burn-history-for-strand` (all tombstones for a burned strand id, newest first) and `recent-burn-history` (latest N tombstones, newest first, N required — no unbounded default). Both return decoded keyword-keyed maps (attributes/edges decoded from JSON), each with a docstring.
- **TASK-Tomb-001.MI4:** A failed tombstone insert propagates and aborts the burn transaction (TEN-003) — no rescue, no partial burn.

## TASK-Tomb-001.P3 Done when

- **TASK-Tomb-001.DW1:** Cold focused run green: `clojure -M:test skein.core.db-test` (plus any other namespace touched).
- **TASK-Tomb-001.DW2:** Tests cover: single-op `burn-by-id!`/`burn-by-ids!` writes one tombstone per strand; batch `:burn` path writes tombstones; tombstone captures archived attributes distinguishably and incident edges (both directions) with their attributes; burning a strand with no edges/attrs produces a well-formed tombstone; read fns return decoded shapes newest-first; the deletes themselves still behave as before (existing burn tests stay green).
- **TASK-Tomb-001.DW3:** `make fmt-check lint reflect-check` clean on the touched files; every new fn has a docstring.
- **TASK-Tomb-001.DW4:** Work committed on branch `5ys8r-burn-tombstones` (atomic commit, message explains why per repo git rules); worker stops at implemented+committed — no landing.

## TASK-Tomb-001.P4 Out of scope

- REPL wrapper, spec promotion, docs (task 2).
- Any `skein.api.*.alpha`, CLI, or Go surface.
- Retention/GC, undo/restore ops, changes to burn validation semantics.

## TASK-Tomb-001.P5 References

- `src/skein/core/db.clj` — `schema-sql`, `delete-strands!`, `burn-by-ids!`, batch mutation (`apply-batch-in-transaction!`), `scheduler_history` schema entry as the style precedent, `->json`/`<-json`.
- `devflow/specs/strand-model.md` SPEC-001.P6 (batch payload shapes), P8.
- `test/skein/core/db_test.clj` — existing burn and batch coverage to extend.
