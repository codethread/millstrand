# Task 1: serves relation declaration (catalog + core acyclic set)

**Document ID:** `TASK-Aep-001` **Slice:** `PLAN-Aep-001.S1` **Harness:** build **Type:** AFK **Depends on:** none (lands first)

## TASK-Aep-001.P1 Scope

Type: AFK

Declare the `serves` relation before any code writes a `serves` edge, so edges are cycle-checked from the first write (`PROP-Aep-001.C1` "declared acyclic", `PROP-Aep-001.R3`, `PLAN-Aep-001.A2`).

**Owned files (disjoint):**

- `src/skein/api/relations/alpha.clj`
- `src/skein/core/db.clj`
- `test/skein/relations_test.clj`

## TASK-Aep-001.P2 Must implement exactly

- **TASK-Aep-001.MI1:** Add a `serves` operational entry to the advisory catalog in `src/skein/api/relations/alpha.clj`: `:family :operational`, `:direction "run --serves--> served-target"`, `:declared-acyclic? true`, help text describing it as the engine-owned "this run is a delegation of that strand's own work" edge (`PROP-Aep-001.C11` bullet 2).
- **TASK-Aep-001.MI2:** Add `"serves"` to `shipped-acyclic-relations` in `src/skein/core/db.clj` (near line 217) so `bootstrap-acyclic-relation!` declares it at storage init.
- **TASK-Aep-001.MI3:** Update the `relations_test` catalog-set assertion and any `list-acyclic-relations` db assertion to include `serves`.

## TASK-Aep-001.P3 Done when

- **TASK-Aep-001.DW1:** `serves` appears in `operational-relations`/`catalog` with `declared-acyclic? true`; `bootstrap-acyclic-relation!` declares it at storage init.
- **TASK-Aep-001.DW2:** Cold focused run `clojure -M:test skein.relations-test` green. The core acyclic-set change is additionally covered by the full suite's db assertions at Task 12 — do not run the full suite here.
- **TASK-Aep-001.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Aep-001.P4 Out of scope

- **TASK-Aep-001.OS1:** Any `serves`-edge write or read (Tasks 2–6).
- **TASK-Aep-001.OS2:** The strand-model root-spec edit (Task 10, `SPEC-Aep-001`).

## TASK-Aep-001.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-001.P6 References

- **TASK-Aep-001.REF1:** `PLAN-Aep-001.S1`, `PLAN-Aep-001.A2`.
- **TASK-Aep-001.REF2:** `PROP-Aep-001.C1` (catalog/acyclicity bullet), `C11` bullet 2, `R3`.
