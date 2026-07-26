# Code workflow executor plan

**Document ID:** `PLAN-Cwe-001`
**Feature:** `fbqbc`
**Proposal:** [`proposal.md`](./proposal.md)
**RFC:** None
**Root specs:** [`alpha-surface.md`](../../specs/alpha-surface.md)
**Feature specs:** [`specs/alpha-surface.delta.md`](./specs/alpha-surface.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-07-26

## PLAN-Cwe-001.P1 Goal and scope

Ship the reference `:code` workflow executor described by the proposal and feature delta,
including its runtime lifecycle, bounded execution semantics, tests, and contract surfaces.

## PLAN-Cwe-001.P2 Approach

- **PLAN-Cwe-001.A1:** Add a sibling executor module rather than extracting a shared shell
  abstraction. The modules share workflow and runtime APIs, but their pool, claim, timeout,
  and terminal-outcome semantics differ.
- **PLAN-Cwe-001.A2:** Validate persisted request attributes at execution and resolve the
  function Var through the runtime spool classloader. Offer a start-gated task to the fixed
  zero-queue executor; after the pool accepts it, stamp the token claim and release the task
  to run. Rejection leaves the gate unstamped for a later scan.
- **PLAN-Cwe-001.A3:** Schedule timeout handling separately from worker capacity. Every
  terminal path re-reads the claim token before writing, so timeout and late completion race
  safely.

## PLAN-Cwe-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Cwe-001.AA1 | `spools/workflow` | New executor module and generated API surface |
| PLAN-Cwe-001.AA2 | `test/skein/spools/executors` | Executable contract tests |
| PLAN-Cwe-001.AA3 | `spools/executors` | Human-facing executor contract |
| PLAN-Cwe-001.AA4 | `devflow/specs` and spool catalogue | Reference-spool enumeration |

## PLAN-Cwe-001.P4 Contract and migration impact

- **PLAN-Cwe-001.CM1:** Adds the `code/*` attribute namespace and `:code` executor kind. No
  existing executor behavior or persisted schema changes.

## PLAN-Cwe-001.P5 Implementation phases

### PLAN-Cwe-001.PH1 Runtime module

Outcome: a loadable executor that validates, resolves, dispatches, times out, and records
token-guarded terminal outcomes.

### PLAN-Cwe-001.PH2 Executable contract

Outcome: focused tests prove request validation, outcomes, resolution, concurrency, timeout,
attention, contribution, reconcile, and removal behavior.

### PLAN-Cwe-001.PH3 Published surface

Outcome: contract docs, generated API docs, catalogue metadata, and the root-spec enumeration
agree with the executable behavior.

## PLAN-Cwe-001.P6 Validation strategy

- **PLAN-Cwe-001.V1:** Run the cold code-executor test namespace, then the repository format,
  lint, reflection, docs, smoke, spool-suite, Go, and locked full-suite gates required for
  landing.
- **PLAN-Cwe-001.V2:** Use deterministic coordination for pool and late-completion tests;
  wall-clock waiting is limited to the timeout behavior under test.

## PLAN-Cwe-001.P7 Risks and open questions

- **PLAN-Cwe-001.R1:** A timed-out invocation may ignore interruption. The fixed pool bounds
  the leak, saturation stays visible, and token checks prevent abandoned code from publishing
  an outcome.
- **PLAN-Cwe-001.R2:** Module refresh preserves spool state. Version the state shape and close
  both executors when replacement or removal reinitializes it.

## PLAN-Cwe-001.P8 Task context

- **PLAN-Cwe-001.TC1:** Card `fbqbc` is the authority for fixed pool size, no-queue
  saturation, claim tokens, nil results, timeout behavior, reload asymmetry, and documentation
  ownership.

## PLAN-Cwe-001.P9 Developer Notes

### PLAN-Cwe-001.DN1 Initial slice — 2026-07-26

- Keep the shell and code modules separate. Their common-looking scan mechanics encode
  materially different dispatch guarantees.
