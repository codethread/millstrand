# Clojure crash course for Millstrand users

This is a tiny Clojure primer for using `mill weaver repl`.

## Calls

```clojure
(weaver/add! rt {:title "Write docs"})
```

means: call `add!` in the `weaver` namespace with two arguments, `rt` and a map.

## Names

Millstrand has one registration story with three calling surfaces. Put durable behavior in a module source with an authoring form such as `millstrand/defquery!`; the bang form defines a Var and selects the declaration when its module is collected. For reusable library declarations, use inert `defquery` plus an explicit `use-query!`. For code and tests that already hold a runtime, use the explicit-runtime `millstrand.api.*.alpha` functions. In the connected REPL, `millstrand.repl` provides the same verbs with the runtime implied. The REPL is a shorter calling style, not a separate capability.

The strand functions live in `millstrand.api.weaver.alpha` and all take the weaver runtime first:

```clojure
weaver/add!
weaver/show
weaver/list
weaver/ready
weaver/update!
```

The explicit-runtime registration form is used by code and tests:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.graph.alpha :as graph])

(graph/register-query! (current/runtime) 'mine [:= [:attr :owner] "ct"])
```

The live registration verbs live in `millstrand.repl` and imply the runtime because you are already sitting inside the weaver. `mill weaver repl` starts in `user` with `millstrand.repl` aliased as `repl`; in another nREPL session, first run `(require '[millstrand.repl :as repl])`.

```clojure
repl/register-query!
repl/replace-query!
repl/unregister-query!
```

The registry is owner-partitioned and layered. `register-query!` claims a fresh name and fails loudly if another owner already supplies it. `replace-query!` records intent to shadow an existing entry. `unregister-query!` removes only your own entry and restores the entry below it; it does not remove or change the Var. Removing a shadow and registering again is not a substitute for replace.

Common row keys:

```clojure
:id
:title
:state
:attributes
```

Keywords such as `:owner` or `:example_outcome` inside `:attributes` are user-chosen.

## Bind a value

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(def rt (current/runtime))
(def s (:id (weaver/add! rt {:title "My first strand"})))
```

That captures the running weaver, creates a strand, extracts its `:id`, and stores the id in `s`.

## Inspect and update

```clojure
(weaver/show rt s)
(weaver/update! rt s {:state "closed"})
```

Closed strands stay in the store with `:state "closed"`. Deletion is `strand burn` on the CLI.

## Collections

```clojure
(weaver/list rt)
(weaver/ready rt)
```

`ready` returns active strands whose direct `depends-on` targets are not active.

## Anonymous functions

```clojure
#(= "ct" (get-in % [:attributes :owner]))
```

means: for one strand row, check whether its user attribute `owner` is `"ct"`.

## Threading

```clojure
(->> (weaver/list rt)
     (filter #(= "ct" (get-in % [:attributes :owner])))
     (filter #(= "active" (:state %)))
     vec)
```

That means: list strands, keep the ones owned by `ct`, keep active-state rows, and return a vector.

## def, defn, defn-, defonce, private

- `def` binds a name to a value (a top-level **var**), evaluated once at load time.
- `defn` is `def` + a function value: `(defn f [x] ...)`.
- `defn-` / `^:private` on a `def` marks the var **private** to its namespace — a visibility hint, not real security. From another namespace, `ns/name` is blocked, but `@#'ns/name` (deref the Var object) still reaches it.
- `defonce` binds only if the var isn't already bound; re-evaluating the form (REPL reload, hot reload) is a no-op. Used for state that must survive namespace reload, e.g. an `atom` holding runtime data.
- "Var" = the named storage cell `def`/`defn`/`defonce` create at the namespace top level (as opposed to a local `let`/fn-arg binding, which has no Var behind it).

## Namespaces

A namespace groups related names, like a module or file. `.millstrand/policy/config.clj` declares `(ns ct.policy.config ...)`, so its top-level definitions live under `ct.policy.config`. From elsewhere you can reach one with a qualified symbol such as `ct.policy.config/work`, or bring names in unqualified with `require`.

## Require helper namespaces

```clojure
(require '[millstrand.api.runtime.alpha :as runtime]
         '[millstrand.api.graph.alpha :as graph])
```

These are privileged built-in helper namespaces shipped with Millstrand. The aliases let you call functions like `runtime/refresh!` and `graph/strands-by-ids`.

## Quick reference

```clojure
(weaver/add! rt {:title "Title"})
(weaver/add! rt {:title "Title" :attributes {:owner "ct"}})
(weaver/add! rt {:title "Scratch" :attributes {:temporary "true"}})
(weaver/update! rt strand-id {:state "closed"})
(weaver/show rt strand-id)
(weaver/list rt)
(weaver/ready rt)
```

## Talking about the code

Terms to use when discussing Clojure with an agent (or another dev), so requests are unambiguous:

- **function** / **fn** — not "method". `weaver/add!`, `ref-symbol`, `plan-strand` are all functions.
- **var** — a top-level name created by `def`/`defn`/`defonce`, e.g. "`devflow-workflows` is a var" or "check the `ref-symbol` fn". Say "the `X` var" only when `X` isn't a function.
- **atom** — mutable state held in a var, e.g. "`devflow-summary-notifications` is a defonce atom"; "reset the atom" / "check the atom".
- **namespace** — a named group of vars, e.g. `config`, `millstrand.api.runtime.alpha`.
- **keyword** — a `:like-this` token, usually a map key.
- **symbol** — a bare name like `foo` or `config/foo`, used to refer to a var or namespace.
- **macro** — code that generates code at compile time, e.g. `defn` itself is a macro (expands to a `def` of a function).

Prefer naming the exact var/fn over vague phrasing: "check `active-devflow-plan-roots`" rather than "check the def/function around devflow roots".

## Further reading

- [Learn X in Y minutes: Clojure](https://learnxinyminutes.com/clojure/)
- [Learn X in Y minutes: Clojure Macros](https://learnxinyminutes.com/clojure-macros/)
