# Spools

Spools are trusted, authorable Clojure loaded into the weaver. The `millstrand.spools.*` namespace family
is reserved for exactly this kind of code (see the [REPL API spec](../devflow/specs/repl-api.md)).
The agent family (`agent-run`, `executors.subagent`, `delegation`, `bench`) lives in
[`codethread/agent-harness.spool`](https://github.com/codethread/agent-harness.spool) under
`ct.spools.*`, the author-prefix convention used by external spools such as `ct.spools.kanban`.
The spools in this directory ship with Millstrand as working references. Use them directly, copy them
as starting points, or study them to author your own.

Every spool loads through one convention: an approved coordinate in `.millstrand/spools.edn` and a stable `runtime/module!` declaration guarded by its `:spools` roots. Full refresh resolves those roots, collects the module's authoring forms, publishes its owner-complete contribution, and runs its lifecycle declarations. Authoring forms are the durable path and are collected from module source; explicit-runtime registration functions are the live code/test seam, and the in-process REPL supplies the same live verbs with the runtime implied. Evaluating an authoring form at a REPL defines its Var but publishes nothing. [Customising your workspace](../docs/spools/customisation.md) is the operational walkthrough.

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

The batteries and unsafe-text-search spools remain in this checkout under `spools/<name>/src`, off the production weaver classpath. Their `{:millstrand/source-root "spools/<name>"}` coordinates resolve against the mill-selected Millstrand checkout. Workflow, Chime, Cron, and the gate executors are external Millhouse roots, pinned by this workspace at [`8f386b09`](https://github.com/codethread/millhouse.spool/tree/8f386b09fb8e8506a3c38105dce8e8552142dbf8). Their contracts live in that repository. For publishing a spool by git coordinate, SHA-pinned approval, README dependency/activation snippets, Maven-only spool-root dependencies, and local development overrides, see [Writing shared spools](../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution).

| Spool | Coordinate (`.millstrand/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `millstrand.spools.unsafe-text-search` **(UNSAFE)** | `:millstrand/source-root "spools/unsafe-text-search"` | [unsafe-text-search.md](./unsafe-text-search.md) | [unsafe-text-search.api.md](./unsafe-text-search.api.md) · [cookbook](./unsafe-text-search.cookbook.md) | **UNSAFE reference spool** — requires `millstrand.core.db` and runs SQL against the physical tables to `LIKE`-search titles and attribute values, including archived rows the query language cannot see. Registers the `search` op. A maintained example of breaking the namespace-tier rules in the open, not a blessed path; read its [Unsafe declaration](./unsafe-text-search.md#unsafe-declaration) before activating. |
| Millhouse workflow, Chime, Cron, and gate executors | git, SHA-pinned `millhouse/spools` family | [Millhouse contracts][millhouse-contracts] | [Millhouse documentation][millhouse-contracts] | External domain spools consumed by this workspace. |
| `ct.spools.agent-run` | git, sha-pinned (see below) | [agent-run/README.md][agent-run-contract] | [agent-run.api.md][agent-run-api] · [cookbook][agent-run-cookbook] | Agent-run **engine**: readiness-driven headless coding-agent runs plus interactive multiplexer sessions (backend registry, claims-model reaping), harness aliases, crash reconciliation, storage-enforced write-once run memory, and the preamble seam. Registers no ops. |
| `ct.spools.delegation` | git, sha-pinned (see below) | [delegation/README.md][delegation-contract] | [delegation.api.md][delegation-api] · [cookbook][delegation-cookbook] | Cross-harness subagent surface over agent-run: the `strand agent` verbs, the `agent-plan` weave pattern, delegation/retry/status, and the worker + coordinator guidance. |
| `ct.spools.executors.subagent` | git, sha-pinned `agent-run` root (see below) | [agent-run/subagent.md][subagent-contract] | [subagent.api.md][subagent-api] · [cookbook][subagent-cookbook] | Workflow gate bridge: fulfills ready `:subagent` gates by spawning agent-run runs and delivering successful results through `workflow/complete!`. |
| `ct.spools.kanban` | git, sha-pinned (see below) | [kanban.md](https://github.com/codethread/kanban.spool/blob/87f61bc2750e7026f3650235907db25f19b1536e/kanban.md) | — | User-facing kanban board: feature/epic cards, refinement/pending/claimed/in_review lanes, notes and handovers via `strand kanban`; epics have a reversible finish lifecycle (`finish` completes or abandon-cascades, `reopen` inverts an abandon). |
| `ct.spools.bench` | git, sha-pinned (see below) | [bench/README.md][bench-contract] | [bench.api.md][bench-api] | Deterministic, containerized benchmarking of coding-agent harnesses: pinned repo/prompt/memory overlays, bench-owned entry execution, normalized metrics, and an agent-run served judge. |
| `ct.spools.devflow` | git, sha-pinned (see below) | [devflow.md](https://github.com/codethread/devflow.spool/blob/528e0ba636e28032985f7f9706c8350e9f785d97/devflow.md) | — | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |
| `millstrand.spools.dresser` | *(none approved in this repo)* | [dresser.md](https://github.com/codethread/dresser.loom/blob/fea1d340be3591d008cf0ddeb72b0091d95a380d/dresser.md) | — | Brings a repo onto shared working conventions and surfaces convention upgrades later. Two flavours: scaffold a new shared-spool repo, or install a self-contained `.millstrand/` workspace into any host repo. Applied versions are recorded in the target at `.millstrand/conventions.edn`. |

[agent-run-contract]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/README.md
[agent-run-api]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/agent-run.api.md
[agent-run-cookbook]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/agent-run.cookbook.md
[delegation-contract]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/delegation/README.md
[delegation-api]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/delegation/delegation.api.md
[delegation-cookbook]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/delegation/delegation.cookbook.md
[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/subagent.md
[subagent-api]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/subagent.api.md
[subagent-cookbook]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/agent-run/subagent.cookbook.md
[bench-contract]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/bench/README.md
[bench-api]: https://github.com/codethread/agent-harness.spool/blob/911bc4cb3364ce23516963761820ca0a55d01a39/bench/bench.api.md
[millhouse-contracts]: https://github.com/codethread/millhouse.spool/tree/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools

`guild` is a never-activated reference root. This repo carries its source and tests because kanban.spool's peering layer depends on it, but adds no `.millstrand/spools.edn` coordinate. A downstream user opts in by adding one.

## External spool consumption

How to apply and verify entries like these: [Writing shared spools](../docs/spools/writing-shared-spools.md) covers the coordinate shape and publishing; [customisation](../docs/spools/customisation.md) covers activating config changes against a running weaver; `strand spool status` shows what the runtime actually serves.

`ct.spools.devflow` is consumed from [`codethread/devflow.spool`](https://github.com/codethread/devflow.spool) by git coordinate rather than a local root — the worked example of publishing a spool for others (RFC-017, [Writing shared spools](../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution)). This repo pins one untagged family coordinate in `.millstrand/spools.edn` with its `:git/url` and full `:git/sha`. That entry is the single source of the pin, and `.millstrand/init.clj` activates the spool with `:required? true`. Tests consume the approved coordinate rather than carrying a second pin, so the test and weaver pins cannot drift. Developers override the coordinate with a gitignored `spools.local.edn` local root to work against a checkout.

`ct.spools.kanban` does not choose a workflow system. This repo activates Devflow's adapter after both spools are active. The currently pinned untagged Devflow family requires no consumer-owned tracker seed.

`ct.spools.kanban` is the second external spool: it lives in [`codethread/kanban.spool`](https://github.com/codethread/kanban.spool). Like Devflow, its pin lives only in `.millstrand/spools.edn`; unlike Devflow, Kanban still uses a release `:git/tag` plus its peeled `:git/sha`. Tests consume that same entry, and developers override it with a gitignored `spools.local.edn` local root.

The `ct.spools.agent-run`, `ct.spools.executors.subagent`, `ct.spools.delegation`, and `ct.spools.bench` family lives in [`codethread/agent-harness.spool`](https://github.com/codethread/agent-harness.spool). The table lists its contract-bearing `agent-run`, `delegation`, and `bench` roots; `harness-core`, `codex-harness`, and `agent-cli` are support roots from the same untagged family approval. Tests consume those roots from that one entry. Developers override the whole family from one checkout with one gitignored `spools.local.edn` entry:

```clojure
{:spools
 {ct.spools/agent-run
  {:local/root "../../agent-harness.spool"
   :claims "v1"}}}
```

The override inherits the shared family's `:roots`, `:requires`, and `:millstrand/min` declarations.

`millstrand.spools.dresser` ([`codethread/dresser.loom`](https://github.com/codethread/dresser.loom)) is also external, but this repo approves no coordinate for it. Dresser is activated in whichever workspace drives a setup run, and the repo being set up needs no weaver or spool approvals of its own, so consumption is a per-operator choice. Its README carries the dependency and activation recipe.

## Guild example

Guild is kept as a quality-gated example under [`examples/guild`](../examples/guild). It is not approved or activated by this repository's default workspace. Its contract, cookbook, API reference, and source are for study rather than a supported setup path.

## Shipped source-root: batteries

| Spool | Coordinate (`.millstrand/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `millstrand.spools.batteries` | `:millstrand/source-root "spools/batteries"` | [batteries.md](./batteries.md) | [batteries.api.md](./batteries.api.md) · [cookbook](./batteries.cookbook.md) | Shipped core strand command surface as registered ops: add/update/show/supersede/burn/list/ready/subgraph plus `weave`, the `query`/`pattern`/`vocab` registry reads, and `spool` verbs including the folded `spool status` read. Invocable arg-spec leaves declare their own hook and deadline classes. |

`mill init` opts a workspace into batteries by seeding `millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}` and a module guarded by `:spools ['millstrand.spools/batteries]`. The relative coordinate is machine-independent: the running weaver resolves it against the mill-selected Millstrand checkout and persists no absolute source path. Deleting the seeded entry is the supported visible opt-out; a hand-written `{:spools {}}` world has no batteries ops. Batteries is not on the production weaver classpath, so it follows the same approval and activation path as every other spool.

## `util` and `format` left the spool family

`millstrand.spools.util` and `millstrand.spools.format` were never activatable spools — they registered no ops and no world declared modules for them; they were authoring libraries other spools built on. Both have left `millstrand.spools.*` for base-classpath `src/`: `format` is deleted in favor of the already-blessed `millstrand.api.format.alpha` (`fill`/`reflow`), and `util` is promoted to the blessed `millstrand.api.spool.alpha` (`fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until!`, and `entity-projection` — which fails loudly unless its strand-shaped input carries `:id`/`:title`/`:state`/`:attributes` and returns exactly those keys) — the accretion-compatible home for the spool-authoring helpers every reference spool leans on. `poll-until!` takes the runtime's Clock and a relative timeout, so manual time works without changing spool code. After this move, `millstrand.spools.*` is exactly "activatable spools" and nothing else.

## Reference examples

- Each retained contract doc ends with worked examples.
- The test suites drive every documented behavior against a real weaver
  runtime and double as executable examples:
  [`test/millstrand/spools/batteries_test.clj`](../test/millstrand/spools/batteries_test.clj),
  [`test/millstrand/spools/unsafe_text_search_test.clj`](../test/millstrand/spools/unsafe_text_search_test.clj),
  and the standalone external spool test suites.

## Using and extending

- Strand **attributes are the extension surface**. Build your own conventions
  instead of waiting for engine fields, and give them new names only for new
  concepts ([the vocabulary rule](../docs/spools/writing-shared-spools.md#the-rules-for-shared-spools)).
- A spool publishes owner-complete kind entries through contribution forms and owns runtime effects through lifecycle forms. See [Writing shared spools](../docs/spools/writing-shared-spools.md) for the authoring grammar and each contract doc for exact behavior.
- To author and load your own spool from a workspace-local root, follow
  [Authoring your own spool code](../docs/spools/customisation.md#workspace-modules-and-local-spools).
