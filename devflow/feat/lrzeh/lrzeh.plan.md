# Workflow run context completion plan

**Document ID:** `PLAN-Wrc-001`
**Feature:** `lrzeh`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** None
**Root specs:** `devflow/specs/repl-api.md` (context only)
**Feature specs:** None
**Status:** Reviewed
**Last Updated:** 2026-07-26

## PLAN-Wrc-001.P1 Goal and scope

Extend workflow step completion so callers can atomically add JSON-safe values to the active run context through both the Clojure API and generic CLI.

## PLAN-Wrc-001.P2 Approach

- **PLAN-Wrc-001.A1:** Validate and normalize the optional context map with `skein.spools.workflow.internal.compile/default-context` before acquiring the run mutation guard. Its private recursive walker converts keyword leaves and rejects non-JSON-safe values.
- **PLAN-Wrc-001.A2:** Read the current root context under the guard, shallow-merge the new values, and include the root attribute update in the same batch that closes the selected step and any procedure joins.
- **PLAN-Wrc-001.A3:** Add a declared JSON `--context` flag to `workflow complete`, convert it through the existing JSON params boundary, and carry it into the engine request.

## PLAN-Wrc-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Wrc-001.AA1 | `skein.spools.workflow` | Public completion behavior, request spec, and docs |
| PLAN-Wrc-001.AA2 | `skein.spools.workflow.internal.routing` | Transactional close batch includes the root context update |
| PLAN-Wrc-001.AA3 | `skein.spools.workflow.cli` | Declared `--context` input and request assembly |
| PLAN-Wrc-001.AA4 | Existing workflow test suites | Direct API, atomicity, overwrite, and CLI boundary coverage |

## PLAN-Wrc-001.P4 Contract and migration impact

- **PLAN-Wrc-001.CM1:** `complete!` and `workflow complete` gain one optional context input. Existing callers are unchanged. The workflow spool contract lives in `spools/workflow.md`; no root-spec delta is required.

## PLAN-Wrc-001.P5 Implementation phases

### PLAN-Wrc-001.PH1 Engine transaction

Outcome: direct Clojure completion validates context and writes it atomically with the step close.

### PLAN-Wrc-001.PH2 Worker surface

Outcome: the generic workflow CLI accepts the same context update through its declared parser and request spec.

### PLAN-Wrc-001.PH3 Contract and gates

Outcome: focused tests pass, the hand-written contract is current, generated API docs are refreshed, and repository quality gates pass.

## PLAN-Wrc-001.P6 Validation strategy

- **PLAN-Wrc-001.V1:** Extend the existing engine suite to prove same-batch atomicity and shallow last-write-wins replacement.
- **PLAN-Wrc-001.V2:** Extend the existing CLI suite for successful JSON context and invalid top-level JSON.
- **PLAN-Wrc-001.V3:** Run focused cold tests, `make api-docs`, and the relevant formatting, lint, reflection, and docs gates.

## PLAN-Wrc-001.P7 Risks and open questions

- **PLAN-Wrc-001.R1:** A separate root update would expose a closed step without its context. Keep both writes in one batch and assert the payload shape in a regression test.

## PLAN-Wrc-001.P8 Task context

- **PLAN-Wrc-001.TC1:** Preserve the settled shallow last-write-wins rule. Do not add producer declarations or change defer parameter behavior.
- **PLAN-Wrc-001.TC2:** Keep the generic feature disjoint from `.millstrand/workflows.clj`; land-specific adoption is a dependent card.
- **PLAN-Wrc-001.TC3:** This card changes `complete!` and `workflow complete` only. It does not add context to the convenience `advance!` wrapper.

## PLAN-Wrc-001.P9 Developer Notes

### PLAN-Wrc-001.DN1 Initial implementation pass — 2026-07-26

- The active root already owns `workflow/context`; checkpoint `:next` and `:revise` routing already consume it.

### PLAN-Wrc-001.DN2 Plan review — 2026-07-26

- Confirmed the engine, request spec, and CLI seams. The context update extends `routing/close-batch`; it is not a second mutation.
- Kept `advance!` outside the approved card's two named surfaces.
