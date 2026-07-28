# Brief: authoring forms replace `def spool` (authoring-forms)

User brief, 2026-07-28 session (card gq2l4). The two same-day RFC drafts — spool authoring forms (RFC-Saf-001) and lifecycle authoring forms (RFC-Laf-001) — overlapped heavily and have been merged on this branch into [`RFC-Auf-001`](../../rfcs/2026-07-28-authoring-forms.md): replace both `def spool` entry points (`:contribute`, `:reconcile`) with named top-level authoring forms, ending with no `spool` var at all.

Standing direction from the user:

- A TEN-000@1 breaking change is explicitly accepted. Minimise it by ordering, not compatibility machinery: expose all authoring forms first, migrate all sibling spools, then remove the old `(def spool {:contribute ... :reconcile ...})` API in one final break.
- This program is too much for a single feature. The goal of this stage is a sane program-level proposal that also audits the RFC's intent against the current code (the RFC was written from two drafts; its claims about what exists — `.skein` macro prototypes, shipped `defjob`/`defworkflow`, collector seams, the reconciler census, overlapping active features — need verification before scope is frozen).
- After proposal sign-off, break the program into kanban feature cards, which in turn get smaller tasks. Feature slicing belongs after the proposal, not in it.

Maturity split to preserve: the contribution half of RFC-Auf-001 is proposed for acceptance; the lifecycle half is gated on a bounded feasibility spike (RFC-Auf-001.P16.2) before its form names and shapes are accepted.
