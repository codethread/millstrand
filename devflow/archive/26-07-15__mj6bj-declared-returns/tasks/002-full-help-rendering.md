# Task 2: Full help rendering

**Document ID:** `TASK-Dcr-002`

## TASK-Dcr-002.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH2`: expose the shared return explanation in full op help.

Dispatch ordering: Task 3 is blocked until this task commits because both tasks edit `test/skein/weaver_test.clj`.

Owned files:

- `src/skein/api/weaver/alpha.clj`
- `test/skein/weaver_test.clj`
- `test/skein/api/return_shape/alpha_test.clj`

## TASK-Dcr-002.P2 Must implement exactly

- **TASK-Dcr-002.MI1:** Add a JSON-safe `returns` field to `help <op>` detail when the registry entry declares `:returns`, using `skein.api.return-shape.alpha/explain` for flat, subcommand, and stream declarations.
- **TASK-Dcr-002.MI2:** Preserve compact no-argument help summaries, raw-envelope markers, and existing arg-spec rendering. Keep the Go CLI as an opaque relay with no return-shape parser or renderer.

## TASK-Dcr-002.P3 Done when

- **TASK-Dcr-002.DW1:** Full-help tests prove the flat, subcommand, and streaming projections and prove summary help and arg-spec data remain unchanged.
- **TASK-Dcr-002.DW2:** Cold focused gate passes: `clojure -M:test skein.weaver-test skein.api.return-shape.alpha-test`.
- **TASK-Dcr-002.DW3:** `make fmt-check lint reflect-check` passes.

## TASK-Dcr-002.P4 Out of scope

- **TASK-Dcr-002.OS1:** Go dispatcher changes, compact-list expansion, production op declarations, captured-value checks, or invocation-time validation.

## TASK-Dcr-002.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-002.P6 References

- **TASK-Dcr-002.REF1:** `PLAN-Dcr-001.A3`, `PH2`, `V1`.
- **TASK-Dcr-002.REF2:** `DELTA-Dcr-cli-001.CC1/CC2`; `DELTA-Dcr-dr-001.CC3`.
- **TASK-Dcr-002.REF3:** `src/skein/api/weaver/alpha.clj` built-in help implementation.
