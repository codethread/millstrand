# Land CI startup polling proposal

**Document ID:** `PROP-Lcp-001` **Last Updated:** 2026-07-18 **Related RFCs:** None **Related root specs:** None; this is repo-local landing policy.

## PROP-Lcp-001.P1 Problem

The land workflow treats GitHub's short check-registration delay as a failed CI gate. `gh pr checks --watch` exits before watch mode when the pull request has no reported checks, leaving a healthy landing run stalled on `gate/error`.

## PROP-Lcp-001.P2 Goals

- **PROP-Lcp-001.G1:** Absorb the expected delay between a push and check registration.
- **PROP-Lcp-001.G2:** Keep authentication, lookup, head mismatch, CI, and command failures loud.
- **PROP-Lcp-001.G3:** Bound startup waiting at three minutes with useful timeout diagnostics.

## PROP-Lcp-001.P3 Non-goals

- **PROP-Lcp-001.NG1:** No generic retry behavior in the shell executor.
- **PROP-Lcp-001.NG2:** No change to sign-off checkpoint discovery.
- **PROP-Lcp-001.NG3:** No change to the existing 90-minute CI completion timeout.

## PROP-Lcp-001.P4 Proposed scope

- **PROP-Lcp-001.S1:** The feature-branch CI gate captures the local branch HEAD when it starts. It polls the pull request's current head and `statusCheckRollup` count. A successful lookup with a different head or zero checks does not satisfy startup; command, authentication, and lookup errors fail immediately.
- **PROP-Lcp-001.S2:** Failure to observe checks within three minutes stalls the gate with the branch, expected HEAD, last observed PR HEAD, and check count. The count is the pull request's current-head status rollup.
- **PROP-Lcp-001.S3:** Existing non-registration failures remain immediate gate failures.
- **PROP-Lcp-001.S4:** The three-minute startup deadline runs inside the existing 90-minute shell-gate timeout.
- **PROP-Lcp-001.S5:** Deterministic coverage exercises delayed registration, persistent absence, stale PR-head metadata, and immediate lookup failure.

## PROP-Lcp-001.P5 Open questions

None.
