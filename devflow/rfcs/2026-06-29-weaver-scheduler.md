# Weaver Scheduler Primitive

**Document ID:** `RFC-009` **Status:** Implemented **Date:** 2026-06-29 **Related specs:** [Weaver Runtime](../specs/daemon-runtime.md), [Strand Model](../specs/strand-model.md), [REPL API](../specs/repl-api.md), [CLI Surface](../specs/cli.md) **Related code:** `src/skein/core/weaver/runtime.clj`, `src/skein/api/weaver/alpha.clj`, `src/skein/api/events/alpha.clj`, `src/skein/api/views/alpha.clj`, `src/skein/core/db.clj` **Related tenets:** TEN-000@1 (alpha), TEN-002 (trusted agents), TEN-003 (FAIL LOUDLY), TEN-004 (Less is More), TEN-006 (CLI is a thin JSON control surface)

## RFC-009.P1 Problem

Skein is reactive. The weaver acts only on client requests and on post-commit event dispatch; nothing advances the graph on a clock. This blocks a class of workflow primitives that other systems call **gates**: "wait 24h", "deadline passed", "remind in 30m", scheduled releases, and any cooldown/timeout. It also blocks recurring maintenance such as expiring temporary strands.

Two shapes exist for time-driven behaviour, and only one is a real gap:

- **Pull-based** needs nothing new. Store an absolute `wake-at` attribute on a strand and expose a view/query that treats it as due when `now >= wake-at`. Anything already polling `ready` or a view picks it up. This is the idiomatic skein answer and should remain the default recommendation.
- **Push-based** is the gap. When _nothing_ is polling, some component must proactively wake at a time and invoke trusted code. Today every workflow library that wants this must spawn its own JVM executor, invent its own `wake-at` convention and missed-fire policy, persist and re-arm across weaver restarts, and manage reload-safe teardown by hand. That is subtle (durability, crash windows, idempotency, double-arming on reload) and would be re-derived, divergently, per library.

The decision: should skein bless a weaver-owned scheduler so libraries share one durable-timer substrate, and if so, where is the core/userland boundary?

## RFC-009.P2 Guiding philosophy

- **RFC-009.PH1 — Pull stays the default.** A core scheduler must not deprecate `wake-at` + view. It exists only for proactive wakeups with no poller in the loop.
- **RFC-009.PH2 — One async runtime, two triggers.** The weaver already owns a single async dispatch worker for post-commit events. A scheduler is best framed as a second trigger source (a clock) into that same serialized runtime, not a new parallel subsystem.
- **RFC-009.PH3 — Substrate, not policy.** Core owns the hard, get-it-wrong-once mechanics (durable wake records, restart re-arm, lifecycle, named-handler resolution). Recurrence, cron, retries, and missed-fire policy stay in userland.
- **RFC-009.PH4 — Handlers are names, not closures.** A captured closure cannot survive a weaver restart. Scheduled work must reference a fully qualified handler symbol, resolved like events/views/patterns, so a re-armed timer knows what to invoke.
- **RFC-009.PH5 — Fail loudly, deliver at-least-once.** Malformed schedules and unresolvable handlers reject loudly. Crash windows resolve toward at-least-once delivery; handlers are documented as needing idempotency.

## RFC-009.P3 Goals

- **RFC-009.G1:** Provide a weaver-owned way to schedule "at time `T`, invoke handler symbol `H` with a payload".
- **RFC-009.G2:** Persist pending schedules durably so they survive weaver restart, and re-arm them on startup.
- **RFC-009.G3:** Bind scheduler lifecycle to weaver start/stop/reload, with reload-safe teardown that does not leak or double-arm timers.
- **RFC-009.G4:** Resolve handlers through the runtime library classloader by fully qualified symbol, matching events/views/patterns.
- **RFC-009.G5:** Expose data-first introspection (pending schedules, next wake, recent fires/failures) analogous to `skein.events.alpha`.
- **RFC-009.G6:** Keep recurrence and missed-fire handling expressible in userland handlers (a handler may compute and persist its next `wake-at`).

