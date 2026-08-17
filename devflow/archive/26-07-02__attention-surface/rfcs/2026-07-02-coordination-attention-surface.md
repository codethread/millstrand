# Coordination Attention Surface

**Document ID:** `RFC-011` **Status:** Implemented **Date:** 2026-07-02 **Related:** [RFC-010](./2026-07-02-shuttle-backed-coordination.md) (Q6, G4), [Shuttle spool](../../spools/shuttle/README.md), [Treadle spool](../../spools/shuttle/treadle.md), [Workflow spool](../../src/skein/spools/workflow.md), [agent-delegate feature](../feat/agent-delegate/proposal.md), [afk-gates feature](../feat/afk-gates/proposal.md)

## RFC-011.P1 Problem

RFC-010's substrate is now real: on 2026-07-02 the treadle spool, the `agent-delegate` op, and delegated devflow AFK gates were all built _through_ shuttle-delegated agent runs coordinated over strands (five implementer/fixer runs, four reviewer runs, two live workflow demos in one session). That dogfooding produced a consistent observation: **execution is now durable and graph-native, but a coordinator's _attention_ is still hand-rolled.** The coordinator (human or agent) spends its loop discovering _when it is needed_ rather than deciding _what to do_.

Concrete friction from the session:

- **RFC-011.P1.1: No way to block on "the workflow needs me".** Driving the `feat-agent-delegate` build (implement gate → review gate, both treadle-fulfilled) required this cycle, repeated for every gate:

    ```sh
    strand show <gate-id> | jq -r '.attributes."treadle/run"'   # find the run
    strand op agent await <run-id> --timeout-secs 3000           # block on it
    # ...then re-derive workflow state over the REPL:
    printf '(skein.spools.workflow/next-steps "feat-agent-delegate")' \
      | strand weaver repl --stdin
    ```

The coordinator does not actually care about individual shuttle runs — it cares about the workflow's frontier. `agent await` blocks at the wrong level of abstraction, and `workflow/next-steps` answers the right question but cannot block. The same cycle appeared again for the two-task AFK demo (`afk-live-demo`): await gate one's run, re-poll, await gate two's run, re-poll, then finally discover the HITL checkpoint.

- **RFC-011.P1.2: Failure is durable but not discoverable.** Treadle and shuttle fail loudly _onto attributes_ (`shuttle/phase "failed"`, `treadle/error`, `treadle/delivery-blocked`, `exhausted`), by design (TEN-003). But nothing aggregates them: during the treadle build, diagnosing a failed test run meant already knowing which strand to `strand show`. RFC-010.Q6 asked exactly this ("How should failure be represented in ready queries?") and it is still open. Worse, a failed run strand is active with no blockers and no `workflow/role`, so the repo `work` ready query likely _surfaces it as ready work_ — the opposite of guidance.

- **RFC-011.P1.3: Run↔gate↔task provenance exists but is invisible in listings.** Every run carries `treadle/gate`/`treadle/run-id` or a `parent-of` edge to its task, but `strand op agent ps` shows none of it. Tracing "which run belongs to which gate" during the session was done with repeated `strand show <id> | jq` calls — constant low-grade friction, and the session transcript shows it at least six times.

- **RFC-011.P1.4: Run output beyond the final message requires manual file handling.** `shuttle/result` carries the final message, but the full transcript lives in the `.out` file whose path is buried in `shuttle/log`. While debugging the treadle's two flaky tests, inspecting harness output meant `strand show <run> | jq .attributes."shuttle/log"` then `cat`. No CLI surface reads it.

- **RFC-011.P1.5: Watching is one-shot and one-target.** `agent await` accepts multiple ids but a fixed set; a coordinator watching a pipeline cannot say "wake me when _anything_ under this feature needs attention".

## RFC-011.P2 Goals

- **RFC-011.G1:** One blocking primitive that answers the coordinator's real question: _return when workflow run `<id>` is done or needs input I must provide_ — a ready HITL checkpoint, a non-delegated gate, a stalled delegation, or run completion.
- **RFC-011.G2:** Make delegation failures one query away: named queries for failed/exhausted/delivery-blocked runs and error-stamped gates, shipped where the vocabulary is defined (shuttle/treadle install), not per-repo.
- **RFC-011.G3:** Surface provenance in listings: `agent ps` should show what a run is _for_ and be filterable by it.
- **RFC-011.G4:** Keep the CLI thin (TEN-006): every new surface is an op or named query over existing daemon state; no new core commands, no new scheduler state.
- **RFC-011.G5:** Guidance for `work`-style ready queries so failed run strands neither vanish nor masquerade as actionable work.

## RFC-011.P3 Non-goals

