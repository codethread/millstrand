# Task 1: skein.api.vocab.alpha registry + core seed (foundation)

**Document ID:** `TASK-Vr-001` **Slice:** `PLAN-Vr-001.S1` **Harness:** build **Type:** AFK **Depends on:** none (lands first)

## TASK-Vr-001.P1 Scope

Type: AFK

Create the runtime-owned vocabulary registry so every seed and consumer slice can declare into it and read from it (`PROP-Vr-001.C2`–`C5`, `PLAN-Vr-001.A2`). This is the serial foundation — it lands first and blocks every other code slice (`PLAN-Vr-001.S1`, `TC2`). The `new-state` init-fn carries the core seed (reflected edges + core-owned `note/*`); no seed lives in an `install!` hook here (`install!` is a spool's startup activation hook, run once when the runtime loads or reloads that spool).

**Owned files (disjoint):**

- `src/skein/api/vocab/alpha.clj` (new)
- `test/skein/vocab_test.clj` (new)
- `test/skein/test_runner.clj` (register `skein.vocab-test` in `parallel-namespaces`)

## TASK-Vr-001.P2 Must implement exactly

Per `PROP-Vr-001.C1`–`C5`:

- **TASK-Vr-001.MI1:** Define the C1 declaration shape (`PROP-Vr-001.C1`): a map of `:kind` (`:attr-namespace` | `:edge`), `:name` (namespace segment or edge-type name), `:owner` (declaring module — a spool `init.clj` use-key, or `:skein/core` / the owning `skein.api.*.alpha` ns), `:keys` (known keys, `:attr-namespace` only, advisory), and `:doc` (one-line note). Edge declarations additionally carry `:family`/`:direction`/`:declared-acyclic?` reflected from the catalog.
- **TASK-Vr-001.MI2:** `(vocab/declare! runtime declaration)` — validate the C1 shape (fail loud on unknown/missing keys via `skein.spools.util/reject-unknown-keys!`/`fail!` — the `skein.spools.util` validators that throw on an unexpected or absent key — the same validate-then-record selvage pattern selvage uses to parse its check specs), record under `[:kind :name]`; throw `ex-info` with `:name`/`:kind`/ `:existing-owner`/`:declaring-owner` on a _different_ owner; idempotent replace (no throw) for the _same_ owner (`PROP-Vr-001.C3`, the reload invariant `R1`).
- **TASK-Vr-001.MI3:** Read surface (`PROP-Vr-001.C4`), all runtime-first: `(vocab/declarations runtime)` (all, sorted by `[:kind :name]`, full C1 maps), `(vocab/declarations runtime {:kind …})` (narrowed); callers derive singular reads by filtering the returned declarations. No ambient-singleton path — never read the published runtime singleton; every read takes `runtime` explicitly (the blessed-namespace convention).
- **TASK-Vr-001.MI4:** Back the store with `runtime/spool-state` (`skein.api.runtime.alpha/spool-state` — runtime-owned per-spool state that survives reload), versioned per the shape-drift discipline: a `state-version` beside `new-state` (selvage's `new-state`/`state-version` precedent, `PROP-Vr-001.C2`).
- **TASK-Vr-001.MI5:** The `new-state` init-fn is the seed site (`PROP-Vr-001.C5`): it returns the initial registry already carrying the core seed — one `:edge` declaration per `relations.alpha/catalog` entry (owner `:skein/core`, preserving `:family`/`:direction`/`:declared-acyclic?`) plus the core-owned `note/*` `:attr-namespace` (owner `skein.api.notes.alpha`), each a valid C1 map. Reflect the catalog; do not re-list the edge set in `vocab.alpha` source (`PROP-Vr-001.NG3`, `Q3`). No `install!` hook.
- **TASK-Vr-001.MI6:** Register `skein.vocab-test` in `parallel-namespaces` (in `test_runner.clj`, beside `skein.notes-test`) so the whole queue's focused gates can name it (`PLAN-Vr-001.TC4`).
- **TASK-Vr-001.MI7:** Every `ns` gets a docstring describing its purpose (repo rule).

## TASK-Vr-001.P3 Done when

- **TASK-Vr-001.DW1:** On a fresh runtime with no `install!` run, `(vocab/declarations runtime)` already returns the core seed — the reflected `relations.alpha/catalog` edges as owned `:edge` maps plus the core-owned `note/*` `:attr-namespace` (owner `skein.api.notes.alpha`) — because the seed lives in the `new-state` init-fn, not an `install!` hook (`PROP-Vr-001.C5`, `DW2`).
- **TASK-Vr-001.DW2:** `declare!` records a C1 declaration, throws `ex-info` cross-owner (`:existing-owner`/`:declaring-owner`), and is an idempotent replace same-owner; `declarations`/ `declaration` read runtime-first, sorted, `{:kind …}`-narrowable, `nil` for undeclared (`PROP-Vr-001.C3`, `C4`).
- **TASK-Vr-001.DW3:** Cold focused run `clojure -M:test skein.vocab-test` green (focused-runnable, `PLAN-Vr-001.TC4`). Tests cover: fresh-runtime core seed present before any `install!`; declare/query round-trip; cross-owner throw; same-owner idempotent replace (`PROP-Vr-001.R1`, the reload invariant); the `(assert-state-shape #'vocab/new-state #{…})` drift test (`assert-state-shape` in `skein.spools.selvage-test` precedent, `PROP-Vr-001.R4`).
- **TASK-Vr-001.DW4:** `make fmt-check lint reflect-check` pass. The edge set is not duplicated in source (reflected from `relations.alpha`).

## TASK-Vr-001.P4 Out of scope

- **TASK-Vr-001.OS1:** Any spool `install!` `declare!` call (Tasks 2–7 own their spool files); the `strand vocab` op (Task 8); the selvage/carder consumers (Tasks 9/10).
- **TASK-Vr-001.OS2:** The `SPEC-005.C2` alpha-surface enumeration and `strand-model.md` referent (Task 14 owns the root-spec edits, `PROP-Vr-001.C11`).
- **TASK-Vr-001.OS3:** `devflow/*` — out of scope for the whole feature (F5, card `2mp13`, `PROP-Vr-001.C5`, `Q1`). No core row for it.

## TASK-Vr-001.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-001.P6 References

- **TASK-Vr-001.REF1:** `PLAN-Vr-001.S1`, `PLAN-Vr-001.A2`, `PLAN-Vr-001.A6`, `PLAN-Vr-001.AA1`, `PLAN-Vr-001.AA2`, `PLAN-Vr-001.V2`, `PLAN-Vr-001.V5`.
- **TASK-Vr-001.REF2:** `PROP-Vr-001.C1` (shape), `C2` (home + versioned state), `C3` (declare! + hard edge + idempotency), `C4` (reads), `C5` (seed); `R1`, `R4`.
