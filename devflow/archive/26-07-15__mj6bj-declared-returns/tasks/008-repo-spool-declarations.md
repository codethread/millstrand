# Task 8: Repo-local spool declarations

**Document ID:** `TASK-Dcr-008`

## TASK-Dcr-008.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH8`: declare and cover delegation and bench op returns.

Dispatch precondition: do not start until the coordinator confirms `uson2-cli-style-guide` has landed on main and this branch has been rebased onto that main. Capture post-dispatch `:operation` where the landed rule applies.

Owned files:

- `spools/delegation/src/skein/spools/delegation.clj`
- `spools/bench/src/skein/spools/bench.clj`
- `test/skein/delegation_test.clj`
- `test/skein/bench_test.clj`
- generated spool API references if public docstrings change

## TASK-Dcr-008.P2 Must implement exactly

- **TASK-Dcr-008.MI1:** Declare every delegation `agent` subcommand and every bench subcommand with its actual post-dispatch return case.
- **TASK-Dcr-008.MI2:** In both owner suites, enumerate production ops by provenance, fail on missing declarations, derive required leaves from declarations, and check one successful captured value per leaf.
- **TASK-Dcr-008.MI3:** Preserve agent `ps` as its domain-specific summary. Do not normalize agent-run storage, workflow payloads, or polymorphic subcommand results to the four-field entity shape.
- **TASK-Dcr-008.MI4:** Use one closed or deliberately open shape per subcommand and reserve `:json` for genuinely dynamic leaves.

## TASK-Dcr-008.P3 Done when

- **TASK-Dcr-008.DW1:** Every delegation and bench production return leaf has a useful declaration and checked successful representative; `agent ps` retains all current domain fields.
- **TASK-Dcr-008.DW2:** Cold focused gate passes: `clojure -M:test skein.delegation-test skein.bench-test`.
- **TASK-Dcr-008.DW3:** `make fmt-check lint reflect-check` passes. If public docstrings change, `make api-docs` and `make docs-check` also pass and generated changes are committed.

## TASK-Dcr-008.P4 Out of scope

- **TASK-Dcr-008.OS1:** Dispatch-boundary edits, agent-run result delivery, storage normalization, workflow payload changes, or declarations owned by other suites.

## TASK-Dcr-008.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-008.P6 References

- **TASK-Dcr-008.REF1:** `PLAN-Dcr-001.A4/A7`, `PH8`, `V2`, `R1/R3/R4`.
- **TASK-Dcr-008.REF2:** `PROP-Dcr-001.NG5`; `DELTA-Dcr-repl-001.CC7`.
- **TASK-Dcr-008.REF3:** Delegation and bench source and owner suites listed above.
