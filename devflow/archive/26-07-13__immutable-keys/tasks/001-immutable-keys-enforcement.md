# Task 1: immutable_keys registry, write-once guard at all mutation helpers, db tests

**Document ID:** `TASK-Immut-001`

## TASK-Immut-001.P1 Scope

Type: AFK

Storage enforcement for write-once attribute keys in `skein.core.db`, per [PLAN-Immut-001](../immutable-keys.plan.md) A1–A4 and [DELTA-Immut-001](../specs/strand-model.delta.md).

## TASK-Immut-001.P2 Must implement exactly

- **TASK-Immut-001.MI1:** `immutable_keys` table in `schema-sql` (`CREATE TABLE IF NOT EXISTS immutable_keys (key TEXT PRIMARY KEY)`), and init-time seeding of `note/text` and `note/at` alongside the shipped acyclic relations (follow the `bootstrap-acyclic-relation!` idiom, including the tolerate-already-present behavior). A private `immutable-key?` membership check mirroring `acyclic-relation?`.
- **TASK-Immut-001.MI2:** A private guard fn enforcing write-once per DELTA-Immut-001.P1, reading the existing row inside the same transaction, called from:
    - `write-attribute-rows!` — for each registered key being written where a row exists: allow when the decoded existing value equals the decoded new value; reject otherwise. (This covers direct writes, patch value-sets, and the batch update path, which reuse it.)
    - `replace-attribute-rows!` — BEFORE its delete-all: reject when an existing registered-key row would be dropped (key absent from the new map) or changed; identical carried-through values are legal.
    - `patch-attribute-rows!` — a `nil` patch entry for a registered key with an existing row rejects (deletion). Idempotence for non-nil entries compares the existing value to the POST-MERGE result for that key.
    - `archive-attributes-in-transaction!` — reject archiving (`archived?` true) of a registered key that has a row; unarchive (`archived?` false) of a registered key stays legal.
- **TASK-Immut-001.MI3:** Rejections throw `ex-info` with message stating the key is write-once and ex-data `{:key <key-string> :strand-id <id> :existing <decoded-value> :attempted <decoded-value-or-nil>}` (`:attempted nil` for deletion/archive attempts). TEN-003: no silent skips, no partial writes — the transaction aborts.
- **TASK-Immut-001.MI4:** First writes stay legal everywhere: creating a strand with `note/text`/`note/at` (the note birth write), gaining a registered key on an existing strand, and unarchiving. Existing note creation via `skein.api.notes.alpha` must work unchanged.

## TASK-Immut-001.P3 Done when

- **TASK-Immut-001.DW1:** Cold focused run green: `clojure -M:test skein.core.db-test skein.notes-test`.
- **TASK-Immut-001.DW2:** Tests cover, for a registered key: rewrite via update-strand!/patch rejects; identical-value rewrite passes; nil-patch deletion rejects; archive rejects; unarchive passes; batch update path rejects a change and allows identical carry-through; create/birth write passes; gaining the key on an existing strand passes; a NON-registered key still mutates freely on every path; ex-data shape asserted on at least one rejection.
- **TASK-Immut-001.DW3:** `make fmt-check lint reflect-check` clean; new fns have docstrings.
- **TASK-Immut-001.DW4:** Atomic commit on branch `iv22r-immutable-keys` (message: why, per repo git rules); stop at implemented+committed.

## TASK-Immut-001.P4 Out of scope

- Spec promotion / docs (task 2). Userland registration surface, whole-strand seals, edge immutability. Any api.alpha/CLI/Go change. Retroactive detection of pre-upgrade violations.

## TASK-Immut-001.P5 References

- `src/skein/core/db.clj` — `schema-sql`, `init!` (bootstrap seeding site), `acyclic-relation?`/`bootstrap-acyclic-relation!` idiom (~547–589), `write-attribute-rows!`/`replace-attribute-rows!`/ `patch-attribute-rows!` (~377–417), `archive-attributes-in-transaction!` (~825), `<-json`/`->json`.
- `src/skein/api/notes/alpha.clj` — birth write (~86).
- `devflow/specs/strand-model.md` P4/P8/P10; the delta and plan.
- `test/skein/core/db_test.clj`, `test/skein/notes_test.clj`.
