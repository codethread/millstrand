
-----
# <a name="millstrand.api.patterns.alpha">millstrand.api.patterns.alpha</a>


Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `millstrand.core.db`; the shared
  lifecycle and dispatch plumbing in `millstrand.core.weaver.*`.




## <a name="millstrand.api.patterns.alpha/explain">`explain`</a>
``` clojure
(explain runtime pattern-name)
```
Function.

Describe a registered weave pattern and its input contract in `runtime`.

  The input contract is the shared `millstrand.api.spec.alpha` projection:
  `:contract` is the nested node tree, `:template` the copyable JSON skeleton,
  and `:spec-forms` the printed form graph, all resolved against the live spec
  registry with no predicate invoked. Missing patterns or unregistered input
  specs fail loudly. The returned map conforms to `::explain-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L138-L158">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/patterns">`patterns`</a>
``` clojure
(patterns runtime)
```
Function.

Return registered weave pattern metadata from `runtime`, ordered by name.

  Each returned entry conforms to `::millstrand.core.specs/pattern-entry`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L91-L97">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/register-pattern!">`register-pattern!`</a>
``` clojure
(register-pattern! runtime pattern-name fn-sym input-spec)
(register-pattern! runtime pattern-name doc fn-sym input-spec)
(register-pattern! runtime owner pattern-name doc fn-sym input-spec)
```
Function.

Register a trusted weaver pattern handler and input spec in `runtime`.

  Registration input conforms to `::millstrand.core.specs/pattern-registration`,
  and the returned entry conforms to `::millstrand.core.specs/pattern-entry`.
  Re-registering a name this owner already holds replaces that entry; a name
  another owner supplies collides loudly, and `replace-pattern!` is the
  deliberate override for it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L32-L47">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/replace-pattern!">`replace-pattern!`</a>
``` clojure
(replace-pattern! runtime pattern-name fn-sym input-spec)
(replace-pattern! runtime pattern-name doc fn-sym input-spec)
(replace-pattern! runtime owner pattern-name doc fn-sym input-spec)
```
Function.

Replace an already-registered weave pattern, failing loudly when absent.

  Same signature and return shape as `register-pattern!`. This is the deliberate
  override for a name that already exists; unlike `register-pattern!` it requires
  the name to be present. When another owner supplies the name — a
  module-published pattern, say — the override intent is recorded, which is what
  lets the direct entry keep shadowing the original across `runtime/refresh!`.
  `unregister-pattern!` retracts the shadow and the shadowed entry becomes
  effective again. Patterns resolve their handler symbol at invocation, so
  iterating a body under a stable contract needs no registry call at all;
  reach for this when the contract or the symbol itself changes.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L49-L73">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/resolve-pattern">`resolve-pattern`</a>
``` clojure
(resolve-pattern runtime pattern-name)
```
Function.

Return the registered weave pattern for a name.

  Accepts a simple symbol, keyword, or raw CLI string (trimmed, optional leading
  colon), matching `millstrand.api.graph.alpha/resolve-query` string handling.

  Missing patterns fail loudly. The returned entry conforms to
  `::millstrand.core.specs/pattern-entry`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L99-L113">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/unregister-pattern!">`unregister-pattern!`</a>
``` clojure
(unregister-pattern! runtime pattern-name)
(unregister-pattern! runtime owner pattern-name)
```
Function.

Retract `owner`'s own registration of `pattern-name` from `runtime`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-pattern!` rather than a way to delete another owner's
  pattern: retracting a shadow restores the shadowed entry as effective, and
  retracting a fresh claim leaves the name unregistered. Unregistering a name
  this owner never registered is an idempotent no-op. Returns `{:unregistered
  <canonical-name>}`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L75-L89">Source</a></sub></p>

## <a name="millstrand.api.patterns.alpha/weave!">`weave!`</a>
``` clojure
(weave! runtime pattern-name input)
(weave! runtime pattern-name input req-ctx)
```
Function.

Validate pattern input, invoke the pattern, and apply its create-only batch.

  The four-argument arity threads an explicit request-context map for trusted
  callers (the connected-client tier); the three-argument arity derives its own
  weave context. A caller-supplied context conforms to
  `::millstrand.core.specs/request-context`; the pre-commit hook context conforms to
  `::millstrand.core.specs/batch-hook-context`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/patterns/alpha.clj#L172-L214">Source</a></sub></p>
