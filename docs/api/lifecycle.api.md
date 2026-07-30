
-----
# <a name="skein.api.lifecycle.alpha">skein.api.lifecycle.alpha</a>


Authoring forms and data contracts for module lifecycle effects.

  Lifecycle declarations are printable source facts collected with a module's
  owner-complete contribution. The coordinator resolves their fully qualified
  callable symbols before publication and owns all retained live state.
  Malformed declarations fail at definition; unresolved or non-callable
  symbols fail validation before the candidate image is published.




## <a name="skein.api.lifecycle.alpha/defreconcile">`defreconcile`</a>
``` clojure
(defreconcile form-name doc opts)
```
Macro.

Define and collect one repeated desired-state reconciliation effect.

  The form is `(defreconcile name doc {:read-desired qualified-symbol,
  :read-actual qualified-symbol, :apply qualified-symbol,
  :on-removed qualified-symbol, :trigger-kinds #{keywords}, :after #{ids}})`.
  `:trigger-kinds` and `:after` are optional.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L138-L149">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/defresource">`defresource`</a>
``` clojure
(defresource form-name doc opts)
```
Macro.

Define and collect one paired resource acquisition and release effect.

  The form is `(defresource name doc {:open qualified-symbol,
  :close qualified-symbol, :scope :module-or-runtime, :after #{ids}})`.
  `:scope` defaults to `:module`; `:after` is optional.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L126-L136">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/defseed">`defseed`</a>
``` clojure
(defseed form-name doc opts)
```
Macro.

Define and collect one process-lifetime idempotent seed effect.

  The form is `(defseed name doc {:apply qualified-symbol, :after #{ids}})`.
  `:after` is optional.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L115-L124">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/kinds">`kinds`</a>




Closed lifecycle declaration kinds: `:seed`, `:resource`, and `:reconcile`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L13-L15">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/phases">`phases`</a>




Closed lifecycle execution phases.

  The coordinator reports `:validate`, `:resolve`, `:open`, `:apply`, `:close`,
  `:remove`, or `:runtime-stop` when a projection has a phase.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L17-L22">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/reconcile-declaration">`reconcile-declaration`</a>
``` clojure
(reconcile-declaration opts)
```
Function.

Return a validated reconcile declaration from `opts`.

  `opts` requires fully qualified `:read-desired`, `:read-actual`, `:apply`,
  and `:on-removed` callable symbols. It may include `:trigger-kinds` and
  `:after`, both sets of keywords.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L103-L113">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/resource-declaration">`resource-declaration`</a>
``` clojure
(resource-declaration opts)
```
Function.

Return a validated resource declaration from `opts`.

  `opts` requires fully qualified `:open` and `:close` callable symbols. It may
  include `:after`, a set of lifecycle effect ids, and `:scope`, either
  `:module` (the default) or `:runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L91-L101">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/seed-declaration">`seed-declaration`</a>
``` clojure
(seed-declaration opts)
```
Function.

Return a validated seed declaration from `opts`.

  `opts` requires `:apply`, a fully qualified callable symbol. It may include
  `:after`, a set of lifecycle effect ids that must run first.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L82-L89">Source</a></sub></p>

## <a name="skein.api.lifecycle.alpha/statuses">`statuses`</a>




Closed lifecycle effect projection statuses.

  A projection is `:planned`, `:applied`, `:preserved`, `:retained`,
  `:degraded`, `:blocked`, `:removed`, or `:not-attempted`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/lifecycle/alpha.clj#L24-L30">Source</a></sub></p>
