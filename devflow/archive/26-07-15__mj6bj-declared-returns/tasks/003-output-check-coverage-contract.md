# Task 3: Author-side output check and coverage contract

**Document ID:** `TASK-Dcr-003`

## TASK-Dcr-003.P1 Scope

Type: AFK

Execution seat: `sol-med`

Implement `PLAN-Dcr-001.PH3`: the captured-value check and exact CI return-leaf coverage contract.

Dispatch precondition: Tasks 1 and 2 are committed. Task 2 owns the earlier mutation of `test/skein/weaver_test.clj`; begin this task from that committed result.

Owned files:

- `src/skein/test/alpha.clj`
- `test/skein/test/alpha_test.clj`
- `test/skein/weaver_test.clj`
- generated `skein.test.alpha` API reference if its public docstrings change

## TASK-Dcr-003.P2 Must implement exactly

- **TASK-Dcr-003.MI1:** Add `skein.test.alpha/check-op-return!`. Given an explicit runtime, operation, optional subcommand or stream channel, and captured value, select the registered flat, subcommand, `:emits`, or `:result` declaration and delegate to `return-shape/check!`.
- **TASK-Dcr-003.MI2:** Return the checked value on success. On mismatch, report operation, selected declaration, path, and actual value through ordinary `clojure.test` failure/error behavior.
- **TASK-Dcr-003.MI3:** Establish the owner-suite coverage pattern in tests: enumerate production registry entries by spool provenance, fail on every missing `:returns`, derive required leaves from declarations, and require one checked successful representative per leaf. Do not define the must-declare set with a parallel op/leaf list.
- **TASK-Dcr-003.MI4:** Keep the helper narrow: it must not invoke ops, discover fixtures, start a CLI, add an assertion DSL, or wrap spool activation.

## TASK-Dcr-003.P3 Done when

- **TASK-Dcr-003.DW1:** Tests cover flat, subcommand, emitted-item, terminal-result, absent/misaligned declaration, success identity, and structured mismatch diagnostics, plus provenance-derived missing-declaration and unchecked-leaf failures.
- **TASK-Dcr-003.DW2:** Cold focused gate passes: `clojure -M:test skein.test.alpha-test skein.weaver-test`.
- **TASK-Dcr-003.DW3:** `make api-docs`, `make fmt-check lint reflect-check`, and `make docs-check` pass if public docstrings change; generated API changes are committed.

## TASK-Dcr-003.P4 Out of scope

- **TASK-Dcr-003.OS1:** Production op declaration sweeps, generic op invocation, a general assertion DSL, or live serving-path checks.

## TASK-Dcr-003.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-003.P6 References

- **TASK-Dcr-003.REF1:** `PLAN-Dcr-001.A4`, `PH3`, `V2`, `TC4`.
- **TASK-Dcr-003.REF2:** `DELTA-Dcr-repl-001.CC5-CC7`.
- **TASK-Dcr-003.REF3:** `src/skein/test/alpha.clj`, `test/skein/test/alpha_test.clj`.
