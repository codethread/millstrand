# Main CI code gate plan

**Document ID:** `PLAN-Mcg-001` **Feature:** `aqw10-main-ci-code-gate` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** None **Feature specs:** None **Status:** Reviewed **Last Updated:** 2026-07-26

## PLAN-Mcg-001.P1 Goal and scope

Replace the repository's inline main CI shell watch with a code gate while preserving its polling contract, explicit repository selection, and coordinator-only landing boundary.

## PLAN-Mcg-001.P2 Approach

- **PLAN-Mcg-001.A1:** Put the public gate function in the workspace workflow namespace so the persisted `code/fn` symbol resolves through the runtime spool classloader.
- **PLAN-Mcg-001.A2:** Keep subprocess execution at the boundary. Parse the `gh` JSON response into Clojure data, classify run states in pure helpers, and let an interruptible poll loop own stabilization. If interruption lands while a child is active, terminate and join that process before propagating the interrupt.
- **PLAN-Mcg-001.A3:** Pass the worktree in the poured parameter map and set it as the directory of each subprocess. Do not mutate process-wide working-directory state.
- **PLAN-Mcg-001.A4:** Declare the code executor module after `:workflows`. Its first scan can then resolve the function named by a ready gate.

## PLAN-Mcg-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Mcg-001.AA1 | `.skein/workflows.clj` | Replace the script and shell attributes with a public code-gate function and poured parameters. |
| PLAN-Mcg-001.AA2 | `.skein/init.clj` | Activate the code executor after the workflow definitions. |
| PLAN-Mcg-001.AA3 | `test/skein/config_test.clj` | Exercise the watch with fake commands and prove cold startup ordering. |

## PLAN-Mcg-001.P4 Contract and migration impact

- **PLAN-Mcg-001.CM1:** Existing poured land runs retain their frozen shell gate. New runs poured after workspace refresh use the code gate.
- **PLAN-Mcg-001.CM2:** No shipped API, CLI, attribute, or root-spec contract changes.

## PLAN-Mcg-001.P5 Implementation phases

### PLAN-Mcg-001.PH1 Watch function and workflow migration

Outcome: the main CI gate invokes a qualified Clojure function with explicit worktree parameters, and the inline shell script is gone.

### PLAN-Mcg-001.PH2 Activation and deterministic acceptance

Outcome: repository startup activates the executor in dependency order, and focused tests cover polling, stabilization, failure, interruption, timeout, and cwd behavior.

## PLAN-Mcg-001.P6 Validation strategy

- **PLAN-Mcg-001.V1:** Use fake `git` and `gh` commands with recorded invocations and scripted responses. Run the watch from a process cwd unrelated to the supplied worktree.
- **PLAN-Mcg-001.V2:** Prove two consecutive all-green polls are required, success returns the existing run-count summary as `code/result`, an unsuccessful conclusion fails with the run listing in `gate/error`, and interruption terminates and joins an active child.
- **PLAN-Mcg-001.V3:** Start an isolated copied workspace with a ready `main-ci-green` gate and prove cold activation resolves the exact `workflows/main-ci-watch` Var after the workflows module loads.
- **PLAN-Mcg-001.V4:** Run focused cold Clojure tests for every touched namespace, followed by the repository's landing acceptance gates.

## PLAN-Mcg-001.P7 Risks and open questions

- **PLAN-Mcg-001.R1:** A process helper that hides cwd or interruption behavior could silently watch the wrong repository or delay timeout. Keep both explicit in the gate function and assert them at the command boundary.
- **PLAN-Mcg-001.R2:** Test sleeps could make the poll contract slow or flaky. Inject the poll interval or synchronization boundary so tests coordinate deterministically.

No open questions block implementation.

## PLAN-Mcg-001.P8 Task context

- **PLAN-Mcg-001.TC1:** The authoritative scope is card `aqw10`; cite symbols and paths rather than source line numbers.
- **PLAN-Mcg-001.TC2:** `feature-ci-watch` remains a shell gate because it owns a long-lived child that must be killed as a process tree.
- **PLAN-Mcg-001.TC3:** Real-main validation belongs to the coordinator land run after merge.

## PLAN-Mcg-001.P9 Developer Notes

### PLAN-Mcg-001.DN1 Planning — 2026-07-26

- Both prerequisite features are closed on `main`: the code executor and extracted shell scripts.
