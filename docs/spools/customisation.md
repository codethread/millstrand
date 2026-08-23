# Customising your workspace

Millstrand's core is deliberately small; most of what your workspace *means* lives in trusted Clojure code the weaver loads for you — named queries, weave patterns, event handlers, and ops. This page shows the path from a durable module source to live experiments and workspace-owned convenience helpers. Authoring forms in module source come first: they are the durable, owner-complete declarations. Explicit-runtime verbs are the code and test surface for live state, and `millstrand.repl` supplies the same verbs with the runtime implied for an interactive session. When a spool leaves your workspace, its code must keep the runtime explicit; the terse helper layer remains workspace-owned.

If you have not met the weaver, workspaces, or the strand model yet, read the [tutorial](../tutorial.md) first. The [reference](../reference.md) maps Millstrand's specifications, guides, and generated API documentation. Per-function API detail is deliberately absent from this page: the generated [alpha API reference](../api/README.md) documents every `millstrand.api.*.alpha` function, and this page only shows how the pieces compose.

## The files mill init gives you

`mill init` bootstraps missing workspace files without overwriting existing ones. It does not initialize database storage; weaver startup prepares storage for the selected workspace. The full layout of an ordinary repo-local `.millstrand` workspace (or its `.ms` alias):

```text
.millstrand/
  config.json        -> shared alpha workspace config (the low-privilege format marker)
  config.local.json  -> personal config overlay
  init.clj           -> shared trusted startup code loaded by the weaver
  init.local.clj     -> personal startup overlay loaded after init.clj
  spools.edn         -> shared approved spool families and roots
  spools.local.edn   -> personal approved-spool overlay
  me/help.clj        -> default Batteries help-transform election
  spools/            -> optional local spools, created only when you add one
```

When absent, `mill init` creates the shared half: `config.json` with the alpha format marker, `spools.edn` with the seeded batteries source-root coordinate shown below, `init.clj` with its guarded module, and `me/help.clj` with the default help-transform election. It does not create an empty `spools/` directory. Its `.gitignore` ignores only the personal overlays (`config.local.json`, `init.local.clj`, `spools.local.edn`). The overlays are yours to add when you want them: each shared file has a gitignored personal counterpart, so shared config is committed and reviewed while personal config stays on your machine. Explicit `--workspace` bootstrap works the same way on the selected directory, preserving whatever already exists.

## A private repo-local workspace

Run `mill init --stealth` when you want Millstrand in a repository without committing its config. The workspace remains a physical `.millstrand` or `.ms` directory at the Git root, so agents, `rg`, Make, Clojure, and weaver calls see normal repo-local paths. With no existing marker, stealth creates `.millstrand`; with one accepted marker, it keeps that marker. Mill adds its paths to `.git/info/exclude`, which is private to that clone, and reports each file it created, updated, skipped, or left unchanged.

Stealth init does not write shared `AGENTS.md` or `CLAUDE.md`. It creates or updates an untracked `CLAUDE.local.md` when safe and prints the instruction Codex users may add to their own guidance. If `.millstrand` or `.ms` is already tracked or a mill-owned marker block was edited, it refuses before changing anything.

Keep the generated startup small. Put personal activation in `init.local.clj` and approvals in `spools.local.edn`. For workspace-owned code, add a file and activate it with `runtime/module!` and `:file`; use a local spool only when the code needs its own repository and approval boundary. `.millstrand` then remains the repo-local entry point.

## Startup files

The weaver loads startup files in order: `init.clj`, then `init.local.clj`. Missing files are skipped; present
failing files fail loudly with file context. The generated `init.clj` is intentionally small:

```clojure
;; spools.edn
{:spools
 {millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}}}
```

```clojure
;; init.clj
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; batteries load by default, see
;; https://codethread.github.io/millstrand/spools/batteries/ for details
;; adds common commands like `strand add` `strand list` etc
;; you can omit this `module!` and build entirely your own way, see
;; https://codethread.github.io/millstrand/docs/spools/customisation/
(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]})

(runtime/module! runtime :module-me-help
  {:file "me/help.clj"
   :spools ['millstrand.spools/batteries]
   :after [:millstrand/spools-batteries]})
```

The declarations name only source targets and world policy; Batteries publishes through authoring forms in its source. The generated adapter has the same Batteries root guard and registers Batteries' help transform after the module loads, so `strand help` renders text by default while `strand help --json` keeps the raw envelope. The source-root coordinate is relative to the mill-selected Millstrand checkout, so bootstrap persists no absolute checkout path. Delete the seeded entry to opt out of batteries; the guarded modules then publish no batteries ops.

