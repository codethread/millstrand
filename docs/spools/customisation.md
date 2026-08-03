# Customising your workspace

Skein's core is deliberately small; most of what your workspace *means* lives in trusted Clojure code the
weaver loads for you — named queries, weave patterns, event handlers, ops. This page is the ladder for
that code. You start with a few lines in `init.clj`, promote them to a local spool when the config grows, and
only when a spool leaves your machine do the authoring rules change. Everything up to that last step lives here.

If you have not met the weaver, workspaces, or the strand model yet, read the [tutorial](../tutorial.md) first;
the [reference](../reference.md) covers the full command and runtime surface. Per-function API detail is
deliberately absent from this page: the generated [alpha API reference](../api/README.md) documents every
`skein.api.*.alpha` function, and this page only shows how the pieces compose.

## The files mill init gives you

`mill init` bootstraps missing workspace files without overwriting existing ones. It does not initialize
database storage; weaver startup prepares storage for the selected workspace. The full layout of an ordinary
repo-local `.skein` workspace:

```text
.skein/
  config.json        -> shared alpha workspace config (the low-privilege format marker)
  config.local.json  -> personal config overlay
  init.clj           -> shared trusted startup code loaded by the weaver
  init.local.clj     -> personal startup overlay loaded after init.clj
  spools.edn         -> shared approved spool families and roots
  spools.local.edn   -> personal approved-spool overlay
  spools/            -> optional local spools
```

When absent, `mill init` creates the shared half: `config.json` with the alpha format marker, `spools.edn` with the seeded batteries source-root coordinate shown below, `init.clj` with its guarded module, and the `spools/` directory. It also adds a `.gitignore` ignoring the personal overlays (`config.local.json`, `init.local.clj`, `spools.local.edn`). The overlays are yours to add when you want them: each shared file has a gitignored personal counterpart, so shared config is committed and reviewed while personal config stays on your machine. Explicit `--workspace` bootstrap works the same way on the selected directory, preserving whatever already exists.

## A private repo-local workspace

Run `mill init --stealth` when you want Skein in a repository without committing its config. The
workspace remains a physical `.skein` directory at the Git root, so agents, `rg`, Make, Clojure,
and weaver calls see normal repo-local paths. Mill adds its paths to `.git/info/exclude`, which is
private to that clone, and reports each file it created, updated, skipped, or left unchanged.

Stealth init does not write shared `AGENTS.md` or `CLAUDE.md`. It creates or updates an untracked
`CLAUDE.local.md` when safe and prints the instruction Codex users may add to their own guidance.
If `.skein` is already tracked or a mill-owned marker block was edited, it refuses before changing
anything.

Keep the generated startup small. Put personal activation in `init.local.clj`, approvals in
`spools.local.edn`, and substantive code in a local spool. If that code needs history, keep the
spool in its own Git repository and approve its external root from `spools.local.edn`; `.skein`
then remains only the repo-local entry point.

## Startup files

The weaver loads startup files in order: `init.clj`, then `init.local.clj`. Missing files are skipped; present
failing files fail loudly with file context. The generated `init.clj` is intentionally small:

```clojure
;; spools.edn
{:spools
 {skein.spools/batteries {:skein/source-root "spools/batteries"}}}
```

```clojure
;; init.clj
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]})
```

The declaration names only a source target and world policy; Batteries publishes through authoring forms in its source. The source-root coordinate is relative to the mill-selected Skein checkout, so bootstrap persists no absolute checkout path. Delete the seeded entry to opt out of batteries; the guarded module then publishes no batteries ops.

`skein.api.runtime.alpha` is a privileged built-in runtime loader/config helper namespace shipped with Skein —
not an ordinary user spool, which is why loader/config helpers do not live under `skein.spools.*`.

Startup files matter because runtime registries are weaver-lifetime state: named queries, weave patterns,
and event handlers registered from a live REPL vanish with the process. Anything you want after every
restart belongs in startup-loaded code. A first customisation is often a single named query, registered
directly by appending two lines to the generated `init.clj`:

```clojure
(require '[skein.api.graph.alpha :as graph])

(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

Now `strand list --query mine` works from the CLI, in this weaver generation and every one after. Simple
workspaces can keep going exactly like this: shared registrations directly in `init.clj`, personal ones in
gitignored `init.local.clj`. When the file starts accumulating real behavior rather than a handful of
registrations, keep `init.clj` minimal and move the behavior into a local spool — that promotion is the
[second half of this page](#promoting-config-to-a-local-spool).

## Trying config changes in a disposable world

Config runs with weaver authority, and startup failures fail loudly — so try changes somewhere disposable
before they reach the workspace you rely on. A throwaway world costs one `mktemp`:

```sh
ws="$(mktemp -d)"
mill init --workspace "${ws:?}"
# copy or write your candidate init.clj / spools.edn into "$ws", then:
mill weaver start --workspace "${ws:?}"
mill weaver stop --workspace "${ws:?}"
```

The `${ws:?}` guard makes an empty variable fail the command instead of silently resolving to your real
workspace. If the candidate config is wrong, startup tells you with file context, and you throw the directory
away.

Once a customisation is worth keeping, it is worth automated coverage: [`skein.test.alpha`](./testing.md)
weaver worlds take `:init`, `:spools-edn`, and `:files` fixtures, so a test exercises exactly the artifacts
this page has you writing.

## Reloading a live weaver

Use refresh during development instead of restarting the weaver:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])
(runtime/refresh! (current/runtime))
```

