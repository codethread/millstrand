---

# <a name="millstrand.api.registry.alpha">millstrand.api.registry.alpha</a>

Blessed owner-partitioned registry primitive for spool domains.

A spool domain with its own replaceable declarations — chime rules, workflow definitions, harness aliases, and the like — declares each definition family as a _kind_ and then publishes complete owner partitions under it. Declaring a kind (id, entry spec, binding-moment datum, and the fixed layer policy) makes it a valid contribution-map key the refresh kernel publishes uniformly with the core kinds (DELTA-OlrRepl-001.CC5/CC13); an undeclared kind is refused loudly before publication.

The primitive is deletion-complete and effect-free: replacing an owner partition removes every key the owner omits, cross-owner collisions and missing override intent fail before the atom changes, and readers keep the immutable snapshot they started with. Baseline seeding, durable writes, and other lifecycle effects stay in the domain's own API around the calls here — this namespace never runs domain callbacks.

A registry handle is runtime-owned, not a module singleton: create it once inside a `millstrand.api.runtime.alpha/spool-state` init-fn and reuse the handle for the runtime's lifetime. The handle is a metadata-carrying value so versioned spool-state can stamp and re-init it across reloads. The storage kernel lives in `millstrand.core.weaver.owner-registry`; every registered key here stays alpha-qualified.

## <a name="millstrand.api.registry.alpha/declare-kind!">`declare-kind!`</a>

```clojure
(declare-kind! handle declaration)
```

Function.

Declare or replace kind `declaration` in `handle` and publish the snapshot.

`declaration` carries `:id` (the kind id keyword), a registered spec keyword in `:entry-spec`, an arbitrary `:binding-moment` datum, and `:layer-policy`; an omitted layer policy is filled with `layer-precedence`. A declared kind becomes a valid contribution-map key — owner partitions may then publish under it — and existing partitions are revalidated against a changed declaration before publication.

An optional `:candidate-validator` names a fully qualified symbol the module refresh coordinator resolves under the runtime's generation classloader and calls with `{:runtime <rt> :kind <kind-id> :entries <effective candidate entries>   :owners <entry-key to winning owner>}` once every owner's contribution is staged and before any publication. It is the seam for cross-entry rules the per-entry `:entry-spec` cannot express — references between entries contributed by different owners, deletion by omission, resolvability of the symbols an entry names. Throwing rejects the whole refresh, so every affected owner keeps its previous live partition.

Returns a `::declaration-result`; a malformed declaration fails loudly and leaves the registry unchanged.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L54-L83">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/effective">`effective`</a>

```clojure
(effective handle kind-id)
```

Function.

Return the effective entry values for `kind-id` in `handle`.

The result maps each live entry key to the raw value that currently wins its layer contest, in deterministic key order. An undeclared or unpopulated kind yields an empty map. Conforms to `::effective-values`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L116-L123">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/explain">`explain`</a>

```clojure
(explain handle kind-id)
```

Function.

Explain the effective, shadowed, and override state for `kind-id`.

Returns a map from entry key to `{:effective <winning contender> :shadowed   [<lower contenders>] :contenders [<all, low-to-high>]}`. Each contender names its `:owner`, `:layer`, `:value`, and `:override?` intent, so a caller can show why one owner wins and which partitions it shadows. An undeclared or unpopulated kind yields an empty map. Conforms to `::explanation`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L125-L135">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/layer-precedence">`layer-precedence`</a>

The fixed low-to-high owner layer precedence every kind shares: `[:defaults :spools :workspace :direct]`. A kind declaration's `:layer-policy` must equal this; `declare-kind!` fills it in when omitted, so a domain need not depend on the storage kernel to name it.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L32-L37">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/registry">`registry`</a>

```clojure
(registry)
```

Function.

Create a new empty owner-registry handle.

The handle wraps a private storage atom in a metadata-carrying map so it can live in versioned `spool-state`. Store it once per runtime through `millstrand.api.runtime.alpha/spool-state`; never hold it in a module-level atom. The result conforms to `::registry`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L39-L47">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/registry?">`registry?`</a>

```clojure
(registry? x)
```

Function.

Return true when `x` is an owner-registry handle produced by `registry`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L49-L52">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/remove-owner!">`remove-owner!`</a>

```clojure
(remove-owner! handle kind-id owner)
```

Function.

Remove the `owner` partition for `kind-id` from `handle`.

Returns a `::mutation-result` whose `:status` is `:removed` when a partition existed and `:unchanged` otherwise. Removing under an undeclared kind fails loudly and leaves the registry unchanged.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L97-L105">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/replace-owner!">`replace-owner!`</a>

```clojure
(replace-owner! handle kind-id owner partition)
```

Function.

Replace the complete `owner` partition for `kind-id` in `handle`.

`partition` is `{:layer <layer> :entries {<key> <value> ...} :overrides   #{<key> ...}}`; keys the owner omits disappear, and every higher-layer entry that shadows a lower one must restate its `:overrides` intent. An undeclared kind, invalid entry, same-layer duplicate, or missing override intent throws before the atom changes. Returns a `::mutation-result`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L85-L95">Source</a></sub></p>

## <a name="millstrand.api.registry.alpha/snapshot">`snapshot`</a>

```clojure
(snapshot handle)
```

Function.

Return `handle`'s current immutable registry snapshot.

The snapshot carries `:kinds`, `:partitions`, the derived `:effective` and `:owners` projections, and `:provenance`; a reader keeps the value it read even as later publications replace the atom. Conforms to `::snapshot`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/registry/alpha.clj#L107-L114">Source</a></sub></p>
