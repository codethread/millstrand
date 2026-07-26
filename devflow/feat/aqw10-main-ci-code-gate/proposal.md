# Main CI code gate proposal

**Document ID:** `PROP-Mcg-001` **Last Updated:** 2026-07-26 **Related RFCs:** None **Related root specs:** None

## PROP-Mcg-001.P1 Problem

The repository's main-branch CI watch is an inline shell program in `.skein/workflows.clj`. It uses shell and `jq` to emulate a tuple and a stability counter even though its work is data-oriented polling. The shell gate also owns a working-directory attribute that must not be lost when the watch moves in-process.

## PROP-Mcg-001.P2 Goals

- **PROP-Mcg-001.G1:** Run the main-branch CI watch as a workflow `:code` gate with poured parameters and explicit subprocess working directories.
- **PROP-Mcg-001.G2:** Preserve the existing polling, two-poll stabilization, failure, and timeout behavior.
- **PROP-Mcg-001.G3:** Activate the code executor only after the workspace workflow module has loaded the named watch function.

## PROP-Mcg-001.P3 Non-goals

- **PROP-Mcg-001.NG1:** Convert the feature-branch CI watch, whose long-lived child process requires the shell executor's process-tree cleanup.
- **PROP-Mcg-001.NG2:** Change the shipped code executor contract or workflow engine.
- **PROP-Mcg-001.NG3:** Validate the watch against a real GitHub main-branch run outside the coordinator land workflow.

## PROP-Mcg-001.P4 Proposed scope

- **PROP-Mcg-001.S1:** Delete `main-ci-watch-script` and replace it with a qualified Clojure function used by the `main-ci-green` gate.
- **PROP-Mcg-001.S2:** Pour the worktree into `code/params`, and run every `git` and `gh` subprocess from that directory.
- **PROP-Mcg-001.S3:** Add the workspace code-executor module after `:workflows` and prove cold activation in an isolated workspace.
- **PROP-Mcg-001.S4:** Add deterministic tests with fake `git` and `gh` programs for polling, stabilization, unsuccessful conclusions, timeout interruption, and working-directory selection.
- **PROP-Mcg-001.S5:** Pass the focused cold tests for every touched namespace. Warm test output does not satisfy this feature's acceptance gate.

## PROP-Mcg-001.P5 Open questions

None. The epic and feature card settle the executor choice, polling behavior, load order, and validation boundary.
