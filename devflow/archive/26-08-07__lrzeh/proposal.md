# Workflow run context completion proposal

**Document ID:** `PROP-Wrc-001` **Last Updated:** 2026-07-26 **Related RFCs:** None **Related root specs:** `devflow/specs/repl-api.md` (`SPEC-003.C63f`, `SPEC-003.C63g`; context only, no spec delta)

## PROP-Wrc-001.P1 Problem

Workflow steps cannot pass values learned during a run to a later checkpoint continuation. Callers can attach attributes to the completed step, but routing reads the run root's `workflow/context`.

## PROP-Wrc-001.P2 Goals

- **PROP-Wrc-001.G1:** Let a caller merge JSON-safe values into run context while completing a step.
- **PROP-Wrc-001.G2:** Make the context update atomic with the step close.
- **PROP-Wrc-001.G3:** Expose the same behavior through the trusted Clojure API and the generic workflow CLI.

## PROP-Wrc-001.P3 Non-goals

- **PROP-Wrc-001.NG1:** Do not add per-step output declarations.
- **PROP-Wrc-001.NG2:** Do not change defer target parameter behavior.
- **PROP-Wrc-001.NG3:** Do not change repository-specific workflows or executors.

## PROP-Wrc-001.P4 Proposed scope

- **PROP-Wrc-001.S1:** `complete!` accepts an optional context map and shallow-merges it over the active run root's existing context.
- **PROP-Wrc-001.S2:** Existing keys use last-write-wins semantics, including nested values.
- **PROP-Wrc-001.S3:** The workflow CLI accepts a JSON object for the same context write.
- **PROP-Wrc-001.S4:** Invalid context fails before the step or root changes.

## PROP-Wrc-001.P5 Open questions

None. The feature card records the settled merge and validation semantics.
