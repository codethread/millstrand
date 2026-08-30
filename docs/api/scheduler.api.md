---

# <a name="millstrand.api.scheduler.alpha">millstrand.api.scheduler.alpha</a>

Explicit-runtime API for the weaver-owned no-poller scheduler primitive.

A weaver owns one durable clock trigger: trusted code persists a wake keyed by a stable caller key, an absolute instant, and a fully qualified handler symbol, and the runtime re-arms and dispatches it through the weaver's shared serialized async lane so clock-triggered handlers and post-commit event handlers observe one mutation order (RFC-009, DELTA-weaver-scheduler- repl-001). Delivery is at-least-once: handlers must be idempotent.

This namespace validates the wake against the shared `:millstrand.core.specs/scheduler-wake` boundary spec (the persistence seam consults the same spec) and resolves handlers eagerly, so a bad wake or handler fails `schedule!`, not dispatch; durable storage lives in `millstrand.core.db`, and timer arming/dispatch in `millstrand.core.weaver.scheduler`. Persisted rows return as decoded data-first maps (`::pending-wake`, `::cancellation`).

Pull-based `wake-at` strand attributes plus named queries remain the default answer when a poller already exists. Reach for this namespace only for the no-poller case where something must proactively happen at instant T with no client polling to trigger it.

Callers own runtime selection and pass the target weaver runtime as the first argument to every function here; capture it with `millstrand.api.current.alpha/runtime` only at trusted entry points.

## <a name="millstrand.api.scheduler.alpha/cancel!">`cancel!`</a>

```clojure
(cancel! runtime key)
```

Function.

Cancel a pending wake in `runtime` by stable key.

Removes whichever generation currently holds `key` and returns the cancellation's history row as a decoded `::cancellation` map. A missing key fails loudly.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/scheduler/alpha.clj#L52-L61">Source</a></sub></p>

## <a name="millstrand.api.scheduler.alpha/pending">`pending`</a>

```clojure
(pending runtime)
```

Function.

Return all pending wakes in `runtime` as decoded `::pending-wake` maps.

Ordered by wake-at ascending with a stable key tie-break, so the earliest pending wake is `(first (pending runtime))`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/scheduler/alpha.clj#L63-L69">Source</a></sub></p>

## <a name="millstrand.api.scheduler.alpha/schedule!">`schedule!`</a>

```clojure
(schedule! runtime wake)
```

Function.

Persist or replace a durable wake in `runtime` and arm it for dispatch.

`wake` is a map of :key (stable non-blank string), :wake-at (java.time.Instant), :handler (fully qualified symbol resolvable in `runtime`'s generation classloader), and optional :payload (nil or a map that encodes to a JSON object). Replacing an existing key resets its attempt count. Returns the persisted wake as a decoded `::pending-wake` map. Malformed keys/instants/payloads and unresolvable or non-callable handlers fail loudly; no wake is persisted on failure.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/scheduler/alpha.clj#L35-L50">Source</a></sub></p>
