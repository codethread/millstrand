
-----
# <a name="millstrand.spools.batteries">millstrand.spools.batteries</a>


Shipped core strand command surface as parser-backed weaver ops.

  Batteries declares the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — through
  `millstrand.api.millstrand.alpha/defop`. Their
  `:arg-spec` is parsed by `millstrand.api.cli.alpha`. Each op delegates to the same
  `millstrand.api.*.alpha` calls the JSON socket dispatch uses and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  `defop` declarations are the durable, owner-complete source for this surface.
  Explicit-runtime registration functions are the live code and test seam, and
  `millstrand.repl` supplies the same verbs with the runtime implied for an in-process
  session. Evaluating an authoring form outside module collection defines its Var
  but publishes no op.

  Production loading follows the ordinary approved-spool path. `mill init` seeds
  `millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}` in
  `spools.edn`, and its startup module names that root in `:spools`. Deleting
  the seeded entry is the supported opt-out; a workspace without it has no
  batteries ops.

  Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive
  help, and where it adds value they carry closed `:annotations` sub-maps
  (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose.
  `failure-modes` reference the batteries-owned glossary outcomes seeded by the
  batteries module (the load-order contract, DELTA-Dtf-002.CC7).

  Batteries also EXPORTS `default-help-transform` — the reference default help
  transform (DELTA-Dtf-002.CC1): one recursive renderer over the uniform fractal
  node (DELTA-Dtf-001.CC2) with no per-level branch. It is exported for trusted
  config election and never auto-registers.

  Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value`
  is a repeatable, highest-precedence string map whose values may be payload
  references; `--attributes` references a JSON object of typed bulk attributes at
  lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state`
  accepts `active|closed` for mutations and `active|closed|replaced` for `list`
  filtering.




## <a name="millstrand.spools.batteries/add-op">`add-op`</a>
``` clojure
(add-op ctx)
```
Function.

Create a strand with merged attributes, optional state, and outgoing edges.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1401-L1401">Source</a></sub></p>

## <a name="millstrand.spools.batteries/await-op">`await-op`</a>
``` clojure
(await-op ctx)
```
Function.

Block until a named query's result count is inside the requested band.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1518-L1518">Source</a></sub></p>

## <a name="millstrand.spools.batteries/batteries-glossary-seed">`batteries-glossary-seed`</a>




Seed the process-lifetime Batteries failure glossary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1640-L1642">Source</a></sub></p>

## <a name="millstrand.spools.batteries/blockers-active">`blockers-active`</a>




Return active blockers of the strand identified by `id`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1509-L1514">Source</a></sub></p>

## <a name="millstrand.spools.batteries/burn-op">`burn-op`</a>
``` clojure
(burn-op ctx)
```
Function.

Physically delete one strand by id and return the burn summary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1451-L1451">Source</a></sub></p>

## <a name="millstrand.spools.batteries/children-active">`children-active`</a>




Return active children of the strand identified by `parent`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1502-L1507">Source</a></sub></p>

## <a name="millstrand.spools.batteries/default-help-transform">`default-help-transform`</a>
``` clojure
(default-help-transform envelope {:keys [is-tty]})
```
Function.

Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

  The batteries reference default help transform (DELTA-Dtf-002.CC1): a full
  envelope plus terminal capabilities → the string the CLI relays verbatim. It is EXPORTED for trusted
  `init.clj` election through `register-default-help-transform!` (Task 8) and is
  deliberately not auto-registered by the module, so a fresh world keeps the
  raw-JSON floor (DELTA-Dtf-002.D1).

  Both members of the one help-schema family render through the single uniform
  node renderer (`render-node`): the detail envelope carrying `node`, and the
  no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only
  branch is which envelope family this is — an envelope-shape choice, never a
  per-node-level one, so the recursive node renderer stays uniform at every depth
  (the forcing-function invariant, DELTA-Dtf-003.D1). ANSI color is added only
  when the caller reports `:is-tty true`; redirected and agent output stays plain.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1369-L1389">Source</a></sub></p>

## <a name="millstrand.spools.batteries/list-op">`list-op`</a>
``` clojure
(list-op ctx)
```
Function.

List lean-projected strands, optionally filtered by lifecycle state or a named query.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1457-L1457">Source</a></sub></p>

## <a name="millstrand.spools.batteries/note-op">`note-op`</a>
``` clojure
(note-op ctx)
```
Function.

Append a note to a target strand's memory via the note primitive.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1580-L1580">Source</a></sub></p>

## <a name="millstrand.spools.batteries/notes-op">`notes-op`</a>
``` clojure
(notes-op ctx)
```
Function.

Return a target strand's notes in note/at order.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1590-L1590">Source</a></sub></p>

## <a name="millstrand.spools.batteries/pattern-op">`pattern-op`</a>
``` clojure
(pattern-op ctx)
```
Function.

Introspect registered weave patterns: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1568-L1568">Source</a></sub></p>

## <a name="millstrand.spools.batteries/query-op">`query-op`</a>
``` clojure
(query-op ctx)
```
Function.

Introspect registered named queries: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1556-L1556">Source</a></sub></p>

## <a name="millstrand.spools.batteries/read-limit">`read-limit`</a>
``` clojure
(read-limit rt)
```
Function.

Return the runtime's batteries read-result cap for CLI list/ready ops.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L238-L241">Source</a></sub></p>

## <a name="millstrand.spools.batteries/ready-op">`ready-op`</a>
``` clojure
(ready-op ctx)
```
Function.

List lean-projected ready strands, optionally from a named query result set.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1474-L1474">Source</a></sub></p>

## <a name="millstrand.spools.batteries/seed-batteries-glossary!">`seed-batteries-glossary!`</a>
``` clojure
(seed-batteries-glossary! ctx)
```
Function.

Seed Batteries' process-lifetime failure glossary.

  Input conforms to `::seed-context`; the result conforms to `::seed-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1627-L1638">Source</a></sub></p>

## <a name="millstrand.spools.batteries/set-read-limit!">`set-read-limit!`</a>
``` clojure
(set-read-limit! rt limit)
```
Function.

Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L243-L251">Source</a></sub></p>

## <a name="millstrand.spools.batteries/show-op">`show-op`</a>
``` clojure
(show-op ctx)
```
Function.

Return one normalized strand by id.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1438-L1438">Source</a></sub></p>

## <a name="millstrand.spools.batteries/spool-op">`spool-op`</a>
``` clojure
(spool-op ctx)
```
Function.

Dispatch validated `strand spool about|add|bump|status` inputs and results.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1606-L1606">Source</a></sub></p>

## <a name="millstrand.spools.batteries/strand-active">`strand-active`</a>




Return the active strand identified by `id`, when it exists.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1495-L1500">Source</a></sub></p>

## <a name="millstrand.spools.batteries/strand-closed">`strand-closed`</a>




Return the closed strand identified by `id`, when it exists.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1488-L1493">Source</a></sub></p>

## <a name="millstrand.spools.batteries/subgraph-op">`subgraph-op`</a>
``` clojure
(subgraph-op ctx)
```
Function.

Return a relation-scoped subgraph rooted at one strand.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1533-L1533">Source</a></sub></p>

## <a name="millstrand.spools.batteries/supersede-op">`supersede-op`</a>
``` clojure
(supersede-op ctx)
```
Function.

Replace one strand with another and return the supersession result.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1444-L1444">Source</a></sub></p>

## <a name="millstrand.spools.batteries/update-op">`update-op`</a>
``` clojure
(update-op ctx)
```
Function.

Patch one strand's title, state, attributes, and outgoing edges.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1418-L1418">Source</a></sub></p>

## <a name="millstrand.spools.batteries/vocab-op">`vocab-op`</a>
``` clojure
(vocab-op ctx)
```
Function.

List the runtime's vocabulary declarations, optionally narrowed to one kind.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1597-L1597">Source</a></sub></p>

## <a name="millstrand.spools.batteries/weave-op">`weave-op`</a>
``` clojure
(weave-op ctx)
```
Function.

Apply a registered create-only weave pattern to one JSON input value.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1545-L1545">Source</a></sub></p>
