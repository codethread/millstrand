# Shell gate script files plan

**Document ID:** `PLAN-Wgs-001`

**Feature:** `wnwi9-shell-gate-scripts`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** None

**Root specs:** None

**Feature specs:** None

**Status:** Reviewed

**Last Updated:** 2026-07-26

**Configuration identification:** `PLAN-Wgs-001` is the first plan for the shell gate script files feature. Nested point IDs use the full document ID.

## PLAN-Wgs-001.P1 Goal and scope

Extract the feature CI watch and PR merge shell programs into executable repo configuration files without changing poured gate data or shell executor behavior. The [proposal](./proposal.md) owns the motivation and boundaries.

## PLAN-Wgs-001.P2 Approach

- **PLAN-Wgs-001.A1:** Resolve `.millstrand/scripts` once when `.millstrand/workflows.clj` loads, then slurp each program into an immutable string used by the existing gate definitions.
- **PLAN-Wgs-001.A2:** Keep positional parameters as separate `shell/argv` elements. A small helper will assemble the repeated `sh -c` prefix without interpolating values into script text.
- **PLAN-Wgs-001.A3:** Execute both extracted programs against deterministic fake `gh` commands in `skein.config-test`, alongside the existing gate-shape coverage.

## PLAN-Wgs-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Wgs-001.AA1 | `.millstrand/workflows.clj` | Load the script files and use them at the existing gate sites. |
| PLAN-Wgs-001.AA2 | `.millstrand/scripts/` | Hold the standalone feature CI watch and PR merge programs. |
| PLAN-Wgs-001.AA3 | `test/skein/config_test.clj` | Execute both extracted programs with fake GitHub CLI behavior. |

## PLAN-Wgs-001.P4 Contract and migration impact

- **PLAN-Wgs-001.CM1:** No shipped API, CLI, data, or spool contract changes. Existing runs retain the script snapshot persisted in `shell/argv`; refreshed configuration affects only later pours.

## PLAN-Wgs-001.P5 Implementation phases

### PLAN-Wgs-001.PH1 Extract and wire

Outcome: both large shell programs are executable files loaded into the unchanged gate argv contract, while the small pull script remains inline with its rationale recorded beside it.

### PLAN-Wgs-001.PH2 Execute and validate

Outcome: focused tests execute both extracted programs against fake `gh`, and the cold configuration suite passes.

## PLAN-Wgs-001.P6 Validation strategy

- **PLAN-Wgs-001.V1:** Prove the feature CI script still handles delayed registration, malformed results, command failures, and watch failures.
- **PLAN-Wgs-001.V2:** Prove the merge script handles merged, open, draft-ready recovery, invalid state, readiness failure, and merge failure paths without interpolating subject or body into shell source.
- **PLAN-Wgs-001.V3:** Run `clojure -M:test skein.config-test` cold and the relevant formatting and lint gates.

## PLAN-Wgs-001.P7 Risks and open questions

- **PLAN-Wgs-001.R1:** Resolving script paths at pour time would depend on dynamic `*file*`. Capture the directory when the workflow file loads.
- **PLAN-Wgs-001.Q1:** None.

## PLAN-Wgs-001.P8 Task context

- **PLAN-Wgs-001.TC1:** Card `wnwi9` owns the exact scope. Card `u77o8` is the design record. Keep `main-ci-watch-script` unchanged for `aqw10`.

## PLAN-Wgs-001.P9 Developer Notes

### PLAN-Wgs-001.DN1 Worktree correction — 2026-07-26

- Implementation runs in `/Users/ct/dev/worktrees/skein-wnwi9`; the canonical checkout stays on `main`.
