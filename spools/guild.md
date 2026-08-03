# Skein Guild Spool

> This is the **contract** doc: the declaration surface, naming and versioning
> conventions, and the worked two-repo example. Its two companions are
> [`guild.cookbook.md`](./guild.cookbook.md) — worked composition recipes
> (how/why you publish, discover, and evolve a peer op API) — and
> [`guild.api.md`](./guild.api.md) — generated fn signatures and docstrings.
> Reach for the cookbook when you want a runnable pattern, the API doc when you
> want an exact arity, and this doc for what the spool promises.

## Overview

`skein.spools.guild` is a small reference spool for publishing a weaver's trusted operation API to sibling weavers. It does not add a new protocol, server operation, package manager, or permission system. Guild ops are ordinary weaver `op` registry entries with a documented naming/versioning convention and a built-in `guild list` operation for discovery.

Use it when a repo wants other local weavers to call stable, intentional entry points such as `gate.status.v1` or `release.request.v1` instead of reaching into repo-private REPL helpers. The agreement surface is userland: a repo opts in by registering ops from trusted config, usually its checked-in `.skein/init.clj`. For a peering repo, that checked-in `init.clj` is effectively a published API file. Treat its guild declarations like public contract code.

## Loading

Approve the Guild root in `.skein/spools.edn`:

```clojure
{:spools {skein.spools/guild {:local/root "../spools/guild"}}}
```

Then declare it from trusted config:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :skein/spools-guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]})
```

Every Guild fn takes the runtime as its first argument and never reads the
published singleton, so Guild works in unpublished and side-by-side runtimes.
The module publishes the built-in operation and reconciles its runtime-local
declaration state.

At invocation time, `guild list` prefers the runtime metadata name published by the running weaver.

## Operation declaration surface

### `register-op!`

```clojure
(guild/register-op! runtime 'gate.status.v1
  {:doc "Return whether the named gate is satisfied."
   :input-spec ::gate-status-input
   :returns {:type :map
             :required {:gate :string
                        :satisfied :boolean}}}
  'my.repo.guild/gate-status)
```

`register-op!` registers a public guild op in the existing weaver op registry.

| Argument | Meaning |
|---|---|
| `runtime` | The weaver runtime to register into. |
| `name` | Simple unqualified symbol or keyword. By convention use a dotted, version-suffixed handle such as `gate.status.v1`. |
| `opts` | Map satisfying the owning `::register-op-opts` spec: required `:hook-class` (`:read` or `:mutating`) and `:deadline-class` (`:standard` or `:unbounded`), plus optional `:doc`, `:input-spec`, and output `:returns`. Unknown keys fail loudly. |
| `fn-sym` | Fully qualified symbol resolving to the handler function in the weaver JVM. |

Guild op invocation accepts zero arguments or one JSON input argument. The spool parses that JSON to `:guild/input`, validates it against `:input-spec` when supplied, and then calls the resolved handler with the ordinary op context plus `:guild/input`.

A declared `:input-spec` is discoverable before invocation: it rides the generic arg `:spec` convention (SPEC-003.C70), so `strand help <op>` projects the registered spec's nested contract and a copyable JSON template on the `input` positional. Invalid input fails with the same projection fields (`spec`, `spec-forms`, `contract`, `template`) plus `explain` text, so a worker gets the contract and the violation in one payload.

`:returns` uses the shared registry return declaration from
[`skein.api.return-shape.alpha`](../docs/api/return-shape.api.md). It describes
JSON scalar, closed-map, and homogeneous-collection output; routed and streaming
wrappers are also shared with ordinary registered ops. The canonical language
contract is [SPEC-003.C60a/C60b](../devflow/specs/repl-api.md). Run
`strand help <op>` to inspect its JSON-safe explanation.

### `deprecate!`

```clojure
(guild/deprecate! runtime 'gate.status.v1
  {:replacement "gate.status.v2"
   :since "2026-07-02"})
```

`deprecate!` replaces a registered guild op with a stub that always fails loudly. A deprecated stub may explain, redirect, or refuse — it must never pretend to succeed. The thrown data includes `{:code :operation/deprecated}` plus the op name and replacement guidance.

### Activation

```clojure
(runtime/module! runtime :skein/spools-guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]})
(guild/set-fallback-guild-name! runtime "frontend")
```

Guild activates through the module lifecycle. The declaration names only the source target; Guild's authoring forms publish the `guild` op and its lifecycle resource resets previous guild declarations in that runtime. The guild name is read from runtime metadata when available; `set-fallback-guild-name!` records a fallback for contexts without it. Call it after activation.

### `guild list`

Peers call `guild list` through the ordinary `op` socket operation. It returns JSON-safe metadata:

```clojure
{:guild "backend"
 :operation "guild list"
 :active [{:name "gate.status.v1" :doc "Return whether the named gate is satisfied." :input-spec "backend/gate-status-input"}]
 :deprecated [{:name "gate.old.v1" :replacement "gate.status.v1" :since "2026-07-02"}]}
