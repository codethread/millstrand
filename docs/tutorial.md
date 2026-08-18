# Millstrand tutorial

This tutorial starts with an empty Git repository and leaves you with a working strand graph, a useful named query, and your own `strand hello` op. You do not need to know Clojure before you start. The first half uses the everyday CLI; the second introduces the live weaver and durable workspace code.

If you only want to track work, stop at [Everyday work](#everyday-work). The rest shows how Millstrand becomes a programmable runtime.

## What you need

For a Homebrew installation on macOS:

```sh
brew tap codethread/millstrand https://github.com/codethread/millstrand.git
brew trust --formula codethread/millstrand/millstrand
brew install millstrand
```

Homebrew installs the Clojure and JVM dependencies and records the retained Millstrand source used to launch weavers.

To install from a cloned Millstrand checkout, first install Git, make, Go, the Clojure CLI, and a JVM, then run:

```sh
make install
```

This builds and installs `strand` and `mill` and records the checkout as the source used to launch weavers.

You will also use `jq` once to read an id from JSON. Install it before continuing if it is not already available.

## Create a repository and workspace

Start in a new Git repository:

```sh
mkdir learn-millstrand
cd learn-millstrand
git init
mill init
```

`mill init` creates `.millstrand`, the repository's Millstrand workspace. It contains shared config, startup code, and approved spool coordinates. Runtime metadata, sockets, and the SQLite database live outside the repository under Millstrand's state directory.

You do not need a running `mill` process for `mill init`. It is a local bootstrap command and never initializes the database or runs `git init` for you.

Without `--workspace`, commands resolve the canonical Git repository root and select its `.millstrand` or `.ms` directory. Linked worktrees therefore share one default workspace. Conflicting, invalid, or unsupported legacy markers fail with remediation instead of being guessed or migrated. Outside a supported Git worktree, no-flag selection also fails.

For personal use without tracked workspace files, run `mill init --stealth` instead. For an isolated experiment outside Git, select a directory explicitly on every command:

```sh
workspace="$(mktemp -d)"
mill init --workspace "${workspace:?}"
mill weaver start --workspace "${workspace:?}"
```

`--workspace` is not sticky. This tutorial uses the repository workspace created above, so the remaining commands omit it.

## Start Millstrand

`mill` is the local router and supervisor. Start it in a terminal and leave it running:

```sh
mill start
```

If it reports that `mill` is already running, keep the existing supervisor and continue. Open another terminal in `learn-millstrand`, then start this workspace's weaver:

```sh
mill weaver start
```

The weaver is the long-lived Clojure process that owns the graph and runtime state. Its first start prepares the database. Check both the workspace selection and process state with:

```sh
mill weaver status
```

## The strand model

A strand has an id, a title, one lifecycle `state`, timestamps, and an open map of `attributes`. The state is `active`, `closed`, or `replaced`. Concepts such as owner, priority, outcome, and project are attributes whose names your workspace or a spool defines.

Edges are named, directed links between strands. A `depends-on` edge from A to B means A is blocked while B remains active.

The `strand` CLI is a thin JSON control surface. Its familiar commands come from the Batteries spool that `mill init` approves and activates. Discover the live command surface with:

```sh
strand help
strand help add
```

## Add and inspect work

Create two strands:

```sh
strand add "Review docs" --attr owner=ct --attr area=docs
strand add "Scratch idea" --attr temporary=true
```

The commands return JSON. CLI attributes are `key=value` strings, so `temporary=true` stores the string `"true"`, not a JSON boolean. Trusted Clojure code can write richer values.

List all strands and the active, unblocked strands:

```sh
strand list
strand ready
```

Create a dependency while capturing the blocker's generated id:

```sh
design="$(strand add "Sketch the model" | jq -r '.id')"
strand add "Build the weaver" --edge depends-on:"$design"
strand ready
```

“Build the weaver” is absent from `ready` because its dependency is active. Close the blocker and ask again:

```sh
strand update "$design" --state closed --attr outcome=done
strand ready
```

`update --attr` merges named attributes into the existing map. Closing preserves the strand and makes its dependents eligible for readiness. Physical deletion is a separate operation:

```sh
strand burn <strand-id>
```

Burn only when you intend deletion. It records a forensic tombstone, but it is not an undo command.

Millstrand ships five declared acyclic relations: `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes`. Each rejects cycles in its own relation. Other relation names are allowed as annotations and may form non-self cycles; the whole graph is not necessarily a DAG.

## Everyday work

You now have the basic loop:

1. `strand add` creates work.
2. Edges express dependencies and other relations.
3. `strand ready` finds active work with no active direct blocker.
4. `strand update <id> --state closed` finishes work without deleting it.
5. `strand note <id> "..."` records context for whoever resumes it.

That is enough for ordinary tracking. Run `strand help`, `strand query list`, and `strand pattern list` to discover what the selected workspace currently publishes. The [Batteries reference](../spools/batteries.md) explains the shipped everyday surface.

Continue if you want to inspect and extend the running weaver.

## Attach to the live weaver

Open its REPL:

```sh
mill weaver repl
```

A REPL evaluates Clojure forms inside the running weaver. Calls put the function first inside parentheses; keywords begin with `:`; braces create maps; and `def` gives a value a name. The [Clojure crash course](./clojure-crash-course.md) covers the small amount of syntax used here.

Require the public APIs and capture the current runtime:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(def rt (current/runtime))
(def repl-id
  (:id (weaver/add! rt {:title "Created from the REPL"
                        :attributes {:owner "me" :score 3}})))

(weaver/show rt repl-id)
(weaver/update! rt repl-id {:state "closed"})
```

Unlike CLI `--attr`, this Clojure map stores `:score` as a number. The `!` suffix convention marks functions that change something; it is not special syntax.

## Try a named query live

The REPL starts in the `user` namespace with `millstrand.repl` already aliased as `repl`. Register a runtime-local query:

```clojure
(repl/register-query! 'owned-by-ct [:= [:attr :owner] "ct"])
```

Use it from another terminal:

```sh
strand query explain owned-by-ct
strand list --query owned-by-ct
```

Return to the REPL and remove the experiment:

```clojure
(repl/unregister-query! 'owned-by-ct)
```

Direct registration is useful for experiments. It belongs to this weaver generation and is not the durable authoring path. Next you will put the query and an op in a workspace module that refresh and restart can reconstruct.

## Add a durable workspace module

Exit the REPL with Ctrl-D. From the repository root, create `.millstrand/me/tutorial.clj`:

```clojure
(ns me.tutorial
  "Small declarations owned by the tutorial workspace."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])

(millstrand/defquery unowned
  "Return strands with no owner attribute."
  {}
  [:missing [:attr :owner]])

(millstrand/use-query! unowned)

(def hello-arg-spec
  {:op "hello"
   :doc "Greet one person."
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :name
                  :type :string
                  :required? true
                  :doc "Name to greet."}]})

