# Deterministic Test Time Plan

**Document ID:** `PLAN-Dtt-001` **Feature:** `deterministic-test-time` **Proposal:** [proposal.md](./proposal.md) **RFC:** [Deterministic Test Time RFC-Dtt-001](./rfcs/2026-07-09-deterministic-test-time.md) **Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md), [repl-api.md](../../specs/repl-api.md), [alpha-surface.md](../../specs/alpha-surface.md) **Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) **Status:** Shipped **Last Updated:** 2026-07-09

## PLAN-Dtt-001.P1 Goal and scope

Build the two control seams RFC-Dtt-001 accepted — a runtime-owned clock component and an event-lane quiescence primitive — then migrate the timer-driven and event-delivered serial suites onto them so they stop waiting on real wall-clock time and real async delivery, and graduate the eight suites from the `serial-namespaces` island to `parallel-namespaces`. The outcome is a serial island shrunk toward ~15s (bench + singleton-semantics only) and the RFC-016.P7 timing flakes eliminated by construction. No test coverage changes — only how tests wait. See the proposal for why it matters.

## PLAN-Dtt-001.P2 Approach

- **PLAN-Dtt-001.A1 (two seams first, migrations after):** Land both seams before touching any suite, then migrate suite-by-suite, then move the island vectors last. Each seam and each suite migration is an independently verifiable slice validated with the focused runner mode `clojure -M:test <ns...>` (in-process, named namespaces; landed on main `2bf5f80`). Only the final acceptance slice runs the full locked suite `clojure -M:test`.

- **PLAN-Dtt-001.A2 (REC1 / PROP-Dtt-001.Q1 — advance mechanism: clock-driven due-check + explicit pump, NOT a virtual-time executor):** Timer subsystems read the runtime clock for due-detection and expose a due-check/pump entry point that `advance!` drives. In production the real `ScheduledExecutorService` still arms and fires on real time; under a manual clock, `advance!` moves the clock and synchronously invokes each clock-consuming subsystem's pump to release now-due work. **Why:** the scheduler already proves this exact shape (`scheduler.clj` `dispatch-due!` at `:186`, `dispatch-due!*` at `:156`, clock read at `:81`), so it is the lighter abstraction (RFC-Dtt-001.A3's virtual-time executor is heavier and still would not give ad-hoc `(Instant/now)` reads a deterministic value); it keeps production timer behaviour byte-for-byte unchanged (RFC-Dtt-001.G5); and it reuses a mechanism already under test rather than introducing a new executor abstraction across cron and the scheduler. Concretely: `advance!` moves the runtime clock, then runs the registered clock-consumers' pumps in registration order and returns only after now-due work has been enqueued/run synchronously (DELTA-Dtt-001.CC3). Cron restructures its executor arming so due-ness derives from the runtime clock and the pump fires due jobs; it does not replace its executor with a manual one.

- **PLAN-Dtt-001.A3 (REC7 / PROP-Dtt-001.Q3 — off-lane completion: layered per-owner settle, NOT a shared process-completion await):** `await-quiescent!` settles the shared event lane only. Each off-lane owner keeps its own completion signal layered on top: reed uses an outcome-keyed `poll-until` for the short local `:shell` subprocess, chime joins its per-dispatch notifier threads via the existing `await-notifier-threads!` (`chime_test.clj:42-51`), and treadle awaits its shuttle run reaching a terminal `:shuttle/phase` / `:treadle/delivered` state. **Why:** a shared process-completion await is more permanent runtime surface generalizing to arbitrary worker pools (TEN-004), while the three owners already have precise, cheap settle signals; layering them keeps `await-quiescent!` honestly scoped to the lane so it never reports settled while a private-pool task is still running (DELTA-Dtt-001.CC5, TEN-003). Applied uniformly: every off-lane suite layers its own await; no new blessed off-lane primitive ships.

