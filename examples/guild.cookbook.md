# Guild example — Cookbook

Composition recipes for `skein.examples.guild`: how to publish a stable operation API from one weaver, discover it from another, and evolve it without breaking callers — and *why* each shape is the right one.

This is the **how/why** half of the guild docs. The other two are:

- [`guild/README.md`](./guild/README.md) — the **contract**: the declaration surface
  (`register-op!`, `deprecate!`, module activation, `guild list`), the naming and
  versioning conventions, and the worked two-repo example. Read it for what the
  spool promises.
- [`guild.api.md`](./guild.api.md) — the **generated reference**: every public
  fn's signature and docstring, produced from source.

Division of truth: signatures and the argument tables live in the contract and generated API doc; narrative and composition live here. This cookbook never restates a signature — it links to them.

Guild adds no new protocol, server, or permission system: guild ops are ordinary weaver registry entries with a naming convention and a `guild list` introspection op. The agreement surface is userland — a repo publishes its API by registering ops from its checked-in `.millstrand/init.clj`, which is effectively a public API file. Recipes assume `(require '[skein.examples.guild :as guild])` in trusted config or a live weaver REPL, and a `runtime` in scope — every Guild fn takes it first.

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a runnable declaration or call.
4. **Why this shape** — the reasoning, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the spool's own contract, its test suite, or the blessed peering helpers.

---

## Recipe: Publish a versioned public op for a sibling weaver

**Situation.** Another local weaver needs to ask yours a stable question — "is this gate satisfied?", "accept this release" — and you want it calling a named, intentional entry point, not reaching into your repo-private REPL helpers. You also want bad input rejected before your handler ever runs.

**Composition.** Activate the guild module once to seat the `guild` op and reset declaration state, then `register-op!` each public op under a dotted, version-suffixed handle. An `:input-spec` validates the parsed JSON input, and the handler receives the ordinary op context plus that input at `:guild/input` — so the public contract lives at the fn boundary while the implementation stays private.

```clojure
(ns user
  (:require [clojure.spec.alpha :as s]
            [skein.examples.guild :as guild]))

(s/def ::gate-name string?)
(s/def ::gate-status-input (s/keys :req-un [::gate-name]))

(defn gate-status [{:guild/keys [input]}]
  ;; repo-private implementation; the public contract is this fn's shape
  {:gate (:gate-name input) :satisfied false})

;; Seat the guild op.
(runtime/module! runtime :skein/examples-guild
  {:ns 'skein.examples.guild
   :spools ['skein.examples/guild]})
(guild/register-op! runtime 'gate.status.v1
  {:doc "Return whether a backend gate is satisfied."
   :hook-class :read
   :deadline-class :standard
   :input-spec ::gate-status-input}
  'user/gate-status)
```

A caller invokes it over the ordinary op socket, passing one JSON argument; input that fails the spec is rejected loudly with structured data before the handler runs:

```clojure
;; valid → handler runs with parsed :guild/input
(weaver/op! rt 'gate.status.v1 [(json/write-str {:gate-name "api-ready"})])
;; => {:gate "api-ready" :satisfied false}

;; invalid → ex-info {:code :operation/input-invalid :operation "gate.status.v1" :spec … :contract … :template … :explain …}, no handler call
```

**Why this shape.**

- **A named version is a promise; a REPL helper is not.** `gate.status.v1` is a
  handle a peer can depend on across your refactors. The handler symbol behind it
  can move freely — callers bind to the registry name, not your fn.
- **The spec is the input gate, and it fails loudly.** Declaring `:input-spec`
  means malformed input throws `{:code :operation/input-invalid …}` *before* the
  handler, so a coordination op never runs on half-parsed data. No spec means the
  op simply accepts zero or one JSON argument.
- **`init.clj` is the published API file.** Because the declarations live in
  checked-in trusted config, a peering repo can read exactly what you expose.
  Treat guild declarations like public contract code, not incidental setup.
- **Reconcile is reload-safe.** Every module refresh clears prior declarations
  in that runtime and re-seats the `guild` op, so a trusted-config reload
  re-declares your API cleanly rather than stacking stale ops.

Honest source: the worked two-repo example in [`guild/README.md`](./guild/README.md), and `register-op-registers-and-invokes-through-op-registry` / `input-spec-invalid-input-fails-loudly-with-structured-data` in [`test/clojure/millstrand/guild_test.clj`](../test/clojure/millstrand/guild_test.clj).

---

## Recipe: Discover before you call

**Situation.** Your weaver wants to call a peer's guild op, but you shouldn't hard-code the assumption that a given version still exists. You want to resolve the peer by its portable name, read what it currently offers, and only then invoke.

**Composition.** The blessed `millstrand.api.peers.alpha` helpers resolve same-machine peers from mill metadata; `guild list` is the discovery call. Ask the peer what's `:active` and `:deprecated`, then invoke the version you confirmed with `call!`, handing the JSON input through `:argv`.

