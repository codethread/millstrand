
-----
# <a name="millstrand.api.runtime.glossary.alpha">millstrand.api.runtime.glossary.alpha</a>


Explicit-runtime glossary of named failure outcomes for the discovery surface.

  A glossary outcome maps a qualified, stable name to a short canonical
  definition; the help projection (DELTA-Dtf-002.CC5) resolves per-verb
  `failure-modes` references against this one registry so lifecycle-failure prose
  is defined once and referenced by name, never restated per verb. Each owning
  spool reconciles its outcomes before help resolves the referencing ops (the
  load-order contract, DELTA-Dtf-003.CC2); trusted config may register outcomes
  directly. The registry is runtime-owned service state. Module refresh leaves
  direct entries intact, while an owning module reconciles its complete set.

  Discipline (TEN-000@1, no-migration alpha): outcome names are qualified and
  stable; `register-glossary-outcome!` fails loudly on a name collision, naming
  both registrants, and changed semantics require a **new name**, never a
  redefinition. `replace-glossary-outcome!` is the deliberate override and
  requires the name to already exist.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime.




## <a name="millstrand.api.runtime.glossary.alpha/glossary-outcomes">`glossary-outcomes`</a>
``` clojure
(glossary-outcomes runtime)
```
Function.

Return `runtime`'s registered glossary outcomes as full maps, sorted by name.

  The read/introspection projection: reads the runtime store explicitly, never
  the published ambient singleton.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/glossary/alpha.clj#L80-L89">Source</a></sub></p>

## <a name="millstrand.api.runtime.glossary.alpha/outcome-registered?">`outcome-registered?`</a>
``` clojure
(outcome-registered? runtime name)
```
Function.

True when `runtime`'s glossary carries an outcome named `name`.

  The existence predicate the op-registration glossary-ref check
  (DELTA-Dtf-003.CC2) consults for every `failure-modes` reference.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/glossary/alpha.clj#L95-L101">Source</a></sub></p>

## <a name="millstrand.api.runtime.glossary.alpha/register-glossary-outcome!">`register-glossary-outcome!`</a>
``` clojure
(register-glossary-outcome! runtime outcome)
```
Function.

Register `outcome` in `runtime`'s glossary and return it.

  `outcome` is a closed map `{:name :definition :owner}`: a qualified stable
  `:name` (`ns/term`), a non-blank `:definition`, and an `:owner` naming the
  registrant. Registering an already-registered name fails loudly, naming both
  the existing and attempting owners; use `replace-glossary-outcome!` for a
  deliberate override. The collision check runs inside the `swap!`, so two racing
  registrations of the same name cannot both clear a stale read.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/glossary/alpha.clj#L46-L58">Source</a></sub></p>

## <a name="millstrand.api.runtime.glossary.alpha/replace-glossary-outcome!">`replace-glossary-outcome!`</a>
``` clojure
(replace-glossary-outcome! runtime outcome)
```
Function.

Replace an already-registered glossary `outcome`, failing loudly when absent.

  Same map shape as `register-glossary-outcome!`. This is the deliberate override
  for a name that already exists; unlike the register path it requires the name
  to be present. A changed-semantics redefinition still deserves a new name — this
  path exists for trusted config to re-seat an outcome it owns.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/runtime/glossary/alpha.clj#L64-L74">Source</a></sub></p>