## RFC-009.P4 Non-goals

- **RFC-009.NG1:** Do not deprecate or hide pull-based `wake-at` + view; it remains the recommended default.
- **RFC-009.NG2:** Do not bake in cron syntax, retry/backoff, or jitter. Those are userland concerns on top of the primitive.
- **RFC-009.NG3:** Do not add external event ingestion (GitHub/CI webhooks). That is a separate inbound-transport decision; a scheduler is a clock, not an ingress.
- **RFC-009.NG4:** Do not promise exactly-once delivery or transactional handler side effects across a crash.
- **RFC-009.NG5:** Do not add a public mutating `strand schedule` CLI verb in this RFC; registration is trusted config/REPL like events. Read-only CLI introspection is an open question.
- **RFC-009.NG6:** Do not sandbox handlers; they run with weaver authority.

## RFC-009.P5 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-009.O1 | Userland only; no core scheduler. Each library spawns its own executor. | Smallest core; nothing new to own; honours TEN-004 literally. | Every library re-derives durability, restart re-arm, reload teardown, and missed-fire policy — divergently and error-prone. Competing executors and `wake-at` conventions. No shared introspection. |
| RFC-009.O2 | Full builtin scheduler with cron, recurrence, retries, and missed-fire policy. | Batteries included; one rich system. | Core inherits the entire time tar pit (DST, clock jumps, cron semantics, backpressure) permanently; defaults become breaking to change; large surface against TEN-004. |
| RFC-009.O3 | Pull-only blessing: ship a standard "due" view/query helper, no push. | Tiny; zero new runtime state; trivially restart-safe. | Solves nothing for the no-poller case, which is the actual gap. |
| RFC-009.O4 | Minimal core primitive: durable "fire at T → invoke symbol H" with restart re-arm, reload-safe lifecycle, and introspection; recurrence/policy left to userland handlers. Framed as a clock trigger into the existing async worker. | Owns exactly the hard-once mechanics; one shared substrate and introspection for all libraries; small, principled extension of the async worker; keeps policy in userland. | New weaver runtime state and a persisted schedule record; introduces the first proactive (non-client-driven) mutation path; adds a clock to the core test surface. |

## RFC-009.P6 Recommendation

- **RFC-009.REC1:** Choose **RFC-009.O4**. Add a minimal weaver-owned scheduler primitive, tentatively `skein.scheduler.alpha`, that schedules a durable wake referencing a fully qualified handler symbol, re-arms pending wakes on startup, tears down cleanly on reload, and exposes data-first introspection. Leave recurrence, cron, retries, and missed-fire policy to userland handlers.
- **RFC-009.REC2:** Frame it as generalising the existing single async dispatch worker from one trigger (post-commit events) to two (post-commit events + clock), so timers and mutation events share one serialization point rather than racing through separate library threads. This is what makes the substrate worth centralising and contains the core surface.
- **RFC-009.REC3:** Keep pull-based `wake-at` + view as the documented default (RFC-009.PH1); the scheduler is the escape hatch for proactive wakeups, not the primary path.
- **RFC-009.REC4:** Tentative helper shape, mirroring `skein.events.alpha`:

    ```clojure
    (require '[skein.scheduler.alpha :as sched])

    ;; durable wake; handler is a fully qualified symbol, not a closure
    (sched/schedule! {:key :release-cooldown
                      :wake-at <instant>
                      :handler 'my.workflow/on-cooldown
                      :payload {:strand id}})

    (sched/cancel! :release-cooldown)
    (sched/scheduled)        ;; data-first pending entries + next wake
    ```

Whether the durable record is a strand (with a scheduler attribute convention) or dedicated runtime storage is left to the proposal/spec (see RFC-009.Q1).

## RFC-009.P7 Consequences

