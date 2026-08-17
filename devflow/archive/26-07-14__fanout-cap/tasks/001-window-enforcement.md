# Task 1: Window enforcement + ceiling config (PH1)

**Document ID:** `TASK-Foc-001` **Slice:** `PLAN-Foc-001.PH1` **Type:** AFK **Depends on:** none

## TASK-Foc-001.P1 Scope

Type: AFK

Land the enforcement engine: the workspace fan-out ceiling as trusted-config spool state, and the atomic sliding window inside `claim!` that bounds how many headless runs `scan!` admits. This is the layer that sees every run source (`PROP-Foc-001.G2`); the delegation intent surface that feeds it is PH2 (Task 2), and its contract docs are PH3 (Task 3). Enforcement is back-pressure applied _after_ `weaver/ready`, never a graph edge and never a durable queue (`PROP-Foc-001.NG3`, `NG4`; `PLAN-Foc-001.A2`, `A4`).

**Owned files (disjoint):**

- `spools/agent-run/src/skein/spools/agent_run.clj`
- `test/skein/agent_run_test.clj`

## TASK-Foc-001.P2 Must implement exactly

- **TASK-Foc-001.MI1 — ceiling setter + accessor.** Add `set-fanout-ceiling!` following the `set-read-limit!` convention (`spools/batteries/src/skein/spools/batteries.clj:81`, accessor `read-limit` at `:76`): validate a positive integer, fail loudly (TEN-003) on zero/negative/non-integer, and `reset!`/store into the engine spool-state map. Add a `read`-side accessor `fanout-ceiling` mirroring `read-limit` for the window to consult (`PLAN-Foc-001.A1`, `PROP-Foc-001.C2`). Give `set-fanout-ceiling!` a public docstring — it is the PH3 api-docs surface.
- **TASK-Foc-001.MI2 — default 4 in `new-state`.** Seed the ceiling key with the smart default **4** in `new-state` (`agent_run.clj:131`) so a world that never calls the setter still caps at 4 (`PROP-Foc-001.C2`, `PLAN-Foc-001.A1`).
- **TASK-Foc-001.MI3 — bump `state-version` 3 → 4.** Adding a state key is a shape change: bump the engine `state-version` (`agent_run.clj:115`, currently literal `3`) to **4**, so a post-deploy `reload!` reinits through `migrate-state` rather than reusing a preserved map missing the ceiling (`PROP-Foc-001.C2`, `PLAN-Foc-001.A1`, `R3`; `DELTA-Foc-001.CC2`).
- **TASK-Foc-001.MI4 — migrate-state seeds the ceiling.** Extend `migrate-state` (`agent_run.clj:174`). The simplest correct approach is to add `:fanout-ceiling` (the chosen state key) to the existing `select-keys old [...]` carry-over vector (`agent_run.clj:197`): a v3 map lacks the key so `select-keys` omits it and `new-state`'s default survives, while a v4→v4 reload preserves a configured ceiling. Verify the merge order `(merge (new-state) (select-keys old ...) {registry atoms})` never yields a nil ceiling on the reload path (V1 case 5). No separate v3→v4 migration branch is needed unless this merge check fails (`PLAN-Foc-001.A1`, synthesis note `vo7pb` item 3).
- **TASK-Foc-001.MI5 — atomic window inside `claim!`.** Move the budget check _into_ `claim!`'s existing `swap-vals!` on the in-flight atom (`agent_run.clj:1695`): the swap fn refuses the claim (leaves the map unchanged, returns not-claimed) when the in-flight map already holds `W` headless entries, or when the run's fan-out group is already at `min(W, K)`. Counting and claiming become one atomic compare-and-swap, so concurrent `scan!`s compose with no assumption about event-lane serialization (`PROP-Foc-001.C1`, `PLAN-Foc-001.A2`, `R2`).
- **TASK-Foc-001.MI6 — widen `claim!`'s signature + in-flight entry.** `claim!` today takes only `id` and stores `{:phase :claimed}`, which cannot express "headless?" or "which group". Change `claim!` to take the run (or a claim descriptor derived from it) and widen the stored entry with the fields the count needs — headless flag via `interactive?` (`agent_run.clj:744`, the exemption predicate applied both when counting the running set and when admitting), and the run's `agent-run/fanout-group` / `agent-run/fanout-cap`. Thread the run through `scan!` (`agent_run.clj:1702`) unchanged in every other respect (`PLAN-Foc-001.A3`, `DN1`).
- **TASK-Foc-001.MI7 — keep in-flight readers tolerant.** The widened value is additive: every reader of the in-flight map — `in-flight-run-ids` (`agent_run.clj:227`), teardown/release paths, `supervise!` — must ignore the new keys and release a widened entry correctly on both success and failure. `recovery-deferred?` (`agent_run.clj:1125`) stays ahead of the window and composes untouched (`PLAN-Foc-001.R1`, `A3`).
- **TASK-Foc-001.MI8 — deferral is "not claimed".** A ready run that finds no slot is simply not claimed this pass: it keeps `agent-run/phase "pending"`, writes no attribute, arms no timer. A completion closes its strand → `on-event` → `scan!` (`agent_run.clj:1713`) re-admits the next ready run for free. Add nothing durable (`PROP-Foc-001.C1`, `NG4`; `PLAN-Foc-001.A4`).
- **TASK-Foc-001.MI9 — window tests (V1).** In `test/skein/agent_run_test.clj`, register a gate harness (`defharness!` real `sh`) whose argv blocks until the test releases a per-run sentinel/FIFO, so a run stays `running` exactly until released — no timing assumption. After each `scan!`-triggering mutation settle with `skein.test.alpha/await-quiescent!`, then assert `in-flight-run-ids` for the exact admitted count; for "a slot never opened" poll `skein.api.spool.alpha/poll-until-deadline!` with a fail-loud budget. Cases (`PLAN-Foc-001.V1`, `PROP-Foc-001.P6`): (1) ceiling 2, 5 gated runs → exactly 2 in-flight, 3 pending; (2) release one → its close re-admits a 3rd, width never exceeds 2 across the full drain; (3) ceiling 4, `fanout-cap 2` group → at most 2 run, `fanout-cap 20` group runs 4 wide (`min` never exceeds `W`); (4) N interactive `running` + ceiling 2 → two headless gated runs still admit; (5) `set-fanout-ceiling!` rejects zero/negative/non-integer loudly, default with no setter is 4, and a `state-version` reload preserves a configured ceiling through `migrate-state`. **PH1 has no delegation flag yet**: case (3) must stamp `agent-run/fanout-group` / `agent-run/fanout-cap` manually as fixtures via the `spawn-run!` `:attrs` path (`agent_run.clj:1849`, merge at `:1901-1913`) — the flag surface arrives in PH2 (synthesis note `vo7pb` item 4). Cases (1)/(2) exercise the plain ceiling and need no group attrs.

