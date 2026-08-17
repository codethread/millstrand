# Land run PR context proposal

**Document ID:** `PROP-Lrc-001` **Last Updated:** 2026-07-26 **Kanban card:** `9j332` **Related RFCs:** None **Related root specs:** None; this is repo-local land workflow policy. **Related features:** [`lrzeh`](../lrzeh/proposal.md) supplies generic context writes, [`wnwi9`](../wnwi9-shell-gate-scripts/proposal.md) supplies the file-backed merge script, and [`aqw10`](../aqw10-main-ci-code-gate/proposal.md) precedes this feature in the shared workflow and test files.

**Configuration identification:** This first version uses `PROP-Lrc-001`: document type, short name, and sequential id, with no `@1` suffix. A later externally referenced revision would append `@2`, `@3`, and so on. Nested point IDs carry the full document ID.

## PROP-Lrc-001.P1 Problem

The land workflow discovers a pull request number while opening the draft PR, but it cannot carry that value into the later merge continuation. The current instruction asks the coordinator to preserve the number in prose, while the merge gate looks the PR up by branch. That lookup is ambiguous once a branch has more than one closed or open PR.

The workflow also has no revise route at sign-off. A coordinator cannot re-pour the continuation after refreshing a corrected gate script without losing values learned earlier in the run.

## PROP-Lrc-001.P2 Goals

- **PROP-Lrc-001.G1:** Record the positive pull request number in run context when the draft-PR step closes.
- **PROP-Lrc-001.G2:** Preserve the land wrapper's kanban lane transition while recording that context.
- **PROP-Lrc-001.G3:** Make the merge continuation and shell gate address the exact pull request number.
- **PROP-Lrc-001.G4:** Reject missing or invalid pull request context during approved sign-off routing. After rejection, the old root remains active and the transient merge lock is released.
- **PROP-Lrc-001.G5:** Let sign-off revise and re-pour the land run with its context preserved.

## PROP-Lrc-001.P3 Non-goals

- **PROP-Lrc-001.NG1:** Do not change the initial land CI gate; it still watches the known branch.
- **PROP-Lrc-001.NG2:** Do not add late-bound workflow attributes or a per-step output declaration.
- **PROP-Lrc-001.NG3:** Do not change the generic workflow context contract or executor implementations.

## PROP-Lrc-001.P4 Proposed scope

- **PROP-Lrc-001.S1:** Add the declared `land complete --pr-number <int>` input, accepting positive integers only.
- **PROP-Lrc-001.S2:** Only completion of `push-draft-pr` writes `:pr-number` into `workflow/context`. The generic context channel performs its settled shallow, last-write-wins merge in the same transaction that closes the step.
- **PROP-Lrc-001.S3:** Require `:pr-number` in the merge continuation. The `merge-pr` gate uses it for the PR state check, ready operation, squash merge, and already-merged path; the initial `ci-green` gate remains branch-based.
- **PROP-Lrc-001.S4:** Add a revise choice to land sign-off, preserving the current context during re-pour.
- **PROP-Lrc-001.S5:** Cover valid propagation, lane behavior, help output, and exact merge arguments in the repo-local configuration tests. For a missing or non-positive number at approved sign-off, assert that the old root remains active and no merge lock remains.

## PROP-Lrc-001.P5 Open questions

- **PROP-Lrc-001.Q1:** None. The epic and card settle the input name, validation, overwrite behavior, and rollback contract.
