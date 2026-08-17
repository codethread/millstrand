# Millstrand tutorial

This tutorial takes you from nothing to a working Millstrand setup, then a little further, into the live REPL and a taste of customisation. You can follow it top to bottom. You do not need to know Clojure or any graph tooling to start.

It is written in two halves:

1. **The everyday CLI** — install, start a weaver, add strands, ask what is ready. This is all most people need day to day.
2. **The live machine** — the REPL, named queries, and where customisation starts. Optional, and clearly marked, so you can stop after part one and come back later.

The guide marks places where you can skip ahead. If a section is not what you need right now, skip it. To go straight to the live runtime, jump to [The REPL: a live machine](#the-repl-a-live-machine).

## Prefer to learn by asking?

Millstrand's own repository is written to be read by coding agents, and it ships agent-facing docs, `prime` orientation commands, and the specs behind every contract. If you already work with a coding agent, you can point it at a Millstrand checkout and ask questions as you go.

- `mill prime millstrand` prints orientation for the Millstrand source, docs, and how to extend a `.millstrand` or `.ms` config. `mill prime strand` explains the strand planning-and-tracking workflow. Both run with no weaver.
- `docs/reference.md`, the `spools/` contracts, and `devflow/specs/` hold the real detail.

An agent that has read that surface can answer "how do I model a review step?" or "what belongs in the CLI versus the REPL?" interactively. You can follow this guide without an agent; the agent is optional.

## The mental model in one minute

A **strand** is a small record with three parts: a title, a **lifecycle `state`** (whether it is `active`, `closed`, or `replaced`), and an **open map of `attributes`**. "Open map" means the attribute names are yours to invent (`owner`, `priority`, `kind`, `kanban`, whatever your workflow needs) instead of waiting for a schema to grow. Invention has one boundary: names belong to the concepts they describe, so when you build on a spool that already publishes attribute names, you reuse those names rather than coin your own for the same thing.

Strands connect with **edges**: links from one strand to another. Edges are **typed**, meaning each carries a relation name like `depends-on`, and they can carry attributes of their own.

You work with strands two ways:

- The **`strand` CLI** for everyday create, update, and read. Its commands print JSON, so scripts and agents can consume them.
- The **weaver's REPL** for everything richer: querying, mutating, and extending Millstrand while it runs, without restarting anything. A REPL is an interactive Clojure prompt; more on it in part two.

The CLI stays deliberately thin. Runtime customization lives in trusted config and the REPL. See [PHILOSOPHY.md](../devflow/PHILOSOPHY.md) for why the line is drawn there.

## Before you start

You need a few things on the machine:

- **Git**, and a Git repository to track work in. Millstrand is repo-first: without `--workspace` it selects a repo's `.millstrand` or `.ms`, so no-flag use needs Git. An explicit `--workspace <dir>` bootstraps and runs anywhere, Git or not.
- **make**, **Go**, the Clojure CLI, and a JVM when installing from a source checkout. Go compiles `strand` and `mill`; the JVM runs the weaver.

## Install

On macOS, Homebrew installs Millstrand and its Clojure/JVM runtime:

```sh
brew tap codethread/millstrand https://github.com/codethread/millstrand.git
brew trust --formula codethread/millstrand/millstrand
brew install millstrand
```

Homebrew requires explicit trust for non-official taps. This trusts only the Millstrand formula, not every current or future item in the tap. Homebrew installs Clojure and its JVM dependency.

Homebrew retains Millstrand's source under its `libexec` directory and records that path for `mill` when it launches a weaver.

To install from a cloned checkout instead, install the prerequisites above and run:

```sh
make install
```

This builds and installs the `strand` and `mill` CLIs and records this checkout as mill's source for launching weavers. Neither installation method changes anything `mill init` writes into a repo.

## Start mill

`mill` is the local supervisor. Everything below — `mill init`, starting the weaver, the REPL — routes through it, so start it once in a terminal you can leave open:

```sh
mill start
```

Leave it running for the rest of this guide.

## Choosing a workspace

A workspace is one isolated Millstrand setup. By default `strand` is **repo-first**: when you do not pass `--workspace`, `mill` looks upward for the Git repository root (the "canonical" root that linked worktrees share) and uses that repo's `.millstrand` or `.ms` directory as your workspace. Two worktrees of the same repository talk to the same weaver and the same data.

If you omit `--workspace` outside a Git repository, the command fails with a remediation message instead of guessing. It will not invent a workspace from your current directory or fall back to a global one.

That selected `.millstrand` or `.ms` directory holds trusted config only. The runtime state (metadata, sockets, and the SQLite database) lives under Millstrand's own state directory, not in your repo.

Only one accepted marker may exist, and it must be a directory. If both `.millstrand` and `.ms` exist, or either marker is a non-directory, Mill fails with the paths and remediation; repair the application marker before retrying. The legacy `.millstrand` name is rejected as an application marker; preserve it when it is a repository coordination workspace, and use an explicit `--workspace` or migrate application config to `.millstrand`/`.ms` without deleting coordination state.

Create a workspace in the repo you want to use Millstrand in:

```sh
mill init
```

When no accepted marker exists, `mill init` creates `.millstrand` at the Git root; when exactly one accepted marker exists, it completes that marker in place. It writes shared, committable config files (covered later) and never overwrites ones you already have. It fails loudly outside Git, does not run `git init`, and does not create the database; the weaver prepares storage when it starts.

For personal use without tracked repository changes, use stealth bootstrap:

```sh
mill init --stealth
```

This keeps the selected repo-local workspace and `CLAUDE.local.md` out of the shared repository; its marker-owned Git private exclude block keeps the workspace private. It prints every change and the Codex instruction you may add to your own agent guidance. Keep substantial personal config in a [local spool](./spools/customisation.md#a-private-repo-local-workspace).

**Escape hatch — throwaway workspaces.** For experiments, tests, or agent work, use a disposable workspace so you never touch a real repo's config:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
```

`--workspace` is not sticky, so pass the same path on **every** command that should target it, for example `mill weaver start --workspace "$workspace"`. The plain examples below leave the flag off for readability; the customization examples later use an explicit `$workspace` so you never casually reload a real repo's config.

## Start the weaver

With `mill` running, ask it to start the weaver for your workspace:

```sh
mill weaver start
```

There is no separate database-init step. Starting the weaver prepares storage.

## Add and inspect strands

Add a couple of strands, with a few attributes of your own choosing:

```sh
strand add "Review docs" --attr owner=ct --attr area=docs
strand add "Scratch idea" --attr temporary=true
```

Attributes are plain `key=value` strings. `--attr temporary=true` stores the string `"true"`, not a JSON boolean; richer values are a REPL job, covered later.

List everything, or just what is ready. These commands print JSON:

```sh
strand list
strand ready
```

## Dependencies and readiness

An edge named `depends-on` from `A` to `B` means "A is blocked while B is active". To add one, you need the id of the strand you depend on. `strand add` prints the new strand as JSON, so create the blocker first and read its `"id"` from the output:

```sh
strand add "Sketch the model"
```

The output includes a field like `"id": "ab12c"`. Use that generated id (yours will differ) to add a second strand that depends on it:

```sh
strand add "Build the weaver" --edge depends-on:ab12c
strand ready
```

`ready` returns active strands whose `depends-on` targets are inactive or absent (closed, replaced, or never created), so it shows "Sketch the model" but not "Build the weaver". Close the first and the second becomes ready. Use the id printed for "Sketch the model" earlier:

```sh
strand update ab12c --state closed
strand ready
```

**Scripting tip.** Once you are comfortable, you can capture an id in one line instead of copying it by hand. `jq` is the standard JSON command-line tool, and `$(...)` runs a command and keeps its output in a shell variable:

```sh
design=$(strand add "Sketch the model" | jq -r '.id')
strand add "Build the weaver" --edge depends-on:"$design"
```

`depends-on`, `parent-of`, `supersedes`, `serves`, and `notes` are Millstrand's five **declared acyclic relations**: a "relation" is an edge type, and "acyclic" means Millstrand rejects cycles in each of them (A cannot end up depending on itself through a chain). It also rejects an edge from a strand to itself on any relation.

## Closing and deleting

There is no special "done" command. Close a strand when it is no longer active, and record an outcome as an attribute if you want one:

```sh
strand update <strand-id> --state closed --attr outcome=done
```

On `update`, `--attr` merges into the strand's existing attributes: this call adds `outcome=done` and leaves `owner`, `area`, and anything else already there untouched. To change one attribute, name just that one.

Closed strands stay visible with `state="closed"`. Deletion is separate and explicit:

```sh
strand burn <strand-id>
```

---

That is the everyday CLI: add, relate, ask what is ready, close, and occasionally burn. Those commands come from the [batteries spool](../spools/batteries.md), which `mill init` activates. If you do not need the REPL or runtime customization, you can stop here and run `mill weaver stop`. The rest of this guide covers the REPL and building your own behavior.

## The REPL: a live machine

The weaver is a running Clojure image. `mill weaver repl` attaches directly to it, so the code you type runs inside the weaver, against your real strands, with no restart between edits.

```sh
mill weaver repl
```

For editor-driven work, see the [IDE REPL setup guide](./ide-repl/) for connecting VS Code or Calva to the running weaver's nREPL.

**Reading the Clojure below.** A handful of rules cover everything in this section:

- A call puts the function name first, inside the parentheses: `(weaver/add! rt m)` calls `add!` with `rt` and `m`.
- The `!` on a name is a convention for "this changes something", not syntax.
- A word starting with a colon, like `:owner`, is a **keyword**: a plain, self-describing name often used as a map key.
- Curly braces make a **map** of key/value pairs: `{:owner "ct"}`.
- `def` gives a value a name you can reuse: `(def s ...)` binds `s`.

The [Clojure crash course](./clojure-crash-course.md) covers the rest. For a custom CLI command in trusted Clojure, the worked example lives in [customising your workspace](./spools/customisation.md).

`mill weaver repl` starts in the neutral `user` namespace with `millstrand.repl` aliased `repl`, so the live registration verbs are one keystroke away. Strand reads and writes come from `millstrand.api.weaver.alpha`, whose functions take the runtime first — capture it once:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(def rt (current/runtime))                        ; the weaver running in this JVM

(def s (:id (weaver/add! rt {:title "My first REPL strand"
                             :attributes {:owner "ct"}})))   ; create; keep its :id in s
(weaver/show rt s)                                            ; look it up by id
```

Create several related strands in one transactional call. `:ref` values are temporary handles (stand-in names) so `:edges` can link siblings before the real ids exist; the returned `:refs` map binds each handle to its generated id:

```clojure
(require '[millstrand.api.batch.alpha :as batch])   ; transactional graph mutations

(def refs
  (:refs
   (batch/apply! (current/runtime)
    {:strands [{:ref :design :title "Sketch the data model" :attributes {:owner "ct" :priority "high"}}
               {:ref :build  :title "Implement the weaver"  :attributes {:owner "ct"}}
               {:ref :docs   :title "Write getting-started" :attributes {:owner "agent"}}]
     :edges   [{:op :upsert :from :build :to :design :type "depends-on"}
               {:op :upsert :from :docs  :to :build  :type "depends-on"}]})))
```

The `:edges` vector also takes `{:op :remove :from :to :type}` to delete one exact edge by its `(from, to, type)` identity. Both endpoint handles must come from top-level `:refs` bound to existing durable strand ids: refs created in the same payload work for `:upsert`, but not `:remove`. Removal is strict — if that edge is already gone the whole batch fails loudly rather than passing silently.

Now write a small helper and use it:

```clojure
(defn brief
  "Keep just the :id and :title of each strand row."
  [rows]
  (map #(select-keys % [:id :title]) rows))

(brief (weaver/list rt))   ; every strand, summarized
```

Because the weaver is live, you can improve `brief` while it runs. Redefine it, and the next call uses the new version. No restart, no lost strands:

```clojure
(require '[clojure.pprint :refer [pprint]])

(defn brief
  "Pretty-print just the :id and :title of each strand row."
  [rows]
  (pprint (map #(select-keys % [:id :title]) rows)))

(brief (weaver/list rt))          ; same call, now pretty-printed
(brief (weaver/ready rt))         ; only strands with no active dependency
(weaver/update! rt s {:state "closed"})   ; close one; the row stays, state becomes "closed"
```

Millstrand ships graph helpers too. `graph/subgraph` walks a declared acyclic relation from a root id and returns the connected strands and edges. Fold that into an ASCII tree:

```clojure
(require '[millstrand.api.graph.alpha :as graph]
         '[clojure.string :as str])

(defn dag-tree
  "Render the depends-on subgraph under root-id as an ASCII tree of titles."
  [root-id]
  (let [{:keys [strands edges]} (graph/subgraph (current/runtime) [root-id] {:type "depends-on"})
        title    (into {} (map (juxt :id :title)) strands)
        children (group-by :from_strand_id edges)
        lines    (fn lines [id depth]
                   (cons (str (apply str (repeat (max 0 (dec depth)) "   "))
                              (when (pos? depth) "└─ ")
                              (title id))
                         (mapcat #(lines (:to_strand_id %) (inc depth))
                                 (children id))))]
    (str/join "\n" (lines root-id 0))))

(println (dag-tree (:docs refs)))   ; walk from the docs strand
```

Produces:

```text
Write getting-started
└─ Implement the weaver
   └─ Sketch the data model
```

## Named queries: from the REPL to the CLI

A query is a data expression, here "the `owner` attribute equals `ct`". When a query belongs directly to one workspace module, define and select it with `defquery!`:

```clojure
(ns my.workspace
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])
```

Activate that namespace from trusted startup code:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:ns 'my.workspace})
```

`defquery!` is the direct define-and-select form. It defines the `mine` Var, attaches an authoring descriptor to that Var's metadata, and selects the descriptor for publication. The selection contributes only while the weaver is collecting the selected `my.workspace` module source. Evaluating the same form at a REPL still defines and returns the Var, but collection is not active there, so it publishes nothing.

The descriptor records the authoring protocol, family, channel, registry kind and key, entry data, and fully qualified Var name. Typed selection checks that metadata and the Var's family before it contributes anything. The Var's value remains ordinary Clojure data (or a function for function-backed families); the descriptor is metadata, and registration does not replace or mutate the Var. `defquery!` returns the Var it defined.

### Reuse a catalogue with inert definitions

Use the two-step form when declarations belong to a reusable catalogue. The catalogue defines inert Vars:

```clojure
(ns my.query-catalogue
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])

(millstrand/defquery unowned
  "Return strands without an owner."
  {}
  [:missing [:attr :owner]])
```

`defquery` defines each Var and installs its descriptor, but selects nothing. A module source imports the catalogue and chooses what it owns:

```clojure
(ns my.workspace
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [my.query-catalogue :as catalogue]))