Refresh re-reads `init.clj` and `init.local.clj`, synchronizes approved roots,
reloads changed module source, atomically replaces owner partitions, and reconciles
resources. It preserves unrelated modules, queued events, recent failures, and
spool state. Missing startup files are skipped; present failures fail loudly.

Direct registrations are the one thing refresh can drop. A direct write is not serialized against an in-flight refresh: publication resets each registry to the candidate snapshot taken after source loading, so a direct write landing in the narrow span between that snapshot and publication is overwritten with no error. The window is small and only staged publication sits inside it, but if a registration you made at the REPL disappears while someone else was refreshing, this is why (SPEC-003.C23, constraint F20).

Recovering it takes one look first. The refresh that dropped your entry may have published a module that now owns the same name, and `register-*!` refuses a name another owner supplies, so re-registering blind either restores your entry or fails loudly depending on something you have not checked. Read the current owner first. If the module's version is the one you want, leave it. If you want yours back on top of it, `replace-*!` is the verb that records the override intent, and it is the only one that works across owners. Anything that must survive a refresh belongs in module source rather than a direct write.

For code-only investigation, `reload-code!` takes a root-lib symbol from the
family's effective `:roots` map and reloads that root's namespaces in dependency
order:

```clojure
(runtime/reload-code! (current/runtime) 'skein.spools/kanban)
```

The result names the root lib, canonical root, and namespaces in reload order, and conforms to
`:skein.api.runtime.alpha/reload-code-result`. It deliberately performs no
publication or resource reconciliation; use a targeted refresh for the normal
path.

Some changes cannot load into a running weaver at all: removing an already-loaded root, repointing one at
different source, or bumping a loaded Maven coordinate's version. Refresh refuses those changes and records a
pending generation that takes effect at the next weaver restart. That restart is not free: replacing a weaver
ends every agent run it is supervising, and restarting the canonical weaver requires explicit user sign-off.
Treat a pending generation as a deliberate step, not a reflex; see the [reference](../reference.md) on weaver
generations for the classification and the cutover semantics.

## REPL hygiene in a shared weaver

Much of this iteration happens from `mill weaver repl`. The REPL (and `mill weaver repl --stdin`) evaluates inside the live weaver JVM, in the shared `user` namespace. Exploratory requires and scratch defs mutate that namespace for every other session attached to the same weaver, so use names that are easy to identify and clean up: prefer `:as` aliases over `:refer`, prefix aliases and scratch vars with an owner or session prefix (`ct-`, `agent-abc-`, a task slug), and avoid unprefixed scratch vars like `result`, `x`, or `data`. Clean aliases with `ns-unalias` and scratch vars with `ns-unmap` when done:

```clojure
(require '[clojure.pprint :as ct-pprint])
(def ct-config-publics (keys (ns-publics 'config)))

(ct-pprint/pprint ct-config-publics)

(ns-unalias *ns* 'ct-pprint)
(ns-unmap *ns* 'ct-config-publics)
```

For stronger isolation, create an agent-local namespace and call Skein helpers through an alias:

```clojure
(create-ns 'agent.ct)
(in-ns 'agent.ct)
(clojure.core/refer 'clojure.core)
(require '[skein.api.current.alpha :as current]
         '[skein.api.weaver.alpha :as weaver]
         '[clojure.pprint :as ct-pprint])

(ct-pprint/pprint (weaver/ready (current/runtime)))

(remove-ns 'agent.ct)
```

## Promoting config to a local spool

When `init.clj` outgrows a handful of registrations, move the behavior into a local spool and leave activation
behind. Skein treats runtime extensions as trusted Clojure code, and the
[reference spools](../../spools/README.md) — including the workflow engine and the external,
git-distributed devflow lifecycle — double as worked examples of spool design. All of them load the
same opt-in way yours will. A common layout:

```text
workspace/
  config.json
  init.clj
  spools.edn
  spools/
    my-workflow/
      deps.edn
      src/my/workflow.clj
```

Approve the local spool as an implicit one-root family in `spools.edn`:

```clojure
{:spools {my/workflow {:local/root "spools/my-workflow"}}}
```

