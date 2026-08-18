# Land sign-off input discoverability plan

**Document ID:** `PLAN-Lsd-001`

**Feature:** `fix-land-signoff-details`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** None

**Root specs:** `SPEC-002.C39a`

**Feature specs:** None

**Status:** Reviewed

**Last Updated:** 2026-07-18

## PLAN-Lsd-001.P1 Goal and scope

Expose canonical choice input declarations inside ready land checkpoint views without changing the shared workflow view.

## PLAN-Lsd-001.P2 Approach

- **PLAN-Lsd-001.A1:** Add one private land-result helper that maps ready views.
- **PLAN-Lsd-001.A2:** For checkpoint views only, join `workflow/choice-details` by materialized step id.
- **PLAN-Lsd-001.A3:** Route every land mutation/query result containing `:ready` through the helper.
- **PLAN-Lsd-001.A4:** Update the land manual to state where required inputs appear.

## PLAN-Lsd-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Lsd-001.AA1 | `.skein/workflows.clj` | Land-only result enrichment and guidance. |
| PLAN-Lsd-001.AA2 | `test/skein/config_test.clj` | End-to-end positive, parity, and negative assertions. |

## PLAN-Lsd-001.P4 Contract and migration impact

- **PLAN-Lsd-001.CM1:** Additive repo-local JSON output only. No persisted-data, shared spool, or migration change.

## PLAN-Lsd-001.P5 Validation strategy

- **PLAN-Lsd-001.V1:** The result first reaching sign-off contains the canonical approved and abort input declarations.
- **PLAN-Lsd-001.V2:** `land next` and `land status` return identical details.
- **PLAN-Lsd-001.V3:** Gate views omit `:choice-details`.
- **PLAN-Lsd-001.V4:** Existing missing-input enforcement remains covered.
- **PLAN-Lsd-001.V5:** Focused cold config tests and repository quality gates pass.

## PLAN-Lsd-001.P6 Risks

- **PLAN-Lsd-001.R1:** Copying choice prose would drift. Joining the canonical public `workflow/choice-details` result avoids duplication.

## PLAN-Lsd-001.P7 Developer notes

Plan review `s4imw` reinforced explicit omission checks before and after sign-off. Separate start/choose checkpoint fixtures and synthetic multi-ready land fixtures were rejected: the registered land graph cannot reach those states. One shared mapper covers every result path, while end-to-end tests cover the reachable first-contact, query, and status surfaces.
