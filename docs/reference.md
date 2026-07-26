# Skein user reference

Skein is a local strand graph for agents and humans. It gives you a durable SQLite-backed graph of
work, notes, dependencies, and workflow state, while keeping the command-line surface small and
machine-readable.

The short version:

- A **strand** is one record in your graph.
- A **weaver** is the long-lived local process that owns the database and runtime state.
- The **`strand` CLI** is a thin JSON control surface for scripts and agents.
- The **REPL** is the trusted, high-power surface for customization and exploration.
- Your workflow model lives mostly in custom **attributes** and your own config/spool code.

This guide is written for Skein users and for agents working inside a user's Skein workspace.
Maintainer-facing contracts live in [`devflow/specs/`](../devflow/specs/); see the [spec
index](#spec-index) at the end.

## Mental model

Skein is daemon-core-first behind a small router. You start `mill` once, ask it to start a weaver
for a selected workspace, then clients send requests through `mill` to that weaver.

```text
selected workspace (normally canonical repo .skein)
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

The ordinary workspace is repository-scoped. Without `--workspace`, `mill` resolves the canonical
repository root and uses that repo's `.skein` directory as the selected workspace. Linked worktrees
for the same repository share this default workspace. Outside supported Git layouts, no-flag
commands fail loudly. `mill init` creates or completes `.skein` at the canonical Git root and fails
loudly outside supported Git layouts:

```sh
mill init
```

Mill resolves the Skein source checkout used to launch the weaver from `SKEIN_SOURCE`, the
install-time source recorded by `make install`, or a canonical Skein checkout cwd. `mill init` does
not persist a source path in `.skein/config.json`.

A workspace can also be selected explicitly with:

```sh
strand --workspace /path/to/workspace ...
```

For explicit workspaces, `/path/to/workspace` is the config workspace. Runtime state, metadata,
sockets, and data are owned by mill under Skein's XDG state root for the selected config identity.

The important file is `config.json`:

```json
{
  "configFormat": "alpha"
}
```

`config.json` is only the low-privilege alpha format marker. Source checkout paths are mill launch context, not config workspace state.

## Agent guidance files

From a Skein source checkout, `make install` installs the Go CLIs (`strand` and `mill`) and records
the checkout as mill's default source for weaver launch and the thin nREPL attach client. After
that, use the CLIs directly: `mill start`, `mill init`, and `mill weaver start`.

`mill init` is the normal repo bootstrap path. It creates or completes the canonical repo `.skein` workspace, writes shareable `config.json` with the alpha format marker when absent, and leaves shared config files ready to commit. Generated `spools.edn` opts into the batteries command surface with `skein.spools/batteries {:skein/source-root "spools/batteries"}`; generated `init.clj` activates it through a module guarded by that root. The relative coordinate resolves against the mill-selected Skein checkout, so no absolute source path is persisted. Deleting the seeded entry is the supported opt-out. Init does not run `git init` or initialize database storage; weaver startup prepares storage.

`mill init --stealth` provides the same repo-local workspace for personal use without tracked
config. It refuses if `.skein` is already tracked, maintains a marker-owned block in
`.git/info/exclude`, avoids shared agent guidance, and reports every action. An untracked
`CLAUDE.local.md` receives the standard Skein guidance when safe; Codex guidance is printed for
the user to place according to their own repository policy. See
[customising your workspace](./spools/customisation.md#a-private-repo-local-workspace) for the
recommended local-spool layout.

User-facing Skein documentation lives in the source checkout under `docs/`; the canonical user
reference is this page, `docs/reference.md`. Two harness-agnostic orientation commands surface this
to agents at runtime, with no running weaver required: `mill skein prime` resolves the Skein source
and prints the paths to the docs, the spool index, and the repo coordination guidance, plus how to
extend `.skein` config; `mill strand prime` prints the strand planning/tracking workflow. In a
repo-world bootstrap, `mill init` also seeds a `## Skein / strand` section in the repository-root
`AGENTS.md`/`CLAUDE.md` that points new agents at these two commands. Each shipped spool's per-fn
API reference (`spools/*.api.md`) and each blessed `skein.api.*.alpha` namespace's per-fn reference
(`docs/api/*.api.md`) is generated from source docstrings and regenerated with `make api-docs` —
never hand-edited; see the [spool index](../spools/README.md#doc-triad) for the
contract/cookbook/generated-API triad. The [alpha API index](./api/README.md) maps each
`skein.api.*.alpha` namespace to the concern it owns; the generated pages are reference only, and
the behavior contracts remain the root specs (see the [spec index](#spec-index)).

When working in this repository, also read the "Repo coordination workspace (.skein)" section of the
root [`AGENTS.md`](../AGENTS.md): the `.skein` shared coordination world, its working discipline,
and pointers into the live surface (`strand devflow-conventions` for the registered ops, queries,
and patterns). The config layout — `.skein/init.clj` activation order and its per-concern modules
(`config.clj`, `workflows.clj`, `harnesses.clj`, `reviewers.clj`, `attention.clj`, `nvd_scan.clj`,
`analytics.clj`) — is documented in the `init.clj` header itself.

`.skein/workflows.clj` authors this repo's workflow definitions as static `defworkflow` Vars: `land` with its `land-merge`/`land-abort` continuations, and `story` with its `story-fold`/`story-keep` continuations. Because they are static, a worker reads them with the shipped generic surface, which `.skein/init.clj` activates as its own module beside the engine: `strand workflow list` for the catalogue, `strand workflow show story` for a definition's param contract, then `start`, `ready`, `complete`, `choose`, `continue`, and `await` to drive a run.

The `land` op survives beside that generic surface because it adds behavior the engine has no business knowing: the singleton merge lock and the kanban lane moves.

The **landing workflow** (family `land`) is the coordinator-only discipline for taking a finished
branch to landed. It is three `defworkflow` definitions — `land` with its `land-merge` and
`land-abort` continuations — whose ordering is the enforcement: push the branch and open a draft
PR, drive CI green at HEAD, then run the declared roster sign-off — sign-off is only valid on a
pushed branch with a draft PR and green CI. A coordinator sign-off checkpoint (`approved` continues;
`abort` records a required reason and leaves the branch untouched) carries the approved squash
subject and body into a mechanical continuation. Under the singleton merge lock, an idempotent shell
gate runs `gh pr merge --squash`; the canonical checkout then advances with `git pull --ff-only`,
which stops for an operator rather than stashing or resetting a non-fast-forward state. Main is
protected by a repository ruleset: squash-only PR merges, ten required status checks, and a strict
up-to-date policy make PR CI the complete merged-state gate. The workflow watches main CI after the
merge, then a shell gate deletes the remote branch, removes the worktree, and prunes Git metadata.
The coordinator then tidies resources created
for the feature, finishes the kanban card when one is set
(`strand kanban finish <card> --outcome done`), and closes the run. Worker
agents never land — they stop at implemented+committed; only a coordinator holding delegated sign-off
authority drives a `land` run (`strand land about` for the manual, `strand help land` for the command
surface).

## Weaver

The weaver is the application core. It is a long-lived local Clojure process that owns:

- the SQLite database connection;
- strand creation, update, query, readiness, and burn operations;
- the in-memory named-query registry;
- the in-memory weave-pattern registry;
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

The `strand` CLI is intentionally small. It is for scripts, low-friction agent use, and JSON automation. It does not evaluate rich Clojure forms or mutate runtime extension state.

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

Skein has one deliberate convention for "how do I find out?", with three tiers, and they form an
escalation path. `prime` orients you; `about` explains an op you are about to lean on; `help`
answers exact invocation questions. Each is a **meta-verb** you put first, naming the op second:
`strand prime <op>`, `strand about <op>`, `strand help <op>`. Each tier has a different source of
truth:

| Tier | Source | Question it answers | Examples |
| --- | --- | --- | --- |
| `help` | **Generated** from registered arg-spec data | "What can I type?" — verbs, flags, positionals, types | `strand help`, `strand help <op>`, `strand help <op> <verb>` |
| `about` | **Authored** per-op prose | "What does this op mean?" — runbook context: purpose, who drives it, contracts the live surface cannot state | `strand about agent` |
| `prime` | **Authored** prose orientation | "How do we work here?" — run **before** starting work | `mill skein prime`, `mill strand prime`, `strand prime agent` |

The meta-verb goes first. The old `<op> help` / `<op> about` / `<op> prime` sole-token sugar is retired: a bare `help`, `about`, or `prime` in verb position now fails with a loud redirect to `strand help <op>`, unless the op declares a real subcommand by that name (several spool ops do, noted below). A trailing `--help` or `-h` flag still works on any op, so `strand agent --help` rewrites to `strand help agent`.

**`help` is never hand-written.** It is one declared, versioned schema, uniformly projected; renderings are transforms over it. `strand help` lists every registered op; `strand help <op>` renders one op's detail from its arg-spec; `strand help <op> <verb> [<verb> ...]` slices to any declared node, including an interior node with further verbs.
A detail response is a canonical envelope, `{schema-version, operation, source, glossary, node}`, verbose by default, where `node` is a self-similar shape that recurses into subcommands, `source` points at the handler's `file:line` when it resolves, and `glossary` defines the named failure outcomes a node references. With no help transform registered, that raw envelope JSON is the output. A workspace can register one default help transform to render it as friendlier text instead; `--json` is the sole opt-out, always returning the raw envelope, so a failing transform can never brick help. Missing or unknown subcommands at any depth fail with structured parser errors carrying the path walked and the available names. No spool ever writes its own usage strings or dispatch errors. Contracts: SPEC-002.C39 and the discovery-tier deltas.

**`about` is the op's runbook context.** `strand about <op>` returns the op's authored `:about` prose as a small JSON object: what the op is for, who drives it, and the contracts and attribute conventions a caller cannot read off the live surface. It stays short, and it points at whatever owns the detail: `help` for invocation, and for an op that drives a definition, the definition itself (`strand workflow show <name>`). A step map, a verb list, or a flag table restated here is a second copy that drifts from the first. An op that declares no `:about` prose returns a loud `discovery/unavailable` rather than empty success; purely structural ops (batteries `add`/`list`/...) need none, since their arg-spec already says everything. Some spool ops instead declare their own `about` subcommand, and for those the resolving form is `strand <op> about`: `strand kanban about`, `strand land about`, `strand spool about`.

**`prime` is run-first context priming for agents.** A `prime` command prints the working discipline
for an area: the conventions an agent must load *before* acting, with pointers to deeper docs. `mill
strand prime` (the day-to-day strand workflow) and `mill skein prime` (building on `.skein` and the
source docs, read on demand) need no running weaver: `mill` resolves the Skein source checkout and
renders the topic file the manifest at `docs/prime/index.json` names, so an already-installed `mill`
prints current orientation text from a newer checkout. Op-level primes are spool-authored prose
projected through the builtin `prime` meta-verb (`strand prime agent`), so they can never drift from
the installed surface; spool ops that declare their own `prime` subcommand answer `strand <op> prime`,
such as `strand kanban prime`. Repo-world `mill init` seeds a marker-guarded
section into `AGENTS.md`/`CLAUDE.md` pointing fresh agents at the prime commands (see "Agent guidance
files").

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
not undo: recovery is a REPL-only activity (`skein.repl/burn-history`, `recent-burns`), a replay
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

Skein's core is deliberately small. Most workflow meaning hangs off `attributes`.

Examples:

```sh
strand --workspace "$workspace" add "Draft release notes" \
  --attr owner=agent \
  --attr project=skein \
  --attr kind=doc \
  --attr priority=high
```

Your workspace can decide what attributes mean. For example:

- `owner=agent` can mean an agent should pick it up;
- `kind=feature` can identify feature roots;
- `outcome=done` can record completion reason after deactivation;
- `temporary=true` can identify rows your own tooling treats as temporary;
- `external.issue=123` can link to another system if your tooling understands it.

Skein stores attributes as JSON text. CLI input is simple string pairs; `--attr temporary=true`
stores the string `"true"`, not a JSON boolean. Trusted Clojure workflows can write richer
JSON-compatible values.

Because attributes are userland, your own config and spools should define the conventions for your
workspace. Prefer documenting those conventions in source-controlled docs or in your spool docs.
Attribute names and cleanup behavior are userland choices, not Skein core.

[Modeling attribute values](spools/writing-shared-spools.md#modeling-attribute-values-enums-absence-empty-history)
covers how to model a value once you own the convention: when a finite state wants an enum, when an
optional fact should go absent rather than blank, when an empty string is real data, and when a fact
belongs in recorded history.

## Queries

Queries can be registered in weaver memory, then consumed by the REPL or CLI.

From the live weaver REPL, `defquery!` registers a query for the current weaver lifetime only:

```clojure
(defquery! 'agent-docs
  '[:and
    [:= [:attr :owner] "agent"]
    [:= [:attr :area] "docs"]])
```

Discover and consume it from the CLI:

```sh
strand --workspace "$workspace" query list
strand --workspace "$workspace" query explain agent-docs
strand --workspace "$workspace" list --query agent-docs
strand --workspace "$workspace" ready --query agent-docs

```

`query list` and `query explain <name>` are the read-only discovery pair for named query
definitions. Application stays on the read commands: `list --query <name>` and `ready --query
<name>` with repeated `--param key=value` when the query declares runtime params.

Named query registries are not durable by themselves. If you want a query after every weaver restart, register it from startup-loaded code.

For a simple persistent query, expose a complete contribution from a small workspace namespace and name its entry point in the public `spool` var:

```clojure
(ns my.workspace)

(defn contribute [_ctx]
  {:queries {"mine" [:= [:attr :owner] "ct"]}})

(def spool
  {:contribute 'contribute})
```

Declare that owner from `init.clj`:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:ns 'my.workspace})
```

For a workspace that already declares a local spool, add the query to that
module's contribution so owner-complete refresh installs everything together.

Defining a Clojure var that contains query data is not the same as registering a named query. A
local var can be passed to graph helpers from your own code, but `strand list --query mine` only
works after `mine` has been registered in the weaver's named-query registry.

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

The grammar is a deliberately narrow boundary surface. New forms must meet the [SPEC-001.P9 acceptance criteria](../devflow/specs/strand-model.md); a selection the grammar cannot express belongs in a registered read op. Put `:hook-class :read` and `:deadline-class` on that flat arg-spec leaf, then compose registered queries and extra filtering in Clojure through the `skein.api.graph.alpha` helpers ([Graph helpers](#graph-helpers)). CLI callers invoke it like any other op.

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
surfaces (the REPL `query` helper or `skein.api.graph.alpha`), where params are real EDN values.
Registration to invocation looks like:

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
(`skein.core.query`).

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
(def id (:id (strand! "Explore workflow" {:owner "ct" :kind "spike"})))
(strand id)
(update! id {:state "closed" :attributes {:outcome "captured"}})
(strands)
(ready)
```

Script the live weaver REPL with stdin:

```sh
printf '(skein.api.current.alpha/runtime)\n' | mill weaver repl --stdin --workspace "$workspace"
```

The REPL helper namespace includes common strand functions. Privileged runtime loader/config helpers
are explicit built-in namespaces, not ordinary user spools; require them when needed:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])
(runtime/refresh! (current/runtime))
```

### Burn recovery

Burn is deletion, not undo. Every burn writes a forensic tombstone (SPEC-001.P3/P8), read from the
interactive `skein.repl` surface over the in-process `skein.core.db` read fns. Run these from an
in-process `mill weaver repl`; they throw with remediation from a connected-client REPL that has no
in-process runtime.

```clojure
(recent-burns 20)             ; scan recent deletions across all strands, newest first
(burn-history "<burned-id>")  ; every tombstone recorded for one burned id
```

Each tombstone carries the burned strand's core fields, its full attribute map (each value tagged
`{:value ... :archived ...}` so archived keys stay distinguishable), its incident edges, and
`recorded_at`. The shapes map onto the batch graph mutation payload's strand and edge entries, so
recovery is mechanical: assemble a payload from the tombstone and apply it.

```clojure
(require '[skein.api.batch.alpha :as batch]
         '[skein.api.current.alpha :as current])

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

The weaver loads trusted startup files from the selected workspace in order — `init.clj`, then
`init.local.clj` — and everything registered there (named queries, weave patterns, event
handlers, activated spools) is weaver-lifetime runtime state. The full customisation story is its
own page: [customising your workspace](./spools/customisation.md) covers the files `mill init`
bootstraps, direct `init.clj` registrations, smoke-testing config in a disposable world,
module refresh and `reload-code!` semantics, REPL hygiene in a shared weaver, promoting config to a local
spool, and the `skein.userland.alpha` ergonomics layer. The rules change only when a spool leaves
your machine: [writing shared spools](./spools/writing-shared-spools.md).

## Weave patterns

Weave patterns are trusted owner-defined transformations that turn a JSON-like input payload into an
atomic batch of new strands and edges. They are useful when agents should submit intent and your
workspace should decide the graph shape.

Pattern registration lives in trusted Clojure config or spools, not in the public CLI. A pattern has
a simple name, a fully qualified weaver-loadable function symbol, and a `clojure.spec` input
contract.

```clojure
(ns my.workflow
  (:require [clojure.spec.alpha :as s]))

(s/def ::title string?)
(s/def ::task-input (s/keys :req-un [::title]))

(defn task-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {:owner "agent"}}
   {:ref 'review
    :title (str "Review: " (:title input))
    :attributes {:kind "review"}
    :edges [{:type "depends-on" :to 'impl}]}])
```

Register the pattern from startup config or the live REPL:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.patterns.alpha :as patterns])

(patterns/register-pattern! (current/runtime) 'task 'my.workflow/task-pattern ::task-input)
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
the same batch vector shape accepted by Skein's batch primitive: strand maps with optional `:ref`
and `:edges`. Symbolic refs are transient to the batch and are never durable ids. Input spec
failure, malformed batch output, missing refs, invalid durable targets, cycles, and database errors
fail loudly and leave no partial batch writes.

`weave --pattern` is the CLI-safe, named, spec-checked, create-only front door over the same
transactional batch engine as REPL-only `skein.api.batch.alpha/apply!`. Raw batch is the trusted
loading-dock door: it can create, update, burn, and upsert or remove edges, so it remains a Clojure
config/REPL workflow instead of a public CLI command.

Like queries, patterns are weaver-lifetime runtime state. Register them from startup config if they should always exist after restart or reload.

## Graph helpers

Skein ships built-in privileged alpha namespaces for trusted runtime transformations. They are
source-visible helper namespaces from the Skein checkout/classpath, not user/community spools that
need `spools.edn` approval — `skein.api.spool.alpha` (the spool-authoring helpers `fail!`,
`reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until!`) is
one of them, the blessed home every reference spool builds on:

`poll-until!` takes a Clock and a relative timeout. Runtime-owned callers pass
`(skein.api.runtime.alpha/clock runtime)`, keeping time reads and sleeps on one
authority; tests can install `skein.test.alpha/manual-clock` to avoid wall-time waits.

```clojure
(require '[skein.api.graph.alpha :as graph])
```

Graph helpers include operations such as query id selection, strand hydration by ids, ancestor-root traversal, subgraph expansion, and burn-by-id helpers.

```clojure
(ns my.workflow
  (:require [skein.api.graph.alpha :as graph]
            [skein.api.current.alpha :as current]))

(defn owned-strands [params]
  (let [rt (current/runtime)
        ids (graph/query-ids rt 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids rt ids)}))
```

Register the `owned` query from startup config or the live REPL:

```clojure
(graph/register-query! (current/runtime) 'owned [:= [:attr :owner] "ct"])
```

For scripts, use `mill weaver repl --stdin`:

```sh
printf "(do (require 'my.workflow) (my.workflow/owned-strands {}))\n" \
  | mill weaver repl --stdin --workspace "$workspace"
```

Named read surfaces beyond queries are registered CLI operations. Their flat arg-spec leaves carry `:hook-class :read` and `:deadline-class`; registration opts carry classes only for raw-envelope ops. They add docs, arg parsing, and `strand <op>` invocation on top of plain trusted functions like the one above.

## Events

Skein ships `skein.api.events.alpha` for trusted config and live REPL workflows that need to react to strand mutations. There are no public JSON socket or `strand` CLI commands for event registration.

Register handlers from startup-loaded code or weaver-loadable spools:

```clojure
(ns my.workflow)

(defn cleanup-temporary! [event]
  ;; Handler receives one event map and can call trusted Skein helpers/APIs.
  (when (= :strand/updated (:event/type event))
    ;; your workspace-specific cleanup here
    nil))
```

Register the handler from startup config or the live REPL:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.events.alpha :as events])

(events/register-handler! (current/runtime)
                          :my/cleanup-temporary
                          #{:strand/updated}
                          'my.workflow/cleanup-temporary!
                          {:purpose :cleanup})
```

Handlers are selected by explicit event-type filters such as `:strand/added`, `:strand/updated`, and
`:strand/burned`. Registration uses a stable key and a fully qualified function symbol resolvable in
the weaver JVM; duplicate keys replace prior handlers for reload workflows.

Event dispatch is asynchronous after successful mutations. Handler exceptions do not roll back the mutation; inspect bounded failure state from trusted Clojure:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.events.alpha :as events])
(events/handlers (current/runtime))
(events/recent-failures (current/runtime))
```

Event handler state is weaver-lifetime runtime state. Register handlers from `init.clj` or a loaded spool module if they should exist after startup or refresh.

## Scheduler (no-poller wakeups)

The default answer for time-based work is still pull: stamp a `wake-at` attribute on a strand and
let a named query surface it to whatever already polls the graph. That keeps timing in ordinary,
inspectable strand data. Reach for the scheduler only for the **no-poller** case — when something
must proactively happen at instant `T` and there is no client polling to trigger it.

`skein.api.scheduler.alpha` is a blessed explicit-runtime namespace for that case. A wake is keyed
by a stable caller key, an absolute `java.time.Instant`, a fully qualified handler symbol, and an
optional JSON-encodable payload; the weaver persists it in dedicated weaver-owned tables (never as a
strand), re-arms pending wakes across startup and trusted reload, and dispatches due handlers
through the same serialized async lane as post-commit events. Delivery is at-least-once, so handlers
must be idempotent. There is no mutating `strand schedule` CLI: scheduling is a trusted
REPL/config/API surface only.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.scheduler.alpha :as scheduler])

(defn remind! [{:keys [runtime key payload]}]
  ;; Handler receives one context map and runs on the shared mutation lane.
  ;; It may call trusted Skein APIs; its return value is ignored.
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

Skein intentionally fails loudly instead of guessing. Expect errors for malformed config,
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
- `skein.api.runtime.alpha` loader/config helpers;
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