`millstrand.api.runtime.alpha` is a privileged built-in runtime loader/config helper namespace shipped with Millstrand —
not an ordinary user spool, which is why loader/config helpers do not live under `millstrand.spools.*`.

Startup files matter because runtime registries are weaver-lifetime state: named queries, weave patterns, and event handlers registered from a live REPL vanish with the process. Anything you want after every restart belongs in startup-loaded code. Put durable behavior in a module source and activate that module from `init.clj`:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:ns 'my.workspace})
```

The module source owns the query or other registry entries, so refresh and restart can reconstruct them. For a quick live experiment in code or a test, call the explicit-runtime function instead:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.graph.alpha :as graph])

(graph/register-query! (current/runtime) 'mine [:= [:attr :owner] "ct"])
```

Inside the weaver REPL, `millstrand.repl/register-query!` is the same operation without the runtime argument. Direct entries are useful for experiments and startup code can reapply them after a restart, but a refresh can still overwrite them. Simple workspaces can keep activation in `init.clj` and personal activation in gitignored `init.local.clj`; keep substantive declarations in module source. When the file starts accumulating real behavior, choose a workspace module for repo policy or a local spool for reusable classpath code. The [workspace modules and local spools section](#workspace-modules-and-local-spools) explains the boundary.

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

Once a customisation is worth keeping, it is worth automated coverage: [`millstrand.test.alpha`](./testing.md)
weaver worlds take `:init`, `:spools-edn`, and `:files` fixtures, so a test exercises exactly the artifacts
this page has you writing.

## Reloading a live weaver

Use refresh during development instead of restarting the weaver:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])
(runtime/refresh! (current/runtime))
```

Refresh re-reads `init.clj` and `init.local.clj`, synchronizes approved roots,
reloads changed module source, atomically replaces owner partitions, and reconciles
resources. It preserves unrelated modules, queued events, recent failures, and
spool state. Missing startup files are skipped; present failures fail loudly.

Direct registrations are the one thing refresh can drop. A direct write is not serialized against an in-flight refresh: publication resets each registry to the candidate snapshot taken after source loading, so a direct write landing in the narrow span between that snapshot and publication is overwritten with no error. The window is small and only staged publication sits inside it, but if a registration you made at the REPL disappears while someone else was refreshing, this is why (SPEC-003.C23, constraint F20).

Recovering it takes one look first. The registry is owner-partitioned and layered: a direct entry lives in your partition, while a module or spool owns its own partition. The refresh that dropped your entry may have published a module that now owns the same name, and `register-*!` refuses a name another owner supplies. Read the current owner before acting. If the module's version is the one you want, leave it. If you want yours above it, `replace-*!` records the override intent and carries the shadow across refresh. To end that experiment, `unregister-*!` removes only your entry and restores the shadowed value. Remove-then-register is not a substitute for replace because the other owner's entry still occupies the name. Anything that must survive a refresh belongs in module source rather than a direct write.

For code-only investigation, `reload-code!` takes a root-lib symbol from the
family's effective `:roots` map and reloads that root's namespaces in dependency
order:

```clojure
(runtime/reload-code! (current/runtime) 'millstrand.spools/batteries)
```

The result names the root lib, canonical root, and namespaces in reload order, and conforms to
`:millstrand.api.runtime.alpha/reload-code-result`. It deliberately performs no
publication or resource reconciliation; use a targeted refresh for the normal
path.

Some changes cannot load into a running weaver at all: removing an already-loaded root, repointing one at different source, or bumping a loaded Maven coordinate's version. Refresh refuses those changes and records a pending generation that takes effect at the next weaver restart. Use the Mill-owned transition when you are ready. If other users share this weaver, get their explicit sign-off before running it:

```sh
mill weaver restart --workspace "${workspace:?}"
```

Restart probes the fresh generation before stopping the old one. A failed probe leaves the old generation serving and retains diagnostics. After the cutover begins, the old generation does not return to service. Mill-routed requests that have not been sent wait for the replacement within their existing deadline; an accepted request is sent once and is never replayed. Direct REPL sessions see a disconnect and reconnect themselves.

Restart continuity covers native children only when their owning spool uses `millstrand.api.process.alpha` and reconciles the retained process facts. Ordinary in-JVM callbacks remain interruptible, and domain spools decide how interruption changes their durable state. The [Weaver Runtime specification](../../devflow/specs/daemon-runtime.md) defines the probe, admission, custody, and interruption contracts in SPEC-004.C113–C123.

## REPL hygiene in a shared weaver

Much of this iteration happens from `mill weaver repl`. The REPL (and `mill weaver repl --stdin`) evaluates inside the live weaver JVM, in the shared `user` namespace. Exploratory requires and scratch defs mutate that namespace for every other session attached to the same weaver, so use names that are easy to identify and clean up: prefer `:as` aliases over `:refer`, prefix aliases and scratch vars with an owner or session prefix (`ct-`, `agent-abc-`, a task slug), and avoid unprefixed scratch vars like `result`, `x`, or `data`. Clean aliases with `ns-unalias` and scratch vars with `ns-unmap` when done:

```clojure
(require '[clojure.pprint :as ct-pprint])
(def ct-config-publics (keys (ns-publics 'config)))

(ct-pprint/pprint ct-config-publics)

(ns-unalias *ns* 'ct-pprint)
(ns-unmap *ns* 'ct-config-publics)
```

For stronger isolation, create an agent-local namespace and call Millstrand helpers through an alias:

```clojure
(create-ns 'agent.ct)
(in-ns 'agent.ct)
(clojure.core/refer 'clojure.core)
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver]
         '[clojure.pprint :as ct-pprint])

(ct-pprint/pprint (weaver/ready (current/runtime)))

(remove-ns 'agent.ct)
```

## Workspace modules and local spools

Keep repository-specific policy in workspace `:file` modules. They are loaded from the exact path declared in `init.clj`, so their directories organize the workspace but do not make a classpath. Give every such namespace an owner-qualified root to avoid collisions in the shared weaver JVM:

```text
.millstrand/
  init.clj
  workflows/
    ralph.clj
```

```clojure
;; workflows/ralph.clj
(ns acme.workflows.ralph)
```

```clojure
;; init.clj
(runtime/module! runtime :workflows.ralph
  {:file "workflows/ralph.clj"})
```

The directory and namespace use the same concern name for discovery, but the `:file` declaration selects the source. It does not add `.millstrand` to the classpath. If one workspace module requires another, declare the dependency with `:after` so its namespace has loaded first.

Use a local spool when the code needs a classpath root or is worth reusing independently of this workspace. Millstrand treats runtime extensions as trusted Clojure code, and the
[reference spools](../../spools/README.md) — including the workflow engine and the external,
git-distributed devflow lifecycle — double as worked examples of spool design. All of them load the
same opt-in way yours will. A common layout:

```text
workspace/
  config.json
  init.clj
  spools.edn
  spools/
    acme-workflows/
      deps.edn
      src/
        acme/
          spools/
            workflows/
              mine.clj
```

A spool source root is on the classpath, so its path mirrors its namespace. Approve the local spool as an implicit one-root family in `spools.edn`:

```clojure
{:spools {acme.spools/workflows {:local/root "spools/acme-workflows"}}}
```

A shared local entry has one root at `.` under the entry's symbol. Git families can map
several roots with `:roots`; local overlays inherit that map from their shared Git family. Relative
`:local/root` values resolve against the selected workspace. Absolute paths are accepted as
explicit user-approved paths, and `~` expands to your home directory. Create a minimal `deps.edn`
in the root (if `:paths` is omitted, Millstrand's namespace loading defaults to `["src"]`):

```clojure
{:paths ["src"]}
```

Then implement the spool with an authoring form. Every source evaluation collects the module's complete owner partition:

```clojure
(ns acme.spools.workflows.mine
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery! mine
  "Return strands owned by ct."
  {}
  [:= [:attr :owner] "ct"])
```

Use the bang form when this namespace both defines and selects the query. If definitions live in a reusable catalogue, keep them inert and select only what this module owns:

```clojure
(ns acme.spools.workflows.mine
  (:require [acme.spools.workflows.catalogue :as catalogue]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-query! catalogue/mine catalogue/ready-mine)
```

Removing `catalogue/ready-mine` from that form removes it from this module at the next successful refresh. The catalogue Var remains defined and another module may still select it. Selection options, duplicate behavior, lifecycle differences, and retained-image replay are covered once in [Writing shared spools](./writing-shared-spools.md#author-contributions-with-kind-specific-forms).

`init.clj` names only a source target and world policy:

```clojure
(runtime/module! runtime :acme.workflows/mine
  {:ns 'acme.spools.workflows.mine
   :spools ['acme.spools/workflows]})
```

Each piece has one job. `spools.edn` approves source. `runtime/module!` declares the desired module, and the refresh coordinator acquires its roots, collects its declarations, replaces that owner's entries, and reconciles lifecycle effects. A direct `require` from `mill weaver repl` evaluates in the weaver JVM and is useful for trusted experimentation, but for repeatable module activation and status, go through `runtime/module!` or `runtime/refresh!` from startup config or the live REPL.

Extension code runs with weaver authority, so only load trusted code. And there is no per-module isolation or
unload guarantee: restart the weaver when you need a clean runtime.

## Your own CLI command

Every `strand` command is a registered op, and ops use the same three-layer order as queries. Define and select a durable command with `millstrand/defop!` in module source; use `millstrand.api.weaver.alpha/register-op!` from explicit-runtime code or tests for a live experiment; use `millstrand.repl/register-op!` from the connected REPL. `strand help` lists registered ops and `strand help <op>` explains one. The CLI forwards everything after the op name to the handler as string argv.

The durable form belongs in the module source:

```clojure
(def echo-arg-spec
  {:op "echo"
   :doc "Echo the given text."
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :text :type :string :required? true :doc "Text to echo."}]})

(millstrand/defop! echo
  "Echo raw argv."
  {:arg-spec echo-arg-spec}
  [ctx]
  {:operation "echo" :argv (:op/argv ctx)})
```

Activate that module with `runtime/module!` as in the query example above. The form owns the op's help, parser contract, and handler declaration as one published contribution.

```clojure
(ns my.workflow)

(defn echo-op [{:op/keys [name argv]}]
  {:operation name :argv argv})
```

For a temporary live experiment, register it from the connected REPL:

```clojure
(require '[millstrand.repl :as repl])

(repl/register-op! 'echo "Echo raw argv" 'my.workflow/echo-op)
```

```sh
strand echo --flag value
```

Op handlers return data; the CLI prints it as JSON. The explicit-runtime registration is weaver-lifetime state, so keep a durable command in module source. To mask a spool op durably, put `(millstrand/defop! {:override? true} ...)` in a workspace module; a local-root and a git-pinned spool follow the same registry rules. `replace-op!` is the live, intentional shadow; `unregister-op!` retracts only your shadow and restores the original. The [Kanban spool](https://github.com/codethread/millhouse.spool/tree/main/spools/kanban) is a complete example of this pattern: a board surface built from ops, queries, and attributes.

Name an op by what it exposes. When your command fronts another spool's surface, keep that spool's
verbs, nouns, and attribute keys — the op is your entry point to the primitive, not a new language
over it ([the vocabulary
rule](./writing-shared-spools.md#the-rules-for-shared-spools)). Kanban earns its own vocabulary
(cards, lanes, claim) because a board is a concept the engine has no word for; a command that
starts, advances, or lists an existing primitive speaks that primitive's terms.

## Terse daily driving

Explicit-runtime code threads a `runtime` argument through every call. That is the right discipline for durable config and can be tedious at the REPL. If your workspace needs shorter calls, put the helper in your own namespace and make the trade explicit. This is workspace-owned userland sugar, not a fourth Millstrand registration tier: authoring forms still own durable declarations, and the explicit-runtime and `millstrand.repl` verbs remain the registration surface.

```clojure
(ns my.helpers
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.weaver.alpha :as weaver]))

;; Scoped binding only; keep actual state runtime-owned.
(def ^:dynamic *runtime* nil)

(defn runtime []
  (or *runtime* (current/runtime)))

(defmacro with-runtime [runtime & body]
  `(let [runtime# ~runtime]
     (when (nil? runtime#)
       (throw (ex-info "Cannot scope a nil Millstrand runtime" {:runtime :nil})))
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

Resolution is local first: `with-runtime` provides a dynamic value scoped to its body, and `current/runtime` reads the active or published ambient runtime. That dynamic binding is not mutable module-level state: this helper has no atom or process-global default. The helper owns only its strand CRUD vocabulary. On a shared weaver, call the helpers inside `with-runtime`, so each entry point names its target explicitly. The ambient fallback is a convenience for a weaver-owned session where that ambient runtime is authoritative. Keep this pattern in workspace-owned code; shared spools should keep taking an explicit runtime.

## When a spool leaves your workspace

Everything above assumes the code is yours alone, running in your weaver, free to resolve the ambient runtime and stay informally structured. Even here, keep actual state runtime-owned and treat ambient resolution as the convenience — not unmanaged state. The moment other people run your spool, those liberties become bugs: a shared spool must work in any weaver runtime, including unpublished runtimes that coexist with others in a single JVM, so it takes the runtime explicitly as the first argument of every public function, keeps its state runtime-owned, registers behavior by symbol rather than closure, and never touches the ergonomics layer. Those rules, the helper namespaces that support them, and the git publishing and pinning story live in [writing shared spools](./writing-shared-spools.md) — the one step of the ladder this page does not cover.