```

## Naming and evolution conventions

- Registry names are simple unqualified handles; do not use namespace-qualified
  symbols or keywords.
- Use dotted names with explicit version suffixes: `domain.action.v1`,
  `gate.close.v1`, `gate.close.v2`.
- Evolve additively. Add a new version when inputs or semantics change in a way
  an existing caller cannot safely assume.
- Keep old versions registered while callers migrate, or deprecate them with a
  loud structured stub via `deprecate!`.
- Never install a noop compatibility stub for a coordination operation. A false
  success can corrupt manager state; a loud failure leaves the workflow visibly
  stalled and fixable.

## Worked two-repo example

Assume two repos, `frontend` and `backend`, each with a checked-in portable weaver name in `.skein/config.json`:

```json
{
  "configFormat": "alpha",
  "name": "backend"
}
```

A machine with two clones can disambiguate locally with `.skein/config.local.json` when needed; the local overlay is not committed.

With the Guild root approved in the backend repo's `.skein/spools.edn` and activated as shown in [Loading](#loading) (`runtime/refresh!` + `runtime/module!`), the backend's checked-in `.skein/init.clj` publishes a guild API:

```clojure
(ns user
  (:require [clojure.spec.alpha :as s]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]))

(def runtime (current/runtime))

(runtime/module! runtime :skein/spools-guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]})

;; Required here for the declarations below.
(require '[skein.spools.guild :as guild])

(s/def ::gate-name string?)
(s/def ::gate-status-input (s/keys :req-un [::gate-name]))

(defn gate-status [{:guild/keys [input]}]
  ;; Repo-private implementation. Keep the public contract at this fn boundary.
  {:gate (:gate-name input)
   :satisfied false})

(guild/register-op! runtime 'gate.status.v1
  {:doc "Return whether a backend gate is satisfied."
   :input-spec ::gate-status-input
   :returns {:type :map
             :required {:gate :string
                        :satisfied :boolean}}}
  'user/gate-status)
```

From the frontend weaver (or a manager weaver), discover the backend by its portable name with the blessed alpha peering helpers and call the guild op over the existing JSON socket protocol:

```clojure
(require '[clojure.data.json :as json]
         '[skein.api.peers.alpha :as peers])

(peers/call! "backend" "guild" {:argv ["list"]})
;; => {"guild" "backend", "operation" "guild list", "active" [...], "deprecated" [...]}

(peers/call! "backend" "gate.status.v1"
  {:argv [(json/write-str {:gate-name "api-ready"})]})
;; => {"gate" "api-ready", "satisfied" false}
```

`skein.api.peers.alpha` is explicit-require userland API: `(peers/peers)` enumerates running sibling weavers from mill metadata, and `(peers/call! peerish op args)` invokes one named op on a metadata row, peer name, or workspace path over the invoke envelope (optional `:argv`/`:payloads` in `args`); unknown ops and the peer's payload hooks reject receiving-side, and stream-class ops fail loudly as unsupported. It does not auto-start peers or add retries; unavailable peers fail loudly.

## See also

- [`guild.cookbook.md`](./guild.cookbook.md) — worked composition recipes for this spool.
- [`spools/README.md`](./README.md) — shipped spools index and loading notes.
- [`skein.spools.workflow`](./workflow.md) — workflow gates are the durable wait points guild ops often inspect or complete.
- [`ct.spools.executors.subagent`][subagent-contract] — external gate adapter shape that
  guild-backed adapters can mirror.
- [Weaver Runtime spec](../devflow/specs/daemon-runtime.md) — local weaver peering contract (SPEC-004.P10c).
- [REPL API spec](../devflow/specs/repl-api.md) — blessed `skein.api.peers.alpha` helper listing.

[subagent-contract]: https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/agent-run/subagent.md
