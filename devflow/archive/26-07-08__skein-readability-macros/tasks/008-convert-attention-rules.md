# Task 8: Convert attention.clj rules to defrule

**Document ID:** `TASK-Srm-008`

## TASK-Srm-008.P1 Scope

Type: AFK

Rewrite `attention.clj`'s chime attention rules as `defrule` blocks and register them through `install-rules!`, deleting `register-chime-rules!`. This is a data-preserving refactor: the registered chime rule keys, their handler functions, and their firing behaviour stay identical. `attention.clj` is disjoint from the config-file chain, so this slice depends only on the `defrule` macro (task 4).

## TASK-Srm-008.P2 Must implement exactly

- **TASK-Srm-008.MI1:** Add `[skein.macros.rules :refer [defrule]]` to the `attention` ns `:require`. Rewrite each rule fn as a `defrule` block carrying its docstring, arg vector, and body unchanged: `hitl-checkpoint-ready`, `agent-failure`, `treadle-error`, `kanban-started`, `kanban-completed`, `kanban-blocked`, `parked-run`.
- **TASK-Srm-008.MI2:** Keep the chime rule keys (`:hitl-checkpoint-ready`, `:agent-failure`, `:treadle-error`, `:kanban-started`, `:kanban-completed`, `:kanban-blocked`, `:parked-run`) and the `attention/<name>-rule` handler symbols exactly as today, in the same registration order.
- **TASK-Srm-008.MI3:** Leave the private helpers (`config-attr`, `failed-blocker?`, `active-descendants`, `blocking-failures`, `strand-age-ms`) and the module-level state (`parked-run-threshold-ms`, `sqlite-timestamp-formatter`, `logged-ts-parse-failures`) unchanged. Delete `register-chime-rules!`.
- **TASK-Srm-008.MI4:** In `attention/install!`, replace `(register-chime-rules!)` with an `install-rules!` call for the `attention` namespace that preserves the `:chime-rules` return value shape. Keep the `:installed`/`:namespace` entries intact.

## TASK-Srm-008.P3 Done when

- **TASK-Srm-008.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green; the startup fixture that asserts chime rules still passes with no loosened assertions.
- **TASK-Srm-008.DW2:** The registered chime rule keys, handler functions, and firing behaviour are unchanged from before the slice.
- **TASK-Srm-008.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check reflect-check` is green.
- **TASK-Srm-008.DW4:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-008.P4 Out of scope

- **TASK-Srm-008.OS1:** Any change to rule semantics or the helper/state vars — this is a relocation only.
- **TASK-Srm-008.OS2:** `config.clj` (tasks 5-7).

## TASK-Srm-008.P5 References

- **TASK-Srm-008.REF1:** [PLAN-Srm-001.PH4](../skein-readability-macros.plan.md), PLAN-Srm-001.A3, PLAN-Srm-001.CM2, PLAN-Srm-001.TC3.
- **TASK-Srm-008.REF2:** `.skein/attention.clj` rule fns, `register-chime-rules!`, and `install!`; `skein.macros.rules` from TASK-Srm-004.
