# Runtime-owned Clock and deterministic polling plan

**Document ID:** `PLAN-Clp-001`

**Feature:** `clock-aware-polling` (kanban `2g75m`)

**Proposal:** [proposal.md](./proposal.md)

**RFC:** [RFC-Dtt-001 Deterministic Test Time and Async Quiescence](../../archive/26-07-09__deterministic-test-time/rfcs/2026-07-09-deterministic-test-time.md)

**Root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)

**Feature specs:** [REPL API delta](./specs/repl-api.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md), [Alpha Surface delta](./specs/alpha-surface.delta.md)

**Status:** Shipped

**Last Updated:** 2026-07-20

## PLAN-Clp-001.P1 Goal and scope

Implement the Clock contract approved on decision card `99utx`: one required Clock value governs runtime time reads and polling waits, manual polling advances deterministically, and the pre-v1 epoch-deadline helper is replaced by a relative-timeout surface. The proposal and feature deltas own the product and durable contracts; this plan owns the build sequence.

## PLAN-Clp-001.P2 Approach

- **PLAN-Clp-001.A1:** Add `skein.api.clock.alpha` as a small public-first module. The Clock is a validated capability value (`{:now-fn :sleep-fn}`), not a protocol, so a namespace reload never strands a live clock. `clock` builds one and `clock?` tests one; a real system implementation validates Duration input, reads `Instant/now`, and blocks with `Thread/sleep`.
- **PLAN-Clp-001.A2:** Change the runtime's clock slot from an atom of zero-argument functions to an atom of Clock values. Core and scheduler reads dispatch through the protocol. `runtime.alpha/clock` returns the same value and `runtime.alpha/now` remains a data-first read.
- **PLAN-Clp-001.A3:** Implement the manual Clock in `skein.test.alpha`, behind a public constructor. It is a Clock capability carrying extra control state (its virtual instant and installed runtime), so no protocol is involved. Uninstalled sleep advances virtual time and runs no pumps. Installation binds one manual Clock to one runtime; installed sleep and explicit advancement update its Instant and synchronously run that runtime's clock pumps. A zero sleep is a valid no-op that still pumps, while explicit advancement stays strictly positive. Installing the same manual Clock into another runtime and advancing a non-manual Clock fail loudly.
- **PLAN-Clp-001.A4:** Replace the spool helper with `poll-until!`. Validate the Clock and the exact five-key option shape before the loop; derive an `Instant` deadline from `:timeout-ms`; run the first check immediately; then compare the Clock's `now`, call `on-timeout` at or after the deadline, or sleep for the positive polling Duration and repeat.
- **PLAN-Clp-001.A5:** Migrate workflow and roster by obtaining `(runtime/clock runtime)` at their existing explicit-runtime boundary and deleting their `System/currentTimeMillis` deadline calculations. Preserve their timeout defaults and result shapes. Tighten their `:poll-ms` validation from non-negative to positive because a zero-duration manual sleep cannot advance a timeout.
- **PLAN-Clp-001.A6:** Promote the reviewed feature deltas into the three root specs; update the spool API namespace docstring, `docs/reference.md`, the shared-spool guide, `spools/README.md`, and caller docs; regenerate API reference from docstrings; and leave no deprecated alias for the pre-v1 helper.

## PLAN-Clp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Clp-001.AA1 | `skein.api.clock.alpha` | New validated Clock capability and real system implementation. |
| PLAN-Clp-001.AA2 | Weaver runtime and scheduler | Store Clock values, expose the runtime Clock, and route current-time reads through it. |
| PLAN-Clp-001.AA3 | `skein.test.alpha` | Manual Clock construction, installation, deterministic sleep, and explicit advancement. |
| PLAN-Clp-001.AA4 | Spool authoring, workflow, and roster | Relative-timeout polling helper and caller migration. |
| PLAN-Clp-001.AA5 | Specs, guides, and public-surface tests | Contract promotion, generated reference, and deterministic regression coverage. |

## PLAN-Clp-001.P4 Contract and migration impact

- **PLAN-Clp-001.CM1:** This is a pre-v1 clear cut: `poll-until-deadline!` and its epoch `:deadline` disappear; `poll-until!` requires a Clock and `:timeout-ms`.
- **PLAN-Clp-001.CM2:** `test.alpha/set-clock!` accepts a Clock rather than a zero-argument function. Existing repository tests move to `test.alpha/manual-clock`; no compatibility union or adapter is retained.
- **PLAN-Clp-001.CM3:** Workflow and roster `:poll-ms` become positive-integer options. Their timeout options remain non-negative, so a zero timeout still checks once and returns an immediate result or timeout.

## PLAN-Clp-001.P5 Implementation phases

### PLAN-Clp-001.PH1 Clock and runtime seam

Outcome: the new Clock public module, runtime storage/accessors, scheduler read, and manual test controls compile together; existing deterministic scheduler and cron tests use manual Clock values.

### PLAN-Clp-001.PH2 Polling and callers

Outcome: `poll-until!` has deterministic direct coverage and workflow/roster use the runtime Clock with unchanged result semantics.

### PLAN-Clp-001.PH3 Contract promotion and validation