(millstrand/use-query! catalogue/mine catalogue/unowned)
```

Keep the `runtime/module!` declaration pointed at `my.workspace`, not `my.query-catalogue`. Requiring the catalogue evaluates its inert definitions outside collection of the selected source. The `use-query!` call runs in `my.workspace`, validates that both symbols resolve to query declaration Vars, contributes them in source order, and returns a vector containing those Vars. A query selector cannot accept an op Var or arbitrary expression. Registry selectors may take a leading `{:override? true}` when the module intentionally shadows another owner's key.

Collection is source-scoped. Requiring a catalogue may evaluate inert `def*` forms in that required namespace; they define descriptor-bearing Vars and are safe. If a `use-*!` or `def*!` form is evaluated in a foreign namespace while collection is active, collection fails loudly with the module, namespace, and file context. Outside module collection, definitions and selectors evaluate passively: they return their documented Vars and values without changing a registry. This is why evaluating a source form in an editor is safe but is not a publication step.

Each successful module collection is owner-complete. The collected set replaces that module owner's previous set. If `catalogue/unowned` is omitted from the next version of `my.workspace`, refresh removes the module's `unowned` registration. If the source selects nothing, refresh publishes an empty partition and removes every registry entry previously owned by that module. You do not need matching unregister calls for declarations removed from source.

Ops, patterns, hooks, handlers, and bins use the same three forms: inert `def*`, typed `use-*!`, and direct `def*!`. Custom open registry families created with `defauthoring` follow the same model. Lifecycle families are parallel: `defseed`, `defresource`, and `defreconcile` are inert; their `use-...!` forms select typed lifecycle Vars; and the bang forms define and select. Lifecycle selectors do not take registry override options.

Older module sources may rely on definitions being collected implicitly. Make every intended selection explicit when migrating:

- Change a declaration owned and used in the same source from `defquery` to `defquery!` (and likewise for the other families).
- Leave reusable catalogue declarations inert and add the matching typed `use-query!`, `use-op!`, or other selector in the selected module source.
- Refresh the module and inspect the resulting registry. Owner-complete collection removes old entries that the new source omits.

Do not add direct registration calls to preserve the old effect. They create runtime-local entries outside the module's owner-complete publication.

For a live experiment, use the same verb at the explicit-runtime tier:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.graph.alpha :as graph])

(def rt (current/runtime))
(graph/register-query! rt 'mine [:= [:attr :owner] "ct"])
```

