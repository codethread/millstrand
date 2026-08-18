# fanout-cap Plan

**Document ID:** `PLAN-Foc-001`

**Feature:** `fanout-cap`

**Proposal:** [proposal.md](./proposal.md) (PROP-Foc-001)

**RFC:** none

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) (SPEC-004)

**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (DELTA-Foc-001)

**Status:** Reviewed

**Last Updated:** 2026-07-14

## PLAN-Foc-001.P1 Goal and scope

Bound how many headless runs execute concurrently in a workspace so a wide delegation fan-out drains as a sliding window instead of a thundering herd on the single SQLite write lane (the 2026-07-14 `[SQLITE_BUSY]` incident, PROP-Foc-001.P1). Enforcement lives in one place — agent-run's `claim!` admission seam — because it is the only layer that sees every run source (rosters, councils, `delegate --ready`, raw `spawn`, crash respawns). The cap is layered config: a smart default of 4, replaced by a trusted-config workspace ceiling `W`, tightened but never raised by a per-fan-out group cap `K` a delegating verb stamps on its runs, giving effective width `min(W, K)` per group and `W` workspace-wide. Interactive/hitl runs are exempt (PROP-Foc-001.D1). Scope is exactly PROP-Foc-001.C1–C4; out of scope: per-harness quotas (NG1), cross-workspace coordination (NG2), any `depends-on`/graph change (NG3), any durable scheduler primitive or clock (NG4), and cross-group fairness (NG6/Q1).

## PLAN-Foc-001.P2 Approach

- **PLAN-Foc-001.A1 — the ceiling is C95-shaped spool state.** agent-run exposes `set-fanout-ceiling!` (working name) following the `set-read-limit!` convention (`batteries.clj:81`): validate a positive integer, fail loudly otherwise (TEN-003), and `reset!`/store into the engine spool-state map (`new-state`, `agent_run.clj:131-154`), which carries the default **4** so a world that never calls the setter still caps at 4. Adding a state key is a shape change, so the engine `state-version` (`agent_run.clj:115`) bumps and `migrate-state` (`agent_run.clj:174-198`) is extended to seed the ceiling into a preserved pre-feature map — the exact discipline SPEC-004.C95 mandates and DELTA-Foc-001.CC2 records. A `read`-side accessor (`fanout-ceiling`) mirrors `read-limit` for the window to consult.
- **PLAN-Foc-001.A2 — admission is atomic inside `claim!`, not a pre-read in `scan!`.** A budget computed in `scan!` before the admission loop races: two concurrent `scan!`s could each observe the same running count `R` and together admit `2×(W − R)` (PROP-Foc-001.C1). So the window check moves _into_ `claim!`'s existing `swap-vals!` (`agent_run.clj:1695-1700`): the swap function refuses the claim (leaves the map unchanged, returns not-claimed) when the in-flight map already holds `W` headless entries, or when the run carries a group already at `min(W, K)`. Counting and claiming become one atomic compare-and-swap on the in-flight atom, so concurrent `scan!`s compose safely with no assumption about event-lane serialization.
- **PLAN-Foc-001.A3 — the in-flight entry must carry enough to count.** Today `claim!` takes only `id` and stores `{:phase :claimed}` (`agent_run.clj:1695-1700`), which cannot express "headless?" or "which group". `claim!`'s signature changes to take the run (or a claim descriptor derived from it) so the swap-fn can (a) skip counting interactive runs via `interactive?` (`agent_run.clj:744`) — the exemption predicate applied both when counting the running set and when admitting — and (b) tally per-group occupancy by reading `agent-run/fanout-group`/`fanout-cap` off the run. The stored entry gains the fields the count needs (e.g. `:interactive?`, `:fanout-group`, `:fanout-cap`); the exact entry shape is an implementation choice, but every reader of the in-flight map (`in-flight-run-ids` at `agent_run.clj:227`, teardown paths, `supervise!`) must tolerate the widened value. `scan!` (`agent_run.clj:1702-1711`) threads the run through `claim!` unchanged in every other respect; `recovery-deferred?` filtering (`agent_run.clj:1125`) stays ahead of the window and composes untouched.
- **PLAN-Foc-001.A4 — deferral is "not claimed", completions wake for free.** A ready run that finds no slot is simply not claimed this pass: it keeps `agent-run/phase "pending"`, writes no attribute, adds no queue row, arms no timer — nothing durable to reconcile after a crash. When a run finishes it closes its strand, a graph mutation re-fires `on-event` → `scan!` (`agent_run.clj:1713-1718`), so the next ready pending run is reconsidered the instant a slot frees. After a restart the running count is recomputed from the in-flight registry / `running-query` (`agent_run.clj:720`) and the window rebuilds on the first scan (PROP-Foc-001.C4).
- **PLAN-Foc-001.A5 — delegation expresses intent by stamping attributes.** Each fan-out verb mints one fresh `fanout-group` id shared by all its runs and stamps `agent-run/fanout-group <id>` + `agent-run/fanout-cap <K>` through the `:attrs` slot `spawn-run!` already carries (`agent_run.clj:1849`), so no new `spawn-run!` argument is needed and raw `spawn` runs (no group) are governed by `W` alone. The `--max-concurrent <K>` flag is parsed where each verb already parses flags and threaded into its single spawn seam: `op-review` (`delegation.clj:1786`) → `review!`'s `spawn-spec!` (`delegation.clj:1606`, one call for reviewers and synthesizer); `op-council` (`delegation.clj:1812`) → `council!`/`panel!` seat spawns (`delegation.clj:1645,1489`); `op-delegate` (`delegation.clj:651`) → `delegate-task`'s `spawn-run!` (`delegation.clj:621`), one group across the classified batch. The synthesizer already `depends-on` its reviewers (`delegation.clj:1629`), so carrying it in the group is harmless and keeps group accounting whole.
- **PLAN-Foc-001.A6 — precedence is arithmetic, not policy.** Effective group width is `min(W, K)`; since `min(W, K) ≤ W`, a runtime override can only narrow — `--max-concurrent 20` under ceiling 4 runs 4 wide. This realizes the brief's "tighten below the ceiling, never exceed it" as a `min`, not a drift-prone policy check (PROP-Foc-001.C4). Readiness stays the sole scheduler: the window applies only _after_ `weaver/ready`, never makes a run ready, never adds an edge (PROP-Foc-001.NG3).

