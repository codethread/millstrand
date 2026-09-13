# Fan-out concurrency cap: back-pressure at run start

**Document ID:** `PROP-Foc-001` **Last Updated:** 2026-07-14 **Related brief:** [brief.md](./brief.md) (card `0bxfh`; the brief fixes the requirements and is the scope contract) **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004), [CLI Surface](../../specs/cli.md) (SPEC-002), [REPL API](../../specs/repl-api.md) (SPEC-003), [Alpha Surface](../../specs/alpha-surface.md) (SPEC-005) **Related sources:** `spools/agent-run/src/skein/spools/agent_run.clj`, `spools/delegation/src/skein/spools/delegation.clj`, `spools/agent-run/README.md`, `spools/delegation/README.md`

**Reading context:** this assumes the Skein run model — a run is a strand carrying `agent-run/*` attributes, the engine listens for graph mutations and spawns each ready pending run (`spools/agent-run/README.md:17`), and readiness over `depends-on` is today the only scheduling primitive (`spools/agent-run/README.md:164`). Line numbers are verified in the `fanout-cap` worktree. Every point ID is a grepable anchor.

## PROP-Foc-001.P1 Problem

Neither spool throttles run starts. `delegate --ready`, `agent review`, and `agent council` create N unblocked pending runs, and agent-run's `scan!` starts _every_ ready pending run at once on an unbounded cached thread pool (`agent_run.clj:1702-1711`, launching each claimed run via `.execute` on the `newCachedThreadPool` built at `agent_run.clj:133`). Fan-out width is therefore unbounded by design, and every reviewer, seat, and synthesizer contends for the workspace's single SQLite write lane at the same instant.

On 2026-07-14 a coordinator fanned out three change-review rosters at once — 18 reviewer runs plus 3 synthesizers, all started immediately. The workspace write queue stalled past `skein.core.db`'s 5-second busy timeout, and two run-finalization writes failed `[SQLITE_BUSY]` (runs `tljyb`/`xdj2y`) even though both worker processes had completed their reviews and written their findings; the runs had to be superseded and retried. The busy-wait is exactly the "concurrent in-process writers … wait briefly on write-lock contention rather than fail immediately" behavior SPEC-004 promises (SPEC-004.C7a) — but that wait is bounded, so enough simultaneous finalizers exhaust it. The database tuning is correct; the missing piece is back-pressure on how many runs are in flight producing that write pressure.

## PROP-Foc-001.P2 Goals

- **PROP-Foc-001.G1:** A bounded number of headless runs execute concurrently in a workspace, so a wide fan-out drains as a sliding window instead of a thundering herd — the write lane never sees more simultaneous finalizers than the cap.
- **PROP-Foc-001.G2:** Enforcement lives in one place — agent-run's claim seam — because it is the only layer that sees every run source (rosters, councils, `delegate --ready`, raw `spawn`, crash-recovery respawns). Delegation expresses per-fan-out _intent_; it does not police concurrency.
- **PROP-Foc-001.G3:** The cap is layered, honest configuration: a smart default (4), replaced by trusted workspace config as the ceiling, and tightened — never raised — by a runtime override a delegating agent stamps on the runs it creates. Effective cap = `min(workspace ceiling, group cap)`.
- **PROP-Foc-001.G4:** The scheduler contract survives. Readiness over `depends-on` stays the only thing that decides _whether_ a run may start; the cap only decides _how many_ ready runs start now versus wait for a slot. It is back-pressure at run start, not new graph semantics.

## PROP-Foc-001.P3 Non-goals

- **PROP-Foc-001.NG1:** No per-harness or per-backend quotas. The window counts runs, not model seats; "at most 2 claude and 3 pi" is not expressible and not wanted (the incident was write-lane contention, which is harness-blind).
- **PROP-Foc-001.NG2:** No cross-workspace or global coordination. The ceiling is per-weaver-workspace state; two workspaces on one host do not share a budget. The write lane they contend for is per-workspace (SPEC-004.C1).
- **PROP-Foc-001.NG3:** No change to `depends-on` readiness or any graph edge semantics. A capped run is a _ready_ pending run waiting for a slot; it is never modeled as blocked, never gets a synthetic edge, and never appears in `depends-on` traversal. Readiness stays the sole scheduling primitive (`spools/agent-run/README.md:164`).
- **PROP-Foc-001.NG4:** No new durable scheduler primitive and no clock involvement. The window is derived live from the running-run count on each scan; it stores no queue and arms no timer. This is deliberately _not_ the durable scheduler wake substrate (that is `skein.api.scheduler.alpha`); a deferred run is simply a pending run not yet claimed.
- **PROP-Foc-001.NG5:** Interactive/hitl sessions are not throttled or counted (PROP-Foc-001.D1).
- **PROP-Foc-001.NG6:** No priority, fairness, or preemption policy beyond the admission order agent-run already uses (weaver readiness order). A wide group does not starve a narrow one by design; if that surfaces it is a follow-up.

