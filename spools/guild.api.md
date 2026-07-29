
-----
# <a name="skein.spools.guild">skein.spools.guild</a>


Reference spool for declaring a versioned public weaver operation API.

  Guild ops are ordinary CLI operations registered in the weaver op registry.
  Names are documented as dotted, version-suffixed handles such as
  `gate.close.v1`; the underlying registry requires simple unqualified handles
  and therefore rejects namespaced keyword or symbol names. Optional input specs
  validate the parsed op input before the declared handler runs. Deprecation
  replaces an op with a stub that always fails loudly with structured data.




## <a name="skein.spools.guild/contribute">`contribute`</a>
``` clojure
(contribute _ctx)
```
Function.

Return Guild's owner-complete built-in operation contribution.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L305-L316">Source</a></sub></p>

## <a name="skein.spools.guild/deprecate!">`deprecate!`</a>
``` clojure
(deprecate! runtime name opts)
```
Function.

Replace a registered guild operation in `runtime` with a loud deprecation stub.

  `opts` requires `:replacement` and may include `:since`. Deprecated ops never
  return success; invocation throws ex-info with `:code :operation/deprecated`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L263-L287">Source</a></sub></p>

## <a name="skein.spools.guild/deprecated-op">`deprecated-op`</a>
``` clojure
(deprecated-op {:op/keys [name], :as ctx})
```
Function.

Fail loudly for a deprecated guild operation.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L198-L207">Source</a></sub></p>

## <a name="skein.spools.guild/dispatch-op">`dispatch-op`</a>
``` clojure
(dispatch-op {:op/keys [name args], :as ctx})
```
Function.

Dispatch a guild-declared operation after parsing and validating input.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L190-L196">Source</a></sub></p>

## <a name="skein.spools.guild/ops">`ops`</a>
``` clojure
(ops {:op/keys [runtime-metadata], :as ctx})
```
Function.

Return JSON-safe metadata describing the registered guild API.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L289-L303">Source</a></sub></p>

## <a name="skein.spools.guild/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile Guild's runtime-owned declarations per the module contract.

  Applied and removed contributions deliberately share one body: resetting
  the runtime-owned declaration atoms (including the fallback guild name) and
  republishing clears every prior declaration, which is both
  fresh-application hygiene and complete teardown (SPEC-004.C46b). Any other
  status is a direct-call error and fails loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L318-L339">Source</a></sub></p>

## <a name="skein.spools.guild/register-op!">`register-op!`</a>
``` clojure
(register-op! runtime name opts fn-sym)
```
Function.

Register a guild operation in `runtime`'s CLI operation registry.

  `name` is a simple unqualified registry handle, conventionally dotted and
  version-suffixed such as `gate.close.v1`. `opts` must satisfy the owning
  `::register-op-opts` spec: caller-supplied leaf `:hook-class` (`:read` or
  `:mutating`) and `:deadline-class` (`:standard` or `:unbounded`), plus
  optional `:doc`, `:input-spec`, and `:returns`; unknown options fail
  loudly. Guild supplies no class defaults.
  `:returns` is the shared registry return-shape declaration, not a
  Guild-specific schema. `fn-sym` must be a fully qualified symbol resolving in
  the weaver JVM. The handler receives the usual op context plus parsed JSON
  input at `:guild/input`.

  A declared `:input-spec` is discoverable over the wire: it rides the generic
  arg `:spec` convention (SPEC-003.C70), so `strand help <op>` projects the
  registered spec's contract and template, and invalid input fails with the
  same projection fields plus explain text.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L209-L255">Source</a></sub></p>

## <a name="skein.spools.guild/set-fallback-guild-name!">`set-fallback-guild-name!`</a>
``` clojure
(set-fallback-guild-name! runtime guild-name)
```
Function.

Record `guild-name` as the fallback guild name in `runtime`'s state.

  The guild name is normally read from runtime metadata; the fallback covers
  contexts without it. Module reconcile resets the fallback to nil, so
  trusted config and tests call this after activation. Passing nil clears
  the fallback; a non-nil value must be a non-blank string and anything else
  fails loudly with the offending value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L341-L353">Source</a></sub></p>

## <a name="skein.spools.guild/spool">`spool`</a>




Entry-point declaration for the guild spool (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'skein.spools.guild :spools [...]}`) and never mirrors
  the pair. Unqualified symbols resolve against this namespace; fn values are
  rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/guild/src/skein/spools/guild.clj#L355-L365">Source</a></sub></p>
