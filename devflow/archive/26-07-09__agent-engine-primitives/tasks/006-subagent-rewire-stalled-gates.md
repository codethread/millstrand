# Task 6: subagent executor rewire + stalled-gates + loom/flow-status

**Document ID:** `TASK-Aep-006` **Slice:** `PLAN-Aep-001.S6` **Harness:** build **Type:** AFK **Depends on:** TASK-Aep-002, TASK-Aep-003 (disjoint files from Tasks 4/5 — may run alongside them)

## TASK-Aep-006.P1 Scope

Type: AFK

The subagent executor stops maintaining its own run↔gate link and uses `serves` plus lineage instead; the `stalled-gates` query and `loom/flow-status` follow (`PROP-Aep-001.C8`–`C9`). A gate's current delegated run = the current run serving the gate.

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/executors/subagent.clj`
- `spools/src/skein/spools/loom.clj`

## TASK-Aep-006.P2 Must implement exactly

- **TASK-Aep-006.MI1:** `spawn-for-gate!` (`subagent.clj:193`) passes `:serves gate-id` and stops writing `gate/step` as a link (`:198`).
- **TASK-Aep-006.MI2:** Delete outright: `stamp-run-on-gate!` (`subagent.clj:156-176`) and `ensure-run-stamp!` (`:178`) with the `gate/run` attr + `delegates` edge machinery; the `gate/superseded-by` back-marker (`:172-174`) and its exclusion in the query (`:280`). A superseded run is identified by its incoming `supersedes` edge / `superseded` phase (`PROP-Aep-001.C8`).
- **TASK-Aep-006.MI3:** `spawn-idempotency-run-for-gate` (`subagent.clj:66`) stays (crash-between-spawn-and-edge idempotency) but selects on the incoming `serves` edge to the gate, not `gate/step`.
- **TASK-Aep-006.MI4:** `deliver-run!` (`subagent.clj:88,199`) reads the served target from the `serves` edge; `gate/run-id` (run → workflow-run pointer) stays. **The delivery condition stays byte-for-byte semantically identical: `agent-run/phase "done"` + non-blank result** (`PROP-Aep-001.C14`, `NG2`) — only the served-target read moves.
- **TASK-Aep-006.MI5:** Drop `"superseded"` from `stalled-run-phases` (`subagent.clj:22-29`); `gate-stalled?` (`:233`) reads the current serving run via the `serves` edge and reports stalled only on `failed`/`exhausted` (plus spawn-side `gate/error`).
- **TASK-Aep-006.MI6:** Rewrite the `stalled-gates` named query (`subagent.clj:273`) over `[:edge/in "serves" ...]` per the `PROP-Aep-001.C9` sketch (the `:edge/in` predicate is supported, `core/query.clj:268`); no `gate/superseded-by` bridge. The query and `gate-stalled?` must agree by construction on "the current serving run is dead."
- **TASK-Aep-006.MI7:** Rewrite `loom/flow-status` (`loom.clj:266-272`) to resolve each gate's current serving run via incoming `serves` sources (non-superseded) instead of `gate/run`; its `gate/error`-keyed `stalled-gates` sub-projection (`:267`) is unaffected.
- **TASK-Aep-006.MI8:** Untouched: `gate/error` (spawn-side failure), `gate/delivered`, `gate/delivery-blocked` — delivery bookkeeping, not the run↔gate link. `stalled-shell-gates` (`shell.md:105`) is the shell executor's separate query and is out of scope.

## TASK-Aep-006.P3 Done when

- **TASK-Aep-006.DW1:** No `gate/run`/`gate/superseded-by`/`gate/step`-as-link in live source; `stalled-gates` (query + `gate-stalled?` + `flow-status`) all resolve "the current serving run is dead" from `serves`+lineage; `agent retry <gate-run-id>` recovers the gate with no re-link step; delivery condition semantically identical to pre-F2.
- **TASK-Aep-006.DW2:** Cold focused run `clojure -M:test skein.executors.subagent-test skein.spools.loom-test` green.
- **TASK-Aep-006.DW3:** `make fmt-check lint reflect-check` pass.

## TASK-Aep-006.P4 Out of scope

- **TASK-Aep-006.OS1:** Any change to what counts as a deliverable run (`xwhe7`, `PROP-Aep-001.C14` — the two must not be entangled).
- **TASK-Aep-006.OS2:** subagent.md/cookbook prose (Task 9).

## TASK-Aep-006.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Aep-006.P6 References

- **TASK-Aep-006.REF1:** `PLAN-Aep-001.S6`, `PLAN-Aep-001.V3/V4`.
- **TASK-Aep-006.REF2:** `PROP-Aep-001.C8` (exact deletions), `C9` (query sketch + flow-status), `C14`/`NG2` (delivery predicate frozen).
