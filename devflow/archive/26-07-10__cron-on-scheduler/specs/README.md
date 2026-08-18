# Spec deltas for cron-on-scheduler

**Feature:** `cron-on-scheduler`

**Proposal:** [../proposal.md](../proposal.md) (`PROP-cron-on-scheduler-001`)

**Decision:** One root spec delta — [`daemon-runtime.delta.md`](./daemon-runtime.delta.md) (`DELTA-cron-on-scheduler-runtime-001`), staging a `SPEC-004` amendment.

## What was checked

`devflow/specs/alpha-surface.md` (SPEC-005) and the scheduler section of `devflow/specs/daemon-runtime.md` (SPEC-004.P10d, `.C1a`, `.C91a`, `.C95`, `.C97`–`.C105`) were read against the accepted proposal.

## The one delta

The cron spool itself is userland, not shipped alpha surface (`SPEC-005.C4` names `spools/cron` among repo-local approved spools whose README/docs are their own contract), so cron's own contract lives in `spools/cron/README.md`

- `spools/cron.api.md`, not in a root spec — its rewrite carries no root change.

Building cron on the scheduler, however, surfaced a latent primitive defect that the proposal folds in one narrow fix for (`PROP-cron-on-scheduler-001.NG1`, `.P1`): scheduler wake retirement is key-only, so a `SPEC-004.C101`-blessed handler that schedules its own next same-key wake loses that replacement the instant its delivery completes. The fix — generation-aware retirement, mirroring the generation-specific delivery-attempt increment `SPEC-004.C102` already defines — is a change to the shipped scheduler contract, so it was staged as [`daemon-runtime.delta.md`](./daemon-runtime.delta.md) and promoted into the root spec at finish (`PLAN-cron-on-scheduler-001.PH5` / `.V6`): `SPEC-004.C102` now names retirement as the second generation-sensitive transition, and `SPEC-004.C102b` carries generation-aware retirement. No other contract in SPEC-003, SPEC-004, or SPEC-005 changes shape.

## One finish-time cleanup (not a delta)

`SPEC-004.C1a`'s non-normative parenthetical lists "the userland cron spool" as an example of a subsystem that arms its own `ScheduledExecutorService` timer off the runtime clock and registers its own clock-consumer pump. Cron no longer does that (the scheduler's pump releases cron's wakes), so that illustrative example went stale. It was an example, not a contract, and the normative clock behaviour is unchanged — no delta was warranted. The finish pass dropped the cron mention from `SPEC-004.C1a` (`PLAN-cron-on-scheduler-001.DN1`).