Inside `mill weaver repl`, `millstrand.repl` supplies the same verb with the runtime implied:

```clojure
(repl/register-query! 'mine [:= [:attr :owner] "ct"])
```

`register-query!` is the same operation in both live tiers; the explicit-runtime form is for code and tests, while `millstrand.repl` is the short form for an interactive session. The registry is owner-partitioned and layered. Each writer changes its own entry map, and the effective name-to-query view is the merge of those partitions.

Use `replace-query!` when you intend to shadow an existing owner. `unregister-query!` removes only your own entry and restores the entry below it; registry verbs never remove or change the Clojure Var. Removing a direct shadow and registering again is not a substitute for replace, because the other owner's entry still occupies the name. For queries, replacing the value is also how you iterate behavior: there is no handler function to redefine, and `query explain` always reads the current registered value.

For live work on ops, patterns, hooks, and event handlers, use explicit-runtime `register/replace/unregister-*!` calls in code or tests and the runtime-implied `millstrand.repl` wrappers while iterating interactively. A workspace can durably mask a spool op with `millstrand/defop! {:override? true}` in a workspace module. The coordinate used to acquire that spool, local or git-pinned, does not change these registry rules.

The plain CLI can discover and run the same query for as long as this weaver keeps running:

```sh
strand query list
strand query explain mine
strand list --query mine
strand ready --query mine
```

