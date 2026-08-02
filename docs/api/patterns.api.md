
-----
# <a name="skein.api.patterns.alpha">skein.api.patterns.alpha</a>


Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `skein.core.db`; the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`.




## <a name="skein.api.patterns.alpha/explain">`explain`</a>
``` clojure
(explain runtime pattern-name)
```
Function.

Describe a registered weave pattern and its input contract in `runtime`.

  The input contract is the shared `skein.api.spec.alpha` projection:
  `:contract` is the nested node tree, `:template` the copyable JSON skeleton,
  and `:spec-forms` the printed form graph, all resolved against the live spec
  registry with no predicate invoked. Missing patterns or unregistered input
  specs fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L65-L82">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/patterns">`patterns`</a>
``` clojure
(patterns runtime)
```
Function.

Return registered weave pattern metadata from `runtime`, ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L45-L48">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/register-pattern!">`register-pattern!`</a>
``` clojure
(register-pattern! runtime pattern-name fn-sym input-spec)
(register-pattern! runtime pattern-name doc fn-sym input-spec)
(register-pattern! runtime owner pattern-name doc fn-sym input-spec)
```
Function.

Register a trusted weaver pattern handler and input spec in `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L28-L43">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/resolve-pattern">`resolve-pattern`</a>
``` clojure
(resolve-pattern runtime pattern-name)
```
Function.

Return the registered weave pattern for a name.

  Accepts a simple symbol, keyword, or raw CLI string (trimmed, optional leading
  colon), matching `skein.api.graph.alpha/resolve-query` string handling.

  Missing patterns fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L50-L63">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/weave!">`weave!`</a>
``` clojure
(weave! runtime pattern-name input)
(weave! runtime pattern-name input req-ctx)
```
Function.

Validate pattern input, invoke the pattern, and apply its create-only batch.

  The four-argument arity threads an explicit request-context map for trusted
  callers (the connected-client tier); the three-argument arity derives its own
  weave context.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L89-L123">Source</a></sub></p>