```clojure
(require '[clojure.data.json :as json]
         '[millstrand.api.peers.alpha :as peers])

;; discover: what does this weaver actually expose right now?
(peers/call! "backend" "guild" {:argv ["list"]})
;; => {"guild" "backend"
;;     "operation" "guild list"
;;     "active" [{"name" "gate.status.v1" "doc" … "input-spec" …}]
;;     "deprecated" [{"name" "gate.old.v1" "replacement" "gate.status.v1" …}]}

;; then invoke the version you confirmed is active
(peers/call! "backend" "gate.status.v1"
  {:argv [(json/write-str {:gate-name "api-ready"})]})
;; => {"gate" "api-ready" "satisfied" false}
```

**Why this shape.**

- **List-then-call survives evolution.** Reading `:active`/`:deprecated`
  before invoking means a caller notices a version has moved to deprecated
  instead of blindly calling a handle that now fails. The discovery step is
  cheap and turns a silent break into a visible migration.
- **Portable names decouple caller from checkout.** `peers/call!` resolves a
  published peer name, so the caller never encodes a
  filesystem path. A machine with two clones disambiguates locally without the
  caller changing.
- **Peering fails loudly, never silently degrades.** The helpers don't
  auto-start peers or add retries; an unavailable peer, an unknown op, or a
  stream-class op all throw. That is the right default for coordination — a
  missing peer should stop you, not be papered over.

Honest source: the peer-side of the worked example in [`guild/README.md`](./guild/README.md) (`peers/call!` and the `guild list` payload) and the `millstrand.api.peers.alpha` helper listing in the [REPL API spec](../devflow/specs/repl-api.md); the `guild list` `:active` / `:deprecated` shape is pinned by `guild-list-reports-active-and-deprecated-ops` in [`test/clojure/millstrand/guild_test.clj`](../test/clojure/millstrand/guild_test.clj). (The two-weaver call is distilled from the contract's worked example and the listing test rather than run live here.)

---

## Recipe: Evolve an op with a loud stub, never a silent noop

**Situation.** `gate.status.v1`'s input or semantics need to change in a way an existing caller can't safely assume. You must not just repoint the old handle, and you must never leave behind a compatibility stub that *pretends* to succeed — for a coordination op, a false success can corrupt a peer's state.

**Composition.** Add the new version alongside the old with a second `register-op!`, let callers migrate while both are registered, then `deprecate!` the old handle. Deprecation replaces it with a stub that always throws structured data — it can explain or redirect, but it can never report success.

```clojure
;; 1. add the new version; keep v1 registered while callers migrate
(guild/register-op! runtime 'gate.status.v2
  {:doc "Return gate status with a reason."
   :hook-class :read
   :deadline-class :standard
   :input-spec ::gate-status-v2-input}
  'user/gate-status-v2)

;; 2. once callers have moved, deprecate v1 with a pointer to its replacement
(guild/deprecate! runtime 'gate.status.v1
  {:replacement "gate.status.v2" :since "<YYYY-MM-DD>"})

;; now guild list reports v2 :active and v1 :deprecated; invoking v1 throws:
;; ex-info {:code :operation/deprecated :operation "gate.status.v1" :replacement "gate.status.v2"}
```

**Why this shape.**

- **Additive versioning keeps callers alive.** Registering `v2` next to `v1`
  means no caller breaks at the moment you ship the change; each migrates on its
  own schedule. You only remove or deprecate `v1` once the callers are gone.
- **A loud stub leaves the workflow fixable.** A deprecated op throws `{:code
  :operation/deprecated …}`, so a caller that missed the migration stalls *visibly* and
  someone can repoint it. A noop that returned a plausible-looking success would
  let a peer commit on a lie — the failure mode the contract explicitly forbids.
- **Deprecation is discoverable, not folklore.** The stub's `:replacement` and
  `:since` also surface through `guild list`, so the migration path is
  readable from the peer itself rather than buried in a changelog.

Honest source: the naming/evolution conventions and the "never install a noop compatibility stub" rule in [`guild/README.md`](./guild/README.md), with the deprecated-op behaviour pinned by `deprecated-op-throws-structured-error-and-never-succeeds` and the listing output by `guild-list-reports-active-and-deprecated-ops` in [`test/clojure/millstrand/guild_test.clj`](../test/clojure/millstrand/guild_test.clj).

---

## See also

- [`guild/README.md`](./guild/README.md) — the contract: declaration surface, naming and
  versioning conventions, and the full worked two-repo example.
- [`guild.api.md`](./guild.api.md) — generated signatures and docstrings.
- Workflow gates are the durable wait points guild ops often inspect or complete.
- [REPL API spec](../devflow/specs/repl-api.md) — the blessed
  `millstrand.api.peers.alpha` peering helpers used to discover and call a sibling.
