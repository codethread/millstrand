# Task 2: engine serves edge + read-side rewrite (agent_run.clj)

**Document ID:** `TASK-Aep-002` **Slice:** `PLAN-Aep-001.S2` **Harness:** build **Type:** AFK **Depends on:** TASK-Aep-001

## TASK-Aep-002.P1 Scope

Type: AFK

Give the agent-run engine the `serves` write and move its own serving reads onto the edge (`PROP-Aep-001.C1`, `C3`). This is the first of two strictly sequential slices in `agent_run.clj`; Task 3 (lineage) follows in the same file and must not start before this task's commit lands (`PLAN-Aep-001.A3`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj`

## TASK-Aep-002.P2 Must implement exactly

- **TASK-Aep-002.MI1:** Add a `:serves <target-id>` option to `spawn-run!` that writes exactly one `serves` edge (run → target) alongside the existing `parent-of` placement writes (`agent_run.clj:1593,1621`). Placement is unchanged: a serving run carries both `parent-of` (placement) and `serves` (semantics); a helper carries `parent-of` only (`PROP-Aep-001.C1` "Placement vs. serving").
- **TASK-Aep-002.MI2:** Rewrite `run-for-target` (`agent_run.clj:1644`) to read the run's outgoing `serves`-edge target (one indexed outgoing-edge read), else nil. Delete the `parent-of`-minus-`spawned-by` heuristic and fold in `gate/step` (the gate id is now the `serves` target — `PROP-Aep-001.C3` table row 2).
- **TASK-Aep-002.MI3:** Rewrite `run-summary` `:for` (`agent_run.clj:1674`): serving runs resolve `:for` from the `serves` edge; helpers fall back to their structural `parent-of` parent — unchanged UX, `spawn --for X` still shows the helper "for X" (`PROP-Aep-001.C3` table row 3).
- **TASK-Aep-002.MI4:** Rewrite the `runs*` `--for` filter (`agent_run.clj:1706-1718`) to the union of `parent-of` children (structural, helpers) and incoming `serves` sources (serving runs); remove the `gate/step` clause (subsumed by `serves`) (`PROP-Aep-001.C3` table row 4).
- **TASK-Aep-002.MI5:** Clarify the docstrings/prose at `agent_run.clj:28,1564-1570`: headless serving is the `serves` edge; interactive `agent-run/for` stays as the interactive completion target and is NOT folded into `serves` (`PROP-Aep-001.Q1` resolution, `C3` table row 5).

## TASK-Aep-002.P3 Done when

- **TASK-Aep-002.DW1:** `spawn-run! :serves` writes exactly one `serves` edge; `run-for-target`/`run-summary`/`runs*` read serving from `serves` with no `parent-of`-serving inference and no `gate/step` link; `agent-run/for` interactive completion unchanged.
- **TASK-Aep-002.DW2:** Cold focused run `clojure -M:test skein.delegation-test skein.executors.subagent-test skein.spools.loom-test` green (these exercise the engine's `serves` reads). The authoritative `skein.agent-run-test` is an add-libs shard that runs only in the full locked suite at Task 12 — do not run it here (`PLAN-Aep-001.A5`).
- **TASK-Aep-002.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 12.

## TASK-Aep-002.P4 Out of scope

- **TASK-Aep-002.OS1:** `supersede-and-respawn!`/lineage (Task 3, same file, after this commit).
- **TASK-Aep-002.OS2:** Callers passing `:serves` (delegation Task 4, subagent Task 6).
- **TASK-Aep-002.OS3:** Doc/cookbook prose (Task 7).

## TASK-Aep-002.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-002.P6 References

- **TASK-Aep-002.REF1:** `PLAN-Aep-001.S2`, `PLAN-Aep-001.A1/A3/A5` (context-window sizing, same-file serialization, focused gates).
- **TASK-Aep-002.REF2:** `PROP-Aep-001.C1` (who writes/reads), `C3` (exact reader table), `Q1` (resolved: `agent-run/for` coexists).
