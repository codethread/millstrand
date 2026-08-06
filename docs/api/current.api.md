
-----
# <a name="millstrand.api.current.alpha">millstrand.api.current.alpha</a>


Isolated convenience facade for reading and scoping the current Millstrand runtime.

  This namespace is the blessed public facade for trusted in-process config,
  spool, and REPL code that must capture the active weaver runtime explicitly and
  then pass it to `millstrand.api.*.alpha` functions. It never falls back to client or
  connected REPL state.

  The public interfaces are identified by the `s/fdef`s and the `::runtime`
  spec at the foot of this file: a runtime is an opaque non-nil handle — hold
  it and pass it on, never reach inside it.




## <a name="millstrand.api.current.alpha/runtime">`runtime`</a>
``` clojure
(runtime)
```
Function.

Return the thread-bound or published in-process weaver runtime.

  Trusted startup, reload, and nREPL contexts bind a per-thread runtime. Daemon
  processes also publish one ambient runtime for legacy REPL ergonomics. When
  neither exists, fail loudly. Use `runtime-or-nil` when a missing runtime is a
  branch rather than an error.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/current/alpha.clj#L29-L42">Source</a></sub></p>

## <a name="millstrand.api.current.alpha/runtime-or-nil">`runtime-or-nil`</a>
``` clojure
(runtime-or-nil)
```
Function.

Return the thread-bound or published in-process weaver runtime, or nil.

  The non-throwing sibling of `runtime`, and the only sanctioned way to *probe*
  for an ambient runtime without fabricating one. Trusted resolvers that want to
  fall back to another source (a caller-held binding, a connected client, a loud
  error with their own message) branch on this nil instead of catching
  `runtime`'s exception as control flow. This is the single ambient-runtime read
  point: callers must not reach into `millstrand.core.weaver.runtime` internals.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/current/alpha.clj#L16-L27">Source</a></sub></p>

## <a name="millstrand.api.current.alpha/with-runtime">`with-runtime`</a>
``` clojure
(with-runtime runtime & body)
```
Macro.

Evaluate `body` with `runtime` bound as the thread-local ambient runtime.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/current/alpha.clj#L55-L58">Source</a></sub></p>

## <a name="millstrand.api.current.alpha/with-runtime*">`with-runtime*`</a>
``` clojure
(with-runtime* runtime thunk)
```
Function.

Call `thunk` with `runtime` bound as the thread-local ambient runtime.

  Trusted in-process code uses this to scope a chosen runtime for a dynamic
  extent so nested `(runtime)` reads and explicit-runtime callees agree on the
  same runtime without threading it through every call.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/current/alpha.clj#L44-L53">Source</a></sub></p>