## PROP-Foc-001.P4 Design

Four clauses: C1 enforces in agent-run, C2 threads the ceiling from trusted config, C3 expresses intent in delegation, C4 states the precedence and scheduler-preservation argument.

### PROP-Foc-001.C1 — the sliding window at `claim!`

Today `scan!` claims _every_ ready pending run and launches each immediately:

```clojure
;; agent_run.clj:1702-1711 (current)
(defn scan! []
  (let [runtime    (rt)
        workers    (worker-executor)
        ready-runs (remove recovery-deferred? (weaver/ready runtime pending-query {}))
        claimed    (filterv (comp claim! :id) ready-runs)]
    (doseq [run claimed]
      (.execute workers ^Runnable (fn [] (launch-run! runtime run))))
    (mapv :id claimed)))
```

The window is a bounded admission step between `ready-runs` and `claimed`:

- **Count what is running.** The live in-flight registry (`agent_run.clj:139`, run-id → `{:phase …}`) and the `running-query` (`agent_run.clj:720-721`) already track exactly the runs occupying slots. `scan!` computes the current concurrent headless count from in-flight state — the same source `in-flight-run-ids` (`agent_run.clj:227`) exposes — filtered to non-interactive runs (PROP-Foc-001.D1).
- **Admit up to the budget, in readiness order.** With workspace ceiling `W` and current headless running count `R`, the global budget is `W − R`. `scan!` walks `ready-runs` in the order weaver readiness returns them and admits each only while the global budget remains and its fan-out group (PROP-Foc-001.C3) has a free group slot; every admitted run decrements the budgets it consumes.
- **Admission is atomic inside `claim!`, not a pre-read in `scan!`.** A budget read taken before the admission loop would race: two concurrent `scan!`s could each observe the same `R` and together admit up to `2×(W − R)`. The window check therefore moves _into_ `claim!`'s existing `swap-vals!` (`agent_run.clj:1695-1700`): the swap function refuses the claim when the in-flight map already holds `W` headless entries (or the run's group is at its cap), so counting and claiming are one atomic compare-and-swap on the in-flight atom. Concurrent `scan!`s then compose safely with no assumption about event-lane serialization; the pass stays cheap and idempotent.
- **Deferred runs stay pending.** A ready run that finds no slot is simply _not claimed_ this pass. It keeps `agent-run/phase "pending"` and no attribute is written — there is no "deferred" phase, no queue row, nothing durable to reconcile after a crash. On the next scan it is reconsidered from scratch.
- **Completions wake the window for free.** A finishing run closes its run strand, which is a graph mutation, and every mutation re-fires `on-event` → `scan!` + `supervise!` (`agent_run.clj:1713-1718`). So the instant a slot frees, the next ready pending run is reconsidered and admitted. No timer, no poll loop — the existing event lane already delivers the wake-up. The one edge is a workspace whose running set is entirely interactive and idle: headless deferrals then wait, correctly, until some run completes and fires a mutation — which is the intended back-pressure, not a stall (interactive runs never occupied a slot, so they never blocked a headless start).

`recovery-deferred?` (the existing quiet-retry skip, `agent_run.clj:1125`) is unchanged and composes: a recovery-deferred run is removed before the window even counts.

### PROP-Foc-001.C2 — the workspace ceiling in trusted config

The ceiling is runtime-owned weaver-lifetime state, set by trusted config, following the pattern agent-run and batteries already use for tunables. Three precedents converge on one shape:

- the batteries read cap, "runtime state set by trusted config through `skein.spools.batteries/set-read-limit!`; invalid cap values fail loudly" (SPEC-004.C95);
- `set-preamble-extension!` (`agent_run.clj:768`), reload-tolerant set-once state held in the spool-state map; and
- `set-default-review-contract!` (`agent_run.clj:2368`), a `reset!` into a spool-state atom.

