# Brief: selectable authoring declarations

User brief, 2026-08-13 session (epic `z2yhh`, intake card `j2dj2`, workflow run `selectable-authoring-declarations`).

Millstrand's authoring forms currently define a Var and contribute it whenever the form is evaluated as part of a module source. That coupling makes a spool namespace an activation decision: a library cannot offer a collection of ops, queries, workflows, lifecycle effects, or domain declarations for a consumer to select in its own module.

The authoring surface will split definition from selection through one naming convention per declaration kind:

```clojure
def<kind>       ; define an inert reusable declaration
use-<kind>!     ; contribute an existing declaration Var
def<kind>!      ; define and contribute
```

There is no generic `use!`. Explicit forms such as `use-op!`, `use-query!`, `use-workflow!`, and `use-rule!` keep the declaration kind visible in source, documentation, grep results, lint output, and error messages. A shared ecosystem utility named `defauthoring` lets a domain spool define its own `def<kind>`, `use-<kind>!`, and `def<kind>!` family over the common declaration protocol.

Library code defines declarations without activating them:

```clojure
(millstrand/defop blah ...)
(millstrand/defquery blah-query ...)
(workflow/defworkflow blah-workflow ...)
```

The consumer module owns selection:

```clojure
(millstrand/use-op! lib/blah)
(millstrand/use-query! lib/blah-query)
(workflow/use-workflow! lib/blah-workflow)
```

A module that owns both definition and selection uses the permanent shorthand:

```clojure
(millstrand/defop! my-op ...)
```

Every `def<kind>` defines exactly the name it is given. `defop blah` therefore defines `blah`, not `blah-op`; the same rule applies to executor, rule, and other forms that currently synthesize a differently named handler Var. The Var retains its natural value, while validated declaration metadata carries the kind, key, registration entry, source identity, and collection behavior needed by the matching `use-<kind>!` form. A typed use form rejects a Var from another declaration kind.

Selection remains passive outside module collection so code-only reload and direct REPL evaluation do not alter live registries. Direct runtime and REPL `register/replace/unregister-*!` functions remain the imperative weaver-lifetime surface. Override intent belongs to selection: `use-<kind>!` accepts `{:override? true}`, and `def<kind>!` may route that option to its generated use step.

This is a direct TEN-000@1 break. No compatibility namespace, alias, dual behavior, deprecation window, or runtime probe ships. Existing publishing modules migrate mechanically from `def<kind>` to `def<kind>!`; reusable libraries split into `def<kind>` and consumer-owned `use-<kind>!` calls where selection is wanted.

The authorized repository set is closed:

- Millstrand, including its shipped spools, workspace modules, fixtures, docs, specs, tests, and clj-kondo export.
- Millhouse, including its domain authoring forms and every spool maintained in that repository.
- agent-harness.
- devflow.
- codethread.

Do not migrate, patch, audit for completeness, or add compatibility for any other spool repository. Separate repositories such as Kanban and third-party spools are explicitly outside this epic even if they still use the old forms after the cutover.

The selected workspace still requires the separately pinned Kanban spool. Millstrand will keep that pin unchanged and replace its direct module declaration with a Millstrand-owned consumer adapter. On a fresh generation, the unchanged Kanban source is compiled against the new inert `def<kind>` macros, producing declaration Vars; the adapter explicitly selects the declarations the workspace uses. A disposable fresh-generation test must prove that path before restart. This changes no Kanban source and does not preserve the old publishing semantics.

Cron's `defjob` joins the Var-based convention rather than retaining its current keyword-only shape. It takes a symbol name, a docstring, and the job map; the registry id is `(keyword name)`. `use-job!` accepts those declaration Vars, and `defjob!` also accepts an optional selection-options map before the job map. Duplicate keys within one `use-<kind>!` form fail locally. Repeating a key in separate top-level selection forms keeps the existing collector rule: the later contribution replaces the earlier one deterministically.

The coordinated cutover updates the Millstrand API, migrates and publishes the four authorized sibling repositories, updates the selected workspace's pins, and then restarts affected weavers so the new generation loads one coherent API. The user's restart authorization applies to that final cutover after the required releases and pins exist; it does not authorize an early restart while the API set is mixed.

The first delivery includes updated authoring tests, consumer proofs, clj-kondo macro analysis, contracts, and human documentation for the new three-form convention. A stricter published lint policy that warns about `def<kind>!` or `use-<kind>!` outside declared module source namespaces is a possible follow-up, not a prerequisite for this break.