## PLAN-Foc-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Foc-001.AA1 | `skein.spools.agent-run` (engine/state) | `set-fanout-ceiling!` + `fanout-ceiling` accessor; default 4 in `new-state`; `state-version` bump; `migrate-state` seeds the ceiling key. |
| PLAN-Foc-001.AA2 | `skein.spools.agent-run` (`claim!`/`scan!`) | Atomic window inside `claim!`'s `swap-vals!`; `claim!` takes the run; widened in-flight entry (headless?/group/cap); interactive exemption via `interactive?`. |
| PLAN-Foc-001.AA3 | `skein.spools.delegation` (`review`/`council`/`delegate`) | `--max-concurrent` on the three op arg-specs; mint per-fan-out group id; stamp `agent-run/fanout-group`/`fanout-cap` via `spawn-run!` `:attrs`. |
| PLAN-Foc-001.AA4 | `test/skein/agent_run_test.clj` (shard B) | Gate-harness window cases (ceiling holds, completion admits next, group tighten, interactive exempt) + config-surface/reload cases. |
| PLAN-Foc-001.AA5 | `test/skein/delegation_test.clj` | Flag-parse + group/cap stamping assertions for the three verbs. |
| PLAN-Foc-001.AA6 | `spools/agent-run/README.md` | Scheduling section (`:164`) states the window as back-pressure over readiness; state-shape paragraph (`:45`) notes the `state-version` bump; attribute reference gains `fanout-group`/`fanout-cap` and `set-fanout-ceiling!`. |
| PLAN-Foc-001.AA7 | `spools/delegation/README.md` | Document `--max-concurrent` and per-fan-out group stamping in the `review`/`council`/`delegate --ready` sections. |
| PLAN-Foc-001.AA8 | `spools/agent-run.api.md`, `spools/delegation.api.md` | Regenerated by `make api-docs` from changed docstrings; never hand-edited. |
| PLAN-Foc-001.AA9 | `devflow/specs/daemon-runtime.md` | On ship, merge DELTA-Foc-001 into SPEC-004.C95 (promotion step, not an implementation slice). |

## PLAN-Foc-001.P4 Contract and migration impact