So agent-run exposes `set-fanout-ceiling!` (working name), which validates a positive integer (fail-loud on anything else, TEN-003) and stores it in the engine spool-state map (`new-state`, `agent_run.clj:131-154`), defaulting to **4** when trusted config sets nothing. A workspace raises or lowers the ceiling with one line in `init.clj`, exactly like the `register-query!` example in the customisation guide (`docs/spools/customisation.md:82-86`):

```clojure
;; init.clj — after agent-run's use!
(skein.spools.agent-run/set-fanout-ceiling! 6)
```

Adding a state key means bumping the engine `state-version` (`agent_run.clj:115-129`) so a post-deploy `reload!` reinits through `migrate-state` (`agent_run.clj:174-198`) rather than reusing a preserved map missing the ceiling — the same discipline SPEC-004.C95 mandates and the same incident the version guard already documents. The default lives in `new-state`, so a world that never calls the setter still caps at 4.

### PROP-Foc-001.C3 — per-fan-out intent in delegation

A delegating verb expresses "run my fan-out at most K wide" by stamping two attributes on every run it creates, through the `:attrs` slot `spawn-run!` already carries (`agent_run.clj:1849,1900-1905`):

- `agent-run/fanout-group <group-id>` — a fresh id minted per fan-out, shared by all runs of that fan-out (its reviewers + synthesizer, its council seats, or its `--ready` batch).
- `agent-run/fanout-cap <K>` — the requested width for that group.

agent-run's window (C1) reads these off the ready runs: a run with a group is admitted only while fewer than `min(W, K)` runs of that group are already running; a run with no group is bounded by `W` alone. Because the stamp is plain run attributes, this needs no new `spawn-run!` argument beyond the existing `:attrs` merge, and raw `spawn` runs (which carry no group) are naturally governed by the workspace ceiling only.

The flag surface is `--max-concurrent <K>` on the three multi-run verbs, parsed where each already parses its flags and threaded into the one place each spool spawns:

- `agent review` — `op-review` arg-spec (`delegation.clj:1786-1788`) → `review!`'s `spawn-spec!` closure (`delegation.clj:1606-1614`), which is the single `spawn-run!` call for both reviewers and the synthesizer.
- `agent council` — `op-council` arg-spec (`delegation.clj:1812-1814`) → `council!` (`delegation.clj:1645`) / `panel!` (`delegation.clj:1435,1489`) seat spawns.
- `delegate --ready` — `op-delegate` (`delegation.clj:651`) → `delegate-task`'s `spawn-run!` (`delegation.clj:621`), stamping the shared group across the classified batch (`delegation.clj:662-670`).

The synthesizer already `depends-on` its reviewers (`delegation.clj:1629`), so it cannot start until they finish regardless of the cap; carrying it in the same group is harmless and keeps the group's accounting whole.

### PROP-Foc-001.C4 — precedence and scheduler preservation

- **Precedence is `min`, and it only tightens.** Config _replaces_ the default as the workspace ceiling `W` (PROP-Foc-001.C2). A group cap `K` from a runtime override yields effective width `min(W, K)` for that group. Since `min(W, K) ≤ W`, an override can only narrow below the ceiling — a group asking for `--max-concurrent 20` under a ceiling of 4 still runs 4 wide. This is the brief's rule ("a runtime override can only tighten below the effective ceiling, never exceed it") realized as arithmetic, not a policy check that could drift.
- **Readiness stays the only scheduler.** The window is applied _after_ `weaver/ready` (`agent_run.clj:1707`) has already decided which runs are startable; it never makes a run ready, never blocks one via an edge, and never reorders the graph. A capped run is a ready pending run that has not been claimed yet — indistinguishable, in the graph, from a ready run in a scan that simply has not fired. The README line "Readiness is the only scheduling primitive" (`spools/agent-run/README.md:164`) stays true; the amendment states the cap as back-pressure layered on top of it, consistent with "Agent-run is intentionally not core scheduler infrastructure" (`spools/agent-run/README.md:21`).
- **No durability added.** Because deferral is "not claimed this pass" and completions re-fire `scan!` through the existing event lane, the feature stores no queue and arms no clock — it cannot desynchronize from the graph across a restart, because after a restart the running count is recomputed from in-flight/`running-query` and the window rebuilds itself on the first scan.

