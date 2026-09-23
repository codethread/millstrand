# REPL API delta for config files

**Document ID:** `DELTA-Cff-001` **Root spec:** [REPL API](../../../specs/repl-api.md) **Feature:** [Proposal](../proposal.md) **Status:** Draft **Last Updated:** 2026-09-23

## DELTA-Cff-001.P1 Summary

Extend SPEC-003.C17f–C17h with consumer-local lifecycle selection options and a file-backed resource family. Add the pure hydration helper to the trusted Clojure surface. Existing registry override semantics and module activation remain unchanged.

## DELTA-Cff-001.P2 Contract changes

### DELTA-Cff-001.CC1 Lifecycle selection owns ordering

`use-seed!`, `use-resource!`, `use-reconcile!`, and `use-configfile!` accept an optional leading map closed to `:after`, followed by one or more declaration Var symbols. `:after` is a set of keyword effect IDs. Absence preserves each declaration's default; presence replaces that set exactly, including `#{}`. One options map applies to every Var selected by that call.

Selection validates the complete input before collection and returns the selected Vars in argument order, as today. Options are applied to a copied normalized entry in the consuming owner's record. The source Var's root and metadata remain unchanged. Image replay retains the consumer's effective dependency set, not the unmodified library default.

Unknown selection keys, invalid values, wrong-family Vars, and arbitrary Var expressions fail at the selection boundary. Duplicate effect IDs anywhere in one module collection remain errors; selection overrides do not authorise duplicate effects or another owner's replacement. Missing dependencies and cycles are checked against the complete selected effect graph before publication, so source order does not constrain dependency selection.

`:after` orders effects within the consumer module. It does not name modules, require namespaces, resolve aliases, or grant a new cross-module dependency grammar. Module `:after` remains SPEC-003.C25a. Registry `:override?` remains separate and is not accepted by lifecycle selections.

### DELTA-Cff-001.CC2 Config-file authoring family

`millstrand.api.config.alpha` owns `defconfigfile`, `use-configfile!`, and `defconfigfile!`. The definition grammar is `(defconfigfile name doc options (fn [context configfile] body...))`; the bang form takes the same definition grammar and additionally selects the declaration. The function literal is one ordinary two-argument function, compiled at the exact authored Var name. It is not a callback expression evaluated later or a closure stored in declaration data. There is no second generated handler Var or runtime `eval`.

The descriptor follows SPEC-003.C17h, with lifecycle channel and a file-backed resource kind. The normalized entry carries the open callable's qualified symbol, the close symbol, and data-only source options. Invalid declaration options cannot replace an existing Var. Definition forms return the installed Var; typed selection returns a Var vector. Outside selected module collection, forms validate but publish nothing and perform no config-file I/O. Foreign-source selection is rejected as for other lifecycle families.

For the first version, `options` is closed to required `:location`, `:format`, and `:close`, plus optional default `:after`. `:format` is exactly `:markdown`; `:close` is a fully qualified callable symbol. Config-file resources have module lifetime; no `:scope` option ships. The location map is closed to required non-blank strings `:path` and `:filename`, and optional boolean `:recursive?`, default false. The runtime delta defines their filesystem semantics.

Bang forms select declaration defaults. A consumer wanting a selection override uses an inert declaration followed by the typed use form; this feature adds no second options position to existing lifecycle definition grammars.

### DELTA-Cff-001.CC3 Parsed document and callbacks

The open callable takes two arguments: the existing lifecycle context, including explicit `:runtime`, and a document map containing exactly `:path`, `:frontmatter`, and `:body`. `:path` is the matched path relative to the declared root, using `/` separators. `:frontmatter` is a keyword-keyed map of YAML data; `:body` is the uninterpreted Markdown string after the frontmatter delimiter. Nested maps have keyword keys and sequences become vectors. Strings, numbers, booleans, and nil remain data, with no domain coercion, keyword-valued enum inference, or set conversion.

