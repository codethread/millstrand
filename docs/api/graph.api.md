
-----
# <a name="millstrand.api.graph.alpha">millstrand.api.graph.alpha</a>


Explicit-runtime API for the named-query registry, query selection, strand
  hydration, graph traversal, and burn.

  This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by
  id, relation-scoped traversal, edge adjacency, and burn with its pre-commit
  gate and event fanout — and reads in that order. The query compiler lives
  in `millstrand.core.query`, the SQL engine in `millstrand.core.db`, and the shared
  lifecycle and dispatch plumbing in `millstrand.core.weaver.*`.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here.




## <a name="millstrand.api.graph.alpha/ancestor-root-ids">`ancestor-root-ids`</a>
``` clojure
(ancestor-root-ids runtime seed-ids)
(ancestor-root-ids runtime seed-ids opts)
```
Function.

Return ancestor root ids reachable from `seed-ids`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L325-L330">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/burn-by-ids!">`burn-by-ids!`</a>
``` clojure
(burn-by-ids! runtime ids)
(burn-by-ids! runtime ids req-ctx)
```
Function.

Delete strands by id and enqueue burn events for removed rows.

  Loads the before-images and deletes inside one transaction, running the
  `:strand/burn-before-commit` validation gate between the two so a rejecting
  hook rolls the whole burn back; then enqueues the `:strand/burned` event
  carrying requested ids, burned ids, and before-images. The `req-ctx` arity
  threads an explicit request-context map (the same shape
  `millstrand.api.batch.alpha/apply!` accepts) into the gate; the two-argument
  form derives its own burn context.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L389-L418">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/coerce-declared-params">`coerce-declared-params`</a>
``` clojure
(coerce-declared-params query-def params)
```
Function.

Coerce string-keyed CLI `params` to a definition's declared keyword names.

  Restricts `params` to `query-def`'s declared `:params`, returning a map keyed
  by the declared keywords for the names actually supplied. Unknown param names
  fail loudly with the offending names and the full declared set in ex-data,
  mirroring the socket read path's contract (batteries hand-rolled this against
  the JSON dispatch) so a spool's `--query` support rejects exactly the params
  the built-in path does. A definition with no declared `:params` accepts an
  empty map and rejects every name. The helper accepts a bare vector expression
  or a map with vector `:where` and optional sequential keyword `:params`; other
  definitions fail with ex-info at this seam.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L265-L288">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/conjoin-where">`conjoin-where`</a>
``` clojure
(conjoin-where query-def extra-where)
(conjoin-where query-def extra-where params)
```
Function.

Return a query definition that conjoins `extra-where` onto `query-def`.

  Resolves `query-def` to its where-expression — validating `params` against
  any declared `:params` — and returns the canonical
  `[:and <where> <extra-where>]` shape a caller then lists or readies with the
  same `params`. A nil `extra-where` returns `query-def` unchanged so callers
  thread an optional overlay (a state filter, say) without a surrounding
  conditional. `millstrand.core.query` owns the where grammar and resolves
  `[:param name]` references at compile time, not here. The helper accepts a
  bare vector expression or a map with vector `:where` and optional sequential
  keyword `:params`; other definitions fail with ex-info at this seam.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L237-L256">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/incoming-edges">`incoming-edges`</a>
``` clojure
(incoming-edges runtime to-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than
  a missing-id error (unlike subgraph/ancestor-root-ids seeds).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L358-L365">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/outgoing-edges">`outgoing-edges`</a>
``` clojure
(outgoing-edges runtime from-ids edge-type)
```
Function.

Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L371-L377">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/queries">`queries`</a>
``` clojure
(queries runtime)
```
Function.

Return registered query definitions keyed by canonical string name.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L129-L132">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/query-explain">`query-explain`</a>
``` clojure
(query-explain runtime query-name)
```
Function.

Describe a registered query definition and how CLI callers invoke it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L162-L176">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/query-ids">`query-ids`</a>
``` clojure
(query-ids runtime query-or-name params)
```
Function.

Return strand ids matching an ad hoc query definition or registered query name.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L184-L190">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/readiness-where">`readiness-where`</a>




Canonical where-expression for ready strands.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L45-L47">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/referenced-params">`referenced-params`</a>
``` clojure
(referenced-params query-def)
```
Function.

Return ordered distinct `[:param name]` keyword references in `query-def`.

  Reads the definition's where-expression — a map's `:where` or a bare vector —
  and reports each referenced parameter name in first-seen order without
  compiling SQL. This is the composable read a spool uses to describe a query's
  runtime params; `query-explain` is the by-name descriptive projection that
  carries this same list beside the definition's declared `:params`. The helper
  accepts a bare vector expression or a map with vector `:where` and optional
  sequential keyword `:params`; other definitions fail with ex-info at this seam.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L294-L306">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/register-query!">`register-query!`</a>
``` clojure
(register-query! runtime query-name query-def)
(register-query! runtime owner query-name query-def)
```
Function.

Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and validates that the
  definition compiles before it reaches the registry, so malformed query
  data fails loudly at registration time; `millstrand.core.query` is the
  grammar authority for definitions and compiles the stored definition at
  each use. Re-registering a name this owner already holds replaces that
  entry; a name another owner supplies collides loudly, and `replace-query!`
  is the deliberate override for it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L53-L68">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/replace-query!">`replace-query!`</a>
``` clojure
(replace-query! runtime query-name query-def)
(replace-query! runtime owner query-name query-def)
```
Function.

Replace an already-registered query, failing loudly when the name is absent.

  Same signature and return shape as `register-query!`. This is the deliberate
  override for a name that already exists; unlike `register-query!` it requires
  the name to be present. When another owner supplies the name — a
  module-published query, say — the override intent is recorded, which is what
  lets the direct entry keep shadowing the original across `runtime/refresh!`.
  `unregister-query!` retracts the shadow and the shadowed entry becomes
  effective again. Queries are value-registered, so replacing a definition is
  the whole iteration loop: there is no function body to redefine.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L77-L98">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/resolve-query">`resolve-query`</a>
``` clojure
(resolve-query runtime query-name)
```
Function.

Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L138-L143">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/strands-by-ids">`strands-by-ids`</a>
``` clojure
(strands-by-ids runtime ids)
```
Function.

Return normalized strands for ids, preserving first-seen input order.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L314-L317">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/subgraph">`subgraph`</a>
``` clojure
(subgraph runtime root-ids)
(subgraph runtime root-ids opts)
```
Function.

Return a normalized strand subgraph rooted at `root-ids`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L341-L349">Source</a></sub></p>

## <a name="millstrand.api.graph.alpha/unregister-query!">`unregister-query!`</a>
``` clojure
(unregister-query! runtime query-name)
(unregister-query! runtime owner query-name)
```
Function.

Retract `owner`'s own registration of `query-name`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-query!` rather than a way to delete another owner's
  query: retracting a shadow restores the shadowed definition as effective, and
  retracting a fresh claim leaves the name unregistered. Unregistering a name
  this owner never registered is an idempotent no-op. Returns `{:unregistered
  <canonical-name>}`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/graph/alpha.clj#L107-L121">Source</a></sub></p>