- **RFC-011.NG1:** No push/notification infrastructure (webhooks, watches over the socket). Blocking ops that poll in-weaver are the established precedent (`await-runs` sleeps 250ms in-process); event-subscription is an optimization behind the same surface, not a prerequisite.
- **RFC-011.NG2:** No auto-remediation. Attention surfaces _report_; recovery stays coordinator-owned (RFC-010.REC7, NG1).
- **RFC-011.NG3:** No TUI/dashboard. Raw structured JSON per TEN-001; rendering is a userland concern.

## RFC-011.P4 Current state (for grounding)

- `skein.spools.shuttle/await-runs` (spools/shuttle/src/skein/spools/shuttle.clj) blocks on run ids by polling `strands-by-ids` every 250ms — the in-op blocking precedent.
- `skein.spools.workflow/next-steps` returns the ready frontier with `:gate`, `:checkpoint`, `:checkpoint-kind` already distinguishable; `done?` exists. Step views therefore classify the _kind_ of attention (checkpoint vs gate vs done) — but they do **not** carry `treadle/run`, `treadle/error`, or any `shuttle/phase`, so distinguishing "subagent gate being fulfilled" from "subagent gate stalled" requires additional reads of the gate strand and its delegated run beyond `next-steps`. That extra read is exactly what the pluggable stall predicate in REC1 encapsulates.
- Treadle stamps: `treadle/error` (gate, spawn-side), `treadle/run` (gate), `treadle/gate`/`treadle/run-id`/`treadle/delivered`/`treadle/delivery-blocked` (run). Shuttle stamps `shuttle/phase` in `pending|running|done|failed|exhausted`.
- The repo `work` query (.skein/config.clj) filters only on `workflow/role`; shuttle run strands pass through it.
- The weaver event system dispatches on a single worker with registered handlers (`api/register-event-handler!`) — available if the poll loop ever needs replacing.

## RFC-011.P5 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-011.O1 | Do nothing; document the poll patterns in AGENTS.md. | Zero code. | The session evidence shows the pattern is already documented-by-repetition and still costs every coordinator the same toil; agents burn tokens re-deriving it. |
| RFC-011.O2 | `flow-await` as a **workflow-spool fn + repo-local op**: poll `next-steps`/`done?` plus treadle/shuttle stamps, return on attention conditions. | Small; mirrors `await-runs`; op form is CLI-reachable; conditions are pure reads of existing attributes. | Polling burns a weaver thread per waiter (acceptable at current scale); workflow spool must read `treadle/*`/`shuttle/*` attribute _names_ (data coupling only — see P6 note). |
| RFC-011.O3 | Event-subscription await: register a temporary event handler per waiting client, park on a promise. | No polling; instant wake. | New lifecycle to get right (handler leaks on client disconnect); the event worker is single-threaded so handler work must stay tiny; premature vs O2's simplicity. |
| RFC-011.O4 | Failure queries + `ps` enrichment only; skip the blocking primitive. | Cheapest slice of value. | Leaves P1.1 — the highest-cost friction — unaddressed. |
| RFC-011.O5 | Full "attention inbox": a materialized strand per attention item. | Queryable history of attention. | New writes, new lifecycle, new vocabulary for what is derivable by reading; violates TEN-004. |

## RFC-011.P6 Recommendation

