
-----
# <a name="skein.examples.guild">skein.examples.guild</a>


Example for declaring a versioned public weaver operation API.

  Guild ops are ordinary CLI operations registered in the weaver op registry.
  Names are documented as dotted, version-suffixed handles such as
  `gate.close.v1`; the underlying registry requires simple unqualified handles
  and therefore rejects namespaced keyword or symbol names. Optional input specs
  validate the parsed op input before the declared handler runs. Deprecation
  replaces an op with a stub that always fails loudly with structured data.




## <a name="skein.examples.guild/deprecate!">`deprecate!`</a>
``` clojure
(deprecate! runtime name opts)
```
Function.

Replace a registered guild operation in `runtime` with a loud deprecation stub.

  `opts` requires `:replacement` and may include `:since`. Deprecated ops never
  return success; invocation throws ex-info with `:code :operation/deprecated`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L274-L298">Source</a></sub></p>

## <a name="skein.examples.guild/deprecated-op">`deprecated-op`</a>
``` clojure
(deprecated-op {:op/keys [name], :as ctx})
```
Function.

Fail loudly for a deprecated guild operation.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L209-L218">Source</a></sub></p>

## <a name="skein.examples.guild/dispatch-op">`dispatch-op`</a>
``` clojure
(dispatch-op {:op/keys [name args], :as ctx})
```
Function.

Dispatch a guild-declared operation after parsing and validating input.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L201-L207">Source</a></sub></p>

## <a name="skein.examples.guild/guild-op">`guild-op`</a>
``` clojure
(guild-op #:op{:keys [runtime runtime-metadata]})
```
Function.

Return JSON-safe metadata describing the registered Guild API.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L330-L347">Source</a></sub></p>

## <a name="skein.examples.guild/guild-state">`guild-state`</a>




Own Guild's reset and publication boundary for the module lifetime.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L349-L352">Source</a></sub></p>

## <a name="skein.examples.guild/register-op!">`register-op!`</a>
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
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L220-L266">Source</a></sub></p>

## <a name="skein.examples.guild/reset-guild!">`reset-guild!`</a>
``` clojure
(reset-guild! context)
```
Function.

Reset Guild's runtime-owned declarations during module open and close.

  `context` must conform to `::lifecycle-context`; the result conforms to
  `::reset-result`. Guild is the irregular lifecycle boundary: both transitions
  clear its active and deprecated operations, fallback name, and published
  declaration owner. The coordinator retains this callable for omission cleanup.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L314-L328">Source</a></sub></p>

## <a name="skein.examples.guild/set-fallback-guild-name!">`set-fallback-guild-name!`</a>
``` clojure
(set-fallback-guild-name! runtime guild-name)
```
Function.

Record `guild-name` as the fallback guild name in `runtime`'s state.

  The guild name is normally read from runtime metadata; the fallback covers
  contexts without it. The `guild-state` resource resets the fallback when its
  module opens or closes; preserving a healthy resource preserves the current
  fallback. Passing nil clears the fallback; a non-nil value must be a
  non-blank string and anything else fails loudly with the offending value.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/examples/guild/src/skein/examples/guild.clj#L300-L312">Source</a></sub></p>