- **PLAN-Dtt-001.A4 (worker-idle flag before dequeue):** `await-quiescent!` reads an atomic dispatch-in-progress flag the single event worker (`runtime.clj:55-96`) raises _before_ it dequeues an event and lowers after the handler returns, combined with queue-empty — never a bare queue-empty check, which could return while a just-claimed dispatch is still in flight (DELTA-Dtt-001.CC4).

## PLAN-Dtt-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dtt-001.AA1 | `src/skein/core/weaver/runtime.clj` | Add `:clock` runtime-map slot (default `#(Instant/now)`), a core-tier clock accessor, a clock-consumer pump registry, and the event-worker dispatch-in-progress flag. |
| PLAN-Dtt-001.AA2 | `src/skein/api/runtime/alpha.clj` | Accrete `(now runtime)` reading the runtime clock. |
| PLAN-Dtt-001.AA3 | `src/skein/api/events/alpha.clj` | Accrete `(await-quiescent! runtime [opts])` over the worker-idle flag + queue-empty. |
| PLAN-Dtt-001.AA4 | `src/skein/test/alpha.clj` | Accrete `(set-clock! runtime clock-fn)` and `(advance! runtime duration)`. |
| PLAN-Dtt-001.AA5 | `src/skein/core/weaver/scheduler.clj` | Collapse private `:clock`/`set-clock!` into the runtime clock; register a pump; bump `scheduler-state-version`. |
| PLAN-Dtt-001.AA6 | `spools/cron/src/skein/spools/cron.clj` | Read `runtime.alpha/now` for due-ness; restructure executor arming so the pump fires due jobs; note the clock dependency in `spools/cron.md`. |
| PLAN-Dtt-001.AA7 | `spools/shuttle/src/skein/spools/treadle.clj`, `spools/src/skein/spools/reed.clj`, `spools/chime/src/skein/spools/chime.clj` | No engine behaviour change; test migration consumes existing settle signals (reed `poll-until`, chime `await-notifier-threads!`, treadle terminal-state await). |
| PLAN-Dtt-001.AA8 | `test/skein/*` (scheduler-*, cron, treadle, reed, chime, weaver) | Replace real-timer waits and fixed sleeps with the seams. |
| PLAN-Dtt-001.AA9 | `test/skein/test_runner.clj` | Move the eight suites from `serial-namespaces` to `parallel-namespaces`; trim serial comments to bench + singleton-semantics; update RFC-016 references. Final slice only. |

## PLAN-Dtt-001.P4 Contract and migration impact

- **PLAN-Dtt-001.CM1:** Durable contract changes are staged in the three feature spec deltas: runtime clock component + `runtime.alpha/now` + `events.alpha/await-quiescent!` + scheduler clock-consumer collapse (DELTA-Dtt-001), `test.alpha` manual-clock controls (DELTA-Dtt-002), and the alpha-surface classification (DELTA-Dtt-003). Dropping the scheduler's private `:clock` from spool-state bumps `scheduler-state-version` (SPEC-004.C95/C96). Production behaviour is unchanged; cron's spool doc (`spools/cron.md`, a userland spool contract, not a root spec) gains the clock-dependency note at implementation time.

## PLAN-Dtt-001.P5 Implementation phases

### PLAN-Dtt-001.PH1 Clock seam

Outcome: the runtime owns a clock component (default real wall clock) read via a core accessor and the blessed `runtime.alpha/now`; `test.alpha/set-clock!`/`advance!` install and pump a manual clock; the scheduler consumes the runtime clock (REC5) with its version bumped. Scheduler suites stay green through the collapse.

### PLAN-Dtt-001.PH2 Event-lane quiescence seam

Outcome: `events.alpha/await-quiescent!` blocks on the worker-idle flag + queue-empty and throws on timeout, with direct coverage of the not-report-early guard.

### PLAN-Dtt-001.PH3 Scheduler and cron migration

