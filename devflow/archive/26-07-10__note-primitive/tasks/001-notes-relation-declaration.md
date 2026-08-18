# Task 1: notes relation declaration (catalog + core acyclic set)

**Document ID:** `TASK-Np-001`

**Slice:** `PLAN-Np-001.S1` **Harness:** build **Type:** AFK

**Depends on:** none (lands first)

## TASK-Np-001.P1 Scope

Type: AFK

Declare the `notes` relation before any code walks or writes it as a declared relation, so `notes` edges are cycle-checked from storage init and the primitive (Task 2) can rely on the declaration (`PROP-Np-001.C1`, `R4`, `PLAN-Np-001.A2`).

**Owned files (disjoint):**

- `src/skein/api/relations/alpha.clj`
- `src/skein/core/db.clj`
- `test/skein/relations_test.clj`

## TASK-Np-001.P2 Must implement exactly

- **TASK-Np-001.MI1:** Add a `notes` operational entry to the advisory catalog `catalog` in `src/skein/api/relations/alpha.clj`, beside `serves` (`alpha.clj:26`): `:relation "notes"`, `:family :operational`, `:direction "note --notes--> target"`, `:declared-acyclic? true`, and help text describing it as "Append-only memory: a closed note strand attached to its target." (`PROP-Np-001.C2` — the catalog is documentation-only, not a storage allowlist).
- **TASK-Np-001.MI2:** Add `"notes"` to `shipped-acyclic-relations` in `src/skein/core/db.clj` (near `db.clj:217`, currently `#{"depends-on" "parent-of" "supersedes" "serves"}`) so `bootstrap-acyclic-relation!` declares `notes` in `acyclic_relations` at storage init (`db.clj:267`; `PROP-Np-001.C1` "Acyclicity", `R4`).
- **TASK-Np-001.MI3:** Update the `relations_test` catalog-set assertion (`relations_test.clj:9`, the operational set currently `#{"depends-on" "parent-of" "supersedes" "serves"}`) and any `list-acyclic-relations` db assertion to include `notes`.

## TASK-Np-001.P3 Done when

- **TASK-Np-001.DW1:** `notes` appears in `operational-relations`/`catalog` with `declared-acyclic? true`; `bootstrap-acyclic-relation!` declares it in `acyclic_relations` at storage init.
- **TASK-Np-001.DW2:** Cold focused run `clojure -M:test skein.relations-test skein.core.db-test` green (both focused-runnable per `PLAN-Np-001.TC4`). The core acyclic-set change is additionally covered by the full suite's db assertions at Task 12 — do not run the full suite here.
- **TASK-Np-001.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Np-001.P4 Out of scope

- **TASK-Np-001.OS1:** Any `note!`/`notes` primitive write or read (Task 2 owns `skein.api.notes.alpha`).
- **TASK-Np-001.OS2:** The strand-model / alpha-surface root-spec edits (Task 10, `SPEC-Np-001` records why the catalog code is not a doc edit).

## TASK-Np-001.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Np-001.P6 References

- **TASK-Np-001.REF1:** `PLAN-Np-001.S1`, `PLAN-Np-001.A2`, `PLAN-Np-001.AA1`.
- **TASK-Np-001.REF2:** `PROP-Np-001.C1` (semantics + acyclicity), `C2` (catalog entry), `R4`.
