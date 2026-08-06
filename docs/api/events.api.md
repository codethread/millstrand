
-----
# <a name="millstrand.api.events.alpha">millstrand.api.events.alpha</a>


Explicit-runtime API for managing and inspecting weaver event handlers.

  Registration, replacement, and unregistration mutate the runtime's
  weaver-lifetime handler registry; `handlers` and `recent-failures` are
  the data-first reads over registry and failure state. Every registration
  is validated loudly at the seam — stable key, non-empty keyword type set,
  fully qualified function symbol resolvable under the runtime spool
  classloader, data-first metadata — and entries replace by key within the
  registering owner's partition, which is what makes reload workflows
  idempotent. Event submission is not public surface: internal
  mutation APIs submit events through `millstrand.core.weaver.dispatch`
  (SPEC-004.C73), and the event-lane quiescence await ships in
  `millstrand.test.alpha` (SPEC-004.C74b).

  Callers own runtime selection and pass the target weaver runtime as
  the first argument.




## <a name="millstrand.api.events.alpha/handler-provenance">`handler-provenance`</a>
``` clojure
(handler-provenance runtime)
```
Function.

Return owner/provenance diagnostics for `runtime`'s event handler registry.

  Maps each handler key to `{:effective :shadowed :contenders}` (see
  `millstrand.core.weaver.core-registry/explain`); each contender names its `:owner`,
  `:layer`, and `:override?`/`:effective?` flags, and its `:value` handler entry
  has the resolved `:fn-value` stripped, so no function value or internal handle
  leaves the registry (SPEC-004.C66, DELTA-OlrDrt-001.CC9).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L104-L113">Source</a></sub></p>

## <a name="millstrand.api.events.alpha/handlers">`handlers`</a>
``` clojure
(handlers runtime)
```
Function.

Return `runtime`'s event handler registry as data-first entries.

  Each entry is `{:key :types :fn :metadata}` — never the resolved function
  value (SPEC-004.C66) — sorted by printed key so ordering is deterministic
  across mixed key types.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L94-L102">Source</a></sub></p>

## <a name="millstrand.api.events.alpha/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures runtime)
```
Function.

Return `runtime`'s recent asynchronous handler failures, oldest first.

  Failures are bounded weaver-lifetime introspection state (SPEC-004.C67):
  each record carries `:handler/key`, `:handler/fn`, `:event/id`,
  `:event/type`, `:exception/message`, and `:failed/at`. Handler exceptions
  never fail the already-committed mutation that emitted the event.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L115-L123">Source</a></sub></p>

## <a name="millstrand.api.events.alpha/register-handler!">`register-handler!`</a>
``` clojure
(register-handler! runtime key types fn-sym)
(register-handler! runtime key types fn-sym metadata)
(register-handler! runtime owner key types fn-sym metadata)
```
Function.

Register an event handler in `runtime` for selected event types.

  Builds the registry entry from loudly validated pieces — `key` a keyword,
  symbol, or non-blank string; `types` a non-empty set of event type
  keywords; `fn-sym` a fully qualified symbol resolving to a callable under
  the runtime spool classloader (resolution happens here, so a bad symbol
  fails registration, not dispatch); `metadata` a data-first map — swaps it
  into the registry, and returns the entry as data (the resolved function
  value stays internal). Re-registering a key this owner already holds
  replaces that entry; a key another owner supplies collides loudly, and
  `replace-handler!` is the deliberate override for it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L29-L48">Source</a></sub></p>

## <a name="millstrand.api.events.alpha/replace-handler!">`replace-handler!`</a>
``` clojure
(replace-handler! runtime key types fn-sym)
(replace-handler! runtime key types fn-sym metadata)
(replace-handler! runtime owner key types fn-sym metadata)
```
Function.

Replace an already-registered event handler, failing loudly when absent.

  Same signature and return shape as `register-handler!`. This is the
  deliberate override for a key that already exists; unlike
  `register-handler!` it requires the key to be present. When another owner
  supplies the key — a module-published handler, say — the override intent
  is recorded, which is what lets the direct entry keep shadowing the
  original across `runtime/refresh!`. `unregister-handler!` retracts the
  shadow and the shadowed entry becomes effective again. Handlers capture
  their resolved function value at registration rather than binding it at
  dispatch, so redefining the underlying fn does not reach a registered
  handler: iterating one is always this call.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L50-L75">Source</a></sub></p>

## <a name="millstrand.api.events.alpha/unregister-handler!">`unregister-handler!`</a>
``` clojure
(unregister-handler! runtime key)
(unregister-handler! runtime owner key)
```
Function.

Retract `owner`'s own event handler registration for `key` in `runtime`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-handler!` rather than a way to delete another
  owner's handler: retracting a shadow restores the shadowed entry as
  effective, and retracting a fresh claim leaves the key unregistered.
  Validates `key` like registration; a key this owner never registered is a
  quiet no-op, so unregistration is idempotent. Returns `{:unregistered
  key}`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/events/alpha.clj#L77-L92">Source</a></sub></p>