## TASK-Foc-001.P3 Done when

- **TASK-Foc-001.DW1:** `scan!` admits at most `W` headless runs workspace-wide and at most `min(W, K)` per stamped group; deferred runs stay `pending` with no new attribute; completions re-admit through `on-event`; interactive runs consume no slots (`PROP-Foc-001.DW1`, `DW2`).
- **TASK-Foc-001.DW2:** `set-fanout-ceiling!` exists, defaults to 4, fails loudly on invalid input, and survives `reload!` through the bumped `state-version` (4) / `migrate-state`.
- **TASK-Foc-001.DW3:** Cold gate green: `clojure -M:test --shard B` (`skein.agent-run-test` is add-libs shard B; a focused ns run is rejected). Warm output never satisfies this gate.
- **TASK-Foc-001.DW4:** `make fmt-check lint reflect-check` pass; `git status --short` shows no generated SQLite/runtime artifacts.
- **TASK-Foc-001.DW5:** One atomic commit on `fanout-cap`, why-focused HEREDOC message. No push, no merge.

## TASK-Foc-001.P4 Out of scope

- **TASK-Foc-001.OS1:** The `--max-concurrent` flag and any group-minting/stamping in delegation verbs — Task 2 owns `delegation.clj` (`PLAN-Foc-001.PH2`). PH1 only _reads_ group/cap attributes the window enforces; it stamps them only as test fixtures.
- **TASK-Foc-001.OS2:** README / `*.api.md` prose — Task 3 owns the contract docs and api-docs regen (`PLAN-Foc-001.PH3`). Ship `set-fanout-ceiling!`'s docstring here, but do not run `make api-docs` or hand-edit `*.api.md`.
- **TASK-Foc-001.OS3:** The SPEC-004.C95 merge into `daemon-runtime.md` — a land-time promotion step, not an implementation slice (`PLAN-Foc-001.AA9`).
- **TASK-Foc-001.OS4:** Any `depends-on`/graph edge, durable queue, timer, per-harness quota, cross-workspace budget, or cross-group fairness (`PLAN-Foc-001.TC6`, `Q1`).

## TASK-Foc-001.P5 References

- **TASK-Foc-001.REF1:** `PLAN-Foc-001.PH1`, `A1`–`A4`, `AA1`, `AA2`, `AA4`, `V1`, `R1`–`R3`, `TC2`, `TC3`, `TC5` (setter convention, engine seam anchors, test-harness anchors).
- **TASK-Foc-001.REF2:** `PROP-Foc-001.C1` (window at `claim!`), `C2` (ceiling in trusted config), `C4` (precedence + scheduler preservation), `D1` (interactive exemption), `P6` (validation cases).
- **TASK-Foc-001.REF3:** `DELTA-Foc-001.CC2` (state-version discipline); `SPEC-004.C95` (the trusted-config-state pattern this mirrors); `set-read-limit!` at `batteries.clj:81`.
- **TASK-Foc-001.REF4:** Review synthesis `vo7pb` (strand `elo7d`): item 2 (state-version target = 4), item 3 (migrate-state select-keys simplification + merge-order check), item 4 (PH1 group tests stamp attrs manually).
