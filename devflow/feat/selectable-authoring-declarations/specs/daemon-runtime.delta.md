# Weaver runtime delta for selectable authoring declarations

**Document ID:** `DELTA-Sad-002`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-08-13
**Configuration identification:** `Sad` abbreviates selectable authoring declarations. This is the second delta in that feature's ordered set, so it takes `DELTA-Sad-002`. Nested IDs carry the complete document ID.

## DELTA-Sad-002.P1 Summary

Module collection records explicit selections rather than every declaration defined while a source loads. Publication, retained image replay, lifecycle ordering, and owner-complete removal continue to operate on the resulting collected record.

## DELTA-Sad-002.P2 Contract changes

- **DELTA-Sad-002.CC1 (amends SPEC-004.C46 and C46h):** Evaluating `def<kind>` installs a validated descriptor on its Var and never writes to the active contribution or lifecycle collector. Only `use-<kind>!`, `def<kind>!`, and the existing low-level collection functions add declarations to the selected module's owner-complete record. Omission of a prior selection removes that contribution on the next successful publication.
- **DELTA-Sad-002.CC2:** Typed selection remains passive outside module source collection but still resolves and validates every referenced Var and descriptor. A direct REPL evaluation or code-only reload therefore catches a malformed selection without changing a live owner partition. Reloading a library may redefine its Vars and descriptors, but selected live entries change only when a consumer module is refreshed or activated.
- **DELTA-Sad-002.CC3:** Required library namespaces may define inert declarations while another module source collector is active. Those definitions are not foreign contributions and do not trigger the source-ownership guard. A selection or bang form still belongs to the exact selected source context; evaluating one from a required foreign namespace fails before collection with the existing module, namespace, and file context.
- **DELTA-Sad-002.CC4:** Selection copies the descriptor's normalized kind, key, entry, and consumer-supplied override intent into the active collector. The collected and retained record contains publication data, not a live Var or metadata reference. Image activation therefore replays the consumer module's last successful selected set without source loading and without requiring the declaration Var metadata to be rebuilt.
- **DELTA-Sad-002.CC5:** Repeating a kind/key in separate top-level selection forms uses the current deterministic replacement rule. A duplicate within one typed use form fails before any declaration from that form is collected, so one malformed multi-selection cannot leave a partial staged result. Lifecycle forms keep their stricter duplicate-effect rule across the complete source collection.
- **DELTA-Sad-002.CC6:** Callable binding moments do not change. Function-backed definitions now bind the callable at the exact authored Var symbol, and their normalized entries name that symbol. Dispatch-time kinds continue to resolve the current Var root at dispatch; generation-bound kinds and lifecycle effects continue to validate and resolve at their existing boundaries.
- **DELTA-Sad-002.CC7:** A fresh source-mode generation can load an unchanged library written with unbanged forms against the new Millstrand macros, then select the resulting descriptor-bearing Vars from an authorized consumer adapter. This is valid only where the source is compatible with the new exact-name contract. The selected workspace uses that path for the separately pinned Kanban spool, with a disposable fresh-generation acceptance proof before restart. Its retained adapter record then follows CC4 for image replay.

## DELTA-Sad-002.P3 Design decisions

### DELTA-Sad-002.D1 The consumer owns activation

- **Decision:** Publication ownership is assigned at the selection form, not at the declaration Var's namespace.
- **Rationale:** A library can expose choices without mutating any weaver, while omission and override remain properties of the consumer's module partition.
- **Rejected:** Library-owned auto-publication and direct registration performed by use forms.

### DELTA-Sad-002.D2 Retained records stay self-contained

- **Decision:** Collection materializes descriptor data into the retained module record rather than retaining a Var reference.
- **Rationale:** Image replay remains data-first and does not depend on arbitrary namespace evaluation or mutable Var metadata.
- **Rejected:** Re-expanding use forms or dereferencing library Vars during image activation.

## DELTA-Sad-002.P4 Open questions

None.
