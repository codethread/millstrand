# Alpha surface delta for selectable authoring declarations

**Document ID:** `DELTA-Sad-003`
**Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-08-13
**Configuration identification:** `Sad` abbreviates selectable authoring declarations. This is the third delta in that feature's ordered set, so it takes `DELTA-Sad-003`. Nested IDs carry the complete document ID.

## DELTA-Sad-003.P1 Summary

The blessed alpha tier gains the shared authoring-family namespace and takes one coordinated pre-v1 break to split definition from selection.

## DELTA-Sad-003.P2 Contract changes

- **DELTA-Sad-003.CC1 (amends SPEC-005.C2):** `millstrand.api.authoring.alpha` joins the blessed spool-facing API. It owns the protocol-1 declaration descriptor and `defauthoring` domain-family macro described by `DELTA-Sad-001`. The generated macros are public surface of their defining domain namespace, not re-exports from Millstrand.
- **DELTA-Sad-003.CC2 (replaces SPEC-005.C12's form list):** The six core kinds each expose the complete definition, typed-selection, and define-and-select family in `millstrand.api.millstrand.alpha`. Their registry ownership and read protocols do not change.
- **DELTA-Sad-003.CC3:** Under TEN-000@1 and explicit repository-owner approval, the in-contract core and lifecycle authoring macros take an in-place compatibility break: unbanged forms stop collecting, bang forms become the publishing shorthand, function-backed forms bind the exact supplied name, and typed use forms are added. No deprecated alias, dual behavior, compatibility namespace, or feature probe ships. This is a bounded exception to SPEC-005.C9a, not a general repeal of alpha accretion.
- **DELTA-Sad-003.CC4:** The coordinated userland migration is limited to Millhouse, agent-harness, devflow, and codethread. Their own contracts and release markers record their corresponding breaks. Kanban v25 at peeled SHA `a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e` is the explicitly authorized external exception. This repository consumes only its core-only adapter surface through the Millstrand-owned adapter and fresh-generation constraint in `DELTA-Sad-002.CC7`; Guild, peering operations, and lifecycle resources are absent. No compatibility bridge or other compatibility claim is made for an external spool repository.

## DELTA-Sad-003.P3 Design decisions

### DELTA-Sad-003.D1 Domain family generation is blessed

- **Decision:** The descriptor protocol and family generator live in `millstrand.api.authoring.alpha`.
- **Rationale:** Domain spools need one supported way to teach the same API and clj-kondo model without depending on `millstrand.core.*`.
- **Rejected:** Copying the three-macro implementation into each spool or making every domain family part of `millstrand.api.millstrand.alpha`.

### DELTA-Sad-003.D2 The break is direct

- **Decision:** Old unbanged publication semantics disappear in the same release that introduces typed selection.
- **Rationale:** The authorized repositories move as one pinned set, and retaining both meanings would make source inspection and activation ownership ambiguous.
- **Rejected:** A grace release, warnings, aliases, and runtime capability detection.

## DELTA-Sad-003.P4 Open questions

None.
