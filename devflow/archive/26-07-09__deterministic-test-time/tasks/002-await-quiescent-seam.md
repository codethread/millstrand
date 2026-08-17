# Task 2: Event-lane await-quiescent! seam

**Document ID:** `TASK-Dtt-002`

## TASK-Dtt-002.P1 Scope

Type: AFK

Add the event-lane quiescence primitive over the shared event worker (RFC-Dtt-001.REC3/REC4, DELTA-Dtt-001.CC4). Lane-only; no off-lane completion here. No suite graduates in this task.

## TASK-Dtt-002.P2 Must implement exactly

- **TASK-Dtt-002.MI1:** In `src/skein/core/weaver/runtime.clj`, add an atomic dispatch-in-progress flag to the single event worker (`:55-96`): the worker raises it _before_ it dequeues an event and lowers it after the handler returns. This guards against reporting settled while a just-claimed dispatch is still in flight (TEN-003).
- **TASK-Dtt-002.MI2:** Accrete `(await-quiescent! runtime)` / `(await-quiescent! runtime opts)` onto `src/skein/api/events/alpha.clj`. It blocks until the bounded queue is empty _and_ the dispatch-in-progress flag is down, then returns. On timeout it throws an `ex-info`. The default budget comes from `skein.spools.test-support/await-budget-ms`, overridable via `:timeout-ms`.
- **TASK-Dtt-002.MI3:** Add no bare `quiescent?` predicate (RFC-Dtt-001.REC4).
- **TASK-Dtt-002.MI4:** Add direct coverage of the not-report-early guard in a new namespace `skein.events-quiescence-test` (`test/skein/events_quiescence_test.clj`): a handler mid-dispatch with an empty queue must not let `await-quiescent!` return, and a timeout must throw. It drives its own unpublished runtime, so declare it in `parallel-namespaces` with a comment noting the per-runtime event lane makes it parallel-safe.

## TASK-Dtt-002.P3 Done when

- **TASK-Dtt-002.DW1:** `clojure -M:test skein.weaver-test skein.events-quiescence-test` is green (weaver-test exercises the event lane; the new namespace covers the settle guard).
- **TASK-Dtt-002.DW2:** `make fmt-check lint reflect-check` pass for the touched namespaces.
- **TASK-Dtt-002.DW3:** The only `test/skein/test_runner.clj` change is adding `skein.events-quiescence-test` to `parallel-namespaces`.

## TASK-Dtt-002.P4 Out of scope

- **TASK-Dtt-002.OS1:** Off-lane completion signals (reed poll-until, chime notifier join, treadle shuttle await) — those live in the per-suite migration tasks.
- **TASK-Dtt-002.OS2:** Any island-vector move or suite graduation.

## TASK-Dtt-002.P5 References

- **TASK-Dtt-002.REF1:** DELTA-Dtt-001.CC4, PLAN-Dtt-001.PH2, PLAN-Dtt-001.A4.
- **TASK-Dtt-002.REF2:** RFC-Dtt-001.REC3/REC4; `runtime.clj:39,55-96`; `events/alpha.clj:50-85`; `test_support.clj:39-58` (await-budget-ms).