- **RFC-011.REC1:** Implement **O2 now**, structured as:
    - `skein.spools.workflow/await!` `(run-id opts)` — blocks until an attention condition, returns `{:reason :done|:checkpoint|:gate|:stalled|:timeout :ready [step-view …] :done bool :detail …}`. Attention conditions, in priority order: run done; a ready checkpoint (any `:kind` — a decision is a decision); a ready gate whose waiter is **not** being fulfilled (waiter ≠ `subagent`, or waiter = `subagent` with a `treadle/error` stamp or a stamped run in `failed`/`exhausted` phase); timeout (default generous, e.g. 1800s).
    - The subagent-stall check needs `treadle/*`/`shuttle/*` **attribute literals** in the workflow spool. To respect RFC-010.NG5 (no workflow→shuttle coupling), take the stall predicate as an option with a shipped default of "never stalled", and let the **treadle** register the real predicate at `install!` (a stall-checker registered by name, exactly like workflow's named-workflow registry). Workflow stays vocabulary-free; treadle plugs in the vocabulary it owns.
    - A repo-local op `flow-await <run-id> [--timeout-secs n]` in `.skein/config.clj` wrapping it (same thin-op pattern as `devflow-*`).
- **RFC-011.REC2:** Ship failure queries with the spools' `install!`, since the vocabulary owner should own the projections (mirrors `register-default-harnesses!`): shuttle registers `agent-failures` (active runs, phase ∈ failed/exhausted); treadle registers `stalled-gates` (active gates with `treadle/error`, or `treadle/run` pointing at a failed/exhausted run) and `blocked-deliveries` (closed runs with `treadle/delivery-blocked`, missing `treadle/delivered`). Resolves RFC-010.Q6.
- **RFC-011.REC3:** Enrich `run-summary` with `:for` — strictly the **delegated target**: `treadle/gate` when present, else the run's `parent-of` source that is _not_ its `shuttle/spawned-by` run (spawn-run! records both `:parent` and `:spawned-by` as parent-of edges, but the spawner is provenance, not the target, and `run-summary` already exposes `:spawned-by` separately — `:for` must never fall back to it, or `ps --for` mislabels nested runs). Add `strand op agent ps --for <strand-id>` filtering on it.
- **RFC-011.REC4:** `strand op agent logs <run-id> [--tail n]` reading the `shuttle/log` file (and `.err` sibling) from the weaver side; fails loudly when the file is gone (state dirs are disposable).
- **RFC-011.REC5:** Repo `work` query gains guidance, not machinery: exclude strands with `shuttle/run "true"` from `work` (runs are execution records, not assignable work) and document that failures are found via `agent-failures`/`stalled-gates`.
- **RFC-011.REC6:** Defer O3 (event-driven wake) until O2's polling is measured to matter; the surface must not change when the internals do.

## RFC-011.P7 Likely user-facing shape

```sh
# the whole coordinator loop for a delegated pipeline:
strand op flow-await feat-x --timeout-secs 3600
# => {"reason":"checkpoint","ready":[{"checkpoint":"human-acceptance-afk",
#     "choices":["accepted","revise","abort"],...}],"done":false}
strand op devflow-choose feat-x accepted
strand op flow-await feat-x
# => {"reason":"done","ready":[],"done":true}

# noticing failure without knowing where to look:
strand ready --query stalled-gates
strand list --query agent-failures
strand op agent ps --for <gate-or-task-id>
strand op agent logs <run-id> --tail 80
```

## RFC-011.P8 Open questions

- **RFC-011.Q1:** Should `await!` return on _agent_-kind checkpoints, or only HITL? Session experience says yes to both — the coordinator drives agent checkpoints too — but an `--attend` filter (checkpoint kinds / gate waiters) may be wanted once non-treadle waiters (`ci`) appear.
- **RFC-011.Q2:** One waiter thread per blocked op call: at what concurrency does polling hurt, and should the op cap concurrent waiters loudly?
- **RFC-011.Q3:** Should `stalled-gates` also cover gates whose runs have been `running` beyond some duration (hang detection), or is wall-clock policy strictly userland?
- **RFC-011.Q4:** Does `flow-await` belong in the workflow spool (shipped, with pluggable stall predicate) or entirely in repo config? The recommendation says spool, because every future treadle consumer re-needs it, but the stall-predicate indirection is the price.

## RFC-011.P9 Outcome

- **RFC-011.OUT1:** Open for review. If accepted, implement REC2/REC3 (queries + ps enrichment) first — pure reads, immediately useful — then REC1's `await!` with the treadle-registered stall predicate, then REC4/5.
- **RFC-011.OUT2 (2026-07-02):** Accepted and implemented via the delegated pipeline. `workflow/await!` shipped with the pluggable stall-predicate registry (treadle registers the real predicate at install; workflow stays vocabulary-free), `agent-failures`/`stalled-gates`/`blocked-deliveries` named queries register at spool install, `run-summary` gains `:for` (strictly the delegated target), `agent ps --for` and `agent logs` shipped, the repo `work` query excludes shuttle run records, and `flow-await` wraps it all as a repo op. Q1 answered: await returns on any ready checkpoint. One scoping note from review: `stalled-gates` covers spawn-side `treadle/error` only — failed/exhausted-run stalls are detected by the stall predicate (a query cannot join gate to run phase).
- **RFC-011.OUT3 (2026-07-02, field findings from first live use):** (a) A backgrounded `flow-await` died after long idle with `malformed mill response … i/o timeout` — the mill proxy enforces a read deadline shorter than useful await windows (shorter `agent await` calls of ~20 min survived). Follow-up options: client-side chunked re-invocation under the deadline, or a mill deadline exemption for blocking ops (core change, needs its own proposal). (b) A review run exited 0 with empty output, so the treadle closed its gate with no findings recorded anywhere — gate success does not verify contract fulfillment. Follow-up: an opt-in result validation on gates (e.g. `shuttle/require-result "true"` treating a blank result as a failed run) so contract-carrying runs cannot silently no-op.
- **RFC-011.OUT4 (2026-07-02, follow-up):** There is still no single high-level view of a running pipeline — the coordinator composes `run-history` + `next-steps` + `agent ps --for` + `stalled-gates` by hand. Well-shaped gap: a `flow-status <run-id>` op joining run history, the ready frontier, delegated-run phases, and stall state into one payload (pure reads over existing surfaces), with any renderer (mermaid gate-chain, HTML) as userland on top. Tracked as a task strand in the canonical workspace.