- **RFC-009.C1:** `devflow/specs/daemon-runtime.md` needs a scheduler section: runtime state, the clock trigger on the async worker, durable wake storage, startup re-arm, and reload teardown semantics.
- **RFC-009.C2:** `src/skein/weaver/runtime.clj` needs scheduler runtime state (executor/armed-wakes) bound to weaver lifecycle, with reload clearing/re-arming alongside queries, views, patterns, and events.
- **RFC-009.C3:** A new `src/skein/scheduler/alpha.clj` helper namespace provides trusted registration/cancellation/introspection, analogous to `skein.events.alpha`.
- **RFC-009.C4:** Handler invocation must route through the runtime library classloader and resolve fully qualified symbols, consistent with events/views/patterns.
- **RFC-009.C5:** Documentation must present pull-based `wake-at` + view as the default and the scheduler as the proactive escape hatch, with an explicit at-least-once / idempotent-handler warning.
- **RFC-009.C6:** Tests must prove: wakes fire near their time; pending wakes survive a weaver restart and re-arm; reload neither leaks nor double-fires; unresolvable handlers and malformed schedules fail loudly; handler exceptions are captured in bounded failure state without crashing the worker.
- **RFC-009.C7:** This is the first proactive mutation path. The core test surface gains a clock; an injectable/virtual clock is needed for deterministic tests.
- **RFC-009.C8:** A follow-up workflows library can build gates (timer, human, deadline) on this primitive plus the existing batch/pattern/edge/readiness machinery, without further core changes.

## RFC-009.P8 Storage shape decision

- **RFC-009.Q1.OUT:** Store durable scheduler wakes in dedicated weaver-owned SQLite tables, not as first-class strand records. A wake is daemon runtime coordination state: `wake-at`, stable key, handler symbol, payload, status/attempt bookkeeping, and fire/failure metadata. It should persist beside the strand graph so startup can re-arm pending rows, reload can cancel/rebuild in-memory timers without graph mutation, and scheduler introspection can return data-first runtime rows without polluting user task queries, readiness, DAG traversals, or userland graph conventions.
- **RFC-009.Q1.R1:** Dedicated storage aligns with the strand model by keeping strands as user graph records with lifecycle, attributes, edges, readiness, and explicit burn semantics. Encoding wakes as strands would create user graph noise, require reserved scheduler attributes and states, risk accidental closure/burn/update by normal workflows, and make timer cleanup indistinguishable from graph lifecycle policy.
- **RFC-009.Q1.R2:** Dedicated storage aligns with the daemon-runtime tenet that runtime behaviour and registries live in the weaver while only explicitly durable feature state is persisted. Scheduler wake rows are the explicit durable state needed by RFC-009.G2; handler registries remain non-durable and are reloaded through trusted config/REPL like events, views, patterns, and hooks.
- **RFC-009.Q1.R3:** Tests should cover schema initialization, schedule/cancel/list persistence, startup re-arm from pending rows, reload teardown/re-arm without duplicate fires, malformed wake rejection, unresolvable handler failure capture, and that scheduler rows do not appear in `list`, `ready`, strand queries, relation traversals, or burn/update paths.

## RFC-009.P8a Follow-up tracking

Future implementation slices and unresolved design decisions are tracked on the kanban board (`strand kanban board`). This RFC remains the design rationale; the board is the canonical home for pending work items.

## RFC-009.P9 Outcome

- **RFC-009.OUT1:** Accepted. Pending implementation work was tracked on the kanban board (`strand kanban board`).
- **RFC-009.OUT2:** Implemented. The minimal RFC-009.O4 primitive shipped as the blessed trusted-Clojure `skein.api.scheduler.alpha` surface (`src/skein/api/scheduler/alpha.clj`) over weaver-owned durable wake storage in `src/skein/core/weaver/scheduler.clj`, specified by the `SPEC-004.P10d Weaver scheduler` section of `devflow/specs/daemon-runtime.md`, and archived at `devflow/archive/26-07-05__weaver-scheduler/`.
