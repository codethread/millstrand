
-----
# <a name="millhouse.spools.cron">millhouse.spools.cron</a>


Userland recurrence layer over the weaver's durable scheduler wake primitive.

  Cron registers named jobs that fire on a fixed interval with uniform jitter.
  It owns no timing of its own: each registered job is a durable `cron/<id>`
  scheduler wake (`millstrand.api.scheduler.alpha`), so the cadence survives weaver
  restart and reload, and scheduler introspection is the single timing view. A
  caller registers a job by fully-qualified `:handler` symbol resolving to a
  `(fn [runtime] ..)`; the engine owns only the wake wiring, the job's
  last-result status, and a loud inspectable failure log. It is deliberately
  just recurrence — workflow/gate integration is intentionally out of scope.

  Delivery model. The scheduler dispatches a due `cron/<id>` wake to
  `fire-wake` on the weaver's shared serialized event lane. `fire-wake` stays
  tiny so it never holds the lane on job work: it reschedules the next wake and
  hands the job body off to a cron-owned execution executor, then returns so the
  scheduler completes the delivered wake. The job's own success/failure is
  recorded cron-side and never interrupts cadence. Delivery is at-least-once, so
  `:handler` bodies must tolerate duplicate fires (TEN-003, `SPEC-004.C101`).

  State is runtime-owned via `millstrand.api.runtime.alpha/spool-state`, so two
  runtimes in one JVM keep independent executors, job tables, and failure logs.
  The in-memory job table carries no cadence: collected `defjob` declarations
  converge through Cron's lifecycle effect after publication, while the durable
  wake in SQLite is the sole authority for when a job next fires. Trusted
  callers may still use `register!` directly.




## <a name="millhouse.spools.cron/actual-jobs">`actual-jobs`</a>
``` clojure
(actual-jobs {:keys [runtime], :as context})
```
Function.

Return Cron's currently managed jobs for a `::lifecycle-context`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L405-L409">Source</a></sub></p>

## <a name="millhouse.spools.cron/apply-jobs!">`apply-jobs!`</a>
``` clojure
(apply-jobs! {:keys [runtime desired actual], :as context})
```
Function.

Converge Cron's managed jobs from a validated `::apply-context`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L427-L442">Source</a></sub></p>

## <a name="millhouse.spools.cron/await-quiescent!">`await-quiescent!`</a>
``` clojure
(await-quiescent! runtime)
(await-quiescent! runtime {:keys [timeout-ms], :as opts})
```
Function.

Block until every offloaded cron job on `runtime` has finished, then return
  `runtime`.

  The deterministic join for tests: because job bodies run off the event lane,
  `millstrand.test.alpha/await-quiescent!` returns before a job completes. The
  in-flight latch is incremented on the event lane in `fire-wake` before submit,
  so once the lane has quiesced any offloaded job is already counted. Polls the
  latch atom until the count reaches zero or the budget expires on the runtime
  Clock, throwing loudly on timeout (TEN-003), mirroring the event-lane join in
  `millstrand.test.alpha/await-quiescent!`. `opts` accepts `:timeout-ms` (a
  positive integer); unknown keys are rejected loudly. The default budget comes
  from `millstrand.spools.test-support/await-budget-ms`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L267-L295">Source</a></sub></p>

## <a name="millhouse.spools.cron/defjob">`defjob`</a>
``` clojure
(defjob id job)
(defjob id options job)
```
Macro.

Collect one cron job declaration for the current runtime module.

  `id` is the stable cron job key and `job` is the same literal map accepted by
  `register!`. The optional `options` map conforms to `::job-options`. The macro
  performs no scheduling itself; Cron's lifecycle effect applies the complete
  effective declaration after publication.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L377-L388">Source</a></sub></p>

## <a name="millhouse.spools.cron/desired-jobs">`desired-jobs`</a>
``` clojure
(desired-jobs {:keys [runtime], :as context})
```
Function.

Return the effective Cron job declarations for a `::lifecycle-context`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L395-L403">Source</a></sub></p>

## <a name="millhouse.spools.cron/fire-wake">`fire-wake`</a>
``` clojure
(fire-wake {:keys [runtime payload]})
```
Function.

Scheduler wake handler for a `cron/<id>` fire, run on the shared event lane.

  Invoked by `millstrand.core.weaver.scheduler/run-fire!` with its context map. Stays
  tiny so it never holds the lane on job work (`PLAN-cron-on-scheduler-001.A2`):
  (1) decode `{:job id}`; (2) look up the in-memory job — absent means the job
  was unregistered, so return without rescheduling; (3) reschedule the next
  `cron/<id>` wake **before** offload, so the cadence is persisted even if the
  offload fails; (4) count the job in-flight and submit its `:handler` to the
  cron-owned execution executor, recording an executor rejection loudly cron-side
  without throwing; (5) return so the scheduler completes the delivered wake. The
  job body never runs on the lane.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L237-L265">Source</a></sub></p>

