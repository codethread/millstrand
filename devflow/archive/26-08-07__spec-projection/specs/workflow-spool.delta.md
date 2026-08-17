# Workflow spool delta for spec-projection

**Document ID:** `DELTA-Spj-003` **Contract doc:** [spools/workflow.md](../../../../spools/workflow.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-29 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID.

## DELTA-Spj-003.P1 Summary

The workflow spool adopts `skein.api.spec.alpha` on its three spec-facing wire surfaces: `workflow show` params, checkpoint choice-input discovery, and invalid-params/input failures (TEN-000@1 sanctioned wire changes, epic uruz0).

## DELTA-Spj-003.P2 Contract changes

- **DELTA-Spj-003.CC1:** `workflow show`'s `params` view (kind `"spec"`) carries `contract` and `template` from the shared projection alongside `spec`, `defaults`, and `spec-forms`. `spec-forms` keeps its name and entry shape, accreting the module's `doc`/`private` enrichment on entries whose form is a resolvable predicate symbol.
- **DELTA-Spj-003.CC2:** New CLI read verb `workflow choices <run-id> [--step <id>]` fronts the trusted `choice-details`: it returns the ready checkpoint's choices keyed by name, each with its label, description, routing hints, and — for a spec-first choice input — the input spec identity, doc, and the live `contract`/`template`/`spec-forms` projection. The stored pour-time record keeps the printed-form graph only; the richer views are derived live at read (the live spec is the authority; PROP-Wcd-001.S10's identity-resolved-again rule unchanged).
- **DELTA-Spj-003.CC3:** Ready frontier items stay lean: a checkpoint item carries choice names only, and a defer item carries target workflow names only. Workers discover contracts through the point reads (`workflow choices` for a ready checkpoint, `workflow show <target>` before `workflow defer`); the workflow docs state this discipline.
- **DELTA-Spj-003.CC4:** `require-conformant!` failures (invalid params, invalid checkpoint input) carry the same named projection fields discovery uses — `spec`, `spec-forms` (enriched), `contract`, `template` — alongside `explain` (`s/explain-str` text). No raw `s/explain-data` crosses the wire.
