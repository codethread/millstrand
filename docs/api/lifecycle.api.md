
-----
# <a name="skein.api.lifecycle.alpha">skein.api.lifecycle.alpha</a>


Authoring forms and data contracts for module lifecycle effects.

  Lifecycle declarations are printable source facts collected with a module's
  owner-complete contribution. The coordinator resolves their fully qualified
  callable symbols before publication and owns all retained live state.




## <a name="skein.api.lifecycle.alpha/defreconcile">`defreconcile`</a>
``` clojure
(defreconcile form-name doc opts)
```
Macro.

Define and collect one repeated desired-state reconciliation effect.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L112-L118">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/defresource">`defresource`</a>
``` clojure
(defresource form-name doc opts)
```
Macro.

Define and collect one paired resource acquisition and release effect.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L104-L110">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/defseed">`defseed`</a>
``` clojure
(defseed form-name doc opts)
```
Macro.

Define and collect one process-lifetime idempotent seed effect.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L96-L102">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/kinds">`kinds`</a>




Closed lifecycle declaration kinds.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L11-L13">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/phases">`phases`</a>




Closed lifecycle execution phases.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L15-L17">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/reconcile-declaration">`reconcile-declaration`</a>
``` clojure
(reconcile-declaration opts)
```
Function.

Return a validated reconcile declaration from `opts`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L88-L94">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/resource-declaration">`resource-declaration`</a>
``` clojure
(resource-declaration opts)
```
Function.

Return a validated resource declaration from `opts`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L80-L86">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/seed-declaration">`seed-declaration`</a>
``` clojure
(seed-declaration opts)
```
Function.

Return a validated seed declaration from `opts`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L74-L78">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/statuses">`statuses`</a>




Closed lifecycle effect projection statuses.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L19-L22">Source</a></sub></p>
