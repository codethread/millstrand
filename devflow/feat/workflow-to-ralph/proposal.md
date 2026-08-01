# Workflow to Ralph proposal

**Document ID:** `PROP-Wtr-001`
**Status:** Approved
**Approved:** 2026-08-01
**Related RFCs:** None
**Related root specs:** [SPEC-006 Repository workflows](../../specs/repo-workflows.md)

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Wtr-001.P1 Problem

The Ralph scripts refuse to run an epic until it has the `ralph` label, but the repository has no workflow that establishes when an epic is sufficiently decomposed to receive that label. A partially planned epic can therefore be marked ready without feature-level task breakdowns or review.

## PROP-Wtr-001.P2 Goals

- **PROP-Wtr-001.G1:** Give coordinators one registered workflow for preparing an existing kanban epic for a Ralph loop.
- **PROP-Wtr-001.G2:** Require the epic to be decomposed into feature cards and each feature into task strands.
- **PROP-Wtr-001.G3:** Give every feature breakdown its own explicit review before the epic can become Ralph-ready.
- **PROP-Wtr-001.G4:** Make applying the `ralph` label the final action after all reviews pass.

## PROP-Wtr-001.P3 Non-goals

- **PROP-Wtr-001.NG1:** Change the Ralph shell loops or their readiness checks.
- **PROP-Wtr-001.NG2:** Automate feature implementation or landing.
- **PROP-Wtr-001.NG3:** Add new kanban operations or attributes.

## PROP-Wtr-001.P4 Proposed scope

- **PROP-Wtr-001.S1:** Add a repository workflow that guides decomposition of an existing epic into feature cards and task strands.
- **PROP-Wtr-001.S2:** Represent each feature review as a separate workflow decision whose approval is required for readiness.
- **PROP-Wtr-001.S3:** Keep the `ralph` label absent until every declared feature review is approved.
- **PROP-Wtr-001.S4:** Publish the workflow through the repository's live workflow catalogue with discoverable parameter and choice contracts.
- **PROP-Wtr-001.S5:** Apply the `ralph` label to the existing epic as the workflow's final action after all feature reviews are approved.

## PROP-Wtr-001.P5 Open questions

None.