- **PLAN-Foc-001.CM1:** One durable contract change, staged in DELTA-Foc-001 against SPEC-004.C95: agent-run's fan-out ceiling is trusted-config runtime spool state (`set-fanout-ceiling!`, default 4, fail-loud), a second instance of the existing C95 pattern. No new clause number.
- **PLAN-Foc-001.CM2:** No SPEC-002 (CLI Surface) delta — DELTA-Foc-001.D2. `--max-concurrent` is a userland spool op flag; the dispatcher ships argv verbatim (SPEC-002.C30) and per-command userland contracts are not root-spec (SPEC-002.C43, SPEC-005.C4). Recorded so no one adds a spurious cli.md edit.
- **PLAN-Foc-001.CM3:** No SPEC-004 scheduling-clause delta — DELTA-Foc-001.D3. The window is userland engine back-pressure layered after `weaver/ready`; it changes no scheduling contract and adds no durable scheduler state (the SPEC-004.C97–C102a durable scheduler is untouched).
- **PLAN-Foc-001.CM4:** No `alpha-surface.md` (SPEC-005) delta. `spools/agent-run` and `spools/delegation` are userland reference spools contracted by their own READMEs (SPEC-005.C4); the window, the flag, and the `fanout-group`/`fanout-cap` attributes land in those READMEs, not the blessed-tier index.
- **PLAN-Foc-001.CM5:** Migration: the only state migration is the agent-run `state-version` bump, which reinits (or migrates) a preserved engine state map so it gains the ceiling key on `reload!`; it stores no new persisted/durable schema. Deferred runs are plain pending runs, so no data migration and nothing to reconcile after a crash.

## PLAN-Foc-001.P5 Implementation phases

Three sequential vertical slices, each one AFK run: PH1 → PH2 → PH3 (`blocked_by` chain). PH1 lands the enforcement engine, PH2 the intent surface that feeds it, PH3 the contracts that describe both.

### PLAN-Foc-001.PH1 Window enforcement + ceiling config

Outcome: `set-fanout-ceiling!` exists (fail-loud on zero/negative/non-integer), defaults to 4 with no setter, and survives `reload!` through the bumped `state-version`/`migrate-state`. The sliding window is atomic inside `claim!`'s `swap-vals!`: `scan!` admits at most `W` headless runs workspace-wide and at most `min(W, K)` per stamped group, deferred runs stay `pending` with no new attribute, completions re-admit through `on-event`, and interactive runs consume no slots. Verified by the gate-harness window cases and the config-surface/reload cases (V1). Cold gate: `clojure -M:test --shard B` (skein.agent-run-test is add-libs shard B; a focused ns run is rejected) plus `make fmt-check lint reflect-check`.

### PLAN-Foc-001.PH2 Delegation flag + group stamping

Outcome: `agent review`, `agent council`, and `delegate --ready` accept `--max-concurrent <K>`, mint one shared `fanout-group` per fan-out, and stamp `agent-run/fanout-group`/`fanout-cap` on every run they create via `spawn-run!` `:attrs`; the synthesizer/seat runs carry the same group; raw `spawn` and single `delegate` carry none. Verified by flag-parse and stamping assertions (V2). Cold gate: `clojure -M:test skein.delegation-test` plus `make fmt-check lint reflect-check`.

### PLAN-Foc-001.PH3 Contract docs + api-docs

Outcome: `spools/agent-run/README.md` states the window as back-pressure over readiness (ceiling, default 4, `set-fanout-ceiling!`, `min(W, K)`, interactive exemption, `state-version` note) and lists the new attributes/setter; `spools/delegation/README.md` documents `--max-concurrent` and group stamping on the three verbs; `make api-docs` regenerates `spools/*.api.md` clean with only the expected `scan!`/`spawn-run!`/`set-fanout-ceiling!`/delegation-op diffs. Verified through the docs-style gate and a clean api-docs regen. Cold gate: `make api-docs fmt-check lint reflect-check docs-check` with `git status --short` showing only the expected `*.api.md` diff. The DELTA-Foc-001 → daemon-runtime.md merge is a land-time promotion step, not part of this slice.

## PLAN-Foc-001.P6 Validation strategy

Deterministic and condition-driven — the change-review roster hunts `Thread/sleep` in tests, so every wait settles on a condition, never a clock (PROP-Foc-001.P6).

