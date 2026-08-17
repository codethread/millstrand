# Treadle: shuttle-backed fulfillment of workflow subagent gates

**Status:** Implemented (2026-07-02) — shipped as `skein.spools.treadle`, contract in [`spools/shuttle/treadle.md`](../../../spools/shuttle/treadle.md) **Related:** [RFC-010](../../rfcs/2026-07-02-shuttle-backed-coordination.md) (REC8, Q2, Q7), [Workflow spool](../../../src/skein/spools/workflow.md) (§3 Gates, §7 vocabulary), [Shuttle spool](../../../spools/shuttle/src/skein/spools/shuttle.clj)

## Summary

`skein.spools.treadle` is a small event-driven adapter that fulfills workflow **gate** steps whose waiter is `subagent` by spawning shuttle runs and, on success, completing the gate through the ordinary workflow surface. It is the RFC-010.REC8 "workflow gate bridge", built so that:

- `skein.spools.workflow` needs **zero changes** — the existing `gate` primitive plus caller-supplied attributes are the whole declarative interface (RFC-010.NG5: workflow must not depend on shuttle).
- `skein.spools.shuttle` needs **zero changes** — the treadle drives it through `spawn-run!` and reads ordinary run strands (RFC-010.C6: shuttle gains no workflow concepts).
- The treadle is the only place that knows both vocabularies. It lives beside shuttle (`spools/shuttle/src/skein/spools/treadle.clj`) because it cannot function without it; workflow's vocabulary is the stable contract and the binding lives with the tool, mirroring workflow.md §3 tool bindings.

Name: the treadle is the loom pedal that opens the shed so the shuttle can pass — it is what makes the loom advance.

## Declarative contract (what a workflow author writes)

```clojure
(workflow/gate :implement "Implement the widget" :subagent
               :depends-on [:design]
               :attributes {"shuttle/harness" "pi-main"
                            "shuttle/prompt" "Implement the widget per specs/widget.md ..."
                            "shuttle/cwd" "/path/to/worktree"})
```

Request attributes read from the gate step (all plain data, TEN-001):

| Attribute | Required | Meaning |
| --- | --- | --- |
| `workflow/gate` = `"subagent"` | yes | Marks the gate treadle-fulfilled. Any other waiter is ignored by the treadle. |
| `shuttle/harness` | yes | Harness or alias name passed to `spawn-run!`. Missing → durable `treadle/error` on the gate (see failure semantics). |
| `shuttle/prompt` | no | Run prompt. When absent the treadle composes one from the step's `workflow/instruction`, `description` attribute, and title (in that order of preference, falling back through them); if nothing non-blank is derivable → `treadle/error`. |
| `shuttle/cwd` | no | Passed through to `spawn-run!` `:cwd`. |
| `shuttle/max-attempts` | no | Passed through to `spawn-run!` `:max-attempts`. |

## Engine behavior

`install!` registers one weaver event handler `:treadle/engine` on the same event types shuttle listens to (`:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded`), then runs one initial `scan!`. It **fails loudly** if the shuttle is not installed (no `:shuttle/engine` in `api/event-handlers`) — the treadle without a shuttle would create runs nothing spawns. No new op, no CLI surface (TEN-004, RFC-010.G8): inspection is `strand show`, `strand op agent ps`, and the workflow surface.

Every event triggers `scan!`, which does two phases. Handlers run serially on the weaver event-worker thread, but `install!`'s initial scan runs on the installing thread, so `scan!` body is wrapped in `locking` on a private monitor.