Outcome: root specs and authored docs match the implementation, generated API reference is current, focused suites and repository quality gates pass, and no old helper reference remains outside archived feature history.

## PLAN-Clp-001.P6 Validation strategy

- **PLAN-Clp-001.V1:** Public Clock coverage limits the system implementation to `Instant` return shape and fail-loud Duration validation. Deterministic manual coverage proves installed and uninstalled sleep advancement, zero-sleep pump execution, explicit advancement, and single-runtime installation without wall waits.
- **PLAN-Clp-001.V2:** Spool-helper coverage proves success and timeout paths against a manual Clock, exact option validation, positive cadence, and no `System/currentTimeMillis` or `Thread/sleep` dependency.
- **PLAN-Clp-001.V3:** Focused cold suites cover the new clock namespace plus runtime alpha, spool alpha, notes, scheduler runtime and E2E, scheduler alpha, cron unit and E2E, workflow, and roster namespaces. All six existing `set-clock!` caller files migrate: notes replaces its mutable clock-function closure with newly installed manual Clocks at each absolute test instant; the other callers replace constant functions with manual Clocks. Existing harnesses are reused; no new timing sleeps or live services are introduced.
- **PLAN-Clp-001.V4:** `make api-docs`, `make fmt-check lint reflect-check docs-check`, the full locked Clojure suite, Go tests, and smoke form the final acceptance gate.

## PLAN-Clp-001.P7 Risks and open questions

- **PLAN-Clp-001.R1:** Re-evaluating a Clojure protocol invalidates instances created against the old interface, which would strand the clock the runtime already holds under a hot reload. The Clock is therefore a validated capability value with no generated interface, so re-evaluating the namespace leaves every live clock valid.
- **PLAN-Clp-001.R2:** Clock pumps enqueue scheduler work but do not settle unrelated event or subprocess lanes. The manual Clock contract stops at synchronous pump execution; callers needing completion keep using `await-quiescent!` or their owner-specific join.
- **PLAN-Clp-001.R3:** A zero polling cadence would leave manual time unchanged and could loop forever. Boundary validation rejects it instead of adding an implicit minimum or hidden fallback.
- **PLAN-Clp-001.R4:** Tightening workflow and roster `:poll-ms` from non-negative to positive rejects a previously accepted public value. This pre-v1 narrowing is stated in their source docstrings and authored contract docs; their error messages keep naming caller-facing `:poll-ms`, not the helper's `:timeout-ms`.
- **PLAN-Clp-001.Q1:** None. The Clock shape, manual sleep semantics, timeout domain, and pre-v1 migration policy are fixed in the feature deltas.

## PLAN-Clp-001.P8 Task context

- **PLAN-Clp-001.TC1:** Work only in `/Users/ct/dev/projects/skein-src__clock-aware-polling` on `codex/clock-aware-polling`; the root `main` checkout stays clean.
- **PLAN-Clp-001.TC2:** Preserve one ambient runtime per real weaver and `:publish? false` for tests. Test and smoke experiments use disposable worlds; never restart the canonical weaver.
- **PLAN-Clp-001.TC3:** Public alpha code follows SPEC-003.C19a: public-first story bodies, 96-column source lines, useful docstrings, named specs where they own boundary shapes, and fail-loud rejection at the seam.
- **PLAN-Clp-001.TC4:** The manual Clock is a deterministic polling and runtime-pump seam, not a general scheduler for arbitrary concurrency. Tests must not infer event-lane quiescence from a returned sleep.

## PLAN-Clp-001.P9 Developer Notes

### PLAN-Clp-001.DN1 Proposal review — 2026-07-20

- Reviewer `zz4t7` identified the synchronous-polling requirement that manual `sleep!` advance time itself and run pumps. `PROP-Clp-001.S3` and all three feature deltas now state that contract explicitly.
- The initial proposal was moved off the root checkout before implementation. Card `2g75m` records the corrected branch and worktree.

### PLAN-Clp-001.DN2 Spec and plan review — 2026-07-20

- Reviewer `0je1l` fixed uninstalled manual sleep semantics: it advances time and runs no pumps. Once installed, zero or positive sleeps run that runtime's pumps; explicit `advance!` stays strictly positive.
- The implementation slices must name all six existing `set-clock!` caller files, delete both production caller deadline calculations, update authored API prose before generation, and treat positive `:poll-ms` as a caller-visible pre-v1 narrowing.

### PLAN-Clp-001.DN3 Task queue — 2026-07-20

- Three AFK slices keep every intermediate state verifiable: land the Clock/runtime/manual seam and migrate all existing clock controls; replace polling and its two shipped callers; then promote contracts and run queue acceptance.

### PLAN-Clp-001.DN4 Queue acceptance — 2026-07-20

- The focused Clock, runtime, scheduler, cron, spool, workflow, and roster suites passed with 194 tests and 1,987 assertions.
- Formatting, lint, conventions, reflection, generated API docs, strict docs build, Go tests, and smoke passed. The single locked full Clojure run passed with 762 tests and 5,349 assertions.
- The final worktree check found no SQLite or runtime metadata artifacts.
