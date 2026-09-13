# Task 1: Return-shape foundation and registry declaration

**Document ID:** `TASK-Dcr-001`

## TASK-Dcr-001.P1 Scope

Type: AFK

Execution seat: `sol-med`

Implement `PLAN-Dcr-001.PH1`: the public return-shape language and registry declaration path.

Owned files:

- `src/skein/api/return_shape/alpha.clj`
- `src/skein/api/weaver/alpha.clj`
- `test/skein/api/return_shape/alpha_test.clj`
- `test/skein/weaver_test.clj`
- `test/skein/test_runner.clj`
- `scripts/generate_api_docs.clj` and generated/indexed API-doc files required for the new public namespace

## TASK-Dcr-001.P2 Must implement exactly

- **TASK-Dcr-001.MI1:** Implement `validate!`, `explain`, and `check!` for the finite language in `DELTA-Dcr-repl-001.CC2/CC3`: scalar leaves; scalar-only `[:nullable <scalar>]`; closed maps with required, optional, and explicit extra-value shapes; and homogeneous sequential collections.
- **TASK-Dcr-001.MI2:** Reject malformed, overlapping, non-finite, recursive, predicate, coercion, default, general-union, named-reference, and invalid-nullable declarations. Produce JSON-safe explanations and path-rich mismatches carrying expected shape and actual value.
- **TASK-Dcr-001.MI3:** Extend register and replace metadata with optional `:returns`. Retain valid declarations; atomically reject malformed declarations and flat/subcommand/stream misalignment before registry mutation.
- **TASK-Dcr-001.MI4:** Do not call the evaluator from ordinary handler invocation or `:op/emit!`. Add the new test namespace to the focus-eligible runner inventory and add the public namespace to API-doc generation/indexing.

## TASK-Dcr-001.P3 Done when

- **TASK-Dcr-001.DW1:** Tests cover every scalar, nullable-scalar limits, map key modes, collections, malformed declarations, mismatch paths, JSON-safe explanation, subcommands, streams, retained metadata, and atomic register/replace failure.
- **TASK-Dcr-001.DW2:** Cold focused gate passes: `clojure -M:test skein.api.return-shape.alpha-test skein.weaver-test`.
- **TASK-Dcr-001.DW3:** `make api-docs`, `make fmt-check lint reflect-check`, and `make docs-check` pass with the generated return-shape API page committed.

## TASK-Dcr-001.P4 Out of scope

- **TASK-Dcr-001.OS1:** Help rendering, author-side op checks, production op declarations, entity construction, and runtime result checking.
- **TASK-Dcr-001.OS2:** Functions, arbitrary predicates, general unions, named references, recursive schemas, coercion, defaults, or serving-path validation.

## TASK-Dcr-001.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-001.P6 References

- **TASK-Dcr-001.REF1:** `PLAN-Dcr-001.A1-A3`, `PH1`, `V1`, `TC1-TC3`.
- **TASK-Dcr-001.REF2:** `DELTA-Dcr-repl-001.CC1-CC4`; `DELTA-Dcr-dr-001.CC1/CC2/CC4`; `DELTA-Dcr-as-001.CC1`.
- **TASK-Dcr-001.REF3:** `src/skein/api/weaver/alpha.clj`, `test/skein/weaver_test.clj`, `scripts/generate_api_docs.clj`.