Both phases guard **per item** with try/catch and record failures as durable attributes instead of throwing the scan away (mirroring shuttle's per-run `mark-failed!` style). For an asynchronous engine the durable attribute _is_ the loud failure channel (TEN-003); unexpected errors additionally surface in `api/recent-event-failures` if they escape.

### Phase 1 — deliver finished runs

Query: closed strands with `[:exists [:attr "treadle/gate"]]` and `[:missing [:attr "treadle/delivered"]]`.

For each such run:

- If the gate strand is still active and is currently a ready step of its workflow run: call `workflow/complete!` with the run-id recorded on the run (`treadle/run-id`), opts `{:step <gate-id> :by <run strand id> :notes <shuttle/result>}` (omit `:notes` when the result is blank). This satisfies the gate's mandatory `:by` and records the full provenance chain: `workflow/outcome-by` → run strand → `shuttle/harness`, `shuttle/session-id`, `shuttle/log`. Then stamp the run `treadle/delivered "true"`.
- If the gate is already closed (its stage was routed away mid-flight — workflow.md §5 hard cutover): stamp `treadle/delivered "gate-closed"` and do nothing else. The result stays durable on the run strand.
- If the gate is active but not currently a ready step (e.g. userland added a dependency after spawn): stamp the run `treadle/delivery-blocked` (write-once so the stamp's own event cannot loop), leaving it undelivered so delivery retries when the gate becomes ready again.
- On any unexpected exception: stamp `treadle/delivered` with `"error: <detail>"`.

### Phase 2 — spawn ready gates

Enumerate via the **workflow surface**, not raw core readiness: for each `workflow/active-runs` root, take its `workflow/run-id` and filter `workflow/next-steps` views for `:gate "subagent"`. (Core `api/ready` would wrongly surface gates inside bond-blocked runs; `next-steps` owns those semantics.)

For each ready subagent gate, reading the full gate strand for attributes:

- Skip if the gate carries `treadle/run` or `treadle/error`.
- Crash idempotency: if a run already exists with `treadle/gate` = this gate id (query), just stamp the gate with that run's id and continue — covers a crash between spawn and stamp.
- Otherwise `shuttle/spawn-run!` with:
    - `:harness`, `:cwd`, `:max-attempts` from the request attributes;
    - `:prompt` = a short treadle preamble + the derived prompt. The preamble states: this run fulfills workflow gate `<gate-id>` (title) in run `<run-id>`; the final message is captured as the gate's completion record; do not close or mutate workflow strands yourself — the treadle closes the gate. (Shuttle's own run preamble already covers strand CLI mechanics.)
    - `:title` `"Delegated: <gate title>"`;
    - `:attrs` `{"treadle/gate" <gate-id> "treadle/run-id" <workflow run-id>}`.
- Stamp the gate `treadle/run` = run id and add a `delegates` **annotation edge** gate → run, in one `api/update`. This deviates from RFC-010.REC2's parent-of suggestion deliberately: a parent-of edge would place the run inside the workflow root's subgraph, where `workflow/next-steps` surfaces it as ready work (it carries no `workflow/role`) — discovered when the original parent-of implementation made gate-id selection in tests nondeterministic. REC2's parent-of shape remains right for plain task strands; workflow molecule internals need the annotation edge instead.
- Validation failure (missing/blank harness, underivable prompt, unknown harness, spawn exception): stamp the gate `treadle/error` with the detail. Error-stamped gates are skipped until a coordinator clears the attribute.

### Failure and recovery semantics

- A **failed run** (`shuttle/phase "failed"`, still active) never closes the gate: the gate stays ready and stamped, visibly blocked on its `treadle/run`. Recovery is coordinator-owned (RFC-010.REC7, C8): either reset the run's `shuttle/phase` to `"pending"` (shuttle respawns it; on success the treadle still delivers), or clear the gate's `treadle/run` stamp to get a fresh run.
- Weaver crash mid-run: shuttle's own `reconcile!` respawns the run; the treadle needs no recovery logic beyond the spawn-time idempotency check.

## Attribute vocabulary (`treadle/*`)

| Attribute | On | Meaning |
| --- | --- | --- |
| `treadle/run` | gate step | Id of the delegated shuttle run (spawn stamp / idempotency guard). |
| `treadle/error` | gate step | Durable spawn-side failure detail; gate is skipped until cleared. |
| `treadle/gate` | run strand | Id of the gate step this run fulfills. |
| `treadle/run-id` | run strand | The workflow `run-id` owning the gate (needed for `complete!`). |
| `treadle/delivered` | run strand | `"true"`, `"gate-closed"`, or `"error: …"` — delivery outcome; presence excludes the run from Phase 1. |
| `treadle/delivery-blocked` | run strand | Write-once, non-terminal signal that the gate was active but unready at delivery time; the run stays in Phase 1's query and delivers when the gate is ready again. |

On the closed gate the ordinary workflow vocabulary carries the outcome: `workflow/outcome-by` = run strand id, `workflow/notes` = the run's `shuttle/result`.

## Deliverables

1. `spools/shuttle/src/skein/spools/treadle.clj` — ns docstring required (repo rule); composes only public surfaces (`skein.spools.workflow`, `skein.spools.shuttle`, `skein.api.weaver.alpha`, `skein.repl`).
2. `test/skein/treadle_test.clj` — modeled on `test/skein/shuttle_test.clj` (temp workspace fixture installing shuttle then treadle; `sh` harness), at minimum:
    - happy path: step → subagent gate → step; completing the first step triggers delegation; the run's result closes the gate (`workflow/outcome-by` = run id, `workflow/notes` = result) and the following step becomes ready; the run strand carries `treadle/gate`, `treadle/run-id`, `treadle/delivered "true"`, and a parent-of edge from the gate;
    - a blocked gate does not spawn; it spawns after its blocker closes;
    - missing `shuttle/harness` → `treadle/error` stamped, no run created, and a later unrelated event does not retry or spam duplicate runs;
    - failed run (`sh` exiting non-zero): gate stays ready and stamped; clearing `treadle/run` yields a fresh successful run that closes the gate;
    - routed-away gate: close the gate's stage while a run is in flight (e.g. slow `sh` prompt); on run completion the run is stamped `treadle/delivered "gate-closed"` and nothing throws;
    - non-subagent gates (e.g. waiter `ci`) are never touched.
3. `spools/shuttle/treadle.md` — contract doc in the style of the other spool docs (overview, contract tables above, worked example, see-also).
4. Index/cross-ref updates: a row in `src/skein/spools/README.md` "Approved local-root examples", a one-line pointer in `spools/shuttle/README.md`, and a brief mention in `src/skein/spools/workflow.md` §3 "Gates" and §9 see-also that a shipped adapter exists for `:subagent` gates.

Out of scope: devflow stage changes (a later feature may pour AFK task steps as subagent gates), retry policy beyond the manual paths above, any new CLI command, weaver-side action registry.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

All existing tests must stay green; `git status --short` must show no generated SQLite or runtime metadata artifacts.
