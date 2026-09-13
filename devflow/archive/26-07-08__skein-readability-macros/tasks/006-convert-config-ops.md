# Task 6: Convert config.clj ops to defop

**Document ID:** `TASK-Srm-006`

## TASK-Srm-006.P1 Scope

Type: AFK

Rewrite `config.clj`'s CLI op family as `defop` blocks and register them through `install-ops!`, deleting the `install!` op vector and the `op-metadata` helper. This is the largest readability gap and a data-preserving refactor: every registered op name, handler symbol, op metadata, and generated `help <op>` stays byte-identical. The `devflow-conventions` `:ops` listing stays hand-authored in this slice; task 7 derives it.

## TASK-Srm-006.P2 Must implement exactly

- **TASK-Srm-006.MI1:** Add `[skein.macros.ops :refer [defop]]` to the `config` ns `:require`. Rewrite each op currently in the `install!` op vector as a `defop` block that fuses its handler body, its arg-spec, and the conventions metadata for that op: `current-dags`, `branches`, `carder-report`, `devflow-start`, `devflow-next`, `devflow-choices`, `devflow-choose`, `devflow-complete`, `devflow-advance`, `devflow-describe`, `devflow-history`, `devflow-archive`, `devflow-status`, `workflow-runs`, `devflow-conventions`, `flow-await`, `flow-status`, `hitl`.
- **TASK-Srm-006.MI2:** Keep every registered op name and `config/<name>-op` handler symbol unchanged. The `defop`-generated handler var must be `<name>-op`. Arg-specs may stay named `^:private` vars referenced by `:arg-spec`, or move inline into the `defop` options (either is allowed per RFC-020.Q1); whichever is chosen, the registered arg-spec must be identical to today's.
- **TASK-Srm-006.MI3:** Preserve op-specific metadata: `flow-await` keeps `:deadline-class :unbounded`. Any op metadata `register-op!` receives today must be reproduced through the `defop` options.
- **TASK-Srm-006.MI4:** Keep the private helper fns the handlers use (`require-non-blank!`, `parse-json-object-arg`, `pop-step-selector`, `parse-advance-tail`, `checkpoint-ready?`, `hitl-prompt`, `op-metadata`'s callers, the carder/kanban helpers) as-is unless a helper becomes dead once the op vector is gone. Delete `op-metadata` and the `install!` op vector once every op registers through `install-ops!`.
- **TASK-Srm-006.MI5:** In `config/install!`, replace the `:ops [...]` vector with an `install-ops!` call that returns the same `:ops` value shape (a vector of `register-op!` returns). Keep the `:queries`/`:installed`/`:namespace` return entries intact.
- **TASK-Srm-006.MI6:** Do not change `devflow-conventions-op` in this slice beyond what MI1 requires to author it as a `defop` (its handler body stays the current hand-authored map). The `:ops`/`:queries` listings it returns stay hand-authored here and must render byte-identical; task 7 replaces the mechanical listings with a derivation.

## TASK-Srm-006.P3 Done when

- **TASK-Srm-006.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green; `config_test` still passes with no loosened assertions.
- **TASK-Srm-006.DW2:** Registered op names, handler symbols, op metadata (including `flow-await`'s deadline class), and generated `help <op>` for every op are unchanged from before the slice. `devflow-conventions` output is unchanged.
- **TASK-Srm-006.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check reflect-check` is green.
- **TASK-Srm-006.DW4:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-006.P4 Out of scope

- **TASK-Srm-006.OS1:** Deriving the `devflow-conventions` `:ops`/`:queries` listings from remembered entries (task 7).
- **TASK-Srm-006.OS2:** Any change to op semantics, arg-spec shape, or handler behaviour — this is a relocation only.
- **TASK-Srm-006.OS3:** `attention.clj` (task 8).

## TASK-Srm-006.P5 References

- **TASK-Srm-006.REF1:** [PLAN-Srm-001.PH3](../skein-readability-macros.plan.md), PLAN-Srm-001.A3/A4, PLAN-Srm-001.CM2, PLAN-Srm-001.TC3, PLAN-Srm-001.R2.
- **TASK-Srm-006.REF2:** `.skein/config.clj` op handlers, arg-specs, `op-metadata`, and the `install!` op vector; `register-op!` in `src/skein/api/weaver/alpha.clj`; `skein.macros.ops` from TASK-Srm-003.
- **TASK-Srm-006.REF3:** RFC-020.P4 (ops are the largest gap); RFC-020.REC4 (data-preserving).
