---

# <a name="millstrand.api.runtime.alpha">millstrand.api.runtime.alpha</a>

Explicit-runtime API for trusted weaver runtime loader/config workflows.

Callers own runtime selection and pass the target weaver runtime as the first argument. Use `millstrand.api.current.alpha/runtime` only at trusted in-process entry points that need to capture the active runtime.

The module exposes the live-image lifecycle: declare stable modules (`module!`), collect authoring-form entries and open kinds from module sources (`collect-entry!`, `collect-kind!`), reconcile the running image against them (`refresh!`, with `plan` its effect-free dry-run), inspect the joined offline picture (`status`), reach for the advanced code-only seam (`reload-code!`), and serve runtime-owned state, symbol resolution, and time to trusted spools (`spool-state`, `resolve-var`, `clock`, `now`).

`module!`/`refresh!`/`plan`/`status`/`reload-code!` are the lifecycle surface: declarations are data, refresh replaces owner-complete contributions and reconciles resources without stopping the live image, and `reload-code!` is the sharp code-only tool. Component sub-specs live in `millstrand.api.runtime.internal.shapes`; every registered key stays alpha-qualified.

## <a name="millstrand.api.runtime.alpha/clock">`clock`</a>

```clojure
(clock runtime)
```

Function.

Return `runtime`'s installed `millstrand.api.clock.alpha/Clock`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L416-L419">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/collect-entry!">`collect-entry!`</a>

```clojure
(collect-entry! kind-id entry-key value)
(collect-entry! kind-id entry-key value opts)
```

Function.

Collect one authoring-form registry entry for the module source being evaluated.

`kind-id` conforms to `::contribution-kind` and `opts` to `::collect-entry-opts` (closed to boolean `:override?`); `entry-key` and `value` are deliberately unconstrained here because their shapes belong to the registry kind that owns them. Repeating the same `kind-id`/`entry-key` in one source evaluation replaces the earlier value deterministically; `{:override? true}` records explicit override intent. Outside contribution collection the form is passive, so a code-only source reload defines Vars without publishing declarations. The collection context is scoped to the source form under evaluation, not to a runtime, so this is the one lifecycle function taking no runtime argument. Malformed kinds and options fail loudly; returns `value`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L219-L243">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/collect-kind!">`collect-kind!`</a>

```clojure
(collect-kind! state-key declaration)
```

Function.

Collect one open registry kind for the module source being evaluated.

`state-key` conforms to `::kind-state-key` and names the runtime spool-state slot that owns the registry handle. `declaration` conforms to `::kind-declaration`, the closed registry kind contract. Repeating one state-key/kind id replaces the earlier declaration deterministically. Outside module collection the call is passive. Returns `declaration`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L305-L319">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/collect-lifecycle!">`collect-lifecycle!`</a>

```clojure
(collect-lifecycle! effect-id declaration)
```

Function.

Collect one validated lifecycle declaration from the current module source.

Duplicate ids fail at collection. Outside module source collection the call is passive, allowing code-only reloads to define declaration Vars.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L285-L295">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/module!">`module!`</a>

```clojure
(module! runtime key opts)
```

Function.

Declare one stable runtime module under keyword `key` for `runtime`.

`opts` conforms to `::module-opts`: it is closed to a source target (`:ns` namespace symbol visible in the generation basis, or workspace-relative `:file` string; exactly one is required), an optional `:load :image` mode, optional module-key `:after` dependencies, and an optional boolean `:required?`.

Registry entries and live effects are authored with top-level contribution and lifecycle forms. `opts` names neither callbacks nor entry points: `:contribute` and `:reconcile` are rejected with replacement-form guidance, and a public `spool` var in a loaded module namespace is rejected too. The removed grammar has no alias or fallback.

`:load :image` (SPEC-004.C45/C46) trusts the already-loaded JVM image for the `:ns` target: refresh performs no source load for that module, and it accepts no `:file` target. It replays the namespace's retained authoring declaration record as data. Missing, stale, or foreign records fail module evaluation. The outcome reports `:source/status :image` and carries no source stamp.

