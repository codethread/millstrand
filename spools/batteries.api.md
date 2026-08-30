---

# <a name="millstrand.spools.batteries">millstrand.spools.batteries</a>

Shipped core strand command surface as parser-backed weaver ops.

Batteries declares the everyday strand operations — add/update/show/supersede/ burn/list/ready/subgraph, the create-only `weave` op, and the read-only `query`/`pattern` registry-introspection ops — through `millstrand.api.millstrand.alpha/defop!`. Their `:arg-spec` is parsed by `millstrand.api.cli.alpha`. Each op delegates to the same `millstrand.api.*.alpha` calls the JSON socket dispatch uses and returns the same JSON shapes, so the ops are reachable through `strand <name>` at the CLI root. The namespace owns no module-level state: op handlers read the runtime from their invocation context (`:op/runtime`).

`defop!` declarations are the durable source for this surface. They define Vars and select their declarations during module collection; the selected set publishes as this module's complete, owner-complete ops partition. Explicit-runtime registration functions are the live code and test seam, and `millstrand.repl` supplies the same verbs with the runtime implied for an in-process session. Evaluating an authoring form outside module collection defines its Var but publishes no op.

Batteries also defines an inert `runbook` op (`defop`, not `defop!`): the opinionated strand-tracking loop, loaded from classpath markdown. A workspace elects it with `millstrand/use-op!`.

