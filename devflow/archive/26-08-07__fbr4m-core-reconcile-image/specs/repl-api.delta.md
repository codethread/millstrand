# REPL API delta for core reconcile contract + image activation

**Document ID:** `DELTA-Cri-002` **Root spec:** [repl-api.md](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-23 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID, for example `DELTA-Cri-002.P1`, so references are globally grepable and do not clash across documents.

## DELTA-Cri-002.P1 Summary

SPEC-003 P5 describes the `module!` helper's closed declaration options. It gains the `{:load :image}` grammar ADR-003.P4 adopted, and the helper prose gains the one-sentence reconcile contract ADR-003.P6 formalized (normative text in SPEC-004.C46b via DELTA-Cri-001.CC1). The P6 example stays untouched: it shows a production `:spools`-guarded declaration, and image mode is the bare-runtime/test variant of the exported-base-declaration pattern (ADR-003.P7) — adding `:load :image` there would misdescribe production activation.

## DELTA-Cri-002.P2 Contract changes

- **DELTA-Cri-002.CC1 (P5 helper prose):** The `module!` helper line gains the optional `:load :image` mode: with an `:ns` target and an explicit `:contribute`, the declaration trusts the already-loaded JVM image and performs no source load during collection; an unloaded namespace is that module's failed outcome. The reconcile sentence states the contract: a `:reconcile` fn receives the contribution status and branches — `:applied` ensures registrations and resources, `:removed` tears them down; other statuses (direct calls only) fail loudly.
- **DELTA-Cri-002.CC2 (module `:ns` target paragraph):** The paragraph stating "Module `:ns` targets are ledger-loaded from the complete synchronized root closure" gains the image-mode exception: a `:load :image` module's namespace is trusted from the live image and never source-loaded by refresh. The P6 example is deliberately unchanged (see P1).

## DELTA-Cri-002.P3 Design decisions

- **DELTA-Cri-002.D1:** The `::module-declaration` result spec in `skein.api.runtime.alpha` accepts the optional normalized `:load` key (value `:image`). The public input grammar is the named `::module-opts` spec `module!` consults at the seam (including the `:load :image` cross-field constraints); the coordinator's `normalize-declaration` stays the normalizer and the authority for actionable per-field error prose, with spec/parser disagreement failing loudly as drift.

- **DELTA-Cri-002.D2:** The `refresh!`/`plan` option grammar is the named `::refresh-opts` spec (closed to `:only`, a non-empty collection of module keywords) consulted at the seam and declared in both fdefs; the hand-rolled validator keeps the actionable error prose, with spec/validator disagreement failing loudly as drift.

## DELTA-Cri-002.P4 Open questions

- **DELTA-Cri-002.Q1:** None.