During startup-file collection this only stages the declaration and performs no source load, publication, or reconcile. Outside collection it replaces the desired declaration for `key` and refreshes that module plus affected dependents (CC4). Whole-module removal is expressed by omitting the module from a successfully collected full graph, not here. Malformed declarations fail loudly. The staged or refreshed result conforms to `::module-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L176-L207">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/now">`now`</a>

```clojure
(now runtime)
```

Function.

Return the current java.time.Instant from `runtime`'s clock seam.

Defaults to the real wall clock; deterministic tests inject an advanceable clock through `millstrand.test.alpha/set-clock!`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L425-L431">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/plan">`plan`</a>

```clojure
(plan runtime)
(plan runtime opts)
```

Function.

Return the dry-run intentions of `refresh!` without publishing or reconciling.

Full plan performs the same dependency-basis comparison as `refresh!`. Current-basis plans collect and stage source without publishing, reconciling, or recording coordinator state. They return a `::refresh-result`-shaped map flagged `:dry-run? true` with a `:caveat`; collection may evaluate module source code. Options conform to `::refresh-opts`; malformed options fail loudly. The result conforms to `::plan-result` (DELTA-Dns-Repl-001.C11).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L354-L368">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/refresh!">`refresh!`</a>

```clojure
(refresh! runtime)
(refresh! runtime opts)
```

Function.

Reconcile `runtime`'s live image against its declared module graph.

The no-opts arity re-reads `init.clj`/`init.local.clj`, collects the complete layered graph, compares the workspace dependency basis with the running generation, and applies source evaluation, owner-complete publication, and resource reconciliation when the basis is unchanged. A changed or invalid basis returns the exact dependency result without evaluating activation or module source. `(refresh! runtime {:only keys})` refreshes a non-empty set of known module keys and affected dependents against the active declaration graph without re-reading startup files. Options conform to `::refresh-opts` (closed to `:only`): unknown option keys, an empty or malformed `:only`, and unknown module keys fail loudly. Content-identical staged contributions skip publication and reconcile. The atomic multi-phase reconcile is the coordinator that startup also drives; this surface owns the arities, request classification, and result validation. The joined result conforms to `::refresh-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L326-L347">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/reload-code!">`reload-code!`</a>

```clojure
(reload-code! runtime root-lib)
```

Function.

Reload loaded namespaces from source-backed entries for basis `root-lib`.

This advanced code-only seam reloads namespaces whose file resources belong to the selected library's source paths. It does not publish module contributions or reconcile resources; use `refresh!` for the normal path. The result conforms to `::reload-code-result` (DELTA-Dns-Repl-001.C5).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L395-L406">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/resolve-var">`resolve-var`</a>

```clojure
(resolve-var runtime sym)
```

Function.

Resolve fully qualified `sym` to its Var under `runtime`'s generation classloader, backed by its resolved tools.deps basis.

Declarations name behavior by symbol, and symbols on that basis load only under that classloader — a bare `requiring-resolve` is blind to it. Returns the Var, or nil when its namespace loads but defines nothing under that name; a namespace that cannot be loaded at all throws, carrying the load error as its cause.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L437-L449">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/spool-state">`spool-state`</a>

```clojure
(spool-state runtime key init-fn)
(spool-state runtime key opts init-fn)
```

Function.

Return runtime-owned state for a spool key, creating it with `init-fn` once.

The runtime stores spool state under arbitrary keys in its `:spool-state` atom. `init-fn` is called only when `key` has not been installed for this runtime; the returned value is then reused for the rest of the runtime lifetime. Spools should use this accessor instead of reaching into runtime internals.

Spool state survives `refresh!` by design, so a spool whose state shape changed between refreshes would otherwise silently reuse a preserved value that is missing the new keys. The four-arg arity guards against that: pass opts `{:version v :migrate-fn f}` and, when a preserved value's stored version does not `=` `version`, the runtime deliberately reinits (or, with `:migrate-fn`, hands the old value to `f` to produce the new one) instead of reusing a shape-mismatched map. Silent reuse of shape-mismatched state is impossible once a version is declared. Opts conform to `:millstrand.api.runtime.alpha/spool-state-opts`; a malformed map fails loudly at the call site rather than degrading to the unversioned path.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L468-L522">Source</a></sub></p>

## <a name="millstrand.api.runtime.alpha/status">`status`</a>

```clojure
(status runtime)
```

Function.

Return `runtime`'s offline, read-only joined module status.

Returns exactly the running basis fingerprint, desired modules, resource outcomes, loaded namespaces, and last refresh result. It performs no dependency read, file write, source load, registration, or reconcile. The result conforms to `::status-result` (DELTA-Dns-Repl-001.C12).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/alpha.clj#L375-L389">Source</a></sub></p>
