# Task 6: Core help and batteries declarations

**Document ID:** `TASK-Dcr-006`

## TASK-Dcr-006.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH6`: declare and cover built-in help and every batteries op.

Dispatch precondition: do not start until the coordinator confirms `uson2-cli-style-guide` has landed on main and this branch has been rebased onto that main. Capture post-dispatch `:operation` where the landed rule applies.

Owned files:

- `src/skein/api/weaver/alpha.clj`
- `spools/batteries/src/skein/spools/batteries.clj`
- `test/skein/weaver_test.clj`
- `test/skein/spools/batteries_test.clj`

## TASK-Dcr-006.P2 Must implement exactly

- **TASK-Dcr-006.MI1:** Declare the built-in help op and every production batteries op with the actual post-dispatch flat, subcommand, and stream return cases.
- **TASK-Dcr-006.MI2:** Enumerate owned production registry entries by provenance, fail on missing declarations, derive all required leaves from declarations, and check one successful captured value per leaf.
- **TASK-Dcr-006.MI3:** Keep batteries `show` and `list` outputs unchanged, including timestamps and all other current fields. Declare their richer rows; do not route them through `entity-projection`.

## TASK-Dcr-006.P3 Done when

- **TASK-Dcr-006.DW1:** Every built-in help and batteries return leaf has a non-vague declaration and one checked successful representative; the provenance gate catches wholly undeclared ops.
- **TASK-Dcr-006.DW2:** Cold focused gate passes: `clojure -M:test skein.weaver-test skein.spools.batteries-test`.
- **TASK-Dcr-006.DW3:** `make fmt-check lint reflect-check` passes.

## TASK-Dcr-006.P4 Out of scope

- **TASK-Dcr-006.OS1:** Dispatch-boundary edits, output normalization, entity projection adoption, or declarations owned by another spool suite.

## TASK-Dcr-006.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-006.P6 References

- **TASK-Dcr-006.REF1:** `PLAN-Dcr-001.A4/A5/A7`, `PH6`, `V2`, `R1/R3/R4`.
- **TASK-Dcr-006.REF2:** `DELTA-Dcr-repl-001.CC7`; `spools/batteries/src/skein/spools/batteries.clj`.
