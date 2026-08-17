# Task 4: delegation serving rewrite (delegation.clj)

**Document ID:** `TASK-Aep-004` **Slice:** `PLAN-Aep-001.S4` **Harness:** build **Type:** AFK **Depends on:** TASK-Aep-002 (needs the `serves` edge; disjoint file from Task 3, may run alongside it)

## TASK-Aep-004.P1 Scope

Type: AFK

Move delegation's serving semantics onto the `serves` edge and delete the boolean (`PROP-Aep-001.C2`, `C1`). First of two strictly sequential slices in `delegation.clj`; Task 5 (retry) follows in the same file and must not start before this task's commit lands.

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj`

## TASK-Aep-004.P2 Must implement exactly

- **TASK-Aep-004.MI1:** Delete `serving-run?` (`delegation.clj:518`), `non-serving-attrs` (`delegation.clj:526`), and the `agent-run/serves` merge at spawn (`:654`), review (`:1406`), and panel/council (`:1527`) (`PROP-Aep-001.C2` table rows 1–2).
- **TASK-Aep-004.MI2:** `delegate-task` (`delegation.clj:581`) passes `:serves` to `spawn-run!` in both headless and `--interactive` modes, so interactive delegated runs carry the same edge (`PROP-Aep-001.C1` "Who writes it").
- **TASK-Aep-004.MI3:** Raw `spawn` (`delegation.clj:646`), `review`/`review-synthesis` (`delegation.clj:1406`), and panel/council seats (`delegation.clj:1527`) pass `:parent`/`:spawned-by` only, never `:serves` — helpers by construction (`PROP-Aep-001.C1` "Who does not").
- **TASK-Aep-004.MI4:** Rewrite `serving-runs` (`delegation.clj:528`): runs reached by incoming `serves` edges to the task (`graph/incoming-edges rt [task] "serves"`), minus superseded (has an incoming `supersedes` edge / `superseded` phase). Point the delegation guards at the `serves` count instead of filtering `parent-of` children by the boolean (`PROP-Aep-001.C2` table row 3, `C1` "Who reads it").
- **TASK-Aep-004.MI5:** Drop `"agent-run/serves"` from `preserved-run-attr-keys` (`delegation.clj:1734`) — a helper retry re-spawns with no `:serves`, staying a helper by construction (`PROP-Aep-001.C2` table row 4).
- **TASK-Aep-004.MI6:** Rewrite the `about` prose (`delegation.clj:156,256,267-278,339,368`) to the `serves`-edge model; keep it in the `|`-margin block format. `task-runs`/`children-ids`/ `subtree`/`tree-node` (`delegation.clj:485,488,508,1825`) stay on `parent-of` — structural rendering, unchanged (`PROP-Aep-001.C3` table row 6).
- **TASK-Aep-004.MI7:** Rewrite the arg-spec `:doc` help strings at `delegation.clj:1865,1874` — `spawn --for` ("Served strand id.") becomes structural helper placement, and `ps --for` ("Only runs serving this strand id.") becomes serving runs plus structural helpers. Both are false after F2; `strand help agent` must describe the shipped semantics (`PROP-Aep-001.C10`).

## TASK-Aep-004.P3 Done when

- **TASK-Aep-004.DW1:** No live reader of `agent-run/serves` remains in `delegation.clj`; `serving-runs` and the guards resolve serving from `serves` edges minus superseded; helpers carry no `serves` edge; structural traversals untouched; `./bin/strand help agent` (after `make build`) shows the corrected `--for` docs.
- **TASK-Aep-004.DW2:** Cold focused run `clojure -M:test skein.delegation-test` green.
- **TASK-Aep-004.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Aep-004.P4 Out of scope

- **TASK-Aep-004.OS1:** `op-retry` (Task 5, same file, after this commit).
- **TASK-Aep-004.OS2:** README/cookbook prose (Task 8).

## TASK-Aep-004.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-004.P6 References

- **TASK-Aep-004.REF1:** `PLAN-Aep-001.S4`, `PLAN-Aep-001.A3` (same-file serialization).
- **TASK-Aep-004.REF2:** `PROP-Aep-001.C1` (writers/non-writers), `C2` (exact reader table).
