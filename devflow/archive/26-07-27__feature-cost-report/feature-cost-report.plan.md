# Feature cost report plan

**Document ID:** `PLAN-Fcr-001` **Feature:** `feature-cost-report` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** None **Feature specs:** None **Status:** Shipped **Last Updated:** 2026-07-27

## PLAN-Fcr-001.P1 Goal and scope

Remove the repo-local `feature-costs` op while retaining the same report as an ordinary reducer over the generic `subgraph` JSON envelope.

## PLAN-Fcr-001.P2 Approach

- **PLAN-Fcr-001.A1:** Move the pure aggregation into a `jq` program under the repository's report scripts. It will select `agent-run/run` strands, validate every present usage value, calculate the existing rows and rollups without rounding, and emit the existing report shape. Cost accepts a JSON number or numeric string; integer fields reject fractional values; token maps accept a JSON object or an object encoded as JSON text; timestamps must parse as ISO instants. A bad present value exits nonzero before output and reports its strand id, attribute, and value.
- **PLAN-Fcr-001.A2:** Delete the analytics workspace module after the reducer has fixture coverage. Remove its startup declaration and op-dispatch tests, then regenerate the expected workspace surface.
- **PLAN-Fcr-001.A3:** Document the pipeline from `strand subgraph` into the reducer so operator use does not depend on workspace-specific commands.

## PLAN-Fcr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Fcr-001.AA1 | `scripts/reports` | Own the report-side reducer and fixtures. |
| PLAN-Fcr-001.AA2 | `.skein` | Delete `analytics.clj` and remove its `init.clj` module declaration and file-map entries. |
| PLAN-Fcr-001.AA3 | `test/skein` | Remove analytics loader/provenance and op tests, add reducer fixtures, and update surface expectations. |
| PLAN-Fcr-001.AA4 | `docs/reference.md` | Add the generic reporting pipeline. |
| PLAN-Fcr-001.AA5 | `devflow/README.md` | Remove the stale claim that usage stamps feed the op. |

## PLAN-Fcr-001.P4 Contract and migration impact

- **PLAN-Fcr-001.CM1:** `feature-costs` disappears from `strand help`. Callers obtain the relation-scoped data with `strand subgraph` and pipe it to the checked-in reducer.
- **PLAN-Fcr-001.CM2:** The report JSON retains the old `root`, `runs`, `totals`, `by-harness`, and `missing-usage` fields. Its `operation` field is removed because the report is no longer an op response.

## PLAN-Fcr-001.P5 Implementation phases

### PLAN-Fcr-001.PH1 Report-side reducer

Outcome: a fixture-tested reducer reproduces the useful report and rejects malformed present usage values.

### PLAN-Fcr-001.PH2 Workspace surface removal

Outcome: the analytics module and `feature-costs` op are gone, with docs and surface expectations pointing to the generic read.

## PLAN-Fcr-001.P6 Validation strategy

- **PLAN-Fcr-001.V1:** Fixture tests cover exact report output, `(started-at, id)` ordering, independent timestamp bounds, absent usage, typed and string numbers, integer rejection, token maps and JSON-text token maps, malformed timestamps, invalid run markers, and missing roots. Every malformed case must exit nonzero before output and identify its offending context.
- **PLAN-Fcr-001.V2:** Cold config tests, smoke tests, formatting, lint, reflection, docs, and surface checks prove the workspace still starts and the removed op is absent.

## PLAN-Fcr-001.P7 Risks and open questions

- **PLAN-Fcr-001.R1:** `jq` number conversion can hide malformed values if the reducer uses permissive fallbacks. Present values will be parsed explicitly and errors will include the strand id, attribute name, and bad value.

## PLAN-Fcr-001.P8 Task context

- **PLAN-Fcr-001.TC1:** The existing implementation in `.skein/analytics.clj` defines the compatibility target. The generic envelope contract is `strand subgraph ROOT --relation parent-of`.

## PLAN-Fcr-001.P9 Developer Notes

### PLAN-Fcr-001.DN1 Intake — 2026-07-27

- Repository search found no automated caller of `feature-costs`; the existing consumer is operator reporting. A checked-in `jq` reducer keeps that use available without publishing another weaver surface.

### PLAN-Fcr-001.DN2 Implementation — 2026-07-27

- The reducer accepts the timestamp form emitted by agent-run records: UTC ISO instants with optional fractional seconds. The full locked suite and pinned external spool suites pass after removing the analytics module.

### PLAN-Fcr-001.DN3 Shipped — 2026-07-27

- Shipped the report-side reducer, removed the repo-local analytics module and op, and updated tests and operator guidance. No scope was cut, no root-spec delta was needed, and no RFC was archived.
