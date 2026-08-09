
-----
# <a name="millstrand.api.runtime.help-transform.alpha">millstrand.api.runtime.help-transform.alpha</a>


Explicit-runtime at-most-one slot for the default help transform.

  The slot holds at most one transform: a function from the full canonical help
  response envelope (DELTA-Dtf-001.CC1) to the rendered string the CLI relays
  verbatim (JSON or text — the transform's choice). The `help` op renders through
  it when present, else emits the raw canonical envelope; `--json` always bypasses
  it, so a broken transform never bricks help (DELTA-Dtf-001.CC4). `about`/`prime`
  output is never transformed.

  The slot is runtime-owned service state. Module refresh leaves a direct
  registration intact, while trusted config may reconcile the elected value.
  No spool elects itself, so a fresh world keeps the raw-JSON floor
  (DELTA-Dtf-002.D1).

  Discipline (TEN-002): the slot is at-most-one and set explicitly.
  `register-default-help-transform!` fails loudly when the slot is occupied,
  naming both registrants; `replace-default-help-transform!` is the deliberate
  override and requires a transform to already be registered.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime.




## <a name="millstrand.api.runtime.help-transform.alpha/default-help-transform">`default-help-transform`</a>
``` clojure
(default-help-transform runtime)
```
Function.

Return `runtime`'s registered default-help-transform registration map, or nil.

  The read/introspection projection: reports whether a transform is registered
  (`some?`) and its provenance (`:owner`), reading the runtime store explicitly.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L111-L117">Source</a></sub></p>

## <a name="millstrand.api.runtime.help-transform.alpha/default-help-transform-registered?">`default-help-transform-registered?`</a>
``` clojure
(default-help-transform-registered? runtime)
```
Function.

True when `runtime`'s default-help-transform slot holds a transform.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L123-L126">Source</a></sub></p>

## <a name="millstrand.api.runtime.help-transform.alpha/register-builtin!">`register-builtin!`</a>
``` clojure
(register-builtin! runtime)
```
Function.

Register Batteries' builtin help transform as `runtime`'s default.

  Resolves the transform through the runtime's spool classloader. The Batteries
  root must already be synced into the running weaver. Like direct registration,
  this fails loudly when the slot is occupied.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L59-L71">Source</a></sub></p>

## <a name="millstrand.api.runtime.help-transform.alpha/register-default-help-transform!">`register-default-help-transform!`</a>
``` clojure
(register-default-help-transform! runtime registration)
```
Function.

Register `registration` as `runtime`'s default help transform and return it.

  `registration` is a closed map `{:transform :owner}`: a `:transform` fn (full
  envelope → rendered string) and an `:owner` naming the registrant. Registering
  when the slot is already occupied fails loudly, naming both the existing and
  attempting owners; use `replace-default-help-transform!` for a deliberate
  override. The occupancy check runs inside the `swap!`, so two racing
  registrations cannot both clear a stale read.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L41-L53">Source</a></sub></p>

## <a name="millstrand.api.runtime.help-transform.alpha/replace-default-help-transform!">`replace-default-help-transform!`</a>
``` clojure
(replace-default-help-transform! runtime registration)
```
Function.

Replace `runtime`'s registered default help transform, failing loudly when
  the slot is empty.

  Same map shape as `register-default-help-transform!`. This is the deliberate
  override for an occupied slot; unlike the register path it requires a transform
  to already be registered.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L77-L87">Source</a></sub></p>

## <a name="millstrand.api.runtime.help-transform.alpha/unregister-default-help-transform!">`unregister-default-help-transform!`</a>
``` clojure
(unregister-default-help-transform! runtime owner)
```
Function.

Remove `owner`'s registered default help transform and return it.

  The slot must be occupied by the same owner. An empty slot or another owner
  fails loudly, so lifecycle cleanup cannot remove a replacement registration.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/help_transform/alpha.clj#L93-L105">Source</a></sub></p>
