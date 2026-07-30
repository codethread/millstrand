# Spools

Spools are trusted, authorable Clojure loaded into the weaver. The `skein.spools.*` namespace family
is reserved for exactly this kind of code (see the [REPL API spec](../devflow/specs/repl-api.md)).
The agent family (`agent-run`, `executors.subagent`, `delegation`, `bench`) lives in
[`codethread/agent-harness.spool`](https://github.com/codethread/agent-harness.spool) under
`ct.spools.*`, the author-prefix convention used by external spools such as `ct.spools.kanban`.
The spools in this directory ship with Skein as working references. Use them directly, copy them
as starting points, or study them to author your own.

Every spool loads through one convention: an approved coordinate in `.skein/spools.edn` and a stable `runtime/module!` declaration guarded by its `:spools` roots. Full refresh resolves those roots, collects the module's authoring forms, publishes its owner-complete contribution, and runs its lifecycle declarations. These forms run in trusted config (`.skein/init.clj`) or an explicit-runtime REPL; [customising your workspace](../docs/spools/customisation.md) is the operational walkthrough.

Blessed alpha helpers such as `skein.api.peers.alpha` are also explicit-require userland APIs for trusted config and REPL workflows. Use that namespace's `peers` and `call!` helpers when a spool or repo config needs to discover and invoke same-machine sibling weavers.

## Approved family coordinates

Each `.skein/spools.edn` key names a family. A workspace-local family uses `:local/root`. A Git family pins `:git/url` and `:git/sha`, then maps its public root libs to checkout paths with `:roots`. A spool shipped in the Skein checkout uses the non-acquiring `:skein/source-root` coordinate:

```clojure
{:spools
 {skein.spools/batteries
  {:skein/source-root "spools/batteries"}

  ct.spools/agent-run
  {:git/url "https://github.com/codethread/agent-harness.spool.git"
   :git/sha "<40-lowercase-hex>"
   :git/tag "v1"
   :roots {ct.spools/agent-run "agent-run"
           ct.spools/delegation "delegation"
           ct.spools/bench "bench"}
   :requires {codethread/devflow "v2"}
   :skein/min "v3"}}}
```

Without `:roots`, a family supplies one root named by the family symbol at `"."`. Every root lib
has one owner. Release markers are positive `vN` strings. `:requires` sets minimum release markers
for other approved roots; `:skein/min` sets the minimum running Skein marker. A local development
override names the family once with `:local/root` and an explicit `:claims "vN"`; it inherits the
shared root map and compatibility floors.

## Doc triad

Each shipped spool's docs follow a three-file convention:

- **`<spool>.md`** — the **contract**: hand-authored guarantees, run lifecycle,
  and the attribute vocabulary. This is the load-bearing promise.
- **`<spool>.cookbook.md`** — authored **composition recipes**: how to shape
  real work out of the primitives, and *why* each shape is right. Present only
  where the value is in composition; [`workflow.cookbook.md`](./workflow.cookbook.md)
  is the template.
- **`<spool>.api.md`** — the **generated reference**: every public fn's
  signature, arity, and docstring, produced from source. Never hand-edit these;
  regenerate with `make api-docs`.

Signatures live only in the generated API doc; contracts and cookbooks link to them rather than restating them.

## Index

Each spool shipped in this checkout lives in its own root under `spools/<name>/src`, off the production weaver classpath. Its `{:skein/source-root "spools/<name>"}` coordinate resolves against the mill-selected Skein checkout without fetching source or persisting an absolute checkout path. A `:spools`-guarded `runtime/module!` declaration then lets the refresh coordinator load, publish, and reconcile it.
The repo-local spools also serve as the worked example of authoring your own ([customising your
workspace](../docs/spools/customisation.md#promoting-config-to-a-local-spool)); for publishing a
spool for others by git coordinate, SHA-pinned approval, README dependency/activation snippets,
Maven-only spool-root dependencies, and local development overrides, see [Writing shared
spools](../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution).

| Spool | Coordinate (`.skein/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `skein.spools.workflow` | `:skein/source-root "spools/workflow"` | [workflow.md](./workflow.md) | [workflow.api.md](./workflow.api.md) · [cookbook](./workflow.cookbook.md) | Workflow engine: plain-data definitions compiled to strand batches, with loops, gates, checkpoints, routing, and rebindable tool bindings. Registers no ops. |
| `skein.spools.workflow.cli` | `:skein/source-root "spools/workflow"` (folded into the workflow root) | [workflow.md §5b](./workflow.md#5b-registry-discovery), [§5c](./workflow.md#5c-driving-a-run) | — | Opt-in worker CLI over the workflow engine: registers the `workflow` op (`list`, `show`, `start`, `ready`, `complete`, `choose`, `defer`, `await`). A second module, declared beside the engine and never implied by it. |
| `skein.spools.executors.shell` | `:skein/source-root "spools/workflow"` (folded into the workflow root) | [executors/shell.md](./executors/shell.md) | [executors/shell.api.md](./executors/shell.api.md) · [cookbook](./executors/shell.cookbook.md) | Workflow `:shell` gate executor: runs a ready gate's `shell/argv` command directly on a spool-owned worker pool, closes it with `complete!` on a zero exit, and stamps a loud `shell/error` (with exit code and bounded output) on failure. Registers the `:shell` executor and the `stalled-shell-gates` query. |
| `skein.spools.executors.code` | `:skein/source-root "spools/workflow"` (folded into the workflow root) | [executors/code.md](./executors/code.md) | [executors/code.api.md](./executors/code.api.md) | Workflow `:code` gate executor: resolves a qualified Var through the spool classloader, invokes it with poured JSON params on a bounded worker pool, and records a result or loud `gate/error`. Registers the `:code` executor and the `stalled-code-gates` query. |
| `skein.spools.unsafe-text-search` **(UNSAFE)** | `:skein/source-root "spools/unsafe-text-search"` | [unsafe-text-search.md](./unsafe-text-search.md) | [unsafe-text-search.api.md](./unsafe-text-search.api.md) · [cookbook](./unsafe-text-search.cookbook.md) | **UNSAFE reference spool** — requires `skein.core.db` and runs SQL against the physical tables to `LIKE`-search titles and attribute values, including archived rows the query language cannot see. Registers the `search` op. A maintained example of breaking the namespace-tier rules in the open, not a blessed path; read its [Unsafe declaration](./unsafe-text-search.md#unsafe-declaration) before activating. |
| `skein.spools.guild` | *(none approved in this repo)* | [guild.md](./guild.md) | [guild.api.md](./guild.api.md) · [cookbook](./guild.cookbook.md) | Versioned public weaver op API declarations, `guild.describe` introspection, and loud structured deprecation for local peer coordination. |
| `ct.spools.agent-run` | git, sha-pinned (see below) | [agent-run/README.md][agent-run-contract] | [agent-run.api.md][agent-run-api] · [cookbook][agent-run-cookbook] | Agent-run **engine**: readiness-driven headless coding-agent runs plus interactive multiplexer sessions (backend registry, claims-model reaping), harness aliases, crash reconciliation, storage-enforced write-once run memory, and the preamble seam. Registers no ops. |
| `ct.spools.delegation` | git, sha-pinned (see below) | [delegation/README.md][delegation-contract] | [delegation.api.md][delegation-api] · [cookbook][delegation-cookbook] | Cross-harness subagent surface over agent-run: the `strand agent` verbs, the `agent-plan` weave pattern, delegation/retry/status, and the worker + coordinator guidance. |
| `ct.spools.executors.subagent` | git, sha-pinned `agent-run` root (see below) | [agent-run/subagent.md][subagent-contract] | [subagent.api.md][subagent-api] · [cookbook][subagent-cookbook] | Workflow gate bridge: fulfills ready `:subagent` gates by spawning agent-run runs and delivering successful results through `workflow/complete!`. |
| `skein.spools.chime` | `:skein/source-root "spools/chime"` | [chime/README.md](./chime/README.md) | [chime.api.md](./chime.api.md) · [cookbook](./chime.cookbook.md) | Notification engine: watches graph mutations, evaluates user-registered rules, and sends matches through a user-bound local notifier command. |
| `ct.spools.kanban` | git, sha-pinned (see below) | [kanban.md](https://github.com/codethread/kanban.spool/blob/424daed8dbaa29e7c8600234f8c0fd3c73eda473/kanban.md) | — | User-facing kanban board: feature/epic cards, refinement/pending/claimed/in_review lanes, notes and handovers via `strand kanban`; epics have a reversible finish lifecycle (`finish` completes or abandon-cascades, `reopen` inverts an abandon); this repo binds devflow as its tracker through `.skein/kanban_tracker.clj`. |
| `skein.spools.cron` | `:skein/source-root "spools/cron"` | [cron/README.md](./cron/README.md) | [cron.api.md](./cron.api.md) · [cookbook](./cron.cookbook.md) | Userland recurrence layer over durable scheduler wakes: registers named interval+jitter jobs, records last-outcome/failure status, and leaves next-fire timing to scheduler introspection. Ships no jobs. |
| `ct.spools.bench` | git, sha-pinned (see below) | [bench/README.md][bench-contract] | [bench.api.md][bench-api] | Deterministic, containerized benchmarking of coding-agent harnesses: pinned repo/prompt/memory overlays, bench-owned entry execution, normalized metrics, and an agent-run served judge. |
| `ct.spools.devflow` | git, sha-pinned (see below) | [devflow.md](https://github.com/codethread/devflow.spool/blob/c77486955825e5d4918a4928914578cad61ed08f/devflow.md) | — | Reference devflow lifecycle built on the workflow engine: intake → proposal → spec/plan → tasks/implementation stages with HITL checkpoints. |
| `skein.spools.dresser` | *(none approved in this repo)* | [dresser.md](https://github.com/codethread/dresser.loom/blob/fea1d340be3591d008cf0ddeb72b0091d95a380d/dresser.md) | — | Brings a repo onto shared working conventions and surfaces convention upgrades later. Two flavours: scaffold a new shared-spool repo, or install a self-contained `.skein/` workspace into any host repo. Applied versions are recorded in the target at `.skein/conventions.edn`. |

[agent-run-contract]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/README.md
[agent-run-api]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/agent-run.api.md
[agent-run-cookbook]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/agent-run.cookbook.md
[delegation-contract]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/delegation/README.md
[delegation-api]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/delegation/delegation.api.md
[delegation-cookbook]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/delegation/delegation.cookbook.md
[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/subagent.md
[subagent-api]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/subagent.api.md
[subagent-cookbook]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/agent-run/subagent.cookbook.md
[bench-contract]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/bench/README.md
[bench-api]: https://github.com/codethread/agent-harness.spool/blob/7ffaac7541329f9f4c5643ad1560e2d68ced8b2c/bench/bench.api.md

`guild` is a never-activated reference root. This repo carries its source and tests because
kanban.spool's peering layer depends on it, but adds no `.skein/spools.edn` coordinate. A downstream
user opts in by adding one.

`skein.spools.workflow` is deliberately a spool, and deliberately an in-repo one (decided
2026-07-11). It is replaceable library code: use it, rebuild it, or find
something better. Promotion to `skein.api.*` was rejected because that tier promises more stability
than a deliberately swappable engine should; extraction to its own repo was rejected because
workflow is a hub (devflow.spool, `executors.subagent` in agent-harness.spool, and this repo's
`.skein` config all require it), so an external pin would put bump ceremony on the hottest path of
engine development. Consumers who want the engine pinned independently of a checkout declare one
sha-pinned family and map its root within the checkout — an entry in the consumer's own
`.skein/spools.edn`:

```clojure
{skein.spools/workflow
 {:git/url "https://github.com/codethread/skein.git"
  :git/sha "<40-lowercase-hex>"
  :roots {skein.spools/workflow "spools/workflow"}}}
```

## External spool consumption

How to apply and verify entries like these: [Writing shared spools](../docs/spools/writing-shared-spools.md) covers the coordinate shape and publishing; [customisation](../docs/spools/customisation.md) covers activating config changes against a running weaver; `strand spool status` shows what the runtime actually serves.

`ct.spools.devflow` is consumed from
[`codethread/devflow.spool`](https://github.com/codethread/devflow.spool) by git coordinate rather
than a local root — the worked example of publishing a spool for others (RFC-017, [Writing shared
spools](../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution)).
This repo pins one family coordinate in `.skein/spools.edn`: the `:git/url`, a release
`:git/tag`, and its peeled `:git/sha`. That entry is the single source of the pin, and
`.skein/init.clj` activates the spool with `:required? true`. Tests consume the approved
coordinate rather than carrying a second pin, so the test and weaver pins cannot drift.
Developers override the coordinate with a gitignored `spools.local.edn` local root to work
against a checkout.

`ct.spools.kanban` does not choose a workflow system. This repo declares the
`:kanban/tracker` module in `.skein/init.clj`; its
`.skein/kanban_tracker.clj` reconciliation binds devflow with
`kanban/set-tracker!`, and the projection receives the owning runtime before the
run id. Repos using another tracker provide the same runtime-aware projection
contract. Repos that do not project run state omit the binding.

`ct.spools.kanban` is the second external spool: it lives in
[`codethread/kanban.spool`](https://github.com/codethread/kanban.spool). Kanban loads independently
of a tracker; this repo's `.skein/kanban_tracker.clj` binds devflow after both spools are active.
Like devflow, its pin lives only in `.skein/spools.edn`: a release `:git/tag` plus its peeled
`:git/sha`. Tests consume that same entry, and developers override it with a gitignored
`spools.local.edn` local root.

The `ct.spools.agent-run`, `ct.spools.executors.subagent`, `ct.spools.delegation`, and
`ct.spools.bench` family lives in
[`codethread/agent-harness.spool`](https://github.com/codethread/agent-harness.spool). This repo
pins one family coordinate with a `:roots` map for `agent-run`, `delegation`, and `bench`.
Tests consume all three roots from that one entry. Developers override the whole family
from one checkout with one gitignored `spools.local.edn` entry:

```clojure
{:spools
 {ct.spools/agent-run
  {:local/root "../../agent-harness.spool"
   :claims "v1"}}}
```

The override inherits the shared family's `:roots`, `:requires`, and `:skein/min` declarations.

`skein.spools.dresser` ([`codethread/dresser.loom`](https://github.com/codethread/dresser.loom)) is also external, but this repo approves no coordinate for it. Dresser is activated in whichever workspace drives a setup run, and the repo being set up needs no weaver or spool approvals of its own, so consumption is a per-operator choice. Its README carries the dependency and activation recipe.

## Shipped source-root: batteries

| Spool | Coordinate (`.skein/spools.edn`) | Contract doc | API reference | Purpose |
|---|---|---|---|---|
| `skein.spools.batteries` | `:skein/source-root "spools/batteries"` | [batteries.md](./batteries.md) | [batteries.api.md](./batteries.api.md) · [cookbook](./batteries.cookbook.md) | Shipped core strand command surface as registered ops: add/update/show/supersede/burn/list/ready/subgraph plus `weave`, the `query`/`pattern`/`vocab` registry reads, and `spool` verbs including the folded `spool status` read. Invocable arg-spec leaves declare their own hook and deadline classes. |

`mill init` opts a workspace into batteries by seeding `skein.spools/batteries {:skein/source-root "spools/batteries"}` and a module guarded by `:spools ['skein.spools/batteries]`. The relative coordinate is machine-independent: the running weaver resolves it against the mill-selected Skein checkout and persists no absolute source path. Deleting the seeded entry is the supported visible opt-out; a hand-written `{:spools {}}` world has no batteries ops. Batteries is not on the production weaver classpath, so it follows the same approval and activation path as every other spool.

## `util` and `format` left the spool family

`skein.spools.util` and `skein.spools.format` were never activatable spools — they registered no ops and no world declared modules for them; they were authoring libraries other spools built on. Both have left `skein.spools.*` for base-classpath `src/`: `format` is deleted in favor of the already-blessed `skein.api.format.alpha` (`fill`/`reflow`), and `util` is promoted to the blessed `skein.api.spool.alpha` (`fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until!`, and `entity-projection` — which fails loudly unless its strand-shaped input carries `:id`/`:title`/`:state`/`:attributes` and returns exactly those keys) — the accretion-compatible home for the spool-authoring helpers every reference spool leans on. `poll-until!` takes the runtime's Clock and a relative timeout, so manual time works without changing spool code. After this move, `skein.spools.*` is exactly "activatable spools" and nothing else.

## Reference examples

- Each contract doc ends with worked examples ([workflow.md
  §8](./workflow.md#8-worked-examples), and §4 of the external
  [devflow.md](./devflow.md) contract).
- The test suites drive every documented behavior against a real weaver
  runtime and double as executable examples:
  [`test/skein/spools/workflow_test.clj`](../test/skein/spools/workflow_test.clj),
  and the standalone devflow.spool test suite.

## Using and extending

- Strand **attributes are the extension surface**: [workflow.md
  §7](./workflow.md#7-attribute-vocabulary)'s attribute table is the workflow
  engine's extension API, and §6 of the external
  [devflow.md](./devflow.md) contract documents its conventions on top of
  it. Build your own conventions the same way instead of waiting for engine fields — and give
  them new names only for new concepts. A spool built on workflow or
  agent-run reads and writes `workflow/*` / `agent-run/*` attributes
  directly and reserves its own namespace for state the primitive does not
  carry ([the vocabulary
  rule](../docs/spools/writing-shared-spools.md#the-rules-for-shared-spools)).
- Workflow definitions accept pure-data **tool bindings** ([workflow.md §3
  "Tool bindings"](./workflow.md#3-definition-layer)), so a consumer rebinds
  steps to their own tooling from trusted config without touching these
  namespaces.
- A spool publishes owner-complete kind entries through contribution forms and owns runtime effects through lifecycle forms. See [Writing shared spools](../docs/spools/writing-shared-spools.md) for the authoring grammar and each contract doc for exact behavior.
- To author and load your own spool from a workspace-local root, follow
  [Authoring your own spool code](../docs/spools/customisation.md#promoting-config-to-a-local-spool).
