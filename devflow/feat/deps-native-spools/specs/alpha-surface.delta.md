# Alpha surface delta: deps-native spool dependencies

**Delta ID:** `DELTA-Dns-Alpha-001`
**Target:** [SPEC-005](../../../specs/alpha-surface.md)
**Authority:** [PROP-Dns-001](../proposal.md)
**Status:** Proposed; apply only with the implementing change

This delta replaces `SPEC-005.C2`, `C3`, `C9`, `C9b`, and `C9c`. All other alpha-surface clauses remain unchanged.

## Replacement clauses

- **DELTA-Dns-Alpha-001.C1 (replaces SPEC-005.C2):** The blessed namespace set is unchanged. `millstrand.api.runtime.alpha` remains in-contract with the deps-native contracts and named public specs in `DELTA-Dns-Repl-001.C1`–`C13`; `millstrand.test.alpha` remains in-contract with C14–C18. Each public input and output shape is owned by the qualified clojure.spec named in those clauses, validated where it crosses the public function, activation-file, candidate-basis, or test-fixture boundary, and documented by that public Var's docstring or the cited contract clause. `millstrand.api.spool.alpha` loses nothing: `entity-projection`, `fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, and `poll-until!` remain blessed spool-authoring helpers.

- **DELTA-Dns-Alpha-001.C2 (replaces SPEC-005.C3):** The shipped `batteries` and `unsafe-text-search` spools remain opt-in reference spools contracted by `spools/*.md`. A workspace makes each spool available as an ordinary library in its canonical `deps.edn` or optional `deps.local.edn`, then activates it explicitly from `init.clj` or `init.local.clj` with an owner-complete `runtime/module!` declaration. Dependency presence does not activate code. `mill init` seeds the ordinary batteries dependency and its explicit shared activation; deleting either is the supported opt-out. Both spools share the running generation's tools.deps basis and classloader. The existing `millstrand.api.format.alpha` and `millstrand.api.spool.alpha` helper ownership remains unchanged, and `millstrand.spools.*` remains exactly the activatable reference-spool tier.

- **DELTA-Dns-Alpha-001.C3 (replaces SPEC-005.C9):** Moving a surface across the contract line still requires its owning root spec or spool doc and this index when tier membership changes. This feature removes exactly the following public surface: `millstrand.api.runtime.alpha/approved`, `declared`, `release-marker`, `upsert-spool-entry!`, and `remove-spool-entry!`; their public specs and result shapes; the `runtime/module!` option `:spools`; and the complete `strand spool` `about`, `add`, `bump`, and `status` operation family. `millstrand.api.spool.alpha` has no removals. No other public removal may be inferred from implementation cleanup, including deletion of internal manifest, acquisition, compatibility, approval, and dynamic-classloader namespaces. `millstrand.test.alpha/spool-checkout-root` remains public with the deps-native contract in `DELTA-Dns-Repl-001.C18`. The remaining runtime functions are reshaped only as specified by `DELTA-Dns-Repl-001` and the corresponding SPEC-004 delta.

- **DELTA-Dns-Alpha-001.C4 (replaces SPEC-005.C9b):** The earlier runtime-lifecycle pre-v1 exception remains recorded. This feature adds a second bounded exception under TEN-000@1 for exactly the names, public specs/results, `module! :spools` option, and CLI family listed in C3. They receive no deprecated aliases, forwarding Vars, compatibility namespaces, fallback manifests, feature probes, or alternate package grammar. `deps.local.edn` and `init.local.clj` are ordinary dependency and activation overlays, not compatibility forms. The clean break removes the second package manager and live classpath mutation; it does not repeal C9a or authorize another break.

- **DELTA-Dns-Alpha-001.C5 (replaces SPEC-005.C9c):** The core and lifecycle authoring-family exception remains unchanged: inert definition, typed selection, define-and-select forms, exact function-backed names, coordinated external migration, and the existing Kanban acceptance pin retain their current contracts. Deps-native activation does not add an authoring alias or change those form grammars. Owner-complete publication means that, after a successful refresh, the selected declarations are the module owner's complete published set; omission retracts prior entries as specified by `DELTA-Dns-Repl-001.C7`.

## Traceability

`PROP-Dns-001.S3` maps to C2 and C5. Its exact S6 public-removal table maps to C1, C3, and C4. `PROP-Dns-001.NG1`–`NG3` map to C2 and C4. Restart continuity remains owned by `SPEC-004.C113`–`C123` and is neither narrowed nor restated here.