## <a name="millhouse.spools.cron/job-declaration">`job-declaration`</a>
``` clojure
(job-declaration id options job)
```
Function.

Return a validated Cron job declaration.

  `options` conforms to `::job-options`; `job` conforms to `::job` after the
  stable `id` is attached. Override intent remains collection metadata.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L367-L375">Source</a></sub></p>

## <a name="millhouse.spools.cron/job-kind">`job-kind`</a>




Owner-partitioned kind id for Cron job declarations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L52-L54">Source</a></sub></p>

## <a name="millhouse.spools.cron/jobs">`jobs`</a>
``` clojure
(jobs runtime)
```
Function.

Return the cron jobs registered on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:handler` symbol,
  and (once fired) `:last-result`/`:last-fired-at`/`:last-error`. When a job next
  fires lives in its durable `cron/<id>` wake — read scheduler introspection
  (`millstrand.api.scheduler.alpha/pending`), the single timing view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L462-L470">Source</a></sub></p>

## <a name="millhouse.spools.cron/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures runtime)
```
Function.

Return recorded cron failures for this runtime's weaver lifetime, oldest
  first — the same bounded-ring ordering as
  `millstrand.api.events.alpha/recent-failures`. Each entry carries `:kind` (`:run`
  for a `:handler` throw, `:offload` for an execution-executor rejection),
  `:job`, a `:message`, and `:at`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L124-L131">Source</a></sub></p>

## <a name="millhouse.spools.cron/register!">`register!`</a>
``` clojure
(register! runtime job)
```
Function.

Register (or replace) a named cron job on `runtime` as a durable wake.

  The `job` map is validated against the `::job` spec (a keyword/non-blank
  `:id`, positive `:interval-ms`, optional non-negative `:jitter-ms`, and a
  fully-qualified `:handler` symbol); unknown keys are rejected loudly.

  `job` keys:
  - `:id` — keyword or non-blank string identifying the job.
  - `:interval-ms` — positive integer base period between fires.
  - `:jitter-ms` — non-negative integer; each fire is offset by a uniform value
    in [-jitter, +jitter]. Optional, default 0.
  - `:handler` — fully-qualified symbol resolving to `(fn [runtime] ..)`, invoked
    on every fire. Its return value is recorded as `:last-result`; a thrown
    exception is recorded in `recent-failures` and does not stop the cadence.
    **Delta from `millstrand.api.scheduler.alpha/schedule!`'s `:handler`**, one layer
    down: that handler is the wake-delivery callback and takes the wake context
    map, while this one is the job body and takes the runtime. Cron writes the
    scheduler's own `:handler` (always `millhouse.spools.cron/fire-wake`) on the
    `cron/<id>` wake it arms; a caller never writes that key here.

  Re-registration preserves a pending `cron/<id>` wake when the cadence-defining
  `[interval-ms jitter-ms handler]` tuple is unchanged, or when the runtime has
  no in-memory config yet (fresh JVM adopting a durable wake). A changed tuple arms
  a fresh wake at `now + interval + jitter`; a missing pending wake also arms a
  fresh wake. Returns the job's status map.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L315-L365">Source</a></sub></p>

## <a name="millhouse.spools.cron/remove-jobs!">`remove-jobs!`</a>
``` clojure
(remove-jobs! {:keys [runtime], :as context})
```
Function.

Cancel every managed job for a validated `::lifecycle-context`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L444-L452">Source</a></sub></p>

## <a name="millhouse.spools.cron/scheduled-jobs">`scheduled-jobs`</a>




Keep durable Cron wakes converged on the effective published job registry.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L454-L460">Source</a></sub></p>

## <a name="millhouse.spools.cron/unregister!">`unregister!`</a>
``` clojure
(unregister! runtime id)
```
Function.

Cancel a cron job's pending wake and remove it from `runtime`.

  Returns `{:unregistered id}` when the job existed (in-memory config or a
  pending `cron/<id>` wake), else `{:unregistered nil}` — the delta from
  `millstrand.api.events.alpha/unregister-handler!`, which echoes the key back whether or not
  a handler was registered. Cron reports absence because a job's existence spans
  two stores (the in-memory table and the durable wake), so a caller cannot infer
  it. The scheduler `cancel!` fails loudly on an unknown key, so the cancel is
  guarded behind a `pending` check for `cron/<id>` — a missing wake is tolerated
  while genuine scheduler errors still surface
  (`PLAN-cron-on-scheduler-001.R1`).
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main//Users/ct/.gitlibs/libs/millhouse.spools/cron/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/cron/src/millhouse/spools/cron.clj#L183-L203">Source</a></sub></p>
