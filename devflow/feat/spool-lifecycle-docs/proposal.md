# Spool lifecycle contract documentation proposal

**Document ID:** `PROP-Sld-001`
**Last Updated:** 2026-07-28
**Related RFCs:** None
**Related root specs:** [`devflow/specs/repl-api.md`](../../specs/repl-api.md) (C17c/C17d/C23/C23a/C23b), [`devflow/specs/daemon-runtime.md`](../../specs/daemon-runtime.md) (C45/C46/C46b/C46d)
**Related proposals:** [`PROP-Dsp-001`](../uwnzl-def-spool-convention/proposal.md) (the `def spool` convention this documents)

## PROP-Sld-001.P1 Problem

`(def spool …)` is a designed API surface with no consumer-facing account of either key. `docs/spools/writing-shared-spools.md` documents the `::spool` shape and the reconcile statuses, but it does not document the `contribute` context, its return value, where `:queries` entries belong, or how a contribution differs from direct registration. Those contracts are stated only in the root specs and in `skein.core.weaver.module-refresh` and `skein.core.weaver.module-publication`, so an author has to read coordinator internals to find them.

The guide also omits owner-complete publication: when a module publishes a changed contribution, entries and kinds omitted from that contribution are removed for that owner. Without that, an author reads a contribution as an additive patch.

## PROP-Sld-001.P2 Goals

- **PROP-Sld-001.G1:** State the whole activation path in the guide's existing activation section: consumer declaration, public `spool` var, `contribute` context and return envelope, registry kinds, publication semantics, and `reconcile` context and status contract.
- **PROP-Sld-001.G2:** Carry a worked conversion from a direct `graph/register-query!` call to the equivalent contribution, so an author can place a query without guessing which map owns `:queries`.
- **PROP-Sld-001.G3:** Name owner-complete publication as the semantic difference between direct registration and contribution, and say what removal by omission does.
- **PROP-Sld-001.G4:** Keep the division of labour explicit: contribution data says what should be reachable through the blessed registries, reconciliation makes the running process's live effects and resources agree with the transition.
- **PROP-Sld-001.G5:** Teach entry values as the property of each kind's registered `:entry-spec` rather than enumerating every kind's schema.
- **PROP-Sld-001.G6:** Present the two contribution authoring styles — top-level collecting authoring macros, or one explicit owner-complete `contribute` — as a fork the reader takes before meeting any schema, rather than as a caveat after it. Reconciliation is orthogonal to that choice and says so.
- **PROP-Sld-001.G7:** Ground every copyable example in shipped spool source, and label each snippet's context (spool source namespace, trusted config, trusted REPL). The guide does not present this repository's `.skein` coordination config or its workspace-local macros as public authoring surface.

## PROP-Sld-001.P3 Non-goals

- **PROP-Sld-001.NG1:** No runtime behavior change. This documents shipped behavior only.
- **PROP-Sld-001.NG2:** No root-spec contract change and no feature spec delta. The `specs/` directory exists for the feature layout and stays empty.
- **PROP-Sld-001.NG3:** No API surface or docstring change, and no regenerated `*.api.md` content.
- **PROP-Sld-001.NG4:** No competing standalone lifecycle guide. The material extends the guide section that already owns activation.

## PROP-Sld-001.P4 Proposed scope

- **PROP-Sld-001.S1:** Lift the activation subsections out of "Versioning and release", where they were mis-nested, into one `Activating a module` section. The adjacent Maven-dependency subsection is promoted to a section of its own in place, so no prose block moves.
- **PROP-Sld-001.S2:** Open the section's body with `Choose one contribution authoring style`: collecting authoring macros against one explicit `contribute`, mutually exclusive, loud when both appear, with reconciliation named as orthogonal.
- **PROP-Sld-001.S3:** Extend the section with the `contribute` context map, the contribution envelope in both its long and shorthand forms, the five core kinds plus kinds declared through `registry.alpha/declare-kind!`, and the `mine` conversion example.
- **PROP-Sld-001.S4:** Add owner-complete publication, removal by omission, and the full pre-publication failure list including undeclared override intent and candidate validators.
- **PROP-Sld-001.S5:** Add the `reconcile` context map, the `:applied`/`:removed` branch with its loud default, the per-module `:unchanged` skip condition, retained-reconciler removal, degraded-on-throw behavior, and the data-first return grammar.
- **PROP-Sld-001.S6:** Correct the adjacent glossary-outcome subsection, which currently places outcome registration in the module contribution. Batteries seeds them from its reconciler's `:applied` branch and keeps an effect-free `:removed` branch, and module publication does not run the direct-registration glossary-ref check.

## PROP-Sld-001.P5 Open questions

None.
