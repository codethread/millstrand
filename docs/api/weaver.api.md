---

# <a name="millstrand.api.weaver.alpha">millstrand.api.weaver.alpha</a>

Explicit-runtime API for the strand lifecycle, schema init, and the op registry.

This namespace owns the primitives no domain namespace does: strand create/read/update (`add!`, `update!`, `supersede!`, `archive-attributes!`/`unarchive-attributes!`, `show`, `list`/`list-lean`/`list-query`, and `ready`/`ready-lean`), database schema `init`, acyclic-relation declaration (`declare-acyclic-relation!`/`acyclic-relations`), and the CLI op registry (`register-op!`, `replace-op!`, `ops`, `resolve-op`, `op!`). Domain surfaces (events, hooks, graph queries, batch, patterns, scheduler, runtime config) each own their own alpha namespace.

The module reads in that order. The mutating writes lead — each shows its own transaction/hook/event sequencing at the top level — followed by the acyclic relations, attribute archival, the read surface, and the op registry, whose `op!` is the dispatch entry point for a root-level `strand <name>` invoke. Registration validation and entry construction are plumbing in `millstrand.api.weaver.internal.op-entry`; the built-in `help` op and the help-alias projection live in `millstrand.core.weaver.help`, which both `op!` and the JSON socket consume.

Callers own runtime selection and pass the target weaver runtime as the first argument to every function here.

## <a name="millstrand.api.weaver.alpha/acyclic-relations">`acyclic-relations`</a>

```clojure
(acyclic-relations runtime)
```

Function.

Return declared acyclic edge relation names.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L232-L235">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/add!">`add!`</a>

```clojure
(add! runtime strand)
(add! runtime strand req-ctx)
```

Function.

Create a strand, enqueue a creation event, and return the normalized strand.

The transaction normalizes attributes through the `:attributes/normalize` transform hooks, inserts the strand, applies its edges, and runs the `:strand/add-before-commit` validation hooks before committing; the `:strand/added` event is enqueued only after the commit succeeds.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L83-L119">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/archive-attributes!">`archive-attributes!`</a>

```clojure
(archive-attributes! runtime strand-id)
(archive-attributes! runtime strand-id keys)
```

Function.

Archive all attributes, or an explicit non-empty key set, for one strand.

Archived keys drop out of hot-tier reads (`list`, `ready`, and query execution) but stay visible to full point reads. A later write to an archived key makes that key hot again; untouched archived keys remain archived. Archiving a registered immutable key is rejected — it would hide write-once history.

The strand id and key set are validated by the storage layer against `:millstrand.core.specs/attribute-key-set`, failing loudly on malformed or missing input; the result is checked here against `:millstrand.core.specs/attribute-archive-result`.

This is a trusted in-process primitive only; it has no socket or CLI surface, runs no lifecycle hooks, and enqueues no event.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L243-L262">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/declare-acyclic-relation!">`declare-acyclic-relation!`</a>

```clojure
(declare-acyclic-relation! runtime relation)
```

Function.

Declare an edge relation as acyclic for future graph writes.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L223-L226">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/init">`init`</a>

```clojure
(init runtime)
```

Function.

Initialize the runtime database schema.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L71-L75">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/list">`list`</a>

```clojure
(list runtime)
(list runtime query-def params)
```

Function.

