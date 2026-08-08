# Daemon Runtime Spec Delta: reconcile teardown and retraction-less domains

**Document ID:** `DELTA-Itr-001` **Root spec:** [`devflow/specs/daemon-runtime.md`](../../../specs/daemon-runtime.md) **Proposal:** [PROP-Itr-001](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-24

## DELTA-Itr-001.CC1 C46b amendment — teardown where the domain offers retraction

The rrvnn plan review (step zgs06) surfaced that C46b's unqualified "on `:removed` it tears them down" cannot be satisfied by a reconciler whose domain deliberately offers no retraction API: batteries' glossary outcomes and workflow's vocabulary declarations are process-lifetime seeds — the glossary API ships register/replace and no unregister on purpose, and vocabulary ownership has no retraction surface. Building teardown APIs solely so a never-removed base module could hypothetically retract them fails TEN-004.

**Amendment.** C46b gains one clarifying sentence: teardown on `:removed` extends to the resources the owning domain can retract; where the domain deliberately offers no retraction API, the reconciler's `:removed` branch is an explicit no-effect branch that names that absence, and the defect class stays running registration effects on `:removed`.

## DELTA-Itr-001.D1 Decision

- **DELTA-Itr-001.D1:** Recorded as spec text rather than a per-spool code comment alone, so the next domain with process-lifetime seeds inherits the rule instead of re-litigating C46b. Rejected: adding `unregister-glossary-outcome!`/vocab retraction — new public surface with no caller (TEN-004; ADR-003.P9's revisit-when covers a future domain that genuinely needs kernel-level teardown).
