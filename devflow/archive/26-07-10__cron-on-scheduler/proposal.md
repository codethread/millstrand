# Cron on Scheduler Proposal

**Document ID:** `PROP-cron-on-scheduler-001` **Last Updated:** 2026-07-10 **Related RFCs:** [`RFC-009 Weaver Scheduler Primitive`](../../rfcs/2026-06-29-weaver-scheduler.md) **Related root specs:** [`SPEC-004 Weaver Runtime`](../../specs/daemon-runtime.md)

## PROP-cron-on-scheduler-001.P1 Problem

RFC-009 shipped the weaver-owned durable scheduler primitive (`skein.api.scheduler.alpha`) as the single clock trigger into the weaver's serialized async lane, and deliberately framed recurrence, cron, and jitter as userland concerns layered on top of the durable wake (RFC-009.G6, RFC-009.NG2).

The cron spool did not layer on that primitive. It built a parallel timing substrate: its own `ScheduledThreadPoolExecutor`, its own manual-clock pump, and in-memory next-fire state. That state is not durable, so a registered job's cadence is lost whenever the weaver stops — the opposite of the durability the primitive exists to provide.

The cost surfaces downstream and in operator ergonomics:

- Downstream jobs hand-roll durability. The in-repo `.skein/nvd_scan.clj` job carries a dedicated `initial-delay-fn` seed path that reconstructs its next fire from external GitHub issue state after every restart, precisely because cron forgets when it should fire next.
- The weaver now has two timing substrates and two introspection surfaces for one concept. Operators must know whether a scheduled thing is a scheduler wake or a cron job to find and inspect its next fire.
- Cron never adopted the primitive's at-least-once delivery framing, so job authors have no stated contract for duplicate tolerance.

A latent primitive defect surfaced during plan review, exposed by cron as the first real consumer of the self-reschedule pattern. The scheduler retires a delivered wake by key alone (`retire-wake!` DELETEs `WHERE key = ?`), so when a fire-handler schedules its own next `cron/<id>` wake from inside the handler (same key, replace semantics — the pattern `SPEC-004.C101` explicitly allows), that freshly persisted replacement is deleted the instant the handler returns and the delivery completes — cadence dies after a single fire, and the failure path (`fail-wake!`) shares the defect. Retirement was simply never given the generation discipline `SPEC-004.C102` already applies to the delivery-attempt increment. This feature therefore folds in one narrow, spec-consistent fix to the primitive (see `.NG1`) so the self-reschedule cadence it depends on actually survives.

## PROP-cron-on-scheduler-001.P2 Goals

- **PROP-cron-on-scheduler-001.G1:** A registered cron job's cadence survives weaver restart and reload — the next fire is durable state, not lost when the JVM stops.
- **PROP-cron-on-scheduler-001.G2:** The weaver has one timing substrate; cron becomes the userland recurrence layer over the scheduler wake primitive rather than a competing one.
- **PROP-cron-on-scheduler-001.G3:** Scheduler introspection (`skein.api.scheduler.alpha` pending/history) is authoritative for all armed timing, cron jobs included; cron's own listing remains a job-status projection (registration, last outcome, failures), never a second timing view.
- **PROP-cron-on-scheduler-001.G4:** Cron jobs have a documented at-least-once delivery contract, so job authors write idempotent handlers.
- **PROP-cron-on-scheduler-001.G5:** In-repo cron consumers no longer hand-roll durability; the `nvd_scan` job drops its seed-machinery workaround.

## PROP-cron-on-scheduler-001.P3 Non-goals

- **PROP-cron-on-scheduler-001.NG1:** No reshaping of the scheduler primitive. `skein.api.scheduler.alpha` and its durable wake storage and dispatch (RFC-009) stay as shipped in model and surface; this feature consumes them, it does not reshape them — with one narrow, spec-consistent exception. Wake retirement (`complete`/`fail`) becomes generation-aware, mirroring the generation-specific delivery-attempt increment `SPEC-004.C102` already defines, because the self-reschedule pattern `SPEC-004.C101` explicitly allows — which this feature is the first to use — exposes a latent retire-by-key defect (`.P1`). That is a bug fix inside the existing contract, not a new capability; it is staged as a `SPEC-004` delta (see [`specs/`](./specs/)) and lands in its own plan phase, and the primitive's model, storage, and API surface are otherwise untouched.
- **PROP-cron-on-scheduler-001.NG2:** No cron-syntax or calendar expressions. Cadence stays interval-plus-jitter; DST/calendar semantics remain out of scope (RFC-009.NG2).
- **PROP-cron-on-scheduler-001.NG3:** Jitter stays a cron-side userland concern; it is not pushed into the primitive (RFC-009.NG2).
- **PROP-cron-on-scheduler-001.NG4:** No workflow timer or deadline gates. Those remain the `RFC-009.C8` follow-up and layer on the primitive separately.
- **PROP-cron-on-scheduler-001.NG5:** No exactly-once delivery guarantee. Cron inherits the primitive's at-least-once semantics; handlers remain idempotent (RFC-009.NG4).
- **PROP-cron-on-scheduler-001.NG6:** No public mutating CLI verb for cron registration. Registration stays trusted config/REPL, as it is today.

## PROP-cron-on-scheduler-001.P4 Proposed scope

- **PROP-cron-on-scheduler-001.S1:** Cron becomes the userland recurrence layer over the durable scheduler wake, so a registered job's cadence survives weaver restart and reload.
- **PROP-cron-on-scheduler-001.S2:** Collapse to a single weaver timing substrate — retire cron's parallel executor timing, clock pump, and in-memory next-fire state in favour of the scheduler's durable wakes and one introspection surface.
- **PROP-cron-on-scheduler-001.S3:** State an at-least-once delivery contract for cron jobs in the cron spool's reference documentation, including duplicate tolerance guidance for job authors.
- **PROP-cron-on-scheduler-001.S4:** Migrate the in-repo `nvd_scan` consumer off its hand-rolled `initial-delay-fn` durability workaround, as the reference proof that the substrate now carries durability.
- **PROP-cron-on-scheduler-001.S5:** The cron contract states the split between wake delivery and job completion: a wake is delivered (and the next fire persisted) when the job run is handed off the delivery lane, while the job run's own outcome — success or failure — is recorded in cron's job status and failure log, and never interrupts the cadence. Duplicate tolerance and failure diagnosis are framed against this split.

## PROP-cron-on-scheduler-001.P5 Open questions

- **PROP-cron-on-scheduler-001.Q1:** Resolved into `PROP-cron-on-scheduler-001.S5` during proposal review: the wake-delivery vs job-completion split is contract text, not an open design question.
- **PROP-cron-on-scheduler-001.Q2:** What are the config-equality semantics for re-registration? Which fields must be unchanged for a reload to preserve the pending wake (and its remaining cadence) rather than reset it?
