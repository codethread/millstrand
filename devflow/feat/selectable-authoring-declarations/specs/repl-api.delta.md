# REPL API delta for selectable authoring declarations

**Document ID:** `DELTA-Sad-001`
**Root spec:** [repl-api.md](../../../specs/repl-api.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-08-13
**Configuration identification:** `Sad` abbreviates selectable authoring declarations. This is the first delta in that feature's ordered set, so it takes `DELTA-Sad-001`. Nested IDs carry the complete document ID.

## DELTA-Sad-001.P1 Summary

The authoring API separates declaration definition from module selection. Every supported family has an inert `def<kind>`, a typed `use-<kind>!`, and a `def<kind>!` shorthand that performs both operations. Direct registration verbs remain unchanged.

## DELTA-Sad-001.P2 Contract changes

- **DELTA-Sad-001.CC1 (replaces SPEC-003.C17e):** `millstrand.api.millstrand.alpha` owns six core families: `defop`/`use-op!`/`defop!`, `defquery`/`use-query!`/`defquery!`, `defpattern`/`use-pattern!`/`defpattern!`, `defhook`/`use-hook!`/`defhook!`, `defhandler`/`use-handler!`/`defhandler!`, and `defbin`/`use-bin!`/`defbin!`. `def<kind>` defines exactly the supplied Var name and contributes nothing. Function-backed declarations bind their function at that name; no form synthesizes a second handler name. `def<kind>!` defines the same Var and selects it into the current module source. Both definition forms return the Var.
- **DELTA-Sad-001.CC2 (replaces the authoring portion of SPEC-003.C17f):** `millstrand.api.lifecycle.alpha` owns `defseed`/`use-seed!`/`defseed!`, `defresource`/`use-resource!`/`defresource!`, and `defreconcile`/`use-reconcile!`/`defreconcile!`. Inert and bang forms use the existing name, docstring, and declaration-option grammar. Lifecycle selection has no override option because effect identity is `[consumer-module effect-id]`; its typed use forms accept only declaration Var references.
- **DELTA-Sad-001.CC3:** A `def<kind>` Var carries one printable descriptor under the metadata key `:millstrand.api.authoring.alpha/declaration`. The closed descriptor has `:protocol` equal to `1`, a qualified keyword `:family`, `:channel` equal to `:registry` or `:lifecycle`, the public `:kind` and `:key`, the normalized printable `:entry`, and the fully qualified `:var` symbol. The descriptor is validated when installed and when selected. The Var root remains the declaration's natural value: a handler function for function-backed forms and the validated declaration data for value-backed forms.
- **DELTA-Sad-001.CC4:** Each `use-<kind>!` is a macro accepting one or more symbols that resolve to Vars. The symbols may be qualified or local; arbitrary value expressions are rejected. The form requires a valid protocol-1 descriptor whose `:family` matches the typed use form, contributes every descriptor in argument order, and returns the selected Vars as a vector in the same order. A missing descriptor, stale protocol, wrong family, malformed entry, unresolved symbol, non-Var reference, or repeated effective kind/key within one form fails loudly at selection.
- **DELTA-Sad-001.CC5:** Registry-family use forms accept an optional leading options map closed to boolean `:override?`. Override intent is attached to the consumer's contribution and never stored in the reusable descriptor. Definition-only forms reject selection options. A registry `def<kind>!` keeps the existing call shape and routes `:override?` from its options map to its generated selection after validating the remaining declaration options. Separate top-level selections of the same kind/key retain SPEC-003.C17's deterministic later-replaces-earlier collector rule; the later selection's override intent is retained.
- **DELTA-Sad-001.CC6:** `millstrand.api.authoring.alpha/defauthoring` defines an open registry family's three public macros from one noun and one mode-aware, defmacro-style builder: `(defauthoring noun builder-bindings & plan-body)`. `builder-bindings` is an ordinary macro argument vector with one reserved first symbol. For example, `[mode name doc options argv & body]` generates definition macros whose user arglist is `[name doc options argv & body]`. The reserved symbol receives `:define` for `def<noun>` or `:define-and-use` for `def<noun>!`; it is not a user argument. The builder may branch on mode where the bang form admits a selection-only options position that the inert form rejects. It returns a closed expansion plan with `:name`, `:definition`, `:kind`, `:key`, `:entry`, and `:use-options` forms. `:name` must be the exact simple symbol defined by `:definition`; `:kind`, `:key`, and `:entry` produce the normalized registry contribution; `:use-options` produces a map closed to `:override?`. The generated family is a qualified keyword derived from the defining namespace and noun. `def<noun>` requires evaluated use options to be empty, installs the descriptor, and returns the Var. `use-<noun>!` follows CC4 and CC5. `def<noun>!` installs and selects once with the evaluated use options. Invalid builder plans fail during macro expansion; invalid declarations or use options fail before definition or collection. A domain's generated macro docstrings and tests must state any mode-specific arities. For the current domains, Workflow and Chime route `:override?` from their existing options position, while Cron uses the explicit CC7 grammar.
- **DELTA-Sad-001.CC7:** The built-in forms and generated domain forms share the descriptor and typed-selection contract. Workflow owns `workflow` and `executor` families, Chime owns `rule`, and Cron owns `job`. Cron's definition grammar becomes `(defjob name doc job)`, deriving the registry id as `(keyword name)`. Its bang form also accepts `(defjob! name doc use-options job)`, and its use form accepts the standard optional leading use-options map.
- **DELTA-Sad-001.CC8:** Source search and clj-kondo see the same public vocabulary. Millstrand's export publishes four stable analyze-call hook entrypoints in `hooks.millstrand`: `defauthoring`, `defvalue`, `deffn`, and `use-vars`. `defauthoring` analyzes `(defauthoring noun [mode & user-bindings] & plan-body)` as definitions of the three generated macros and rejects a non-symbol noun or malformed builder binding vector. `defvalue` analyzes a value-backed inert or bang call as a `def` at its exact authored name while marking every remaining argument expression used. `deffn` analyzes a function-backed inert or bang call as a `defn` at its exact authored name, using the first argument vector after the docstring as the function argv and marking preceding option expressions used. `use-vars` marks the optional leading use-options map used and analyzes every remaining symbol as a Var reference without defining it. Millstrand maps every built-in form to the matching hook. A domain export maps its generated value or function family exactly as follows, choosing `defvalue` or `deffn` once for both definition forms:

  ```clojure
  {:hooks
   {:analyze-call
    {domain.api/defrule hooks.millstrand/deffn
     domain.api/defrule! hooks.millstrand/deffn
     domain.api/use-rule! hooks.millstrand/use-vars}}}
  ```

  A value-backed family uses the same three-entry map with `hooks.millstrand/defvalue` for its definition forms. The Millstrand export maps `millstrand.api.authoring.alpha/defauthoring` to `hooks.millstrand/defauthoring`, so the defining domain namespace also sees the generated macro Vars. Hook functions take clj-kondo's standard context map and return `{:node analyzed-node}`; malformed hook context fails loudly. No hook configuration, runtime lookup, generated per-family hook code, or fallback analyzer is part of the contract. The export does not enforce module-location policy in this delivery.
- **DELTA-Sad-001.CC9:** `millstrand.api.graph.alpha`, `patterns.alpha`, `events.alpha`, `hooks.alpha`, `weaver.alpha`, `registry.alpha`, `millstrand.repl`, and domain equivalents keep their direct `register/replace/unregister-*!` behavior. A use form is durable module selection, not an alias for direct runtime mutation.

## DELTA-Sad-001.P3 Design decisions

### DELTA-Sad-001.D1 Selection stays typed

- **Decision:** Every family has a discoverable `use-<kind>!`; there is no generic `use!`.
- **Rationale:** The call site retains the declaration kind for reading, search, lint, documentation, and errors.
- **Rejected:** A single generic use macro and namespace-wide or wildcard selection.

### DELTA-Sad-001.D2 Vars carry reusable declarations

- **Decision:** Definition attaches normalized descriptor data to the exact authored Var while preserving its natural root value.
- **Rationale:** Consumers select one Var without restating parameters, handler symbols, documentation, or registry entry shapes, and ordinary Var use remains natural.
- **Rejected:** Separate recipe Vars, wrapper records as function roots, and consumer-authored registration maps.

### DELTA-Sad-001.D3 Bang forms remain permanent shorthand

- **Decision:** `def<kind>!` remains a supported define-and-select form after the break.
- **Rationale:** Workspace modules and spool entry points often own both decisions and should not need adjacent definition and selection forms.
- **Rejected:** A migration-only bang alias or a grace period with dual semantics for unbanged forms.

## DELTA-Sad-001.P4 Open questions

None.
