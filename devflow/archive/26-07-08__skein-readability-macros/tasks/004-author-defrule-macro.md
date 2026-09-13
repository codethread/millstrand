# Task 4: Author skein.macros.rules defrule

**Document ID:** `TASK-Srm-004`

## TASK-Srm-004.P1 Scope

Type: AFK

Add `skein.macros.rules`: a `defrule` macro that fuses a chime attention rule's function and its chime registration into one block, remembering it per-namespace for an `install-rules!` that registers it through `chime/defrule!`. Mirror the `defpattern` remember-then-install shape. Do not convert `attention.clj` — that is task 8.

## TASK-Srm-004.P2 Must implement exactly

- **TASK-Srm-004.MI1:** New file `.skein/spools/macros/src/skein/macros/rules.clj`, namespace `skein.macros.rules` with an `ns` docstring. Same structural shape as `patterns.clj`: `defonce ^:private` registry atom, `remember-rule!`, `install-rules!` (no-arg and explicit-`ns-sym` arities), and the `defrule` macro.
- **TASK-Srm-004.MI2:** `defrule` signature carries a name symbol, a docstring, the rule arg vector, and the rule body. It expands to a real top-level `(defn <name>-rule <docstring> <argv> <body...>)` plus a `remember-rule!` recording the chime rule key (a keyword from the name, e.g. `agent-failure` -> `:agent-failure`) and the fully-qualified handler symbol (`<current-ns>/<name>-rule`). The handler var name must be `<name>-rule` so today's rule fns (`attention/agent-failure-rule`, ...) are unchanged.
- **TASK-Srm-004.MI3:** `install-rules!` registers each remembered rule through `skein.spools.chime/defrule!` with the same keyword and quoted fully-qualified symbol pairs `attention.clj` passes today, and returns a value shaped like today's `register-chime-rules!` result (a vector of the `defrule!` returns) so `attention/install!` keeps its `:chime-rules` return. Preserve today's registration order.
- **TASK-Srm-004.MI4:** Fail loudly at macroexpansion on a non-symbol name or a missing/non-string docstring, throwing an `ex-info` naming the rule. Follow the `defpattern` guard style. Duplicate-name contract (PLAN-Srm-001.V4): re-evaluating the same `defrule` replaces the remembered entry (reload-friendly); duplicates are not a macroexpansion error.
- **TASK-Srm-004.MI5:** Unit tests in `test/skein/macros/rules_test.clj`: a `defrule` block defines the `<name>-rule` var and remembers the entry with the right keyword and fully-qualified symbol; `install-rules!` calls `chime/defrule!` for each remembered rule (assert against an isolated `:publish? false` runtime with chime installed, or a stubbed `defrule!` seam); the guards throw.

## TASK-Srm-004.P3 Done when

- **TASK-Srm-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green, including the new tests.
- **TASK-Srm-004.DW2:** The macro expands to a real, greppable `(defn <name>-rule ...)`; the symbol remembered for registration is fully qualified to the calling namespace.
- **TASK-Srm-004.DW3:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-004.P4 Out of scope

- **TASK-Srm-004.OS1:** Editing `attention.clj` or deleting `register-chime-rules!` (task 8).
- **TASK-Srm-004.OS2:** `defquery` and `defop` (tasks 2 and 3).

## TASK-Srm-004.P5 References

- **TASK-Srm-004.REF1:** [PLAN-Srm-001.PH2](../skein-readability-macros.plan.md), PLAN-Srm-001.A1/A3, PLAN-Srm-001.TC2/TC3.
- **TASK-Srm-004.REF2:** `.skein/spools/macros/src/skein/macros/patterns.clj`; today's rule fns and `register-chime-rules!` in `.skein/attention.clj`; `skein.spools.chime/defrule!`.
- **TASK-Srm-004.REF3:** RFC-020.P6.1 authoring sketch; RFC-020.Q3 (per-namespace remembering).
