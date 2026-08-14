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

Selection remains passive outside module collection so code-only reload and direct REPL evaluation do not alter live registries. Direct runtime and REPL `register/replace/unregister-*!` functions remain the imperative weaver-lifetime surface. Override intent belongs to registry-family selection: registry `use-<kind>!` forms accept the optional closed map `{:override? true}`, and registry `def<kind>!` forms may route that option to their generated use step. Lifecycle use forms accept declaration Vars only because lifecycle identity is already scoped to the consumer module.

This is a direct TEN-000@1 break. No compatibility namespace, alias, dual behavior, deprecation window, or runtime probe ships. Existing publishing modules migrate mechanically from `def<kind>` to `def<kind>!`; reusable libraries split into `def<kind>` and consumer-owned `use-<kind>!` calls where selection is wanted.

The authorized repository set is closed:

- Millstrand, including its shipped spools, workspace modules, fixtures, docs, specs, tests, and clj-kondo export.
- Millhouse, including its domain authoring forms and every spool maintained in that repository.
- agent-harness.
- devflow.
- codethread.

Do not migrate, patch, audit for completeness, or add compatibility for any other spool repository. The selected workspace keeps the unchanged Kanban v24 tag at peeled SHA `87f61bc2750e7026f3650235907db25f19b1536e`. No Kanban Guild, peering operation, or lifecycle resource belongs to the Millstrand surface.

The selected workspace still requires the separately pinned Kanban spool. Millstrand replaces its direct module declaration with a Millstrand-owned consumer adapter that selects ops `kanban` and `kanban-export`; queries `kanban-cards`, `kanban-pending`, and `kanban-epic-pending`; pattern `kanban-batch`; and bin `kanban-dash`. A disposable fresh-generation test proves that selected v24 adapter surface and retained-image replay before restart. The historical v24 full repository suite still requires the removed Guild root; that incompatibility is a gate fact, not a waiver or a full-suite success claim. This does not preserve the old publishing semantics or add a compatibility bridge.

## Implementation reconciliation — 2026-08-14

The proposal-time Kanban v25 target at peeled SHA `a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e` is superseded. The final outcome is the unchanged Kanban v24 tag and peeled SHA recorded above. The v25 target remains historical context only; it is not an active pin or acceptance target.

Cron's `defjob` joins the Var-based convention rather than retaining its current keyword-only shape. It takes a symbol name, a docstring, and the job map; the registry id is `(keyword name)`. `use-job!` accepts those declaration Vars, and `defjob!` also accepts an optional selection-options map before the job map. Duplicate keys within one `use-<kind>!` form fail locally. Repeating a key in separate top-level selection forms keeps the existing collector rule: the later contribution replaces the earlier one deterministically.

The coordinated cutover updates the Millstrand API, migrates and publishes the four authorized sibling repositories, updates the selected workspace's pins, and then restarts affected weavers so the new generation loads one coherent API. The user's restart authorization applies to that final cutover after the required releases and pins exist; it does not authorize an early restart while the API set is mixed.

The first delivery includes updated authoring tests, consumer proofs, clj-kondo macro analysis, contracts, and human documentation for the new three-form convention. A stricter published lint policy that warns about `def<kind>!` or `use-<kind>!` outside declared module source namespaces is a possible follow-up, not a prerequisite for this break.
