
-----
# <a name="millstrand.api.authoring.alpha">millstrand.api.authoring.alpha</a>


Shared declaration and typed-selection boundary for authoring families.

  Declaration Vars carry a closed protocol-1 `::declaration` in their metadata.
  Registry selections accept only Vars from the expected family, validate their
  descriptors and kind entries, normalize them through `::selection`, and then
  pass trusted values to the existing module collector.

  The public spec sources are `::declaration`, `::registry-use-options`,
  `::builder-bindings`, `::expansion-plan`, `::selection`, and
  `::selected-vars`. `defauthoring` consults the builder and plan specs during
  macro expansion; declaration installation and selection consult the remaining
  specs at their named boundaries.




## <a name="millstrand.api.authoring.alpha/defauthoring">`defauthoring`</a>
``` clojure
(defauthoring noun builder-bindings & plan-body)
```
Macro.

Define an open registry family's inert, typed-use, and bang macros.

  `(defauthoring noun [mode & user-bindings] & plan-body)` conforms the binding
  vector with `::builder-bindings`. `mode` receives `:define` for `def<noun>`
  and `:define-and-use` for `def<noun>!`; callers supply only `user-bindings`.
  The builder returns a closed `::expansion-plan` whose definition names the
  exact simple `:name`. The family's qualified keyword is derived from this
  namespace and `noun`; its qualified kind keyword must name the registered
  entry spec consulted by generated definitions and selections.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L635-L680">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/deflifecycle">`deflifecycle`</a>
``` clojure
(deflifecycle noun builder-bindings & plan-body)
```
Macro.

Define an inert and a define-and-use family of lifecycle forms.

  `(deflifecycle noun builder-bindings & plan-body)` generates `def<noun>`,
  `use-<noun>!`, and `def<noun>!`. The builder receives `:define` or
  `:define-and-use` and returns the same closed expansion plan as
  `defauthoring`; lifecycle use forms are Vars-only.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L591-L633">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/expand-definition">`expand-definition`</a>
``` clojure
(expand-definition mode family namespace form plan)
```
Function.

Return one generated inert or define-and-use expansion.

  The builder result is conformed with `::expansion-plan` and its `:name` is
  checked against the exact Var defined by `:definition`. Declaration and use
  options are validated before the definition executes. Inert definitions
  require empty use options; bang definitions select once and still return the
  installed Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L364-L402">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/expand-lifecycle-definition">`expand-lifecycle-definition`</a>
``` clojure
(expand-lifecycle-definition mode family namespace form plan)
```
Function.

Return one generated inert or define-and-use lifecycle expansion.

  `plan` is the closed plan returned by a lifecycle family builder. Inert forms
  install only their descriptor; bang forms select once and still return the
  installed Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L537-L562">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/expand-lifecycle-use">`expand-lifecycle-use`</a>
``` clojure
(expand-lifecycle-use family namespace form args)
```
Function.

Return the expansion for a generated typed lifecycle use macro.

  Lifecycle use forms accept only one or more symbols resolving to declaration
  Vars; unlike registry families they have no leading selection-options map.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L529-L535">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/expand-registry-use">`expand-registry-use`</a>
``` clojure
(expand-registry-use family entry-spec namespace form args)
```
Function.

Return the expansion for a generated typed registry use macro.

  Expansion rejects arbitrary value expressions and requires one or more Var
  symbols. The optional leading literal map is evaluated and checked against
  `::registry-use-options` by `select-registry!`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L333-L352">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/install-declaration!">`install-declaration!`</a>
``` clojure
(install-declaration! target prepared)
```
Function.

Attach a prepared `::declaration` to `target`; return the Var.

  `prepared` must be the opaque token returned by one of the preparation
  functions. Invalid installer input is rejected before target metadata can
  change; the token's validated descriptor is what metadata stores.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L179-L192">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/prepare-lifecycle-declaration!">`prepare-lifecycle-declaration!`</a>
``` clojure
(prepare-lifecycle-declaration! family kind key entry var-symbol form)
```
Function.

Validate and return a prepared protocol-1 lifecycle declaration.

  Lifecycle selection has no use options; its effect identity is the authored
  keyword and consumer module. The lifecycle entry is checked before the Var
  is defined, so invalid declarations cannot replace an existing Var. The
  returned value is an opaque token accepted by `install-declaration!`; the
  installed metadata remains the descriptor map.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L463-L490">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/prepare-registry-declaration!">`prepare-registry-declaration!`</a>
``` clojure
(prepare-registry-declaration! family kind key entry var-symbol entry-spec form)
```
Function.

Validate and return a prepared protocol-1 registry declaration.

  `entry-spec` is the owning registry kind's registered entry spec. The entry is
  checked once here, followed by the closed descriptor. Macro expansions call
  this before executing their `def` or `defn`, so invalid authored values do not
  replace an existing Var. The returned value is an opaque token accepted by
  `install-declaration!`; the installed metadata remains the descriptor map.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L146-L177">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/register-registry-kind!">`register-registry-kind!`</a>
``` clojure
(register-registry-kind! kind entry-spec)
```
Function.

Associate a public collector kind with its existing entry spec.

  Built-in and domain authoring namespaces register this mapping before their
  generated forms are used. The retained descriptor keeps the public collector
  kind while the shared boundary consults the owning entry spec.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L107-L120">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/reject-definition-use-options!">`reject-definition-use-options!`</a>
``` clojure
(reject-definition-use-options! options form)
```
Function.

Return nil for empty options; reject selection options on an inert definition.

  `defauthoring` emits this check before the generated definition executes.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L354-L362">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/select-lifecycle!">`select-lifecycle!`</a>
``` clojure
(select-lifecycle! family namespace form symbols)
```
Function.

Resolve, validate, and collect one typed lifecycle selection.

  `symbols` are quoted Var references supplied to a generated use macro. Every
  Var carries a protocol-1 lifecycle descriptor from `family`; arbitrary
  expressions and registry use options are not accepted. The returned Vars are
  checked with `::selected-vars` before the existing lifecycle collector is
  called.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L492-L527">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/select-registry!">`select-registry!`</a>
``` clojure
(select-registry! family entry-spec namespace form options symbols)
```
Function.

Resolve, validate, and collect one typed registry selection.

  `symbols` are the quoted Var references supplied to a generated use macro.
  Every Var and protocol-1 `::declaration` is validated before collection;
  `entry-spec` is consulted for every selected entry. Pass nil to derive it from
  each descriptor's qualified `:kind`. The normalized values pass
  through `::selection`, duplicates fail before collection, and the returned Var
  vector is checked with `::selected-vars`.

  Generated families derive the spec from each qualified kind keyword; domain
  authors register that keyword as the kind's entry spec before defining the
  family. Built-in families pass their distinct registered specs explicitly.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L284-L312">Source</a></sub></p>

## <a name="millstrand.api.authoring.alpha/validate-registry-use-options!">`validate-registry-use-options!`</a>
``` clojure
(validate-registry-use-options! options form)
```
Function.

Return registry selection options conforming to `::registry-use-options`.

  The map is closed to boolean `:override?`. Rejections name the authored form
  and the allowed option keys.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/authoring/alpha.clj#L133-L144">Source</a></sub></p>
