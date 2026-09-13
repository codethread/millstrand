# Stealth local workspace initialization plan

**Document ID:** `PLAN-Si-001`

**Feature:** `stealth-init`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** None

**Root specs:** [cli.md](../../specs/cli.md)

**Feature specs:** [specs/cli.delta.md](./specs/cli.delta.md)

**Status:** Reviewed

**Last Updated:** 2026-07-14

## PLAN-Si-001.P1 Goal and scope

Add the repo-only `mill init --stealth` bootstrap described by the proposal and CLI delta. Keep ordinary init and workspace identity unchanged.

## PLAN-Si-001.P2 Approach

- **PLAN-Si-001.A1:** Carry the flag over mill's internal request envelope and keep filesystem policy in the Go config package beside ordinary bootstrap.
- **PLAN-Si-001.A2:** Model marker handling as a small exact-state transition: preflight both files, create absent files, append to marker-free files, accept exact blocks including their final newline, and reject every ambiguous state before ordinary bootstrap writes.
- **PLAN-Si-001.A3:** Return typed internal action reports and render the public JSON once at the mill request boundary. Go structs own the closed keys and enum validation; a typed stealth refusal owns the structured error details.

## PLAN-Si-001.P3 Affected areas

| ID              | Area                  | Expected change                                        |
| --------------- | --------------------- | ------------------------------------------------------ |
| PLAN-Si-001.AA1 | `cli/cmd/mill`        | Flag, request routing, and public JSON result          |
| PLAN-Si-001.AA2 | `cli/internal/config` | Stealth policy, marker ownership, and tracked checks   |
| PLAN-Si-001.AA3 | `devflow/specs`       | Promote the reviewed CLI delta into the root contract  |
| PLAN-Si-001.AA4 | `docs/prime/skein.md` | Route agents to the stealth/local-spool convention     |
| PLAN-Si-001.AA5 | `docs/spools/`        | Explain the private workspace and local-spool workflow |

## PLAN-Si-001.P4 Contract and migration impact

- **PLAN-Si-001.CM1:** One additive CLI flag and one additive success-result object. No config format, storage, workspace identity, or migration changes.

## PLAN-Si-001.P5 Implementation phases

### PLAN-Si-001.PH1 Bootstrap behavior

Outcome: the flag, marker transitions, tracked-file policy, and exact JSON shape are covered by focused Go tests.

### PLAN-Si-001.PH2 Contract and guidance

Outcome: the CLI root spec and user docs describe the shipped mode and local spool convention, with normal quality gates green.

## PLAN-Si-001.P6 Validation strategy

- **PLAN-Si-001.V1:** Run focused Go package tests while iterating, then `(cd cli && go test ./...)` and the repository quality gates.
- **PLAN-Si-001.V2:** Exercise repeat stealth init in a temporary Git repository through the existing mill integration harness, assert the exact success and refusal schemas, and prove rejected preflights make no file changes.

## PLAN-Si-001.P7 Risks and open questions

- **PLAN-Si-001.R1:** Marker code could overwrite user prose. Exact-body comparison and loud rejection prevent that.
- **PLAN-Si-001.R2:** Stealth setup could claim success for tracked content. Check tracked `.skein` before bootstrap and report tracked Claude guidance without changing it.

No open questions block implementation.

## PLAN-Si-001.P8 Task context

- **PLAN-Si-001.TC1:** The nearest tests already use temporary repositories and real files. Reuse them; do not add mocks, sleeps, or a second fixture layer.

## PLAN-Si-001.P9 Developer Notes

None yet.