A shared local entry has one root at `.` under the entry's symbol. Git families can map
several roots with `:roots`; local overlays inherit that map from their shared Git family. Relative
`:local/root` values resolve against the selected workspace. Absolute paths are accepted as
explicit user-approved paths, and `~` expands to your home directory. Create a minimal `deps.edn`
in the root (if `:paths` is omitted, Skein's namespace loading defaults to `["src"]`):

```clojure
{:paths ["src"]}
```

Then implement the spool with an authoring form. Every source evaluation collects the module's complete owner partition:

```clojure
(ns my.workflow
  (:require [skein.api.skein.alpha :as skein]))

(skein/defquery mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])
```

`init.clj` names only a source target and world policy:

```clojure
(runtime/module! runtime :my/workflow
  {:ns 'my.workflow
   :spools ['my/workflow]})
```

Each piece has one job. `spools.edn` approves source. `runtime/module!` declares the desired module, and the refresh coordinator acquires its roots, collects its declarations, replaces that owner's entries, and reconciles lifecycle effects. A direct `require` from `mill weaver repl` evaluates in the weaver JVM and is useful for trusted experimentation, but for repeatable module activation and status, go through `runtime/module!` or `runtime/refresh!` from startup config or the live REPL.

Extension code runs with weaver authority, so only load trusted code. And there is no per-module isolation or
unload guarantee: restart the weaver when you need a clean runtime.

## Your own CLI command

Every `strand` command is a registered op, and ops register the same way queries do, so a local
spool can add commands to the CLI without recompiling anything. `strand help` lists registered ops;
`strand help <op>` explains one. Register your own from trusted Clojure with
`skein.api.weaver.alpha/register-op!` — the CLI forwards everything after the op name to your
handler as string argv:

```clojure
(ns my.workflow)

(defn echo-op [{:op/keys [name argv]}]
  {:operation name :argv argv})
```

Register it from `init.clj` or the live REPL:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.weaver.alpha :as weaver])

(weaver/register-op! (current/runtime) 'echo "Echo raw argv" 'my.workflow/echo-op)
```

```sh
strand echo --flag value
```

Op handlers return data; the CLI prints it as JSON. Like every registration on this page, ops are
weaver-lifetime state — keep them in startup-loaded code, and reload with the verbs above while
iterating. The shipped [kanban board spool](../../spools/kanban.md) is a complete example of this
pattern: a whole board surface built from ops, queries, and attributes, worth reading once your own
command grows past a helper.

Name an op by what it exposes. When your command fronts another spool's surface, keep that spool's
verbs, nouns, and attribute keys — the op is your entry point to the primitive, not a new language
over it ([the vocabulary
rule](./writing-shared-spools.md#the-rules-for-shared-spools)). Kanban earns its own vocabulary
(cards, lanes, claim) because a board is a concept the engine has no word for; a command that
starts, advances, or lists an existing primitive speaks that primitive's terms.

## Terse daily driving

Explicit-runtime code threads a `runtime` argument through every call. That is the right discipline for durable config and can be tedious at the REPL. If your workspace needs shorter calls, put the helper in your own namespace and make the trade explicit:

```clojure
(ns my.helpers
  (:require [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]))

(def ^:dynamic *runtime* nil)

(defn runtime []
  (or *runtime* (current/runtime)))

(defmacro with-runtime [runtime & body]
  `(let [runtime# ~runtime]
     (when (nil? runtime#)
       (throw (ex-info "Cannot scope a nil Skein runtime" {:runtime :nil})))
     (binding [*runtime* runtime#]
       (current/with-runtime runtime# ~@body))))

(defn strand! [title attributes]
  (weaver/add! (runtime) {:title title :attributes attributes}))

(defn strand [id]
  (weaver/show (runtime) id))

(defn strands []
  (weaver/list (runtime)))

(defn update! [id patch]
  (weaver/update! (runtime) id patch))

(defn ready []
  (weaver/ready (runtime)))

(defn burn! [ids]
  (graph/burn-by-ids! (runtime) ids))
```

Resolution is local first: `with-runtime` provides a dynamic value, and `current/runtime` reads the active or published ambient runtime. The helper owns only its strand CRUD vocabulary. On a shared weaver, call the helpers inside `with-runtime`, so each entry point names its target explicitly. The ambient fallback is a convenience for a weaver-owned session where that ambient runtime is authoritative. Keep this pattern in workspace-owned code; shared spools should keep taking an explicit runtime.

## When a spool leaves your workspace

Everything above assumes the code is yours alone, running in your weaver, free to resolve the ambient runtime
and stay informally structured. Even here, runtime-owned state is the easier default — it survives reloads
that a module-level atom loses — so the liberties are ambient resolution and loose structure, not unmanaged
state. The moment other people run your spool, those liberties become bugs: a shared
spool must work in any weaver runtime, including unpublished runtimes that coexist with others in a single
JVM, so it takes the runtime explicitly as the first argument of every public function, keeps its state
runtime-owned, registers behavior by symbol rather than closure, and never touches the ergonomics layer. Those
rules, the helper namespaces that support them, and the git publishing and pinning story live in
[writing shared spools](./writing-shared-spools.md) — the one step of the ladder this page does not cover.
