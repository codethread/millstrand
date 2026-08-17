# Deterministic Test Time Proposal

**Document ID:** `PROP-Dtt-001` **Last Updated:** 2026-07-09 **Related RFCs:** [Deterministic Test Time RFC-Dtt-001](../../rfcs/2026-07-09-deterministic-test-time.md) (the two-seam design decision), [Test Concurrency RFC-016](../../rfcs/2026-07-03-test-concurrency.md) (the serial islands this feature pays down) **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)

## PROP-Dtt-001.P1 Problem

RFC-016 parallelized the suite but left a ~55.7s serial island (measured 2026-07-09, card `tkmvw` note `qtdqa`). About 38–40s of that is tests waiting on real wall-clock time and real async delivery — real executor timers (scheduler suites, cron), hard sleeps polling async gate outcomes (treadle, reed), notifier-worker settling (chime), and event-bus delivery-order assertions under load (weaver-test). The wait is inherent to how these suites synchronize, so it also produces the timing-budget flakes RFC-016.P7 catalogues, which burn delegated AFK runs.

## PROP-Dtt-001.P2 Goals

- **PROP-Dtt-001.G1:** Make the timer-driven and event-delivered suites deterministic so they stop waiting on wall-clock time and real async delivery.
- **PROP-Dtt-001.G2:** Graduate those suites from the serial island to the parallel batch, shrinking the island toward ~15s (bench + singleton-semantics only) and dropping the full-suite critical path toward the shard-A floor.
- **PROP-Dtt-001.G3:** Eliminate the RFC-016.P7 timing flakes by construction rather than by widening budgets.

## PROP-Dtt-001.P3 Non-goals

- **PROP-Dtt-001.NG1:** No change to what any test covers — same subsystems, same assertions; only how tests wait changes.
- **PROP-Dtt-001.NG2:** No CI or `-M:smoke` changes.
- **PROP-Dtt-001.NG3:** Bench's container-engine subprocess tests stay real and serial.
- **PROP-Dtt-001.NG4:** The durable scheduler's persistence and at-least-once semantics (RFC-009) are unchanged; its `wake-at` becomes a consumer of the shared clock, nothing more.

## PROP-Dtt-001.P4 Proposed scope

- **PROP-Dtt-001.S1:** A runtime-scoped time source that timer-driven code reads instead of `(Instant/now)`, with a manual-clock test control that advances time and releases now-due work deterministically.
- **PROP-Dtt-001.S2:** An event-lane quiescence primitive that blocks until asynchronous delivery has settled and fails loudly on timeout, replacing sleep-and-poll for event-delivered outcomes. It settles the shared event lane only; off-lane subprocess completion (reed's `:shell` process, chime's per-dispatch notifier threads, a treadle gate's shuttle run reaching a terminal state) keeps a completion signal layered on top, whose mechanism is PROP-Dtt-001.Q3. chime carries no timer and joins its notifier threads to settle, so it is scoped as event-lane + worker-settle work, not a clock consumer.
- **PROP-Dtt-001.S3:** Migration of the timer-driven and event-delivered serial suites onto the two seams, and their move into `parallel-namespaces`, keeping bench and the singleton-semantics suites serial.

The seam placement, the exposed API surface and its TEN-004 justification, the scheduler-consumer boundary, and the per-suite migration table are decided in RFC-Dtt-001; implementation sequencing belongs in the feature plan.

## PROP-Dtt-001.P5 Open questions

- **PROP-Dtt-001.Q1:** For subsystems arming real `ScheduledExecutorService` timers (cron, scheduler executor), does `advance!` drive a manual/virtual-time executor, or a clock-driven due-check plus an explicit pump (the shape the scheduler already models)? Fixed as a plan-level choice in RFC-Dtt-001.REC1; the plan must pick one and apply it uniformly.
- **PROP-Dtt-001.Q2:** Does any `chime` notifier-subprocess assertion resist a notifier-worker settle signal and have to stay serial, or do all chime tests graduate once the fixed sleeps are replaced?
- **PROP-Dtt-001.Q3:** For off-lane subprocess completion (reed's `:shell` process, chime's notifier daemon threads, treadle's shuttle-run terminal state), does each owning path layer its own completion await on top of `await-quiescent!`, or does one shared process-completion await generalize the lane hook to off-lane worker pools? Fixed as a plan-level choice in RFC-Dtt-001.REC7; the plan picks one and applies it uniformly.