## PROP-Foc-001.P5 Decisions on the brief's open questions

Each brief open question is answered with a recommendation.

- **PROP-Foc-001.D1 — do interactive/hitl sessions consume window slots? Recommendation: no, exempt them.** Interactive runs are long-lived by design — a session completes when the strand it serves closes, not when a process exits (`spools/agent-run/README.md:19`, the claims model) — so counting them would let a handful of parked human sessions permanently starve headless fan-out, which is the opposite of the goal. They also do not produce the burst of run-finalization writes that caused the incident (they finalize on human cadence, one at a time). The window counts and admits headless runs only; `interactive?` (`agent_run.clj:744-745`) is the exemption predicate, applied both when counting the running set and when selecting admittable ready runs. `delegate --ready` is already headless-only and rejects interactive flags (`delegation.clj:656-657`), so the fan-out verbs never mint interactive runs into a group.

- **PROP-Foc-001.D2 — scope of the cap: rosters only, or all multi-run verbs? Recommendation: the workspace ceiling governs every headless run start workspace-wide, with per-fan-out tightening on `review`, `council`, and `delegate --ready`.** The incident was three _rosters_, but the write lane is shared by every run source; a ceiling that only watched rosters would still let a raw `spawn` loop or a `delegate --ready` batch swamp the lane. So `W` (C2) bounds all headless runs unconditionally, and the `--max-concurrent` override (C3) is offered on exactly the three verbs that fan a batch out at once — because per-group tightening only makes sense where a single command creates a group. Single `delegate <task>`, single `spawn`, and crash-recovery respawns carry no group and are governed by the ceiling alone. This is the "enforce workspace-wide, express intent per fan-out" split the brief's ownership finding directs.

- **PROP-Foc-001.D3 — where the ceiling lives, and its spec coverage. Recommendation: trusted-config setter in agent-run spool-state, named in SPEC-004.C95 beside the batteries read cap.** The customisation guide's config surface is trusted `init.clj` calling a spool registration (`docs/spools/customisation.md:56-92`); the ceiling follows the identical shape as `set-read-limit!`/`set-preamble-extension!`/`set-default-review-contract!` (C2). It is _not_ a `config.json` alpha field: `config.json` is the low-privilege shareable marker (SPEC-002.C2), and a concurrency ceiling is trusted operational tuning, not portable workspace identity. Spec coverage is one sentence added to SPEC-004.C95 — which already enumerates the batteries read cap as trusted-config runtime state with fail-loud validation — naming the agent-run fanout ceiling as a second instance of the same pattern (PROP-Foc-001.C6).

## PROP-Foc-001.C6 — spec and doc deltas (named)

Tier discipline (SPEC-003.C19; SPEC-005.C4) decides where each delta lands. `spools/agent-run` and `spools/delegation` are **userland, not shipped alpha surface — their READMEs are their own contracts with their own cadence** (SPEC-005.C4). So the behavioral contract for the window and the flag lives in those READMEs, and the only root-spec touch is the one trusted-config-state clause.

- **Root spec — `devflow/specs/daemon-runtime.md` (SPEC-004.C95):** add one sentence naming the agent-run fanout ceiling as trusted-config runtime spool state (`set-fanout-ceiling!`), fail-loud on invalid values, parallel to the existing batteries-read-cap sentence. This is the sole clause edit; no new clause number is required. (No SPEC-004 scheduling clause changes — agent-run scheduling is not a root-spec contract; it is userland engine behavior per SPEC-005.C4.)
- **`spools/agent-run/README.md`:** amend the scheduling section (`:164`, "Readiness is the only scheduling primitive") to state the concurrency window as back-pressure at run start layered over readiness — the ceiling, its default of 4, the trusted-config setter, the `min(W, K)` group semantics, and the interactive exemption. Note the `state-version` bump in the state-shape paragraph (`:45`). Add `set-fanout-ceiling!` and the `agent-run/fanout-group`/`fanout-cap` attributes to the attribute reference (`:245` table region).
- **`spools/delegation/README.md`:** document `--max-concurrent` on `agent review`, `agent council`, and `delegate --ready`, and the per-fan-out group stamping, in the sections that already describe those verbs.
- **`spools/agent-run.api.md` and `spools/delegation.api.md`:** regenerated by `make api-docs` from the changed docstrings (`scan!`, `spawn-run!`, the new `set-fanout-ceiling!`, and any delegation op doc) — generated, not hand-edited.
- **No SPEC-002 delta:** `--max-concurrent` is a userland spool op flag, not dispatcher surface. The dispatcher ships argv verbatim (SPEC-002.C30) and per-command contracts for userland ops are not root-spec (SPEC-002.C43, SPEC-005.C4).