- **PLAN-Foc-001.V1 (PH1 window + config):** In `skein.agent-run-test`, register a gate harness (`defharness!`) whose argv blocks until the test releases a per-run sentinel (FIFO/sentinel-file), so a run stays `running` exactly until released — no timing assumption. After each `scan!`-triggering mutation, settle with `skein.test.alpha/await-quiescent!`, then assert `in-flight-run-ids` (`agent_run.clj:227`) for the exact admitted count; for "a slot never opened", poll `poll-until-deadline!` (blessed `skein.api.spool.alpha`) with a fail-loud budget. Cases mirror PROP-Foc-001.P6: (1) ceiling 2, 5 gated runs → exactly 2 in-flight, other 3 pending; (2) release one → its close fires `scan!` → a 3rd admits, width never exceeds 2 across the full drain; (3) ceiling 4, `fanout-cap 2` group → at most 2 run, `fanout-cap 20` group runs 4 wide (`min` never exceeds `W`); (4) N interactive `running` + ceiling 2 → two headless gated runs still admit; (5) `set-fanout-ceiling!` rejects zero/negative/non-integer loudly, default is 4 with no setter, and a `state-version` reload preserves the ceiling through `migrate-state`.
- **PLAN-Foc-001.V2 (PH2 intent):** In `skein.delegation-test`, assert each of `agent review`/`agent council`/`delegate --ready` parses `--max-concurrent` and that the spawned runs all carry the same `agent-run/fanout-group` and the requested `agent-run/fanout-cap`; assert raw `spawn`/single `delegate` carry no group.
- **PLAN-Foc-001.V3 (per-slice cold gates):** PH1 → `clojure -M:test --shard B` (shard-member invocation; focused ns rejected); PH2 → `clojure -M:test skein.delegation-test`; each code slice also runs `make fmt-check lint reflect-check`; PH3 runs `make api-docs` + `make docs-check`. Warm output (`make test-warm`) never satisfies a Done-when gate.
- **PLAN-Foc-001.V4 (acceptance/land only):** The full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test`, `(cd cli && go test ./...)`, and `clojure -M:smoke` run at queue acceptance and at land merge-local-verify — never as a per-slice gate.
- **PLAN-Foc-001.V5 (quality gates):** `make fmt-check lint reflect-check docs-check` held at zero findings; `make api-docs` regen clean with `git status --short` showing only the expected `*.api.md` diff.

## PLAN-Foc-001.P7 Risks and open questions

- **PLAN-Foc-001.R1:** Widening the in-flight entry shape touches every reader of the in-flight map (`in-flight-run-ids`, teardown, `supervise!`). Mitigation: keep the widened value additive (existing readers ignore new keys) and cover the teardown path in V1 so a widened entry is released correctly on both success and failure.
- **PLAN-Foc-001.R2:** Moving the count into `claim!`'s `swap-vals!` puts per-group tallying inside a hot compare-and-swap. Mitigation: the tally is over the in-flight map already in hand (no query), stays O(in-flight), and the pass is idempotent — the same cheapness the current `claim!` has; V1 case (2) proves width never exceeds the cap across a concurrent drain.
- **PLAN-Foc-001.R3:** The `state-version` bump means a live weaver must `reload!` to pick up the ceiling key; a preserved map missing it would otherwise be reused. Mitigation: `migrate-state` seeds the default, and V1 case (5) proves the reload path. This is the same discipline SPEC-004.C95 already documents.
- **PLAN-Foc-001.Q1:** Cross-group admission fairness — a very wide group encountered first can fill the ceiling before a narrow group is considered on that pass (PROP-Foc-001.Q1/NG6). Decided _not_ to solve here: bounded total width is the goal; the narrow group admits on subsequent scans as slots free. Round-robin admission is a follow-up if starvation surfaces. Does not block task generation.

## PLAN-Foc-001.P8 Task context

- **PLAN-Foc-001.TC1:** PROP-Foc-001 is the design contract — C1 (window at `claim!`), C2 (ceiling in trusted config), C3 (delegation intent), C4 (precedence + scheduler preservation), D1–D3 (open questions decided), P6 (validation sketch), P7 (Done-when). DELTA-Foc-001 is the sole root-spec change (SPEC-004.C95 amendment) and records why there is no SPEC-002/SPEC-004-scheduling/SPEC-005 delta (D1–D3). Do not add those deltas.
- **PLAN-Foc-001.TC2:** Convention to copy for the setter: `skein.spools.batteries/set-read-limit!` (`batteries.clj:81`) + `read-limit` accessor (`:76`) — validate, `reset!` into spool-state, fail loud. Reload-tolerant set-once precedents: `set-preamble-extension!` (`agent_run.clj:768`), `set-default-review-contract!` (`agent_run.clj:2368`).
- **PLAN-Foc-001.TC3:** Engine seam anchors — `new-state`/`state-version`/`migrate-state` (`agent_run.clj:115-198`); `claim!`/`scan!`/`on-event` (`agent_run.clj:1695-1718`); `in-flight-run-ids` (`:227`), `running-query` (`:720`), `interactive?` (`:744`), `recovery-deferred?` (`:1125`); `spawn-run!` `:attrs` (`:1849`).
- **PLAN-Foc-001.TC4:** Delegation seam anchors — `op-review` (`delegation.clj:1786`) → `review!`/`spawn-spec!` (`:1542,1606`); `op-council` (`:1812`) → `council!`/`panel!` (`:1645,1435,1489`); `op-delegate` (`:651`) → `delegate-task` `spawn-run!` (`:621`); `delegate --ready` is headless-only and rejects interactive flags (`:656-657`), so groups never mint interactive runs.
- **PLAN-Foc-001.TC5:** Test harness anchors — `test/skein/agent_run_test.clj` uses `defharness!` real `sh` harnesses + `await-phase` (`:37,156-160`); settle with `skein.test.alpha/await-quiescent!` and poll with `skein.api.spool.alpha/poll-until-deadline!`; assert against `in-flight-run-ids`. `skein.agent-run-test` is add-libs **shard B** (cold gate `clojure -M:test --shard B`, not a focused ns run); `skein.delegation-test` is a parallel namespace (focused `clojure -M:test skein.delegation-test` is its gate).
- **PLAN-Foc-001.TC6:** Non-goals to hold — no `depends-on`/graph edge for deferral (a capped run is a plain ready pending run), no durable queue/timer, no per-harness quota, no cross-workspace budget, no fairness policy beyond weaver readiness order.
- **PLAN-Foc-001.TC7:** Contract-doc slice (PH3) edits `spools/agent-run/README.md` and `spools/delegation/README.md` through the docs-style gate and regenerates both `*.api.md` via `make api-docs`; these are implementation-time work owned by PH3, not by the spec/plan authoring task.

## PLAN-Foc-001.P9 Developer Notes

### PLAN-Foc-001.DN1 Task elo7d: spec + plan authored — 2026-07-14

- One delta written: `specs/daemon-runtime.delta.md` (DELTA-Foc-001), amending SPEC-004.C95 only — the feature's sole root-spec change.
- Confirmed at authoring (worktree `fanout-cap`): no SPEC-002 delta (D2), no SPEC-004 scheduling-clause delta (D3), no SPEC-005 delta (CM4). Do not add them.
- Seam wrinkle verified in source, not assumed: `claim!` currently takes only `id` and stores `{:phase :claimed}` (`agent_run.clj:1695-1700`), so the atomic window needs `claim!` to take the run and the in-flight entry to widen (headless?/group/cap). PH1 owns that change and its blast radius on in-flight-map readers (R1).
- Test-runner facts verified: `skein.agent-run-test` is add-libs **shard B** (`test/skein/test_runner.clj:57`) — its cold gate is `clojure -M:test --shard B`, a focused ns run is rejected; `skein.delegation-test` is in `parallel-namespaces` (`:16`), so `clojure -M:test skein.delegation-test` is a valid focused gate.

### PLAN-Foc-001.DN2 Task 6nska: task queue authored — 2026-07-14

- Wrote `tasks/index.yml` + three AFK task files (`TASK-Foc-001`/`002`/`003`) mapping PH1→PH2→PH3 as a strict `blocked_by` chain; queue authored on `fanout-cap`, docs-only.
- Folded the actionable review findings from `elo7d` synthesis note `vo7pb` into the task bodies: (a) the `state-version` target is stated explicitly as **4** (current literal is `3` at `agent_run.clj:115`) in `TASK-Foc-001.MI3`; (b) the simpler `migrate-state` approach — add `:fanout-ceiling` to the existing `select-keys old [...]` carry-over vector (`agent_run.clj:197`) rather than a new v3→v4 branch, with a merge-order sanity check — is recorded in `TASK-Foc-001.MI4`; (c) the requirement that PH1 (and PH2) tests stamp `fanout-group`/`fanout-cap` manually via `spawn-run!` `:attrs` as fixtures, since the delegation flag surface only lands in PH2, is in `TASK-Foc-001.MI9` / `TASK-Foc-002.MI5`.
- Re-verified all quoted source anchors against the `fanout-cap` worktree before writing them into task files: `claim!` `:1695` (takes `id`, stores `{:phase :claimed}`), `scan!` `:1702`, `on-event` `:1713`, `spawn-run!` `:1849` (`:attrs` merge `:1901-1913`), `in-flight-run-ids` `:227`, `interactive?` `:744`, `recovery-deferred?` `:1125`, `new-state` `:131`, `migrate-state` `:174` (`select-keys` `:197`); `set-read-limit!` `batteries.clj:81` / `read-limit` `:76`; `op-review` `:1786`, `op-council` `:1812`, `op-delegate` `:651`, `review!` `:1542` / `spawn-spec!` `:1606`, `council!` `:1645`, `panel!` `:1435`, `delegate-task` `:605` (spawn `:621`), synthesizer `:depends-on` `:1629`, `delegate --ready` interactive reject `:656-657`. All within ±1 line of the plan's anchors.
