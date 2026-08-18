# Millstrand user reference

Millstrand is a local strand graph for agents and humans. It gives you a durable SQLite-backed graph of
work, notes, dependencies, and workflow state, while keeping the command-line surface small and
machine-readable.

The short version:

- A **strand** is one record in your graph.
- A **weaver** is the long-lived local process that owns the database and runtime state.
- The **`strand` CLI** is a thin JSON control surface for scripts and agents.
- The **REPL** is the trusted, high-power surface for customization and exploration.
- Your workflow model lives mostly in custom **attributes** and your own config/spool code.

This guide is written for Millstrand users and for agents working inside a user's Millstrand workspace.
Maintainer-facing contracts live in [`devflow/specs/`](../devflow/specs/); see the [spec
index](#spec-index) at the end.

## Mental model

Millstrand is daemon-core-first behind a small router. You start `mill` once, ask it to start a weaver
for a selected workspace, then clients send requests through `mill` to that weaver.

```text
selected workspace (normally canonical repo .millstrand)
  config.json        -> shared alpha workspace config
  config.local.json  -> personal config overlay
  init.clj           -> shared trusted startup code loaded by the weaver
  init.local.clj     -> personal startup overlay loaded after init.clj
  spools.edn         -> shared approved spool families and roots
  spools.local.edn   -> personal approved-spool overlay
  spools/            -> optional local spools

running weaver
  owns SQLite storage
  owns named queries
  owns weave pattern registrations
  owns event handler registrations
  owns synced spool state

clients
  strand CLI       -> mill -> weaver JSON socket, small safe command set
  weaver REPL      -> mill resolution -> direct live nREPL attach to the weaver JVM
```

Different workspaces are different workspaces. Use `--workspace <dir>` when you want an isolated workspace for experiments, agent work, or tests.

## Workspace resolution

The ordinary workspace is repository-scoped. Without `--workspace`, `mill` resolves the canonical repository root and uses that repo's `.millstrand` or `.ms` directory as the selected workspace. Linked worktrees for the same repository share this default workspace. Outside supported Git layouts, no-flag commands fail loudly. When no accepted marker exists, `mill init` creates `.millstrand`; when exactly one accepted marker exists, it completes that marker in place. It fails loudly outside supported Git layouts:

Only one accepted marker may exist, and it must be a directory. If both `.millstrand` and `.ms` exist, or either marker is a non-directory, Mill fails with the paths and remediation; repair the application marker before retrying. The legacy `.millstrand` name is rejected as an application marker; preserve it when it is a repository coordination workspace, and use an explicit `--workspace` or migrate application config to `.millstrand`/`.ms` without deleting coordination state.

```sh
mill init
```

Mill resolves the Millstrand source directory used to launch the weaver from `MILLSTRAND_SOURCE`, the source directory recorded at installation (the Homebrew `libexec` directory or a `make install` checkout), or a canonical Millstrand checkout cwd. `mill init` does not persist a source path in the selected workspace's `config.json`.

A workspace can also be selected explicitly with:

```sh
strand --workspace /path/to/workspace ...
```

For explicit workspaces, `/path/to/workspace` is the config workspace. Runtime state, metadata,
sockets, and data are owned by mill under Millstrand's XDG state root for the selected config identity.

The important file is `config.json`:

```json
{
  "configFormat": "alpha"
}
```

`config.json` is only the low-privilege alpha format marker. Source checkout paths are mill launch context, not config workspace state.

## Agent guidance files

On macOS, `brew install codethread/millstrand/millstrand` installs the Go CLIs (`strand` and `mill`) and records Homebrew's retained `libexec` source directory for weaver launch and the thin nREPL attach client. From a source checkout, `make install` records that checkout instead. After either installation, use the CLIs directly: `mill start`, `mill init`, and `mill weaver start`.

`mill init` is the normal repo bootstrap path. With no accepted marker, it creates the canonical repo `.millstrand` workspace; with one accepted marker, it completes that selected workspace. It writes shareable `config.json` with the alpha format marker when absent and leaves shared config files ready to commit. Generated `spools.edn` opts into the batteries command surface with `millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}`; generated `init.clj` activates it through a module guarded by that root, then activates the generated `me/help.clj` module to render `strand help` as text by default. `strand help --json` remains the raw envelope. The relative coordinate resolves against the mill-selected Millstrand checkout, so no absolute source path is persisted. Deleting the seeded entry is the supported opt-out. Init does not run `git init` or initialize database storage; weaver startup prepares storage.

`mill init --stealth` provides the same repo-local workspace for personal use without tracked config. It refuses if `.millstrand` or `.ms` is already tracked, maintains a marker-owned block in `.git/info/exclude`, avoids shared agent guidance, and reports every action. An untracked `CLAUDE.local.md` receives the standard Millstrand guidance when safe; Codex guidance is printed for the user to place according to their own repository policy. See [customising your workspace](./spools/customisation.md#a-private-repo-local-workspace) for the recommended local-spool layout.

User-facing Millstrand documentation lives in the source checkout under `docs/`; the canonical user reference is this page, `docs/reference.md`. Two harness-agnostic commands surface this to agents at runtime with no running weaver required. `mill prime millstrand` resolves the Millstrand source and prints two labeled absolute paths: the source checkout and its `docs/reference.md`. It carries no separate orientation manual; start with the reference, follow its links, and inspect the source when needed. `mill prime strand` prints the strand planning/tracking workflow. In a repo-world bootstrap, `mill init` also seeds a `## Millstrand / strand` section in the repository-root `AGENTS.md`/`CLAUDE.md` that points new agents at these two commands. Each shipped spool's per-fn API reference (`spools/*.api.md`) and each blessed `millstrand.api.*.alpha` namespace's per-fn reference (`docs/api/*.api.md`) is generated from source docstrings and regenerated with `make api-docs` — never hand-edited; see the [spool index](../spools/README.md#doc-triad) for the contract/cookbook/generated-API triad. The [alpha API index](./api/README.md) maps each `millstrand.api.*.alpha` namespace to the concern it owns; the generated pages are reference only, and the behavior contracts remain the root specs (see the [spec index](#spec-index)).

When working in this repository, also read the "Repo coordination workspace (.millstrand)" section of the root [`AGENTS.md`](../AGENTS.md): it explains the shared coordination world and its working discipline. Use the live discovery commands there: `strand help`, `strand query list`, `strand pattern list`, and `strand workflow list` for registered ops, queries, patterns, and workflows. The header of `.millstrand/init.clj` is the authoritative inventory of module paths and activation order.

The focused modules under `.millstrand/ct/workflows/` author this repo's static `defworkflow` Vars. The live catalogue is authoritative: use `strand workflow list` to discover registered workflows and `strand workflow show <name>` to inspect a definition's contract. The narrow `land` op adds merge-train and kanban-lane policy; `strand help land` describes those boundaries.

Feature work enters the external devflow lifecycle through its registered `intake` definition. Run `strand workflow show intake` before starting it; the live definition owns the required `feature` parameter, the `worktree-check` policy, defaults, and accepted values. Drive the resulting run with the same generic workflow verbs.

To report agent-run cost and usage beneath a work root, read the generic `parent-of` subgraph and pass its JSON to the repository reducer:

```sh
strand subgraph ROOT_ID --relation parent-of |
  jq -f scripts/reports/feature-costs.jq
```

The reducer returns the root summary, start-ordered run rows, cost and token totals, wall-clock bounds, per-harness totals, and ids of runs without cost data. It does not round values. A malformed present usage value exits nonzero and names the strand, attribute, and value.

The narrow `land` op survives beside that generic surface because it adds behavior the engine has no business knowing: the merge train and kanban lane moves. It lives in `.millstrand/ct/workflows/land_policy.clj`, beside the `defworkflow` definitions it drives in `.millstrand/ct/workflows/land.clj`. `strand help land` lists only those policy boundaries. Read the lock with `strand list --query merge-lock` and the train in front of it with `strand list --query merge-queue`.

The **landing workflow** (family `land`) is the coordinator-only discipline for taking a finished branch to landed. It is three `defworkflow` definitions: `land` with its `land-merge` and `land-abort` continuations. Give each landing attempt a new ID and start it with `strand workflow start <new-land-run-id> --workflow land --params '{"feature":"<feature-id>","branch":"<branch>","worktree":"<path>","card":"<optional-card-id>"}'`; the `feature` value identifies the existing feature work and is not the new land run ID. Use generic workflow verbs for frontier reads, ordinary completion, and the `revise` choice. After opening the draft PR, `land complete <land-run-id> --pr-number <number>` records its exact number and moves an optional card to `in_review`. The first and final machine gates run the target repository's tracked executable `.millstrand/land-quality.sh` from the feature worktree. The wrapper requires a clean checked-out branch whose local HEAD matches its upstream, records the contract's combined output on the shell gate, and verifies that checks leave the pushed HEAD and tree unchanged. The contract is the target repository's quality policy; the land workflow does not hard-code this repository's commands. `land choose <land-run-id> approved --input '{"subject":"<semantic squash subject>","body":"<squashed commits body>"}'` acquires the singleton merge lock and routes approval; `land choose <land-run-id> abort --input '{"reason":"<reason>"}'` restores the card lane, leaves the merge train, and routes abort. If a crashed run leaves a stale lock, inspect it with `strand list --query merge-lock`, establish that its owner cannot resume, then record the intervention with `strand land break-lock --reason "<reason>"`; the same call evicts a queued run that stalled before it ever took the lock. Under the lock, an idempotent shell gate addresses the recorded PR number and runs `gh pr merge --squash`; the canonical checkout then advances with `git pull --ff-only`, which stops for an operator instead of stashing or resetting a non-fast-forward state. The local quality contract runs in the canonical checkout after the merge, then a shell gate deletes the remote branch, removes the worktree, and prunes Git metadata. The coordinator tidies feature-owned resources, finishes the kanban card when one is set, and calls `land complete <land-run-id>` at the terminal bookkeeping step, which releases the lock synchronously and leaves the merge train. Worker agents stop at implemented and committed; only a coordinator holding delegated sign-off authority drives a land run.

An already-poured land run keeps the command attributes it received when it was poured. Do not complete an old gate by hand or declare it green. If it is stamped with `gate/error`, fix the branch or environment, push the exact branch HEAD, then clear only that gate's error with `strand update <gate-id> --attributes '{"gate/error":null}'`; the shell executor records the retry. A run can adopt this local contract through `revise` only after it reaches sign-off, where the revision re-pours the registered definition and creates fresh gates. A pre-sign-off run stalled in the old remote-CI gate cannot reach `revise`; leave it active and ask the coordinator to resolve that already-merged run under the landing policy. Do not close the old gate to force migration or start a second merge run for an already-merged PR.

The **merge train** is the queue in front of that lock. `strand land await <land-run-id> [--timeout-secs <n>]` joins it and blocks until this run reaches the front, defaulting to five minutes. Wanting the lock is a declaration of intent to merge, so awaiting is the reservation itself: turns are served first-in first-out, and only the head may acquire. A run joins at sign-off and not before — an early arrival would hold the head while failing every approval it attempted. An approval that never awaited joins the train at that moment, so the queue records every merge. Timing out never costs a place; re-issue `await` and the original position holds.

The train does not remove the rebase. The strict up-to-date policy still applies, so a granted turn means rebasing onto `origin/main` and re-running the tracked local quality contract at the new pushed HEAD before merging. What it removes is the repetition: without a queue, every coordinator races, one wins, and each loser rebases and re-runs the contract before racing again. A granted return carries `landed-since` — the PR number and subject of everything that merged while this run waited — so the single rebase is a known quantity and conflicts can be checked for before the turn arrives.

An ungranted return lists the runs ahead with the evidence needed to judge them: queue position, current stage, whether they hold the lock, and when their land run last changed. A head that has stopped moving blocks everyone behind it. Confirm its owner cannot resume, then break it. A run whose land root has gone reports `run-state: missing` rather than a stage, which is the clearest signal to evict.

## Weaver

The weaver is the application core. It is a long-lived local Clojure process that owns:

- the SQLite database connection;
- strand creation, update, query, readiness, and burn operations;
- the in-memory registered-op registry;
- the in-memory named-query registry;
- the in-memory weave-pattern registry;
- the in-memory lifecycle hook registry, run synchronously inside a mutation;
- the in-memory event handler registry and async dispatch worker;
- the approved-root acquisition state;
- runtime module activation state.

Start mill once, then start the selected workspace's weaver:

```sh
mill start
mill weaver start --workspace "$workspace"
```

`mill weaver start` waits up to 5 minutes for ready metadata while the JVM boots and trusted
workspace config runs. On unusually slow machines or first boots that must fetch and compile a lot
of code, pass a larger positive Go duration:

```sh
mill weaver start --workspace "$workspace" --ready-timeout 10m
```

Stop it:

```sh
mill weaver stop --workspace "$workspace"
```

Check it:

```sh
mill weaver status --workspace "$workspace"
```

`mill weaver status` is read-only and is safe before storage initialization; use it to inspect the selected workspace and see that the weaver is stopped before running `mill weaver start`.

The weaver exposes two local transports:

- a Unix socket used by the Go `strand` CLI;
- an nREPL endpoint used by the live weaver REPL.

A selected workspace may have one running weaver. Runtime registries are weaver-lifetime state, so
named queries, weave patterns, and synced spool state should be loaded from startup config if
you want them to appear after every restart.

## Weaver generations and cutover

A **weaver generation** is one weaver process lifetime. It answers two everyday questions: when a
config or spool change you make takes effect, and what happens to running agent runs when a weaver
is replaced. The spool classloader is created when the weaver boots and is never swapped while it
runs, so some changes load into the live weaver and some must wait for the next generation.

`runtime/refresh!` classifies each change. Additive changes load into the running weaver: a newly
approved root, or a coordinate that has never loaded in this generation. Non-additive changes
cannot, because applying them would mean unloading code the running JVM has no safe way to drop:
removing a root that already loaded, pointing an already-loaded root at different source, or bumping
the version of a loaded Maven coordinate. Refresh refuses those in-JVM, records a
`:pending-generation` entry, and reports `recorded; takes effect at the next weaver generation
(mill-supervised restart, user sign-off)`. The pending change stays visible in
`runtime/status` until the weaver process is replaced. If a change you expected
does not take effect, check status for a pending generation. See daemon-runtime
SPEC-004.C44c–C44f for the full
classification.

Replacing a weaver ends every agent run it is supervising, so a restart is not free. `mill weaver
stop` does not drain the work first. It sends the weaver process SIGTERM, waits about five seconds,
then SIGKILL if it has not exited. The headless runs the weaver was supervising are orphaned, not
lost: the next weaver start recovers them through `reconcile!`, which resets each run to `pending`
for auto-respawn, or marks it `exhausted` (non-retryable) once its attempts are spent. Interactive
sessions survive the stop whatever their backend, tmux included, and the next generation adopts them
instead of respawning. Before cycling a weaver that has live work, a coordinator either drains it
first with `strand agent await` on the outstanding runs, or accepts the respawn-and-retry cost for
whatever runs are still going when the stop lands.

Restarting the canonical weaver requires explicit user sign-off, the same hard rule that governs every canonical-weaver restart (see AGENTS.md).

One-time migration: a weaver started before stateless resolution landed (2026-07,
SPEC-004.C44@sync-owns-resolution) carries process-global `add-libs` and basis residue that an
in-JVM upgrade cannot unwind. A single restart of that weaver sheds it, and every generation after
starts clean with no such restart needed. When to take that restart is a human decision under the
sign-off rule above.

## CLI

The `strand` CLI is intentionally small. It is for scripts, low-friction agent use, and JSON automation. It does not evaluate rich Clojure forms, and it exposes no verb that mutates runtime extension state. That is a property of the command surface, not of the weaver underneath it: the socket the CLI speaks to is internal plumbing rather than a contract, and its op table does reach registration, so do not read the absent CLI verb as a guarantee that nothing on that wire can register.

Common commands:

```sh
mill init --workspace "$workspace"
strand --workspace "$workspace" add "Write docs" --attr owner=agent --attr area=docs
strand --workspace "$workspace" update <id> --state closed
strand --workspace "$workspace" update <id> --edge depends-on:<other-id>
strand --workspace "$workspace" show <id>
strand --workspace "$workspace" list
strand --workspace "$workspace" ready
strand --workspace "$workspace" note <id> "Captured decision"
strand --workspace "$workspace" notes <id>
strand --workspace "$workspace" burn <id>
strand --workspace "$workspace" query list
strand --workspace "$workspace" query explain <query-name>
strand --workspace "$workspace" pattern list
strand --workspace "$workspace" pattern explain <pattern-name>
printf '{"title":"New work"}\n' | strand --workspace "$workspace" --stdin weave --pattern <pattern-name> --input :stdin

```

`burn` deletes a strand and its incident edges, and records a durable forensic tombstone in the same
transaction. It is hand-recoverable from the REPL, not an undo; see the strand model and the
burn-recovery cookbook below.

The public strand/weaver commands emit JSON. CLI attributes are string-valued `key=value` pairs; richer Clojure data belongs in config or REPL workflows.

Use the CLI for:

- creating and updating ordinary strands;
- attaching simple attributes;
- adding edges;
- asking what is ready;
- appending root-level notes with `note` and reading attached notes with `notes`;
- consuming named queries registered by trusted config;
- invoking weave patterns registered by trusted config;
- starting, stopping, and checking the weaver.

Do not expect the CLI to be a package manager, query authoring surface, plugin host, or Clojure evaluator. Those belong to the weaver config and REPL.

## Discovery tiers: help, about, prime

Millstrand has one deliberate convention for "how do I find out?", with three tiers, and they form an
escalation path. `prime` orients you; `about` explains an op you are about to lean on; `help`
answers exact invocation questions. Each is a **meta-verb** you put first, naming the op second:
`strand prime <op>`, `strand about <op>`, `strand help <op>`. Each tier has a different source of
truth:

| Tier | Source | Question it answers | Examples |
| --- | --- | --- | --- |
| `help` | **Generated** from registered arg-spec data | "What can I type?" — verbs, flags, positionals, types | `strand help`, `strand help <op>`, `strand help <op> <verb>` |
| `about` | **Authored** per-op prose | "What does this op mean?" — runbook context: purpose, who drives it, contracts the live surface cannot state | `strand about agent` |
| `prime` | **Authored** run-first context or a pointer to its canonical source | "Where do I start?" | `mill prime millstrand`, `mill prime strand`, `strand prime agent` |

The meta-verb goes first. The old `<op> help` / `<op> about` / `<op> prime` sole-token sugar is retired: a bare `help`, `about`, or `prime` in verb position now fails with a loud redirect to `strand help <op>`, unless the op declares a real subcommand by that name (several spool ops do, noted below). A trailing `--help` or `-h` flag still works on any op, so `strand agent --help` rewrites to `strand help agent`.

**`help` is never hand-written.** It is one declared, versioned schema, uniformly projected; renderings are transforms over it. `strand help` lists every registered op; `strand help <op>` renders one op's detail from its arg-spec; `strand help <op> <verb> [<verb> ...]` slices to any declared node, including an interior node with further verbs.
A detail response is a canonical envelope, `{schema-version, operation, source, glossary, node}`, verbose by default, where `node` is a self-similar shape that recurses into subcommands, `source` points at the handler's `file:line` when it resolves, and `glossary` defines the named failure outcomes a node references. With no help transform registered, that raw envelope JSON is the output. A workspace can register one default help transform to render it as friendlier text instead; `--json` is the sole opt-out, always returning the raw envelope, so a failing transform can never brick help. Missing or unknown subcommands at any depth fail with structured parser errors carrying the path walked and the available names. No spool ever writes its own usage strings or dispatch errors. Contracts: SPEC-002.C39 and the discovery-tier deltas.

**`about` is the op's runbook context.** `strand about <op>` returns the op's authored `:about` prose as a small JSON object: what the op is for, who drives it, and the contracts and attribute conventions a caller cannot read off the live surface. It stays short, and it points at whatever owns the detail: `help` for invocation, and for an op that drives a definition, the definition itself (`strand workflow show <name>`). A step map, a verb list, or a flag table restated here is a second copy that drifts from the first. An op that declares no `:about` prose returns a loud `discovery/unavailable` rather than empty success; purely structural ops (batteries `add`/`list`/...) need none, since their arg-spec already says everything. Some spool ops instead declare their own `about` subcommand, and for those the resolving form is `strand <op> about`, such as `strand kanban about` and `strand spool about`.

**`prime` is run-first context priming for agents.** Most prime commands print the working discipline for an area: the conventions an agent must load before acting, with pointers to deeper docs. `mill prime strand` prints the day-to-day strand workflow. `mill prime millstrand` is deliberately narrower: it prints only the resolved source checkout and the canonical `docs/reference.md` path, so this page remains the one detailed guide and an agent can inspect the implementation when it needs more. Neither command needs a running weaver. After resolving the source checkout, `mill prime millstrand` formats its two paths directly in Go, while `mill prime strand` renders the topic file named by `docs/prime/index.json` so an already-installed `mill` picks up current strand orientation from a newer checkout. Op-level primes are spool-authored prose projected through the builtin `prime` meta-verb (`strand prime agent`), so they can never drift from the installed surface; spool ops that declare their own `prime` subcommand answer `strand <op> prime`, such as `strand kanban prime`. Repo-world `mill init` seeds a marker-guarded section into `AGENTS.md`/`CLAUDE.md` pointing fresh agents at the prime commands (see "Agent guidance files").

Spool authors: the authoring rules for this surface live in [`docs/spools/writing-shared-spools.md`](./spools/writing-shared-spools.md).

## Strand model

A strand has:

- `id` — generated text id;
- `title` — human-readable title;
- `state` — core lifecycle state: `active`, `closed`, or `replaced`;
- `created_at` and `updated_at`;
- `attributes` — userland JSON object.

`state` is the only built-in lifecycle field. Concepts like `status`, `kind`, `type`, `category`,
`outcome`, `owner`, `priority`, `project`, `estimate`, or `retention` are your attributes, not core
fields. "Yours" means yours to define for your own concepts: a consumer of a spool that already
publishes an attribute vocabulary (such as `workflow/*` or `agent-run/*`) reuses those keys
verbatim ([the vocabulary
rule](./spools/writing-shared-spools.md#the-rules-for-shared-spools)).

| Concept | Where it belongs |
| --- | --- |
| done/not done for readiness | core `state`, where `active` participates in readiness |
| completion time | custom attribute if your workflow needs it |
| status like `todo`, `doing`, `blocked`, `done` | custom attribute |
| outcome like `done`, `cancelled`, `abandoned` | custom attribute |
| owner, priority, project, estimate | custom attributes |
| local workflow marker | custom attribute plus your own helper code |

Close work when it is no longer active. There is no special `done` command; use `update --state closed` and optionally record your own outcome attribute:

```sh
strand --workspace "$workspace" update <id> --state closed --attr outcome=done
```

Burn only when you want deletion:

```sh
strand --workspace "$workspace" burn <id>
```

Every burn records a durable forensic tombstone in the same transaction — the burned strand's core
row, full attribute map, and incident edges (SPEC-001.P3/P8). A tombstone supports hand-recovery,
not undo: recovery is a REPL-only activity (`millstrand.repl/burn-history`, `recent-burns`), a replay
mints a new id, and inbound edges from unburned strands are not restored. See the burn-recovery
cookbook in the REPL section.

## Edges and readiness

Edges connect strands with open relation names. The shipped acyclic relations are `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes`.
Other annotation relations, such as `references`, are allowed but may form non-self cycles.

A `depends-on` edge from `A` to `B` means: `A` is blocked while `B` is active.

```sh
strand --workspace "$workspace" update "$docs" --edge depends-on:"$design"
```

`ready` returns active strands whose direct `depends-on` targets are inactive or absent:

```sh
strand --workspace "$workspace" ready
```

Self-edges fail for every relation. The declared acyclic relations `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes` reject relation-local cycles.
Other annotation relations may form non-self cycles.

The batteries `note` verb appends a note strand at the root. The note is born closed, and its
`note/text` and `note/at` content is storage-enforced write-once (SPEC-001.P4): the birth write is
legal, but no later path can rewrite, delete, or archive it. The strand itself stays open to
writer-owned decorating attributes.
`notes` reads the target's attached notes by walking the `notes` relation, no matter which writer created them.

## Attributes are the extension point

Millstrand's core is deliberately small. Most workflow meaning hangs off `attributes`.

Examples:

```sh
strand --workspace "$workspace" add "Draft release notes" \
  --attr owner=agent \
  --attr project=millstrand \
  --attr kind=doc \
  --attr priority=high
```

Your workspace can decide what attributes mean. For example:

- `owner=agent` can mean an agent should pick it up;
- `kind=feature` can identify feature roots;
- `outcome=done` can record completion reason after deactivation;
- `temporary=true` can identify rows your own tooling treats as temporary;
- `external.issue=123` can link to another system if your tooling understands it.

Millstrand stores attributes as JSON text. CLI input is simple string pairs; `--attr temporary=true`
stores the string `"true"`, not a JSON boolean. Trusted Clojure workflows can write richer
JSON-compatible values.

Because attributes are userland, your own config and spools should define the conventions for your
workspace. Prefer documenting those conventions in source-controlled docs or in your spool docs.
Attribute names and cleanup behavior are userland choices, not Millstrand core.

[Modeling attribute values](spools/writing-shared-spools.md#modeling-attribute-values-enums-absence-empty-history)
covers how to model a value once you own the convention: when a finite state wants an enum, when an
optional fact should go absent rather than blank, when an empty string is real data, and when a fact
belongs in recorded history.

## Queries

Queries are named values in the weaver registry. The durable path is a module authoring form:

```clojure
(ns my.workspace
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! agent-docs
  "Return agent-owned documentation strands."
  {}
  [:and
   [:= [:attr :owner] "agent"]
   [:= [:attr :area] "docs"]])
```

Activate the module from trusted startup code:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:ns 'my.workspace})
```

The inert `defquery` form defines the `agent-docs` Var but does not select it. `defquery!`, used above, defines and selects its declaration while the module source is evaluated. A Var containing query data is not itself a named query. Module publication owns the registry entry, so refresh and restart can reconstruct it from source.

For code, tests, or a one-off startup helper that already holds a runtime, use the explicit-runtime verb:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.graph.alpha :as graph])

(graph/register-query! (current/runtime) 'agent-docs
  [:and
   [:= [:attr :owner] "agent"]
   [:= [:attr :area] "docs"]])
```

Inside `mill weaver repl`, the same operation is shorter because `millstrand.repl` supplies the runtime:

```clojure
(repl/register-query! 'agent-docs
  [:and
   [:= [:attr :owner] "agent"]
   [:= [:attr :area] "docs"]])
```

The explicit-runtime and REPL tiers are not separate capabilities. They are the live registration surface in two calling styles; use the first from code and tests, and the second while iterating in the connected weaver.

Discover and consume a registered query from the CLI:

```sh
strand --workspace "$workspace" query list
strand --workspace "$workspace" query explain agent-docs
strand --workspace "$workspace" list --query agent-docs
strand --workspace "$workspace" ready --query agent-docs

```

`query list` and `query explain <name>` are the read-only discovery pair for named query definitions. Application stays on the read commands: `list --query <name>` and `ready --query <name>` with repeated `--param key=value` when the query declares runtime params.

Named query registries are not durable by themselves. The module form above is the durable path; a direct entry is runtime-local, although startup code can reapply it on each restart. Reapplication does not make it refresh-safe or owner-complete. For a workspace that already declares a local spool, add the query to that module's contribution so owner-complete refresh installs everything together.

`defquery` is one of six [authoring families](#authoring-forms) for core registry entries. It defines an inert Var; `use-query!` selects that Var and `defquery!` defines and selects it. The registry key is the Var name exactly, so `defquery! mine-query` registers `"mine-query"`.

The registry is owner-partitioned and layered. Each writer changes only its own entry map, and the effective view is the layered merge. `replace-query!` records intent to shadow an existing name; `unregister-query!` removes only the caller's own entry and restores the entry below it. Removing a shadow and registering again cannot replace another owner's entry, so remove-then-rerun is not a substitute for replace. Registry verbs change the name-to-value binding, not the Var. Queries have no function to redefine: their registered value is the behavior, and `query explain` reads the current value after a replacement.

`strand list --query mine` returns all matching strands unless you also pass a state filter. Use
`strand list --query mine --state active` when you only want active matches. `strand ready --query
mine` always applies readiness semantics, so returned strands are active and unblocked.

### Query expression grammar

A query definition is either a bare where expression, or a map with `:where` and declared
`:params`:

```clojure
[:= [:attr :owner] "ct"]                                       ; bare expression
{:params [:owner]
 :where  [:= [:attr :owner] [:param :owner]]}                  ; parameterized
```

A where expression is an EDN vector of `[operator & args]`:

The grammar is a deliberately narrow boundary surface. New forms must meet the [SPEC-001.P9 acceptance criteria](../devflow/specs/strand-model.md); a selection the grammar cannot express belongs in a registered read op. Put `:hook-class :read` and `:deadline-class` on that flat arg-spec leaf, then compose registered queries and extra filtering in Clojure through the `millstrand.api.graph.alpha` helpers ([Graph helpers](#graph-helpers)). CLI callers invoke it like any other op.

| Form | Meaning |
| --- | --- |
| `[:= f v]` `[:!= f v]` `[:< f v]` `[:<= f v]` `[:> f v]` `[:>= f v]` | compare field `f` against value `v` |
| `[:in f [v …]]` | `f` matches one of a non-empty collection of values |
| `[:exists f]` / `[:missing f]` | `f` has a value / has none |
| `[:and e …]` / `[:or e …]` | compose one or more child expressions |
| `[:not e]` | negate exactly one child expression |
| `[:edge/out rel q]` | this strand has an outgoing `rel` edge to a strand matching `q` |
| `[:edge/in rel q]` | this strand has an incoming `rel` edge from a strand matching `q` |

A field `f` is a core column written as a bare keyword — `:id`, `:title`, `:state`, `:created_at`,
`:updated_at` — or an attribute path `[:attr :key]`. Extra path segments read inside the
attribute's JSON value: `[:attr :external :issue]` matches the `issue` field of the JSON stored
under `external`.

Attribute comparisons run in SQLite over the extracted JSON value: numbers compare numerically,
strings lexically, and mixed types follow SQLite's cross-type ordering, where every number sorts
before every string. The CLI writes strings, so a strand added with `--attr temporary=true`
matches `[:= [:attr :temporary] "true"]`, not `true`. A stored JSON `null` satisfies `:missing`,
not `:exists`, and archived attributes never match — `[:missing [:attr :k]]` is true when the
strand has no hot value under `k`.

`[:param :name]` can stand in for a comparison value, an `:in` collection, or an edge relation
name. CLI params are strings, passed as repeated `--param key=value` pairs, so scalar params work
from the CLI; a query whose `:in` collection is a param can only be invoked from the trusted
surfaces (the REPL `query` helper or `millstrand.api.graph.alpha`), where params are real EDN values.
Explicit-runtime registration to invocation looks like:

```clojure
(graph/register-query! runtime 'owned-by
  {:params [:owner]
   :where  [:= [:attr :owner] [:param :owner]]})
```

```sh
strand list --query owned-by --param owner=ct
```

Edge endpoint queries are strand-local — nesting another `:edge/out` / `:edge/in` inside `q`
fails loudly, as does any malformed expression at registration or compile time; nothing coerces
to an empty or match-all predicate.

`ready` speaks the same grammar: it is equivalent to
`[:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]]`, and
`ready --query <name>` adds your expression on top. The contracts behind this section: queryable
fields and attribute predicate capability in [SPEC-001.P9](../devflow/specs/strand-model.md), edge
predicates in [SPEC-003.C13a](../devflow/specs/repl-api.md), and the `ready` equivalence in
[SPEC-003.C15](../devflow/specs/repl-api.md); the rest reflects the current compiler
(`millstrand.core.query`).

## REPL

The REPL is the trusted, high-power surface. `mill weaver repl` attaches directly to the selected
running weaver nREPL, so forms evaluate in the weaver JVM with weaver process authority. Use it for
richer inspection, custom query authoring, config reloads, and calling your own spool code. Prefer
blessed helper/API paths for operations that should preserve validation, hooks, events, and
normalized return shapes.

Open a live weaver REPL:

```sh
mill weaver repl --workspace "$workspace"
```

Useful forms:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(def rt (current/runtime))

(def id (:id (weaver/add! rt {:title "Explore workflow"
                              :attributes {:owner "ct" :kind "spike"}})))
(weaver/show rt id)
(weaver/update! rt id {:state "closed" :attributes {:outcome "captured"}})
(weaver/list rt)
(weaver/ready rt)
```

Script the live weaver REPL with stdin:

```sh
printf '(millstrand.api.current.alpha/runtime)\n' | mill weaver repl --stdin --workspace "$workspace"
```

The session starts in the neutral `user` namespace with `millstrand.repl` aliased `repl`, which carries
the live registration verbs. Everything else is an ordinary namespace you require. Privileged runtime
loader/config helpers are explicit built-in namespaces, not ordinary user spools:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])
(runtime/refresh! (current/runtime))
```

### Burn recovery

Burn is deletion, not undo. Every burn writes a forensic tombstone (SPEC-001.P3/P8), read from the
interactive `millstrand.repl` surface over the in-process `millstrand.core.db` read fns. Run these from an
in-process `mill weaver repl`; they throw with remediation from a connected-client REPL that has no
in-process runtime.

```clojure
(repl/recent-burns 20)             ; scan recent deletions across all strands, newest first
(repl/burn-history "<burned-id>")  ; every tombstone recorded for one burned id
```

Each tombstone carries the burned strand's core fields, its full attribute map (each value tagged
`{:value ... :archived ...}` so archived keys stay distinguishable), its incident edges, and
`recorded_at`. The shapes map onto the batch graph mutation payload's strand and edge entries, so
recovery is mechanical: assemble a payload from the tombstone and apply it.

```clojure
(require '[millstrand.api.batch.alpha :as batch]
         '[millstrand.api.current.alpha :as current])

;; :refs bind existing strand ids you want to re-point at the recovered strand.
(batch/apply! (current/runtime)
              {:refs    {:parent "<existing-id>"}
               :strands [{:ref :recovered :title "..." :attributes {"note/text" "..."}}]
               :edges   [{:op :upsert :from :parent :to :recovered :type "parent-of"}]})
```

Two caveats. The recovered strand gets a new id, so anything that referenced the old id must be
re-pointed. And only the edges the tombstone recorded are available to replay — inbound edges from
strands that were never burned are not restored, so re-create them explicitly against the new id.

The same `:edges` vector accepts `{:op :remove :from :to :type}` to delete one exact `(from, to, type)` edge, using pre-bound `:refs` for both endpoints. Removal is strict: an absent edge fails the whole batch loudly rather than succeeding silently, so a stale remover must reread and reconcile. Core deletes only that row and adds no graph guard — whether a removal may strand a node or unblock work is caller and hook policy, not an engine promise.

## Startup config and customisation

The weaver loads trusted startup files from the selected workspace in order — `init.clj`, then `init.local.clj` — and everything registered there is weaver-lifetime runtime state: registered ops, named queries, weave patterns, lifecycle hooks, event handlers, and activated spools. The full customisation story is its own page: [customising your workspace](./spools/customisation.md) covers the files `mill init` bootstraps, direct `init.clj` registrations, promoting that config into a local spool whose module publishes through [authoring forms](#authoring-forms), smoke-testing config in a disposable world, module refresh and `reload-code!` semantics, REPL hygiene in a shared weaver, and workspace-owned helper ergonomics. The rules change only when a spool leaves your machine: [writing shared spools](./spools/writing-shared-spools.md).

## Authoring forms

Registry entries are authored, not imperatively registered. `millstrand.api.millstrand.alpha` ships one three-form family per core kind. The inert form defines an ordinary Clojure Var, the typed use form selects it, and the bang form performs both while a runtime module source is evaluated.

| Definition form | Kind it defines | Required options |
| --- | --- | --- |
| `defop` | `:ops`, a `strand <op>` command | `:arg-spec` |
| `defquery` | `:queries`, a named query | none |
| `defpattern` | `:patterns`, a weave pattern | `:spec` |
| `defhook` | `:hooks`, a synchronous pre-commit hook | `:types` |
| `defhandler` | `:events`, an async post-commit handler | `:types` |
| `defbin` | `:bins`, a discoverable executable | `:executable` |

Each form takes a name, a docstring, an options map, and then the body its kind needs: an argument vector and body for the handler kinds, a query definition value for `defquery`. Every form also accepts `{:override? true}` to record explicit intent to shadow a lower layer. `defop`'s `:arg-spec` is the declared argv shape [`millstrand.api.cli.alpha`](./api/cli.api.md) parses and renders help from, so a registered op never writes its own usage strings.

The binding moment differs by kind. Ops, patterns, and hooks resolve their callable Var when they run, so redefining the function is the live hot loop under a stable contract. Registration metadata, including an op's help and arg-spec, stays as it was until the entry is registered again; help can therefore describe the old contract during that window. Event handlers capture the function value at registration, so replace the handler registration to iterate it. Queries have no callable; the registered value is the behavior, and replacing it updates both execution and `query explain`.

The module's coordinate does not affect these registry rules. A workspace module may use `(millstrand/defop! {:override? true} ...)` to declare a durable mask over a spool op whether that spool came from a local root or a git pin. Same-layer collisions remain errors; the override is the consumer's explicit workspace-layer choice.

Selection only happens under a module contribution. Evaluating an inert form at the REPL, or reloading source with `runtime/reload-code!`, defines the Var and publishes nothing. The same rule explains removal: a refresh replaces an owner's whole partition for a kind, so dropping a typed selection from the source drops its entry at the next refresh. There is no unregister call to remember.

Domain spools own forms for their own kinds the same way. The external Millhouse family is one example; its workflow, Cron, Chime, and executor forms and behavior are in the [Millhouse documentation](https://codethread.github.io/millhouse.spool/). Module lifecycle effects are declared with `millstrand.api.lifecycle.alpha`: `defresource`, `defseed`, and `defreconcile`.

`defbin` contributes a module-owned executable declaration rather than a weaver-lifetime runtime registration. `mill bin plan` discovers the effective `:bins` entries, and `mill bin run` executes the selected command in the caller's process with the selected workspace in `MILLSTRAND_WORKSPACE`; the declaration may name a string executable or an anchored family/root path and an argv build recipe.

The direct registration functions still exist and still work: `graph/register-query!`, `patterns/register-pattern!`, `events/register-handler!`, `hooks/register-hook!`, and `weaver/register-op!`. Each writes one entry under the direct-registration owner for the weaver lifetime, which makes them a good REPL tool for trying something out. Anything a module owns belongs in a form. A direct write is also not serialized against an in-flight `refresh!`, so one that lands mid-publication can be overwritten silently ([SPEC-003.C23](../devflow/specs/repl-api.md) constraint F20, explained in [customising your workspace](./spools/customisation.md#reloading-a-live-weaver)).

Retract a direct entry before the same name graduates into a form. The direct-registration owner sits above the module layer, and the collision check runs over the whole candidate at publication, so a direct entry left in place without recorded intent makes the later module publication of that name fail the whole refresh rather than quietly losing. Two ways out, and the order is what matters: `unregister-*!` retracts your own entry so the module's version publishes cleanly, or `replace-*!` records the override intent that deliberately carries the shadow across refresh. Retracting a shadow restores the entry it was shadowing rather than removing the name.

Per-function detail is in the generated [`millstrand.api.millstrand.alpha` reference](./api/millstrand.api.md); [writing shared spools](./spools/writing-shared-spools.md) covers the declaration grammars, the module contract, and how to declare a kind of your own. The contracts behind this section: which namespace owns which form, and what `:override?` means, in [SPEC-003.C17e](../devflow/specs/repl-api.md); collection, owner-complete publication, and removal by omission in [SPEC-004.C46h](../devflow/specs/daemon-runtime.md).

## Weave patterns

Weave patterns are trusted owner-defined transformations that turn a JSON-like input payload into an
atomic batch of new strands and edges. They are useful when agents should submit intent and your
workspace should decide the graph shape.

Pattern registration lives in trusted Clojure config or spools, not in the public CLI. A pattern has a simple name, a handler function loadable in the weaver, and a `clojure.spec` input contract. `millstrand/defpattern` declares all three in one form, in a module source namespace:

```clojure
(ns my.workflow
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.millstrand.alpha :as millstrand]))

(s/def ::title string?)
(s/def ::task-input (s/keys :req-un [::title]))

(millstrand/defpattern! task
  "Create an implementation strand and a review strand that depends on it."
  {:spec ::task-input}
  [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {:owner "agent"}}
   {:ref 'review
    :title (str "Review: " (:title input))
    :attributes {:kind "review"}
    :edges [{:type "depends-on" :to 'impl}]}])
```

The form name is the registered pattern name, so this publishes `task` when `my.workflow` is activated as a module. To try a pattern out without a module, register it directly from the live REPL instead:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.patterns.alpha :as patterns])

(patterns/register-pattern! (current/runtime) 'task 'my.workflow/task ::task-input)
```

CLI callers can discover registered patterns, inspect the input contract, and invoke the pattern with exactly one JSON value on stdin:

```sh
strand --workspace "$workspace" pattern list
strand --workspace "$workspace" pattern explain task
printf '{"title":"Implement review flow"}\n' | strand --workspace "$workspace" weave --pattern task

```

`pattern list` and `pattern explain <name>` are the write-definition discovery pair, parallel to
`query list` / `query explain <name>` for read definitions. Application stays on `weave --pattern
<name>`.

The pattern function runs inside the weaver and receives `{:input input}`. Its return value must be
the same batch vector shape accepted by Millstrand's batch primitive: strand maps with optional `:ref`
and `:edges`. Symbolic refs are transient to the batch and are never durable ids. Input spec
failure, malformed batch output, missing refs, invalid durable targets, cycles, and database errors
fail loudly and leave no partial batch writes.

`weave --pattern` is the CLI-safe, named, spec-checked, create-only front door over the same
transactional batch engine as REPL-only `millstrand.api.batch.alpha/apply!`. Raw batch is the trusted
loading-dock door: it can create, update, burn, and upsert or remove edges, so it remains a Clojure
config/REPL workflow instead of a public CLI command.

Like queries, patterns are weaver-lifetime runtime state. Define and select them in a module source with `defpattern!` if they should always exist after restart or refresh.

## Graph helpers

Millstrand ships built-in privileged alpha namespaces for trusted runtime transformations. They are
source-visible helper namespaces from the Millstrand checkout/classpath, not user/community spools that
need `spools.edn` approval — `millstrand.api.spool.alpha` (the spool-authoring helpers `fail!`,
`reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until!`) is
one of them, the blessed home every reference spool builds on:

`poll-until!` takes a Clock and a relative timeout. Runtime-owned callers pass
`(millstrand.api.runtime.alpha/clock runtime)`, keeping time reads and sleeps on one
authority; tests can install `millstrand.test.alpha/manual-clock` to avoid wall-time waits.

```clojure
(require '[millstrand.api.graph.alpha :as graph])
```

Graph helpers include operations such as query id selection, strand hydration by ids, ancestor-root traversal, subgraph expansion, and burn-by-id helpers.

```clojure
(ns my.workflow
  (:require [millstrand.api.graph.alpha :as graph]
            [millstrand.api.current.alpha :as current]))

(defn owned-strands [params]
  (let [rt (current/runtime)
        ids (graph/query-ids rt 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids rt ids)}))
```

Define and select the `owned` query beside it with `millstrand/defquery!`, or register it directly from the live REPL:

```clojure
(graph/register-query! (current/runtime) 'owned [:= [:attr :owner] "ct"])
```

For scripts, use `mill weaver repl --stdin`:

```sh
printf "(do (require 'my.workflow) (my.workflow/owned-strands {}))\n" \
  | mill weaver repl --stdin --workspace "$workspace"
```

Named read surfaces beyond queries are registered CLI operations, defined and selected with `millstrand/defop!`. Their flat arg-spec leaves carry `:hook-class :read` and `:deadline-class`; registration opts carry classes only for raw-envelope ops. They add docs, arg parsing, and `strand <op>` invocation on top of plain trusted functions like the one above.

## Events

Millstrand ships `millstrand.api.events.alpha` for trusted config and live REPL workflows that need to react to strand mutations. There are no public JSON socket or `strand` CLI commands for event registration.

Author handlers with `millstrand/defhandler` in startup-loaded code or a weaver-loadable spool. The form name becomes the handler's registry key, and `:types` selects the events it sees:

```clojure
(ns my.workflow
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defhandler! cleanup-temporary
  "Drop workspace-owned temporary rows after a strand changes."
  {:types #{:strand/updated}
   :metadata {:purpose :cleanup}}
  [event]
  ;; Handler receives one event map and can call trusted Millstrand helpers/APIs.
  nil)
```

To try a handler out without a module, register it directly from the live REPL:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.events.alpha :as events])

(events/register-handler! (current/runtime)
                          :my/cleanup-temporary
                          #{:strand/updated}
                          'my.workflow/cleanup-temporary
                          {:purpose :cleanup})
```

Handlers are selected by explicit event-type filters such as `:strand/added`, `:strand/updated`, and `:strand/burned`. Every handler has a stable key and a fully qualified function symbol resolvable in the weaver JVM. Re-registering a key you already own replaces your prior handler, which is what makes reload workflows work; a key another owner supplies collides loudly instead, and `replace-handler!` is the deliberate override there.

Event dispatch is asynchronous after successful mutations. Handler exceptions do not roll back the mutation; inspect bounded failure state from trusted Clojure:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.events.alpha :as events])
(events/handlers (current/runtime))
(events/recent-failures (current/runtime))
```

Event handler state is weaver-lifetime runtime state. Author handlers in a module source loaded from `init.clj` if they should exist after startup or refresh.

### Hooks run before the commit

Events run after a mutation commits and cannot change it. For code that runs *inside* the mutation, register a lifecycle hook instead. `millstrand.api.hooks.alpha` owns that registry, and `millstrand/defhook` authors an entry: a `:types` set of hook types plus an optional integer `:order` fixing its place in the chain.

There are two flavours, chosen by the hook type. A **validation** hook has its return value ignored and vetoes the mutation by throwing, which rolls the whole transaction back. Those types are `:strand/add-before-commit`, `:strand/update-before-commit`, `:strand/burn-before-commit`, `:strand/supersede-before-commit`, `:batch/apply-before-commit`, and `:payload/received` for a decoded JSON socket request.

```clojure
(require '[millstrand.api.millstrand.alpha :as millstrand]
         '[millstrand.api.spool.alpha :as spool])

(millstrand/defhook! require-owner
  "Refuse a strand added without an owner attribute."
  {:types #{:strand/add-before-commit} :order 10}
  [ctx]
  (when-not (spool/attr-get (:strand/after ctx) :owner)
    (throw (ex-info "owner attribute is required" {:code "owner/missing"})))
  nil)
```

The one **transform** type is `:attributes/normalize`, which folds every registered hook over the attribute map on its way into storage. A transform hook reads `:hook/value` from its context and must return `{:hook/value replacement}`; anything else fails loudly, as does a replacement that is not JSON-encodable.

Read the chain in execution order, and the owner and shadowing picture behind it, from the same namespace:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.hooks.alpha :as hooks])

(hooks/hooks (current/runtime))
(hooks/hook-provenance (current/runtime))
```

## Scheduler (no-poller wakeups)

The default answer for time-based work is still pull: stamp a `wake-at` attribute on a strand and
let a named query surface it to whatever already polls the graph. That keeps timing in ordinary,
inspectable strand data. Reach for the scheduler only for the **no-poller** case — when something
must proactively happen at instant `T` and there is no client polling to trigger it.

`millstrand.api.scheduler.alpha` is a blessed explicit-runtime namespace for that case. A wake is keyed
by a stable caller key, an absolute `java.time.Instant`, a fully qualified handler symbol, and an
optional JSON-encodable payload; the weaver persists it in dedicated weaver-owned tables (never as a
strand), re-arms pending wakes across startup and trusted reload, and dispatches due handlers
through the same serialized async lane as post-commit events. Delivery is at-least-once, so handlers
must be idempotent. There is no mutating `strand schedule` CLI: scheduling is a trusted
REPL/config/API surface only.

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.scheduler.alpha :as scheduler])

(defn remind! [{:keys [runtime key payload]}]
  ;; Handler receives one context map and runs on the shared mutation lane.
  ;; It may call trusted Millstrand APIs; its return value is ignored.
  nil)

(let [rt (current/runtime)]
  (scheduler/schedule! rt {:key "nightly-sweep"
                           :wake-at (.plusSeconds (java.time.Instant/now) 3600)
                           :handler 'my.workflow/remind!
                           :payload {:scope "temporary"}})
  (scheduler/pending rt)          ; => data-first pending wakes, earliest first
  (first (scheduler/pending rt))  ; => the earliest pending wake, or nil
  (scheduler/cancel! rt "nightly-sweep"))
```

Core stays minimal: no cron, recurrence, retry/backoff, jitter, or DST policy. A handler that wants
to run again schedules its own next wake. See the Weaver Runtime spec (`SPEC-004.P10d`) and REPL API
spec (`SPEC-003.P4a`) for the full contract.

## Fail loudly

Millstrand intentionally fails loudly instead of guessing. Expect errors for malformed config,
unsupported fields, missing weavers, stale metadata, invalid edge targets, cycles, unknown queries,
missing spools, and bad runtime code.

This is by design: the system is flexible because attributes and user code are open-ended, so surprising states should be visible and fixable rather than silently papered over.

## Practical bootstrap

Install from a checkout, start mill, and create a repo-local workspace:

```sh
make install
mill start
mill init
mill weaver start
```

For experiments, use a disposable workspace:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
mill weaver start --workspace "$workspace"
```

In another terminal:

```sh
strand --workspace "$workspace" add "Sketch workflow" --attr owner=agent
strand --workspace "$workspace" ready
```

Stop when finished:

```sh
mill weaver stop --workspace "$workspace"
```

## Spec index

Use this guide for orientation. Use the specs when you need exact behavior, contracts, or implementation boundaries.

### Strand model

Spec: [`devflow/specs/strand-model.md`](../devflow/specs/strand-model.md)

Covers:

- durable strand fields;
- `state` lifecycle values;
- burn deletion;
- JSON attributes;
- relation names and declared acyclic relations;
- readiness semantics;
- queryable fields.

Read this when you need to know what data exists, how lifecycle state works, how `depends-on` works, or what belongs in attributes instead of core fields.

### CLI surface

Spec: [`devflow/specs/cli.md`](../devflow/specs/cli.md)

Covers:

- supported `strand` commands and flags;
- workspace selection;
- `config.json` format;
- JSON-only public output;
- CLI failure behavior;
- `mill init` bootstrap behavior;
- weaver lifecycle commands;
- what the CLI intentionally does not support.

Read this when scripting `strand`, debugging CLI behavior, or deciding whether a workflow belongs in the CLI versus config/REPL code.

### REPL API

Spec: [`devflow/specs/repl-api.md`](../devflow/specs/repl-api.md)

Covers:

- live weaver REPL functions;
- `mill weaver repl --stdin` behavior;
- query registration and execution;
- `millstrand.api.runtime.alpha` loader/config helpers;
- graph, event, and explicit batch helper namespaces;
- runtime spool workspace activation.

Read this when writing trusted Clojure forms, config code, local spools, or custom query workflows.

### Weaver runtime

Spec: [`devflow/specs/daemon-runtime.md`](../devflow/specs/daemon-runtime.md)

Covers:

- weaver process model;
- config/state/data workspace selection;
- runtime metadata and socket discovery;
- JSON socket and nREPL transports;
- weaver API boundaries;
- startup config loading;
- named query registry behavior;
- runtime spool workspace model;
- graph runtime primitives;
- trusted event handler runtime and helper contracts.

Read this when debugging weaver startup, metadata, transports, runtime state, spool loading, or multi-workspace behavior.
