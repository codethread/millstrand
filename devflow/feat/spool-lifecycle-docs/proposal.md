# Spool lifecycle contract documentation proposal

**Document ID:** `PROP-Sld-001`
**Last Updated:** 2026-07-28
**Related RFCs:** None
**Related root specs:** [`devflow/specs/repl-api.md`](../../specs/repl-api.md) (C17c/C17d/C23/C23a/C23b), [`devflow/specs/daemon-runtime.md`](../../specs/daemon-runtime.md) (C45/C46/C46b/C46d)
**Related proposals:** [`PROP-Dsp-001`](../uwnzl-def-spool-convention/proposal.md) (the `def spool` convention this documents)

## PROP-Sld-001.P1 Problem

`(def spool …)` is a designed API surface, and a spool author meets it with no consumer-facing account of what sits behind either key. `docs/spools/writing-shared-spools.md` states the `::spool` shape and the reconcile status contract, then stops: it never says what `contribute` receives, what it must return, which map owns `:queries`, or how a contribution differs from the direct `register-query!` call the same author already knows. The answers exist only in the root specs and in `skein.core.weaver.module_refresh`/`module_publication`, so understanding the schemas today means reading coordinator internals.

The same gap hides the semantics that actually bite. Owner-complete publication — when a module publishes a changed contribution, entries and kinds it now omits are removed for that owner — is nowhere in the guide, so an author reasonably reads a contribution as an additive patch.

## PROP-Sld-001.P2 Goals

- **PROP-Sld-001.G1:** State the whole activation path in the guide's existing activation section: consumer declaration, public `spool` var, `contribute` context and return envelope, registry kinds, publication semantics, and `reconcile` context and status contract.
- **PROP-Sld-001.G2:** Carry a worked conversion from a direct `graph/register-query!` call to the equivalent contribution, so an author can place a query without guessing which map owns `:queries`.
- **PROP-Sld-001.G3:** Name owner-complete publication as the semantic difference between direct registration and contribution, and say what removal by omission does.
- **PROP-Sld-001.G4:** Keep the division of labour explicit: contribution data says what should be reachable through the blessed registries, reconciliation makes the running process's live effects and resources agree with the transition.
- **PROP-Sld-001.G5:** Teach entry values as the property of each kind's registered `:entry-spec` rather than enumerating every kind's schema.
- **PROP-Sld-001.G6:** Present the two contribution authoring styles — collected top-level forms, or one explicit owner-complete `contribute` — as a fork the reader takes before meeting any schema, rather than as a caveat after it. Reconciliation is orthogonal to that choice and says so.
- **PROP-Sld-001.G7:** Ground every example in shipped spool source. The guide does not present this repository's `.skein` coordination config as public authoring surface.

## PROP-Sld-001.P3 Non-goals

- **PROP-Sld-001.NG1:** No runtime behavior change. This documents shipped behavior only.
- **PROP-Sld-001.NG2:** No root-spec contract change and no feature spec delta. The `specs/` directory exists for the feature layout and stays empty.
- **PROP-Sld-001.NG3:** No API surface or docstring change, and no regenerated `*.api.md` content.
- **PROP-Sld-001.NG4:** No competing standalone lifecycle guide. The material extends the guide section that already owns activation.

## PROP-Sld-001.P4 Proposed scope

- **PROP-Sld-001.S1:** Lift the activation subsections out of "Versioning and release", where they were mis-nested, into one `Activating a module` section. The adjacent Maven-dependency subsection is promoted to a section of its own in place, so no prose block moves.
- **PROP-Sld-001.S2:** Open the section's body with `Choose one contribution authoring style`: collected top-level forms against one explicit `contribute`, mutually exclusive, loud when both appear, with reconciliation named as orthogonal.
- **PROP-Sld-001.S3:** Extend the section with the `contribute` context map, the contribution envelope in both its long and shorthand forms, the five core kinds plus kinds declared through `registry.alpha/declare-kind!`, and the `mine` conversion example.
- **PROP-Sld-001.S4:** Add owner-complete publication, removal by omission, and the full pre-publication failure list including undeclared override intent and candidate validators.
- **PROP-Sld-001.S5:** Add the `reconcile` context map, the `:applied`/`:removed` branch with its loud default, the per-module `:unchanged` skip condition, retained-reconciler removal, degraded-on-throw behavior, and the data-first return grammar.
- **PROP-Sld-001.S6:** Correct the adjacent glossary-outcome subsection, which currently places outcome registration in the module contribution. Batteries seeds them from its reconciler's `:applied` branch and keeps an effect-free `:removed` branch, and module publication does not run the direct-registration glossary-ref check.

## PROP-Sld-001.P5 Open questions

None. Every claim is fact-checked against the named root specs, `skein.api.spool.alpha`, `skein.api.runtime.alpha`, `skein.api.registry.alpha`, the coordinator's refresh, publication, owner-registry, and dispatch namespaces, the named-query seam in `skein.api.graph.alpha`/`skein.core.query`, and the shipped spools named in the guide — batteries, chime, cron, guild, workflow, and the shell and code workflow executors.