The open return is the resource handle, with the same allowance for live values as `defresource`. A normal nil return is still a successful open and receives paired cleanup. Close takes one lifecycle context with `:resource` equal to that exact returned handle. The coordinator retains original file provenance for diagnostics; close does not reopen or reparse a deleted or renamed file. Close returns a data-first result under the existing lifecycle contract.

The loader has no built-in alias identity, registration schema, body meaning, or collision policy. Callbacks must finish domain validation before acquisition and must clean up any partial acquisition if they throw before returning a handle. The coordinator does not infer a partial handle from exception data.

### DELTA-Cff-001.CC4 Optional literal hydration

`(config/hydrate bindings configfile)` returns a new document map. `bindings` maps unqualified keywords whose names match `[A-Za-z_][A-Za-z0-9_-]*` to strings. Tokens are exactly `{{` followed by such a name followed by `}}`, with no whitespace; matching is case-sensitive. `:cwd` replaces `{{cwd}}`. Namespaced, blank, or otherwise invalid binding keys fail at this public boundary. Other brace text remains literal. The helper walks strings in frontmatter values recursively through maps and vectors, and the body string. It leaves map keys, non-string values, and `:path` unchanged.

Replacement is literal and single-pass: replacement strings containing another token are not expanded in the same call, and characters such as `$` and backslash have no replacement-language meaning. An absent binding leaves its token unchanged. An empty replacement string is valid. Whole-string tokens still produce strings. No trimming, shell escaping, Markdown escaping, environment lookup, special variable names, expressions, or code evaluation occurs. Callers may explicitly hydrate again with another binding map.

### DELTA-Cff-001.CC5 Validation belongs at the right boundary

The loader parses Markdown/frontmatter structure once. It accepts unknown frontmatter fields and does not introspect a clojure.spec to strip them. A domain callback selects the fields it understands, performs any requested hydration and explicit domain conversions, then invokes its validating registration API or `require-valid!` before registration. Invalid recognised values are errors; unknown enum values are not treated as ignored fields.

No declaration-level `:frontmatter-spec` ships in this version. Validating a raw template against a hydrated domain schema would reject legitimate tokens, while validating after an arbitrary effectful callback would be too late. Clojure composition is the explicit validation boundary. A domain can publish its frontmatter spec and helper without expanding the generic loader's option grammar.

### DELTA-Cff-001.CC6 Discoverable authoring and projections

Public docstrings and named specs cover definition options, lifecycle selection options, parsed documents, hydration inputs, and data-first outcomes. Runtime enforcement happens at external and extension boundaries, not through optional instrumentation. Macro analysis supports the definition's function bindings and the leading selection-options map. Examples and the shipped clj-kondo export must describe the same grammar.

SPEC-003.C25c/C26a–C26c gain the config-file rebuild semantics and consulted closed outcome/error projections in [DELTA-Cff-002](daemon-runtime.delta.md). [DELTA-Cff-003](alpha-surface.delta.md) adds the config namespace to the public contract index. No new CLI, process-global mutable config catalog, or public raw lifecycle-handle projection is introduced. Existing `status` top-level keys stay unchanged.

## DELTA-Cff-001.P3 Design decisions

### DELTA-Cff-001.D1 Consumer-local dependency replacement

Replacement gives the composing workspace the final dependency set without mutating reusable library definitions. Union would make a library default impossible to remove. Lifecycle dependency overrides are independent of registry ownership overrides.

### DELTA-Cff-001.D2 Ordinary Clojure owns domain interpretation

The file surface covers simple data plus prose. Hydration and registration are ordinary functions; adapters keep validation and unknown-field policy near their domain schemas. Standalone format plugins and a generic schema transformation language are excluded.

## DELTA-Cff-001.P4 Open questions

None blocking proposal review. All contracts are staged, not implemented. Public clause numbers will be allocated when these changes are promoted into SPEC-003.
