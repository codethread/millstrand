# Cron-on-Scheduler Runtime Delta

**Document ID:** `DELTA-cron-on-scheduler-runtime-001` **Status:** Promoted (`SPEC-004.C102` amended; `SPEC-004.C102b` added — `PLAN-cron-on-scheduler-001.PH5`/`.V6`) **Feature:** `cron-on-scheduler` **Root spec:** [`SPEC-004 Weaver Runtime`](../../../specs/daemon-runtime.md) **Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../../rfcs/2026-06-29-weaver-scheduler.md) **Related proposal:** [`PROP-cron-on-scheduler-001`](../proposal.md)

## DELTA-cron-on-scheduler-runtime-001.P1 Summary

Extend the generation discipline that `SPEC-004.C102` already gives the delivery-attempt increment so it also governs wake retirement. Completing or failing a delivered wake must retire exactly the generation that was delivered — the wake identified by its key **and** the delivered wake instant — not the key alone, and must record its history entry from the row read before the handler ran, not from whatever pending row currently holds the key. Without this, a handler that follows the `SPEC-004.C101`-blessed pattern of scheduling its own next same-key wake loses that freshly armed replacement the moment its delivery completes, because retire-by-key deletes the replacement generation. This is a bug fix inside the existing scheduler contract, exposed by cron as the first consumer of the self-reschedule pattern; it adds no capability and changes no API surface.

## DELTA-cron-on-scheduler-runtime-001.P2 Contract changes

- **DELTA-cron-on-scheduler-runtime-001.CC1:** Amend `SPEC-004.C102` to name retirement as the second generation-sensitive transition alongside the delivery-attempt increment, cross-referencing `SPEC-004.C102b`: the increment claims the exact delivered generation, and retirement releases that same generation, so a wake rescheduled in the delivery window is neither miscounted nor clobbered.
- **DELTA-cron-on-scheduler-runtime-001.CC2:** Add `SPEC-004.C102b`: Wake retirement is generation-aware. When a delivered wake is completed or failed, the scheduler retires exactly the delivered generation — the wake identified by its key _and_ the delivered wake instant — and records its history entry from the delivered row (the row read before handler invocation), never from whatever pending row currently holds the key. A same-key replacement armed during delivery (the `SPEC-004.C101`-blessed self-reschedule) is a distinct generation: retirement leaves it untouched — no delete, no arming disturbance — and the post-fire re-arm (`SPEC-004.C102`) governs it. If the delivered generation has already vanished or been superseded by retirement time, retirement removes no pending row but still records the delivered fire in history, so at-least-once delivery stays observable. User-initiated cancel-by-key is not a delivery retirement and stays key-based: it cancels whatever generation currently holds the key.

## DELTA-cron-on-scheduler-runtime-001.P3 Design decisions

### DELTA-cron-on-scheduler-runtime-001.D1 Retire the delivered generation, not the key

`SPEC-004.C102` already identifies a wake generation by its `(key, wake instant)` pair and scopes the delivery-attempt increment to exactly that generation. Retirement is the mirror transition and was the one place the key-only shortcut survived. Scoping the completing/failing delete to `(key, delivered wake instant)` — and sourcing the history row from the already-re-read delivered row rather than a fresh key lookup — closes the self-reschedule clobber without touching the wake model, storage layout, or API surface. The delivered row is available at retirement because due dispatch re-reads it before invoking the handler; retirement reuses that row rather than reading the key again.

### DELTA-cron-on-scheduler-runtime-001.D2 Cancel-by-key stays key-based

User-initiated cancellation (`deregister!` / cancel) targets "whichever wake owns this key," not a specific fired generation, so it must remain key-scoped. Only the delivery-completion and delivery-failure paths, which own a specific delivered generation, become generation-aware. Keeping cancel key-based preserves its loud-on-missing-key contract and avoids a caller having to name a generation it never observed.

## DELTA-cron-on-scheduler-runtime-001.P4 Open questions

- **DELTA-cron-on-scheduler-runtime-001.Q1:** None. The delivered generation is fully determined by the envelope's key and wake instant, both already carried into `run-fire!`; no new state or surface is required.
