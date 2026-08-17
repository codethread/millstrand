# Agent-run clock-aware awaiting plan

**Document ID:** `PLAN-Arc-001` **Feature:** `5hzoe-agent-run-clock` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** [REPL API](../../specs/repl-api.md) (`SPEC-003.C17b`) and [Weaver Runtime](../../specs/daemon-runtime.md) (`SPEC-004.C1a`) **Feature specs:** None **Status:** Reviewed **Last Updated:** 2026-07-21

## PLAN-Arc-001.P1 Goal and scope

Move the external agent-run root onto Skein's established Clock-based polling contract, then release the repository and update Skein's consumer pin. The intended behavior is defined in [the proposal](./proposal.md).

## PLAN-Arc-001.P2 Approach

- `PLAN-Arc-001.A1` replaces the local deadline/sleep loop in `await-runs` with `poll-until!`, passing the runtime Clock at the function's explicit runtime boundary.
- `PLAN-Arc-001.A2` preserves the terminal result maps, keeps the 250 ms poll cadence, and runs the interactive-session supervision probe on every eighth poll.
- `PLAN-Arc-001.A3` proves timeout behavior with the existing manual Clock, releases `agent-harness.spool` as the next annotated tag, and changes Skein's tag/SHA pair together.

## PLAN-Arc-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| `PLAN-Arc-001.AA1` | `agent-run/src/ct/spools/agent_run.clj` | Use the runtime Clock through `poll-until!`. |
| `PLAN-Arc-001.AA2` | `test/ct/spools/agent_run_test.clj` | Add deterministic timeout coverage. |
| `PLAN-Arc-001.AA3` | `.skein/spools.edn` | Pin the next agent-harness release tag and peeled SHA. |

## PLAN-Arc-001.P4 Contract and migration impact

- `PLAN-Arc-001.CM1` keeps the public `await-runs` arguments and result shape unchanged. The internal waiting mechanism now uses the already-published Clock and polling APIs.

## PLAN-Arc-001.P5 Implementation phases

### PLAN-Arc-001.PH1 Clock-aware await

Outcome: `await-runs` obtains time and sleeps from the runtime Clock while preserving session supervision.

### PLAN-Arc-001.PH2 Release and consumer update

Outcome: the tagged `agent-harness.spool` release is pinned by its peeled SHA in Skein.

## PLAN-Arc-001.P6 Validation strategy

- `PLAN-Arc-001.V1` runs the agent-harness Clojure suite and checks formatting and linting.
- `PLAN-Arc-001.V2` verifies the release with `git rev-parse 'vN^{}'`, uses that peeled SHA for the consumer pin, and runs Skein's spool-suite gate after the tag/SHA update.

## PLAN-Arc-001.P7 Risks and open questions

- `PLAN-Arc-001.R1` Manual clocks only advance when the polling helper sleeps. The helper's positive 250 ms cadence preserves timeout progress.

## PLAN-Arc-001.P8 Task context

- `PLAN-Arc-001.TC1` The repository is one release unit: tag `agent-harness.spool`, then set both `:git/tag` and `:git/sha` under `ct.spools/agent-run` in `.skein/spools.edn`.

## PLAN-Arc-001.P9 Developer Notes

### PLAN-Arc-001.DN1 Implementation and proposal review — 2026-07-21

- The change and deterministic timeout test were implemented before the plan was recorded. Review confirmed the eighth-poll supervision probe remains and clarified repository-level release terminology.
