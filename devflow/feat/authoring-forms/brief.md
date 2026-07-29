# Brief: authoring forms replace `def spool` (authoring-forms)

User brief, 2026-07-28 session (card gq2l4). The two same-day RFCs — spool authoring forms ([`RFC-Saf-001`](../../rfcs/2026-07-28-spool-authoring-forms.md)) and lifecycle authoring forms ([`RFC-Laf-001`](../../rfcs/2026-07-28-lifecycle-authoring-forms.md)) — overlap heavily; the user asked for them to be considered together and carried forward as one document. That document is this folder's [proposal.md](./proposal.md): replace both `def spool` entry points (`:contribute`, `:reconcile`) with named top-level authoring forms, ending with no `spool` var at all.

Standing direction from the user (all 2026-07-28):

- A TEN-000@1 breaking change is explicitly accepted. Minimise it by ordering, not compatibility machinery: expose all authoring forms first, migrate all sibling spools, then remove the old `(def spool {:contribute ... :reconcile ...})` API in one final break.
- This program is too much for a single feature. This stage produces one sane program-level proposal that audits the RFCs' intent against current code; after sign-off, break the program into kanban feature cards, which in turn get smaller tasks.
- One proposal document, deliberately large: this is one overall change and the single document is the coordination aid.
- The source RFCs from main stay unchanged as the record of original intent; the proposal may advance beyond them as new data comes to light, with departures marked.
- skein-src will not stamp a v1 marker: everything breaking is OK within alpha TEN-000@1, sibling spools included.
- Fact-check reviews route to sol-med; sol findings are guidance, not law — weigh them against PHILOSOPHY (introspectable and repairable at runtime, not a perfect binary).

Maturity split at approval: the contribution half was accepted; the lifecycle half was gated on the bounded feasibility spike in PROP-Auf-001.P16.2. That gate has now passed. The accepted names, policy, census, and provider deltas are recorded in [Lifecycle authoring feasibility result](lifecycle-spike.md), which supersedes the proposal's provisional lifecycle examples without rewriting the approved proposal.