Return strands visible to `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L307-L312">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/list-lean">`list-lean`</a>

```clojure
(list-lean runtime lean-byte-floor)
(list-lean runtime lean-byte-floor query-def params)
(list-lean runtime lean-byte-floor query-def params limit)
(list-lean runtime lean-byte-floor query-def params limit opts)
```

Function.

Return strands with oversized attributes replaced by descriptors.

The optional limit arity is for the CLI/wire read surface; the trusted in-process arities remain unbounded by default. Passing `{:clamp? true}` in the final arity returns at most the limit without running an overflow count.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L319-L336">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/list-query">`list-query`</a>

```clojure
(list-query runtime query-name params)
```

Function.

Return strands matching a registered query definition.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L349-L352">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/op!">`op!`</a>

```clojure
(op! runtime op-name argv)
(op! runtime op-name argv envelope)
```

Function.

Invoke a registered CLI operation with raw string argv from a root-level `strand <name>` invoke.

The handler receives a context map with `:op/name`, `:op/argv`, `:op/runtime`, `:op/runtime-metadata`, and `:op/payloads` (defaulting to `{}`). The envelope arity threads any present `:cwd`, `:worktree-root`, `:git-common-dir`, `:timeout`, `:is-tty`, and `:tty-col` fields into their corresponding `:op/*` context keys, and an envelope `:emit!` fn (supplied by the streaming socket transport for `:stream? true` ops) into `:op/emit!`. Every resolved op declares an `:arg-spec`; `:op/argv` and the attached payloads are parsed through `millstrand.api.cli.alpha/parse` and the result is supplied as `:op/args`; a parse failure throws before the handler runs. A clean trailing `--help`/`-h` flag (the final argv token, no other flags, no payloads) is rewritten to the op's help projection instead of running the handler, for every op class — the op detail, or a verb's sliced node when a verb token precedes the flag; retired `<op> help`/`about`/`prime` sugar and malformed `--help` shapes redirect loudly (DELTA-Dtf-002.CC3). Subcommand map results receive a canonical `:operation` label containing the registered op name and full resolved subcommand path. A handler-supplied `:operation` equal to the derived label is preserved; any other value, including explicit nil, fails loudly with the expected and actual labels.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L568-L628">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/op-provenance">`op-provenance`</a>

```clojure
(op-provenance runtime)
```

Function.

Return owner/provenance diagnostics for `runtime`'s CLI op registry as data.

Maps each registered op name to `{:effective <winning contender> :shadowed   [<lower contenders>] :contenders [<all, low-to-high>]}`; each contender names its `:owner`, `:layer`, `:value` (the op entry), and `:override?`/`:effective?` flags, so a caller sees which owner supplies each op — a built-in under the system owner, a workspace op under the direct owner — and which lower-layer entries an override shadows. Op entries carry the handler symbol as data, not a resolved function value (DELTA-OlrDrt-001.CC9).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L551-L562">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/ops">`ops`</a>

```clojure
(ops runtime)
```

Function.

Return registered CLI operation entries for the current weaver runtime.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L487-L490">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/ready">`ready`</a>

```clojure
(ready runtime)
(ready runtime query-def params)
```

Function.

Return ready strands for `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L358-L363">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/ready-lean">`ready-lean`</a>

```clojure
(ready-lean runtime lean-byte-floor)
(ready-lean runtime lean-byte-floor query-def params)
(ready-lean runtime lean-byte-floor query-def params limit)
```

Function.

Return ready strands with oversized attributes replaced by descriptors.

The optional limit arity is for the CLI/wire read surface; the trusted in-process arities remain unbounded by default.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L370-L382">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/register-op!">`register-op!`</a>

```clojure
(register-op! runtime op-name opts fn-sym)
(register-op! runtime owner op-name opts fn-sym)
```

Function.

Register a trusted weaver-side CLI operation.

Registered operations are invoked at the CLI root as `strand <name>   [args...]`. The handler symbol must resolve to a function that accepts one context map (see `op!` for the context keys) and returns JSON-compatible data. The third positional argument is an op metadata map with keys `:doc`, `:arg-spec` (parser spec, structurally validated at registration), `:returns` (validated return-shape declaration), `:stream?` (default false), `:about`, and `:prime`; unknown keys fail loudly. The metadata map is required to contain `:arg-spec`; every op declares both classes on every arg-spec leaf. Provenance (the registering namespace) is recorded from the handler symbol and must never be caller-supplied.

Registering an already-registered name fails loudly, naming both the existing entry's provenance and the attempted registrant; use `replace-op!` to override deliberately. Registry contents live only for the current weaver lifetime and are normally published by owner-complete modules from init.clj or registered directly from a live REPL. Module refresh replaces its owner's partition; direct registrations remain until explicitly replaced or removed.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L394-L425">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/replace-op!">`replace-op!`</a>

```clojure
(replace-op! runtime op-name opts fn-sym)
(replace-op! runtime owner op-name opts fn-sym)
```

Function.

Replace an already-registered op, failing loudly when the name is absent.

Same signature as `register-op!`. This is the deliberate override for a name that already exists; unlike `register-op!` it requires the name to be present. When another owner supplies the name — a module-published op, say — the override intent is recorded, which is what lets the direct entry keep shadowing the original across `runtime/refresh!`. `unregister-op!` retracts the shadow and the shadowed entry becomes effective again.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L434-L453">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/resolve-op">`resolve-op`</a>

```clojure
(resolve-op runtime op-name)
```

Function.

Return the registered CLI operation entry for `op-name`, or fail loudly.

Reads one effective op snapshot for the invocation already beginning, so a concurrent registry replacement takes effect only for a later resolve — the in-flight lookup and its not-found diagnostic share one immutable view (DELTA-OlrDrt-001.CC9/CC10, op symbols resolve at invocation).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L531-L545">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/show">`show`</a>

```clojure
(show runtime id)
```

Function.

Return one normalized strand by id, or nil when absent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L298-L301">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/supersede!">`supersede!`</a>

```clojure
(supersede! runtime old-id replacement-id)
(supersede! runtime old-id replacement-id req-ctx)
```

Function.

Replace one strand with another and enqueue a supersession event.

The transaction performs the supersession and runs the `:strand/supersede-before-commit` validation hooks with the supersession context; the `:strand/superseded` event is enqueued only after the commit succeeds.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L190-L212">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/unarchive-attributes!">`unarchive-attributes!`</a>

```clojure
(unarchive-attributes! runtime strand-id)
(unarchive-attributes! runtime strand-id keys)
```

Function.

Mark all attributes, or an explicit non-empty key set, hot again for one strand.

Restores hot-tier visibility without changing any value. Untouched archived keys remain archived. Unarchiving a registered immutable key is legal — it is the recovery path for immutable rows archived before enforcement existed.

The strand id and key set are validated by the storage layer against `:millstrand.core.specs/attribute-key-set`, failing loudly on malformed or missing input; the result is checked here against `:millstrand.core.specs/attribute-archive-result`.

This is a trusted in-process primitive only; it has no socket or CLI surface, runs no lifecycle hooks, and enqueues no event.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L270-L288">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/unregister-op!">`unregister-op!`</a>

```clojure
(unregister-op! runtime op-name)
(unregister-op! runtime owner op-name)
```

Function.

Retract `owner`'s own op registration for `op-name`.

Removal reaches only into the calling owner's partition, so it is the counterpart of `replace-op!` rather than a way to delete another owner's op: retracting a shadow restores the shadowed entry as effective, and retracting a fresh claim leaves the name unregistered. Unregistering a name this owner never registered is an idempotent no-op. Returns `{:unregistered   <canonical-name>}`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L466-L480">Source</a></sub></p>

## <a name="millstrand.api.weaver.alpha/update!">`update!`</a>

```clojure
(update! runtime id patch)
(update! runtime id patch req-ctx)
```

Function.

Update a strand and/or add edges atomically, then enqueue an update event.

Rejects unknown patch fields up front. The transaction reads the current strand (failing loudly when absent), normalizes any supplied attributes through the `:attributes/normalize` transform hooks, applies edges, writes the changed columns, and runs the `:strand/update-before-commit` validation hooks; the `:strand/updated` event is enqueued only after the commit succeeds.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/weaver/alpha.clj#L127-L182">Source</a></sub></p>
