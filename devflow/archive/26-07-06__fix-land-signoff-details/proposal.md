# Land sign-off input discoverability proposal

**Document ID:** `PROP-Lsd-001`

**Last Updated:** 2026-07-18

**Related RFCs:** None

**Related root specs:** `SPEC-002.C39a`

## PROP-Lsd-001.P1 Problem

Land checkpoint views carry only `:choices` names. Required JSON inputs are available through help, `workflow/choice-details`, and failed `choose` errors, but not at the point a coordinator first sees the checkpoint.

## PROP-Lsd-001.P2 Goals

- **PROP-Lsd-001.G1:** Make sign-off input requirements visible before a coordinator invokes `land choose`.
- **PROP-Lsd-001.G2:** Reuse the canonical workflow choice-detail shape.
- **PROP-Lsd-001.G3:** Preserve the generic workflow step-view contract.

## PROP-Lsd-001.P3 Non-goals

- **PROP-Lsd-001.NG1:** No `land choices` command.
- **PROP-Lsd-001.NG2:** No change to shared workflow step views or choice validation.
- **PROP-Lsd-001.NG3:** No persisted-data or migration change.

## PROP-Lsd-001.P4 Proposed scope

- **PROP-Lsd-001.S1:** Land results map over `:ready`. Checkpoint views gain a sibling `:choice-details` field beside `:choices`, populated by `workflow/choice-details` for that materialized step. The field is keyed by canonical string choice names and retains the existing input and next fields. Non-checkpoint views omit the field rather than returning nil.
- **PROP-Lsd-001.S2:** The enrichment applies consistently to start, next, complete, choose, and status results when they carry a ready checkpoint. This includes the `land complete` result that first reaches sign-off.
- **PROP-Lsd-001.S3:** Non-checkpoint ready views omit `:choice-details`.
- **PROP-Lsd-001.S4:** `:choices` remains the existing ordered name vector.
- **PROP-Lsd-001.S5:** Results with multiple ready views join details independently by each checkpoint view's materialized `:id`. Non-checkpoint views do not trigger a details lookup.

## PROP-Lsd-001.P5 Council decision

Oracle council `dfsp4`, synthesis `spjxp`, unanimously chose the land-specific join over a new verb or a generic workflow change. The synthesis preferred all land results over next/status alone because `land complete` is the checkpoint's first-contact response.

## PROP-Lsd-001.P6 Open questions

None.

## PROP-Lsd-001.P7 Done when

- The complete result that first reaches sign-off exposes canonical details.
- Next and status expose the same details for that checkpoint.
- All land result paths use the same enrichment helper when a checkpoint is ready.
- Pre-checkpoint and post-checkpoint gate views omit `:choice-details`.
- Existing choice order and missing-required-input enforcement remain covered.