Outcome: the scheduler real-timer remnants and cron move onto `advance!`/`await-quiescent!` and clock-driven due-ness; each suite deterministic in focused mode.

### PLAN-Dtt-001.PH4 Treadle and reed migration

Outcome: treadle on-lane settles via `await-quiescent!` plus a shuttle-run terminal await; reed on-lane settles via `await-quiescent!` plus outcome-keyed `poll-until` for the subprocess.

### PLAN-Dtt-001.PH5 Chime migration

Outcome: chime's fixed sleeps replaced by `await-notifier-threads!` + `await-quiescent!`; PROP-Dtt-001.Q2 resolved. Default: `skein.chime-test` graduates whole. The runner schedules whole namespaces only, so if an assertion genuinely resists the settle signal it is split into a dedicated namespace that stays in `serial-namespaces` with a why-serial comment (precedent: the `skein.chime-sync-test` split) — never a "serial test" inside a parallel namespace.

### PLAN-Dtt-001.PH6 Weaver-test re-evaluation

Outcome: weaver-test settles event delivery with `await-quiescent!` over its own disposable per-runtime worker, removing the remaining load-sensitive ordering waits.

### PLAN-Dtt-001.PH7 Island graduation (acceptance)

Outcome: the eight suites move `serial-namespaces` → `parallel-namespaces` in `test/skein/test_runner.clj`; serial comments trimmed to bench + singleton-semantics; RFC-016 serial-island comments updated. Full locked suite green.

## PLAN-Dtt-001.P6 Validation strategy

- **PLAN-Dtt-001.V1:** Each seam and each suite migration is proven with the focused runner `clojure -M:test <ns...>` naming exactly that slice's namespaces (see each task's Done-when). Production behaviour parity: the default clock returns real instants and real executors fire on real time (no test asserts wall delay is gone in production).
- **PLAN-Dtt-001.V2:** The final acceptance slice runs the full locked suite `clojure -M:test` after moving the island vectors, proving the eight suites pass under parent parallel load. There is no `flock` on this host; the coordinator serializes full-suite runs against sibling agents (do not add a lock to the task).

## PLAN-Dtt-001.P7 Risks and open questions

- **PLAN-Dtt-001.R1 (test_runner.clj island-vector contention):** p1 card `fjo2v` (with-redefs DI, branch `with-redefs-di`, already merged at `1720a00`) edits the same `serial-namespaces`/`parallel-namespaces` vectors in `test/skein/test_runner.clj` and the shared `spools/test_support.clj` helpers. The rebase/merge-order risk is confined to those island vectors and `test_support.clj`; PLAN-Dtt-001.PH7 concentrates all island-vector edits into the single final slice to keep the conflict surface small and rebasable. Mitigation: rebase onto the latest main before PH7 and resolve the vectors as a merge of both graduations, never a discard.
- **PLAN-Dtt-001.R2 (advance/pump reentry):** `advance!` must run pumps synchronously without re-entering the shared event lane in a way that deadlocks; scheduler `dispatch-due!` already enqueues onto the lane, so pump-then-`await-quiescent!` is the settle order. Mitigation: pump enqueues, caller awaits quiescence — never pump-under-lane-lock.
- **PLAN-Dtt-001.Q1 (Q2 chime graduation):** Resolved in PH5 as a task decision with a deterministic fallback (keep a resistant assertion serial), so it does not block task generation.

## PLAN-Dtt-001.P8 Task context

- **PLAN-Dtt-001.TC1:** MVP goal — the eight timer/event serial suites become deterministic on two seams and graduate to parallel, shrinking the serial island toward ~15s with the RFC-016.P7 flakes eliminated by construction. Read before slicing: RFC-Dtt-001 (decisions A1 time seam, B1 tier split, C1 quiescence, REC5 scheduler consumer, REC6 migration table, REC7 off-lane boundary), the three spec deltas under `specs/`, and the affected code named in PLAN-Dtt-001.P3. Two plan-level mechanism choices are fixed: **clock-driven due-check + pump** (PLAN-Dtt-001.A2), and **layered per-owner off-lane settle** (PLAN-Dtt-001.A3). Seams land before migrations; the island-vector edit and the full-suite run are the final acceptance slice only; every other slice validates in focused `clojure -M:test <ns...>` mode. Coordinator serializes full-suite runs (no `flock` on this host). bench and the singleton-semantics suites (`repl-test`, `weaver-publication-test`, `peers-test`, `userland-test`) stay serial.