## PROP-Foc-001.P6 Validation sketch

The window is proven with the existing world/harness harness, deterministically and without sleeps — the change-review roster hunts `Thread/sleep` in tests, so every wait is condition-driven.

- **A gate harness holds slots open.** Tests already `defharness!` real `sh` harnesses and drive completion through `await-phase` (a `poll-until` wrapper, `test/skein/agent_run_test.clj:37,156-160`). For the window, register a harness whose argv blocks until the test releases it — `sh -c 'read < <fifo>'` reading a per-run FIFO the test creates, or an equivalent sentinel-file gate — so a run stays `running` (occupying a slot) until the test writes the sentinel. No timing assumption: the run finishes exactly when, and only when, the test releases it.
- **Settle deterministically, never sleep.** After each `scan!`-triggering mutation, settle the event lane with `skein.test.alpha/await-quiescent!` (SPEC-004.C74b; already used across `test/skein/*` — e.g. `events_quiescence_test.clj`, `scheduler_e2e_test.clj`), then assert against `in-flight-run-ids` (`agent_run.clj:227`) for the exact count admitted. For "a slot never opened," poll `poll-until-deadline!` (blessed `skein.api.spool.alpha`, SPEC-005.C2; used in `test/skein/api/spool_test.clj`) with a fail-loud budget.
- **Cases.**
    1. **Ceiling holds.** Ceiling 2, spawn 5 ready gated runs, scan + quiescent → exactly 2 in-flight; the other 3 stay `pending`.
    2. **Completion admits the next.** Release one gated run; its close fires `on-event` → `scan!`; quiescent → a 3rd run is now in-flight, total still 2. Repeat to drain all 5, asserting the width never exceeds 2 at any settle point.
    3. **Group tighten only.** Ceiling 4, a fan-out stamped `fanout-cap 2` → at most 2 of that group run even though the ceiling would allow 4; a `fanout-cap 20` group under ceiling 4 runs 4 wide (`min` never exceeds `W`).
    4. **Interactive exempt.** With N interactive runs `running` and ceiling 2, two headless gated runs still admit — the interactive sessions consumed no slots.
    5. **Config surface.** `set-fanout-ceiling!` rejects zero/negative/non-integer loudly; default with no setter is 4; a `state-version` reload preserves the ceiling through `migrate-state`.

## PROP-Foc-001.P7 Done-when

- **PROP-Foc-001.DW1:** `scan!` admits at most `min(W, group-cap)` runs per group and at most `W` headless runs workspace-wide; deferred runs stay `pending` with no new attribute, and completions re-admit through `on-event`.
- **PROP-Foc-001.DW2:** `set-fanout-ceiling!` exists, defaults to 4, fails loudly on invalid input, and survives `reload!` through the bumped `state-version`; interactive runs consume no slots.
- **PROP-Foc-001.DW3:** `agent review`, `agent council`, and `delegate --ready` accept `--max-concurrent` and stamp `agent-run/fanout-group`/`fanout-cap` on the runs they create.
- **PROP-Foc-001.DW4:** SPEC-004.C95 names the fanout ceiling; both READMEs and both `.api.md` files reflect the window and flag; validation (P6) is green with no test sleeps.

## PROP-Foc-001.P8 Open questions

**0 open.** The brief's three open questions are decided in PROP-Foc-001.D1–D3. One design judgment is recorded for sign-off:

- **PROP-Foc-001.Q1 — admission fairness across groups.** The window admits in weaver readiness order (PROP-Foc-001.C1), so a very wide group encountered first can fill the whole ceiling before a narrow group's runs are considered on that pass; the narrow group's runs admit on subsequent scans as slots free. This is acceptable for the incident's shape (bounded total width is the goal, not inter-group fairness) and is called out as NG6. If starvation surfaces in practice, round-robin admission across groups is a follow-up, not part of this feature.
