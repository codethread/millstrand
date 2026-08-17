# Land CI startup polling plan

**Document ID:** `PLAN-Lcp-001` **Feature:** `fix-land-ci-startup-poll` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** None **Feature specs:** None **Status:** Reviewed **Last Updated:** 2026-07-18

## PLAN-Lcp-001.P1 Goal and scope

Make the repo-local land gate tolerate GitHub's short check-registration delay without weakening any other CI or shell-gate failure.

## PLAN-Lcp-001.P2 Approach

- **PLAN-Lcp-001.A1:** Replace the direct feature check-watch argv with a POSIX wrapper. Capture local HEAD, poll PR head plus status rollup until they match and checks exist, then replace the wrapper process with the current `gh pr checks --watch --fail-fast` command.
- **PLAN-Lcp-001.A2:** Pass the three-minute startup budget and poll interval as trusted argv values so deterministic tests can use a zero-second interval.
- **PLAN-Lcp-001.A3:** Let failed `gh pr view` and `gh pr checks` commands propagate. Only successful metadata responses with stale head or zero checks enter the bounded retry path. A successful lookup with malformed head/count data fails before any retry.

## PLAN-Lcp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Lcp-001.AA1 | `.skein/workflows.clj` | Add the bounded feature-CI wrapper and update land guidance. |
| PLAN-Lcp-001.AA2 | `skein.config-test` | Cover delayed registration and timeout behavior with a fake `gh`. |

## PLAN-Lcp-001.P4 Contract and migration impact

- **PLAN-Lcp-001.CM1:** No public product API, persisted data, or migration changes. The repo-local land gate gains a three-minute startup wait.

## PLAN-Lcp-001.P5 Implementation phases

### PLAN-Lcp-001.PH1 Gate and regression

Outcome: the feature CI gate waits for checks at the expected HEAD, with deterministic pass and timeout coverage.

### PLAN-Lcp-001.PH2 Land

Outcome: focused and quality gates pass, review is clean, and the fix lands via the workflow it repairs.

## PLAN-Lcp-001.P6 Validation strategy

- **PLAN-Lcp-001.V1:** A fake GitHub CLI returns stale/empty metadata before checks appear; the wrapper reaches the existing watch command without a real sleep.
- **PLAN-Lcp-001.V2:** Persistent absence reaches the startup deadline and prints branch, expected HEAD, observed PR HEAD, and check count.
- **PLAN-Lcp-001.V3:** The focused config test, formatting/lint checks, and repository CI pass.
- **PLAN-Lcp-001.V4:** Coverage asserts the watch argv and proves watch failure remains a non-zero script outcome.

## PLAN-Lcp-001.P7 Risks and open questions

- **PLAN-Lcp-001.R1:** Watching checks for stale branch state could approve the wrong commit. Matching PR head to captured local HEAD before watch prevents that startup path.

## PLAN-Lcp-001.P8 Task context

- **PLAN-Lcp-001.TC1:** The shell executor must remain unchanged. Its non-zero-is-durable-error behavior is the intended generic contract.

## PLAN-Lcp-001.P9 Developer Notes

### PLAN-Lcp-001.DN1 Plan review — 2026-07-18

- Accepted malformed-metadata and watch-failure coverage findings.
- The timeout and interval are trusted constants supplied by the workflow, so the script does not add a second parser for impossible internal values.
