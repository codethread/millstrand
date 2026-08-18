# Spools

Spools are trusted, authorable Clojure loaded into the weaver. The `millstrand.spools.*` namespace family
is reserved for exactly this kind of code (see the [REPL API spec](../devflow/specs/repl-api.md)).
The agent family (`agent-run`, `executors.subagent`, `delegation`, `bench`) lives in
[`codethread/agent-harness.spool`](https://github.com/codethread/agent-harness.spool) under
`ct.spools.*`, the author-prefix convention for external spools.
The spools in this directory ship with Millstrand as working references. Use them directly, copy them
as starting points, or study them to author your own.

Every spool loads through one convention: an approved coordinate in `.millstrand/spools.edn` and a stable `runtime/module!` declaration guarded by its `:spools` roots. Full refresh resolves those roots, collects the module's selected authoring declarations, publishes its owner-complete contribution, and runs its lifecycle declarations. An inert `def<kind>` creates a reusable declaration Var, `use-<kind>!` selects declaration Vars for one module, and `def<kind>!` does both. Authoring forms are the durable path; explicit-runtime registration functions are the live code/test seam, and the in-process REPL supplies the same live verbs with the runtime implied. Evaluating an authoring form at a REPL validates it but publishes nothing. [Customising your workspace](../docs/spools/customisation.md) is the operational walkthrough; [Writing shared spools](../docs/spools/writing-shared-spools.md#author-contributions-with-kind-specific-forms) holds the complete selection contract.

Blessed alpha helpers such as `millstrand.api.peers.alpha` are also explicit-require userland APIs for trusted config and REPL workflows. Use that namespace's `peers` and `call!` helpers when a spool or repo config needs to discover and invoke same-machine sibling weavers.

## Approved family coordinates

Each `.millstrand/spools.edn` key names a family. A workspace-local family uses `:local/root`. A Git family pins `:git/url` and `:git/sha`, then maps its public root libs to checkout paths with `:roots`. A spool shipped in the Millstrand checkout uses the non-acquiring `:millstrand/source-root` coordinate:

```clojure
{:spools
 {millstrand.spools/batteries
  {:millstrand/source-root "spools/batteries"}

  ct.spools/agent-run
  {:git/url "https://github.com/codethread/agent-harness.spool.git"
   :git/sha "<40-lowercase-hex>"
   :git/tag "v1"
   :roots {ct.spools/agent-run "agent-run"
           ct.spools/delegation "delegation"
           ct.spools/bench "bench"}
   :requires {codethread/devflow "v2"}
   :millstrand/min "v3"}}}
```

Without `:roots`, a family supplies one root named by the family symbol at `"."`. Every root lib
has one owner. Release markers are positive `vN` strings. `:requires` sets minimum release markers
for other approved roots; `:millstrand/min` sets the minimum running Millstrand marker. A local development
override names the family once with `:local/root` and an explicit `:claims "vN"`; it inherits the
shared root map and compatibility floors.

## Doc triad

Each shipped spool's docs follow a three-file convention:

- **`<spool>.md`** — the **contract**: hand-authored guarantees, run lifecycle,
  and the attribute vocabulary. This is the load-bearing promise.
- **`<spool>.cookbook.md`** — authored **composition recipes**: how to shape
  real work out of the primitives, and *why* each shape is right. Present only
  where the value is in composition.
- **`<spool>.api.md`** — the **generated reference**: every public fn's
  signature, arity, and docstring, produced from source. Never hand-edit these;
  regenerate with `make api-docs`.

Signatures live only in the generated API doc; contracts and cookbooks link to them rather than restating them.

## Index

The batteries and unsafe-text-search spools remain in this checkout under `spools/<name>/src`, off the production weaver classpath. Their `{:millstrand/source-root "spools/<name>"}` coordinates resolve against the mill-selected Millstrand checkout. Workflow, Chime, Cron, Kanban, and the gate executors are external Millhouse roots, pinned by this workspace at [`7c615bd1`](https://github.com/codethread/millhouse.spool/tree/7c615bd1032be0e443c36fa12e8c50143e8014ff). Their contracts live in that repository. For publishing a spool by git coordinate, SHA-pinned approval, README dependency/activation snippets, Maven-only spool-root dependencies, and local development overrides, see [Writing shared spools](../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution).

| Spool | Coordinate (`.millstrand/spools.edn`) | Contract doc | Documentation | Purpose |
|---|---|---|---|---|
| `millstrand.spools.unsafe-text-search` **(UNSAFE)** | `:millstrand/source-root "spools/unsafe-text-search"` | [unsafe-text-search.md](./unsafe-text-search.md) | [unsafe-text-search.api.md](./unsafe-text-search.api.md) · [cookbook](./unsafe-text-search.cookbook.md) | **UNSAFE reference spool** — requires `millstrand.core.db` and runs SQL against the physical tables to `LIKE`-search titles and attribute values, including archived rows the query language cannot see. Registers the `search` op. A maintained example of breaking the namespace-tier rules in the open, not a blessed path; read its [Unsafe declaration](./unsafe-text-search.md#unsafe-declaration) before activating. |
| Millhouse workflow, Chime, Cron, Kanban, and gate executors | git, SHA-pinned `millhouse/spools` family | [Millhouse contracts][millhouse-docs] | [Millhouse documentation][millhouse-docs] | External domain spools consumed by this workspace. |
| Agent harness (`agent-run`, `delegation`, subagent executor, `bench`) | git, SHA-pinned `ct.spools/agent-run` family | [Agent harness README](https://github.com/codethread/agent-harness.spool#readme) | [Agent harness README](https://github.com/codethread/agent-harness.spool#readme) | Coding-agent runs, delegation, workflow gate execution, and benchmarks. |
| Devflow | git, SHA-pinned `codethread/devflow` family | [Devflow README](https://github.com/codethread/devflow.spool#readme) | [Devflow README](https://github.com/codethread/devflow.spool#readme) | Reference feature lifecycle built on the workflow engine. |
| `millstrand.spools.dresser` | *(none approved in this repo)* | [Dresser README](https://github.com/codethread/dresser.spool#readme) | [Dresser README](https://github.com/codethread/dresser.spool#readme) | Brings a repo onto shared working conventions and surfaces convention upgrades later. |
[millhouse-docs]: https://codethread.github.io/millhouse.spool/

## External spool consumption

External spool contracts live with their projects. The index links to each project's deployed documentation or README; `.millstrand/spools.edn` is the local source of truth for its approved coordinate and pin. [Writing shared spools](../docs/spools/writing-shared-spools.md) covers coordinate shape and publishing, [customisation](../docs/spools/customisation.md) covers activating config changes, and `strand spool status` shows what the runtime serves.

Tests consume each approved coordinate rather than carrying another pin. Developers override a family from a checkout with a gitignored `spools.local.edn` entry. For example, the Agent Harness family uses one entry:

```clojure
{:spools
 {ct.spools/agent-run
  {:local/root "../../agent-harness.spool"
   :claims "v1"}}}
```

The override inherits the shared family's `:roots`, `:requires`, and `:millstrand/min` declarations.

`millstrand.spools.dresser` ([`codethread/dresser.spool`](https://github.com/codethread/dresser.spool#readme)) is also external, but this repo approves no coordinate for it. Dresser is activated in whichever workspace drives a setup run, and the repo being set up needs no weaver or spool approvals of its own, so consumption is a per-operator choice. Its README carries the dependency and activation recipe.

## Shipped source-root: batteries

| Spool | Coordinate (`.millstrand/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `millstrand.spools.batteries` | `:millstrand/source-root "spools/batteries"` | [batteries.md](./batteries.md) | [batteries.api.md](./batteries.api.md) · [cookbook](./batteries.cookbook.md) | Shipped core strand command surface as registered ops: add/update/show/supersede/burn/list/ready/subgraph plus `weave`, the `query`/`pattern` registry reads, and `spool` verbs including the folded `spool status` read. Invocable arg-spec leaves declare their own hook and deadline classes. |

`mill init` opts a workspace into batteries by seeding `millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}` and a module guarded by `:spools ['millstrand.spools/batteries]`. The relative coordinate is machine-independent: the running weaver resolves it against the mill-selected Millstrand checkout and persists no absolute source path. Deleting the seeded entry is the supported visible opt-out; a hand-written `{:spools {}}` world has no batteries ops. Batteries is not on the production weaver classpath, so it follows the same approval and activation path as every other spool.

## `util` and `format` left the spool family

`millstrand.spools.util` and `millstrand.spools.format` were never activatable spools — they registered no ops and no world declared modules for them; they were authoring libraries other spools built on. Both have left `millstrand.spools.*` for base-classpath `src/`: `format` is deleted in favor of the already-blessed `millstrand.api.format.alpha` (`fill`/`reflow`), and `util` is promoted to the blessed `millstrand.api.spool.alpha` (`fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until!`, and `entity-projection` — which fails loudly unless its strand-shaped input carries `:id`/`:title`/`:state`/`:attributes` and returns exactly those keys) — the accretion-compatible home for the spool-authoring helpers every reference spool leans on. `poll-until!` takes the runtime's Clock and a relative timeout, so manual time works without changing spool code. After this move, `millstrand.spools.*` is exactly "activatable spools" and nothing else.

## Reference examples

- Each retained contract doc ends with worked examples.
- The test suites drive every documented behavior against a real weaver runtime and double as executable examples: [`test/clojure/millstrand/spools/batteries_test.clj`](../test/clojure/millstrand/spools/batteries_test.clj), [`test/clojure/millstrand/spools/unsafe_text_search_test.clj`](../test/clojure/millstrand/spools/unsafe_text_search_test.clj), and the standalone external spool test suites.

## Using and extending

- Strand **attributes are the extension surface**. Build your own conventions instead of waiting for engine fields, and give them new names only for new concepts ([the vocabulary rule](../docs/spools/writing-shared-spools.md#the-rules-for-shared-spools)).
- A spool publishes owner-complete kind entries through contribution forms and owns runtime effects through lifecycle forms. See [Writing shared spools](../docs/spools/writing-shared-spools.md) for the authoring grammar and each contract doc for exact behavior.
- To author and load your own spool from a workspace-local root, follow
  [Authoring your own spool code](../docs/spools/customisation.md#workspace-modules-and-local-spools).
