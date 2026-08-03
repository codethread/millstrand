
-----
# <a name="skein.api.hooks.alpha">skein.api.hooks.alpha</a>


Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`.




## <a name="skein.api.hooks.alpha/hook-provenance">`hook-provenance`</a>
``` clojure
(hook-provenance runtime)
```
Function.

Return owner/provenance diagnostics for `runtime`'s lifecycle hook registry.

  Maps each hook key to `{:effective :shadowed :contenders}` (see
  `skein.core.weaver.core-registry/explain`); each contender names its `:owner`,
  `:layer`, and `:override?`/`:effective?` flags, and its `:value` hook entry
  has any directly planted `:fn-value` stripped, so no function value or
  internal handle leaves the registry (DELTA-OlrDrt-001.CC9). The returned map
  conforms to `::skein.core.specs/hook-provenance`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L96-L107">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/hooks">`hooks`</a>
``` clojure
(hooks runtime)
```
Function.

Return data-first lifecycle hook registry entries in execution order.

  Entries sort by `:order`, then printed key for a deterministic tie-break.
  Entries are data — the callable binds at dispatch from the `:fn` symbol —
  and any directly planted `:fn-value` is stripped so no function value
  leaves the registry. Each returned entry conforms to
  `::skein.core.specs/hook-entry`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L83-L94">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/register-hook!">`register-hook!`</a>
``` clojure
(register-hook! runtime key types fn-sym)
(register-hook! runtime key types fn-sym opts)
(register-hook! runtime owner key types fn-sym opts)
```
Function.

Register a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  re-registering a key this owner already holds replaces that entry, while a key
  another owner supplies collides loudly — `replace-hook!` is the deliberate
  override for it. `types` is a non-empty set of hook type keywords, and
  `fn-sym` a fully qualified symbol validated here as resolvable under the
  runtime's spool classloader. The entry stores only the symbol — every hook
  binds its callable at dispatch start, so a reload's fresh definition is the one
  that runs. `opts` may carry an integer `:order` (default 0) plus data-first
  metadata. Registration input and the returned entry conform to
  `::skein.core.specs/hook-registration` and `::skein.core.specs/hook-entry`,
  respectively. Returns the registered entry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L17-L38">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/replace-hook!">`replace-hook!`</a>
``` clojure
(replace-hook! runtime key types fn-sym)
(replace-hook! runtime key types fn-sym opts)
(replace-hook! runtime owner key types fn-sym opts)
```
Function.

Replace an already-registered lifecycle hook, failing loudly when absent.

  Same signature and return shape as `register-hook!`. This is the deliberate
  override for a key that already exists; unlike `register-hook!` it requires the
  key to be present. When another owner supplies the key — a module-published
  hook, say — the override intent is recorded, which is what lets the direct
  entry keep shadowing the original across `runtime/refresh!`.
  `unregister-hook!` retracts the shadow and the shadowed entry becomes effective
  again. Hooks bind their callable at dispatch start, so iterating a body under a
  stable contract needs no registry call at all; reach for this when the types,
  order, metadata, or the symbol itself change.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L40-L64">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/unregister-hook!">`unregister-hook!`</a>
``` clojure
(unregister-hook! runtime key)
(unregister-hook! runtime owner key)
```
Function.

Retract `owner`'s own lifecycle hook registration for `key` from `runtime`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-hook!` rather than a way to delete another owner's
  hook: retracting a shadow restores the shadowed entry as effective, and
  retracting a fresh claim leaves the key unregistered. Unregistering a key this
  owner never registered is an idempotent no-op. Returns `{:unregistered
  <key>}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L66-L81">Source</a></sub></p>