## PLAN-Dtt-001.P9 Developer Notes

### PLAN-Dtt-001.DN1 Spec + plan + task authoring — 2026-07-09

- Authored the three spec deltas, this plan, and the nine-task queue in one pass under strand `vh4sq`. Both plan-level mechanism choices are decided here (A2 pump, A3 per-owner settle); the task queue assumes them. If review overturns either choice, the affected migration tasks (3–8) and DELTA-Dtt-001.CC3/CC5 need revisiting before implementation.

### PLAN-Dtt-001.DN2 Task 001 clock seam — 2026-07-09

- Landed the runtime-owned clock seam with `runtime.alpha/now`, test-side `set-clock!` and `advance!`, a runtime clock-pump registry, and the scheduler as the first pump consumer. The scheduler private clock was removed and its spool-state version bumped for the shape change. Focused scheduler/test-alpha namespaces passed via the in-process classpath invocation because the `:test` runner rejects namespace argv; `make fmt-check lint reflect-check` was clean.

### PLAN-Dtt-001.DN3 Task 002 event-lane quiescence — 2026-07-09

- Added the event-worker dispatch-in-progress flag and `events.alpha/await-quiescent!`, with timeout failures and default test-support budget lookup. The worker raises the flag around a non-blocking claim/dispatch so idle lanes do not look busy while an empty-queue in-flight dispatch cannot report settled. Added `skein.events-quiescence-test` to the parallel batch as a per-runtime lane test. Focused weaver/quiescence namespaces passed via the same in-process classpath invocation, and `make fmt-check lint reflect-check` was clean.

### PLAN-Dtt-001.DN4 Task 003 scheduler suite migration — 2026-07-09

- Migrated the scheduler suite timer remnants to fixed manual clocks, `test.alpha/advance!`, and `events.alpha/await-quiescent!` where work actually fires. The restart-rearm coverage keeps its promise await after restart because the real startup rearm can be scheduled just after an immediate quiescence read; the task's named wall-clock wake paths are still deterministic. Focused scheduler namespaces passed via the in-process classpath invocation, and `make fmt-check lint` was clean.

### PLAN-Dtt-001.DN5 Task 004 cron clock migration — 2026-07-09

- Cron's due-ness now reads `runtime.alpha/now`; `schedule-fire!` stamps `:next-fire-at` off the runtime clock and a registered clock-pump (`fire-due!`) cancels the outstanding real future and fires any job whose `:next-fire-at` is not after the clock, so `advance!` releases due jobs synchronously with no separate settle wait needed (cron calls the job body in-line rather than through the shared event queue, unlike the scheduler). `install!` (re)registers the pump. Test suite drives fires with `test.alpha/set-clock!`/`advance!` instead of real executor-timer polling. Noted the clock dependency in `spools/cron/README.md` (the task text's `spools/cron.md` does not exist; the userland spool contract for cron is `spools/cron/README.md`). Focused `skein.cron-test` passed via the in-process classpath invocation (6 tests, 1022 assertions, 0 failures); `make fmt-check lint reflect-check` was clean and `make api-docs` picked up the changed cron docstrings.

### PLAN-Dtt-001.DN6 Task 005 treadle migration — 2026-07-09