(millstrand/defop! hello
  "Return a greeting as JSON."
  {:arg-spec hello-arg-spec}
  [ctx]
  {:greeting (str "Hello, " (get-in ctx [:op/args :name]) "!")})
```

The three authoring forms differ at the point of selection:

- `defquery` defines an inert Clojure Var carrying a declaration. It publishes nothing by itself, which makes it suitable for a reusable catalogue.
- `use-query!` selects an existing declaration Var for the module currently being collected. Here it publishes the inert `unowned` declaration.
- `defquery!` combines those steps: it defines `mine` and selects it in the same form.

The same pattern applies across authoring families: `defop`/`use-op!`/`defop!`, `defpattern`/`use-pattern!`/`defpattern!`, and so on. The `!` in an authoring form means “select during module collection,” not “register whenever this form is evaluated.” Evaluating `defquery!` casually at the REPL still defines its Var, but outside module collection it publishes nothing.

`defop!` above defines and selects the `hello` op. Every `strand` command is an op; its argument specification supplies parsing and generated help, while its handler returns the JSON value the CLI prints.

Activate the file by adding this declaration to the end of `.millstrand/init.clj`:

```clojure
(runtime/module! runtime :me/tutorial
  {:file "me/tutorial.clj"
   :after [:millstrand/spools-batteries]})
```

The generated `init.clj` already defines `runtime` and activates Batteries, so do not add a second `def runtime`. Module order matters here only because the tutorial op is intended to join the Batteries command surface.

## Refresh and use the module

Attach to the REPL again:

```sh
mill weaver repl
```

Refresh startup config and module source without restarting the weaver:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/refresh! (current/runtime))
```

A successful result has top-level status `:applied` or `:unchanged`. Code may still have been reloaded when publication is unchanged, so judge the result by its status rather than expecting one fixed module-outcome shape. If the source has a syntax or declaration error, refresh fails loudly with file and module context; fix the file and run the same form again.

From another terminal, inspect and use what the module published:

```sh
strand query list
strand list --query mine
strand list --query unowned
strand help hello
strand hello Ada
```

The final command returns JSON containing `{"greeting":"Hello, Ada!"}`.

Edit the greeting in `.millstrand/me/tutorial.clj`, run `runtime/refresh!` again, and call `strand hello Ada` once more. This is the normal customization loop: edit trusted source, refresh the running weaver, and exercise the same CLI surface without throwing away the graph.

The declarations are owner-complete. On each successful collection, this module's selected declarations replace its previous set. If you remove `(millstrand/use-query! unowned)` and refresh, the module stops publishing `unowned`; no matching unregister call is needed. The inert Var can remain in the source for another module to select later.

## Stop when finished

Exit the REPL, then stop this workspace's weaver:

```sh
mill weaver stop
```

Stopping a weaver ends its process-local runtime state. The graph remains in SQLite, and durable modules are reconstructed when the next weaver starts. Do not restart a shared weaver casually: other users or agent runs may depend on that generation.

## Where to go next

- [Millstrand user reference](./reference.md) — the index to Millstrand's guides, generated API documentation, and specifications. Use the specifications for exact behavior.
- [Shipped reference spools](../spools/README.md) — a workflow engine, a feature
  lifecycle, a kanban board, and more, as working code.
- [Customising your workspace](./spools/customisation.md) — the full ladder from
  `init.clj` to your own local spool.
- [Writing shared spools](./spools/writing-shared-spools.md) — building extensions
  other people can run.
- [Testing your config and spools](./spools/testing.md) — from disposable worlds
  to weaver-world integration tests against a chosen Millstrand checkout.
- [Clojure crash course](./clojure-crash-course.md) — enough Clojure to be
  comfortable in the REPL.
- [Tenets](../devflow/TENETS.md) and [philosophy](../devflow/PHILOSOPHY.md) —
  why Millstrand is shaped the way it is.