`query list` and `query explain <name>` are read-only discovery. Applying a query stays on `list --query` and `ready --query`. See the [REPL API spec](../devflow/specs/repl-api.md) for the full predicate language.

Named queries registered directly are runtime-local, although startup code can reapply one on each restart. That reapplication does not make the entry refresh-safe or owner-complete. The module form keeps one across restarts; the explicit-runtime form is the useful middle ground for trusted code and tests.

## Startup config: making it stick

Weaver-lifetime state means direct registrations disappear at the next restart. The workspace loads trusted startup code — `init.clj`, then a gitignored `init.local.clj` — every time the weaver starts. For a small, durable registration, activate a module from that startup code:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:ns 'my.workspace})
```

The module source is the durable owner of the query. The whole ladder — startup files, live registration, reloading a weaver, promoting config to a spool, and worked examples — is [customising your workspace](./spools/customisation.md); this tutorial stops at showing you the rung exists.

## Stop the weaver

Stop the weaver when you are finished. In a repo workspace:

```sh
mill weaver stop
```

For a throwaway workspace, pass the same `$workspace` path you created it with:

```sh
mill weaver stop --workspace "$workspace"
```

## Where to go next

- [Millstrand user reference](./reference.md) — the complete model, CLI, weaver, REPL, and workspace behavior, with a spec index at the end.
- [Shipped reference spools](../spools/README.md) — a workflow engine, a feature lifecycle, a kanban board, and more, as working code.
- [Customising your workspace](./spools/customisation.md) — the full ladder from `init.clj` to your own local spool.
- [Writing shared spools](./spools/writing-shared-spools.md) — building extensions other people can run.
- [Testing your config and spools](./spools/testing.md) — from disposable worlds to weaver-world integration tests against a chosen Millstrand checkout.
- [Clojure crash course](./clojure-crash-course.md) — enough Clojure to be comfortable in the REPL.
- [Tenets](../devflow/TENETS.md) and [philosophy](../devflow/PHILOSOPHY.md) — why Millstrand is shaped the way it is.