- Migrated treadle's gate-outcome waits to layered deterministic settling: the real shuttle-run terminal markers are awaited first, then `events.alpha/await-quiescent!` settles treadle's on-lane delivery/error/supersede reactions before assertions read dependent state. The bare sleeps in gate non-spawn paths are now lane quiescence awaits, with no treadle engine changes. Focused `skein.treadle-test` passed via the in-process classpath invocation, and `make fmt-check lint` was clean.

### PLAN-Dtt-001.DN7 Task 006 reed migration — 2026-07-09

- Migrated reed's dispatch waits to `events.alpha/await-quiescent!` after mutations that can make `:shell` gates ready, while keeping the existing outcome-keyed `poll-until` helper solely for the real off-lane subprocess completion. Reed engine behavior and worker-pool semantics were unchanged. Focused `skein.spools.reed-test` passed via the in-process classpath invocation, and `make fmt-check lint` was clean.

### PLAN-Dtt-001.DN8 Task 007 chime migration — 2026-07-09

- Replaced chime's fixed inter-poll sleeps with lane quiescence followed by the existing notifier-thread join, leaving the real notifier subprocess and chime engine untouched. Q2 resolved without a split: all notifier assertions tolerate the deterministic settle signal as a whole `skein.chime-test` namespace. Focused chime tests passed via the in-process classpath invocation, and `make fmt-check lint` was clean.

### PLAN-Dtt-001.DN9 Task 008 weaver-test migration — 2026-07-09

- Replaced weaver-test's bare event-delivery sleeps with `events.alpha/await-quiescent!` against each fixture's unpublished disposable runtime, preserving the existing fixture drain and exact captured-event assertions. Non-event sleeps and count-based polling helpers were left outside this timing-seam scope. Focused `skein.weaver-test` passed via the in-process classpath invocation, and `make fmt-check lint` was clean.

### PLAN-Dtt-001.DN10 Task 009 island graduation — 2026-07-09

- Graduated the eight deterministic scheduler/cron/treadle/reed/chime/weaver suites from the serial island to the parent parallel batch and trimmed the serial comments to bench plus singleton-semantics suites only. Full `clojure -M:test` passed with the graduated suites reporting `:parent/parallel` and aggregate `{:test 736 :pass 5430 :fail 0 :error 0}`; `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` were also clean. No generated SQLite or runtime-metadata artifacts remained in `git status --short`.

### PLAN-Dtt-001.DN11 Finish/archive — spec promotion — 2026-07-09

- Shipped scope: two runtime control seams — a runtime-owned clock component (`skein.api.runtime.alpha/now` read; `skein.test.alpha/set-clock!`/`advance!` controls; a clock-pump registry `advance!` drives) and an event-lane settle primitive (`skein.api.events.alpha/await-quiescent!` over a dispatch-in-progress flag raised before the worker claims an event) — plus six suite migrations (scheduler, cron, treadle, reed, chime, weaver) onto them and the island graduation. The scheduler collapsed its private clock onto the shared runtime clock (`scheduler-state-version` 1→2); cron reads the runtime clock for due-ness and registers a pump; neither reed nor chime became a clock consumer (they settle via off-lane signals layered on `await-quiescent!`). The serial island is now bench plus the singleton-semantics suites only (`repl`/`userland`/`weaver-publication`/`peers`/`bench`).
- Promoted the three spec deltas into their root specs (DELTA-Dtt-001→SPEC-004.C1/C1a runtime clock, C64/C74b event-lane quiescence, C102a scheduler-as-clock-consumer; DELTA-Dtt-002→SPEC-003.C17 `now` read, C28/C28a `test.alpha` controls, the events.alpha P4 enumeration; DELTA-Dtt-003→SPEC-005.C5a classification) after verifying each claim against the shipped code, and marked each delta `Status: Merged`.
- Focused-runner-timing caveat (recorded in the AFK step note): the `:test` runner rejects namespace argv, so each slice's focused validation ran the named namespaces through an in-process classpath invocation rather than `clojure -M:test <ns...>`; the final acceptance slice ran the full locked `clojure -M:test`.
