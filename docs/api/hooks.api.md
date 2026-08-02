
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
  internal handle leaves the registry (DELTA-OlrDrt-001.CC9).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L95-L104">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L82-L93">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/register-hook!">`register-hook!`</a>
``` clojure
(register-hook! runtime key types fn-sym)
(register-hook! runtime key types fn-sym opts)
(register-hook! runtime owner key types fn-sym opts)
```
Function.

Register or replace a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  registering an existing key replaces that entry. `types` is a non-empty set of
  hook type keywords, and `fn-sym` a fully qualified symbol validated here as
  resolvable under the runtime's spool classloader. The entry stores only the
  symbol — every hook binds its callable at dispatch start, so a reload's fresh
  definition is the one that runs. `opts` may carry an integer `:order`
  (default 0) plus data-first metadata. Registration input and the returned entry
  conform to `::skein.core.specs/hook-registration` and
  `::skein.core.specs/hook-entry`, respectively. Returns the registered entry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L16-L61">Source</a></sub></p>

## <a name="skein.api.hooks.alpha/unregister-hook!">`unregister-hook!`</a>
``` clojure
(unregister-hook! runtime key)
(unregister-hook! runtime owner key)
```
Function.

Unregister a lifecycle hook by stable key from `runtime` and return that key.

  Unregistering an absent key is a no-op returning the validated key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/hooks/alpha.clj#L63-L73">Source</a></sub></p>
