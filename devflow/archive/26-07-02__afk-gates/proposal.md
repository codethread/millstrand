# AFK task execution as treadle-fulfilled subagent gates

**Status:** Implemented (2026-07-02) — shipped in `skein.spools.devflow`, contract in [devflow.md](../../../src/skein/spools/devflow.md) §4 **Related:** [RFC-010](../../rfcs/2026-07-02-shuttle-backed-coordination.md) (REC8 follow-up, C4, C7), [Devflow spool](../../../src/skein/spools/devflow.md) (§2 stage map), [Workflow spool](../../../src/skein/spools/workflow.md) (§3 Gates), [Treadle spool](../../../spools/shuttle/treadle.md)

## Summary

Devflow's `run-afk-loop` stage currently pours one manual step ("run or hand off the AFK task loop") — the approved task queue executes inside a coordinator's private session, exactly the RFC-010.P1 pain. This feature makes the stage pour the approved tasks as a **sequential chain of `:subagent` gates** so the treadle executes them as durable shuttle runs, followed by a human acceptance checkpoint. Delegation is **opt-in per run**: with no task data the stage pours today's single manual step unchanged.

Devflow gains no shuttle/treadle dependency: it emits only the generic gate vocabulary (`workflow/gate "subagent"` + `shuttle/*` request attributes as plain data), exactly like any workflow author (treadle.md). If the treadle is not installed, the gates are ordinary wait-points a coordinator can drive manually — same contract, degraded gracefully by design, not by accident.

## Contract

### Task data

`run-afk-loop-workflow` opts (the routed continuation params) may carry:

- `:tasks` — vector of maps, each with required `:id` (stable, kebab/token string usable in a step id) and `:title`, optional `:body` (becomes the gate's `shuttle/prompt` payload; falls back to title) and `:harness` (per-task override). Invalid shapes (non-vector, empty vector, missing `:id`/`:title`, duplicate ids) **fail loudly** at compile time.
- `:delegate-harness` — default harness for tasks without `:harness`. When `:tasks` is present and neither a task `:harness` nor `:delegate-harness` resolves, **fail loudly** — no silent default harness.
- `:delegate-cwd` — optional working directory stamped as `shuttle/cwd` on every gate (recommended: the feature worktree).

These arrive by either route the engine already supports:

- seeded at `devflow/start!` as start opts (they ride `workflow/context` through every stage and revision loop), or
- passed as choice input at task sign-off: `(devflow/choose! feature :approved {:tasks [...] :delegate-harness "pi-main"})` — `:next` routing merges choice input into the continuation params (workflow.md §5). The `:approved` choice declares an optional `:input` entry for `:tasks` so `choice-details` advertises it.

Note: keyword-vs-string keys. Choice input round-trips through JSON, so task maps may arrive string-keyed; the constructor must read both (the engine's context round-trip note, workflow.md §3).

### Poured shape (delegated mode)

For tasks `[a b c]`:

```
:task-a (gate :subagent) ─▶ :task-b (gate) ─▶ :task-c (gate)
                                                  │
                              :human-acceptance-afk (checkpoint, HITL)
                                  accepted ─▶ (run auto-closes: done)
                                  revise   ─▶ afk stage (revision re-pour)
                                  abort    ─▶ abort
```

- Gates are chained `:depends-on` in task order — the treadle then executes them strictly sequentially (one live agent in the worktree at a time).
- Each gate carries `devflow/task` (the task `:id`), `shuttle/harness`, `shuttle/prompt` (body or title, prefixed with a short devflow context line naming the feature and task), and `shuttle/cwd` when configured.
- `:human-acceptance-afk` (`workflow/decision-point` `"afk-accepted"`) depends on the last gate. `accepted` is terminal (run auto-closes); `revise` is a declarative `:revise {:params {:revision true}}` (the whole stage re-pours — every task gate re-runs; coarse but consistent with devflow's stage-local revision loops); `abort` routes to `:abort` with the required `:reason` input like every other devflow HITL.
- Non-delegated mode (no `:tasks`): today's single `:run-afk-loop` step, byte-for-byte unchanged, including auto-close semantics.

## Deliverables

1. `src/skein/spools/devflow.clj` — extend `run-afk-loop-workflow` per the contract (pure constructor change; loud validation helpers).
2. `test/skein/spools/devflow_test.clj` additions:
    - `describe`-level: delegated opts produce the gate chain (ids, order, `:gate "subagent"`, dependency links) plus the checkpoint with the three choices; no-task opts produce the legacy single step;
    - loud failures: empty `:tasks`, missing `:id`, duplicate ids, missing harness resolution;
    - routing: `choose! :approved {:tasks [...] :delegate-harness "sh"}` on a real run pours the gates under the same run-id with string-keyed input tolerated (no shuttle/treadle install needed — the gates are inert without an engine, which is itself the graceful-degradation claim);
    - revision: `:revise` from the acceptance checkpoint re-pours the gate chain under the same run-id.
3. `src/skein/spools/devflow.md` — §2 stage map (delegated AFK shape and the new checkpoint), §4 agent-usage note showing the `choose!` call with task input, §6 attribute table (`devflow/task`, the consumed `shuttle/*` request attrs, `workflow/decision-point "afk-accepted"`).
4. `.skein/AGENTS.md` — one line in the devflow section: approve tasks with task data to delegate the AFK loop through the treadle.

Out of scope: parallel task execution (needs worktree-per-task policy), automatic verification/closure policy beyond the HITL acceptance checkpoint (RFC-010.NG6), any treadle/shuttle change.

## Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

Full suite green; no generated artifacts in `git status --short`.