Production loading makes Batteries available as an ordinary library in `deps.edn` (or `deps.local.edn`) and activates it explicitly with an owner-complete `runtime/module!` declaration in `init.clj` (or `init.local.clj`). Dependency presence does not activate Batteries; deleting either dependency or declaration is the supported opt-out.

Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive help, and where it adds value they carry closed `:annotations` sub-maps (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose. `failure-modes` reference the batteries-owned glossary outcomes seeded by the batteries module (the load-order contract, DELTA-Dtf-002.CC7).

Batteries also EXPORTS `default-help-transform` — the reference default help transform (DELTA-Dtf-002.CC1): one recursive renderer over the uniform fractal node (DELTA-Dtf-001.CC2) with no per-level branch. It is exported for trusted config election and never auto-registers.

Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value` is a repeatable, highest-precedence string map whose values may be payload references; `--attributes` references a JSON object of typed bulk attributes at lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state` accepts `active|closed` for mutations and `active|closed|replaced` for `list` filtering.

## <a name="millstrand.spools.batteries/add">`add`</a>

```clojure
(add ctx)
```

Function.

Create a strand with merged attributes, optional state, and outgoing edges.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L872-L872">Source</a></sub></p>

## <a name="millstrand.spools.batteries/await">`await`</a>

```clojure
(await ctx)
```

Function.

Block until a named query's result count is inside the requested band.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L989-L989">Source</a></sub></p>

## <a name="millstrand.spools.batteries/batteries-glossary-seed">`batteries-glossary-seed`</a>

Seed the process-lifetime Batteries failure glossary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1106-L1108">Source</a></sub></p>

## <a name="millstrand.spools.batteries/blockers-active">`blockers-active`</a>

Return active blockers of the strand identified by `id`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L980-L985">Source</a></sub></p>

## <a name="millstrand.spools.batteries/burn">`burn`</a>

```clojure
(burn ctx)
```

Function.

Physically delete one strand by id and return the burn summary.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L922-L922">Source</a></sub></p>

## <a name="millstrand.spools.batteries/children-active">`children-active`</a>

Return active children of the strand identified by `parent`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L973-L978">Source</a></sub></p>

## <a name="millstrand.spools.batteries/default-help-transform">`default-help-transform`</a>

```clojure
(default-help-transform envelope {:keys [is-tty]})
```

Function.

Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

The batteries reference default help transform (DELTA-Dtf-002.CC1): a full envelope plus terminal capabilities → the string the CLI relays verbatim. It is EXPORTED for trusted `init.clj` election through `register-default-help-transform!` (Task 8) and is deliberately not auto-registered by the module, so a fresh world keeps the raw-JSON floor (DELTA-Dtf-002.D1).

Both members of the one help-schema family render through the single uniform node renderer (`render-node`): the detail envelope carrying `node`, and the no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only branch is which envelope family this is — an envelope-shape choice, never a per-node-level one, so the recursive node renderer stays uniform at every depth (the forcing-function invariant, DELTA-Dtf-003.D1). ANSI color is added only when the caller reports `:is-tty true`; redirected and agent output stays plain.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L840-L860">Source</a></sub></p>

## <a name="millstrand.spools.batteries/list">`list`</a>

```clojure
(list ctx)
```

Function.

List lean-projected strands, optionally filtered by lifecycle state or a named query.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L928-L928">Source</a></sub></p>

## <a name="millstrand.spools.batteries/note">`note`</a>

```clojure
(note ctx)
```

Function.

Append a note to a target strand's memory via the note primitive.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1051-L1051">Source</a></sub></p>

## <a name="millstrand.spools.batteries/notes">`notes`</a>

```clojure
(notes ctx)
```

Function.

Return a target strand's notes in note/at order.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1061-L1061">Source</a></sub></p>

## <a name="millstrand.spools.batteries/pattern">`pattern`</a>

```clojure
(pattern ctx)
```

Function.

Introspect registered weave patterns: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1039-L1039">Source</a></sub></p>

## <a name="millstrand.spools.batteries/query">`query`</a>

```clojure
(query ctx)
```

Function.

Introspect registered named queries: list all metadata or explain one.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1027-L1027">Source</a></sub></p>

## <a name="millstrand.spools.batteries/read-limit">`read-limit`</a>

```clojure
(read-limit rt)
```

Function.

Return the runtime's batteries read-result cap for CLI list/ready ops.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L122-L125">Source</a></sub></p>

## <a name="millstrand.spools.batteries/ready">`ready`</a>

```clojure
(ready ctx)
```

Function.

List lean-projected ready strands, optionally from a named query result set.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L945-L945">Source</a></sub></p>

## <a name="millstrand.spools.batteries/runbook">`runbook`</a>

```clojure
(runbook _ctx)
```

Function.

Return the batteries strand-tracking runbook.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1080-L1080">Source</a></sub></p>

## <a name="millstrand.spools.batteries/seed-batteries-glossary!">`seed-batteries-glossary!`</a>

```clojure
(seed-batteries-glossary! ctx)
```

Function.

Seed Batteries' process-lifetime failure glossary.

Input conforms to `::seed-context`; the result conforms to `::seed-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1093-L1104">Source</a></sub></p>

## <a name="millstrand.spools.batteries/set-read-limit!">`set-read-limit!`</a>

```clojure
(set-read-limit! rt limit)
```

Function.

Set the runtime's batteries read-result cap for CLI list/ready ops.

Intended for trusted workspace config. Invalid values fail loudly instead of falling back to the default cap.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L127-L135">Source</a></sub></p>

## <a name="millstrand.spools.batteries/show">`show`</a>

```clojure
(show ctx)
```

Function.

Return one normalized strand by id.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L909-L909">Source</a></sub></p>

## <a name="millstrand.spools.batteries/strand-active">`strand-active`</a>

Return the active strand identified by `id`, when it exists.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L966-L971">Source</a></sub></p>

## <a name="millstrand.spools.batteries/strand-closed">`strand-closed`</a>

Return the closed strand identified by `id`, when it exists.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L959-L964">Source</a></sub></p>

## <a name="millstrand.spools.batteries/subgraph">`subgraph`</a>

```clojure
(subgraph ctx)
```

Function.

Return a relation-scoped subgraph rooted at one strand.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1004-L1004">Source</a></sub></p>

## <a name="millstrand.spools.batteries/supersede">`supersede`</a>

```clojure
(supersede ctx)
```

Function.

Replace one strand with another and return the supersession result.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L915-L915">Source</a></sub></p>

## <a name="millstrand.spools.batteries/update">`update`</a>

```clojure
(update ctx)
```

Function.

Patch one strand's title, state, attributes, and outgoing edges.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L889-L889">Source</a></sub></p>

## <a name="millstrand.spools.batteries/weave">`weave`</a>

```clojure
(weave ctx)
```

Function.

Apply a registered create-only weave pattern to one JSON input value.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/spools/batteries/src/millstrand/spools/batteries.clj#L1016-L1016">Source</a></sub></p>
