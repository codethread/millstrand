# Task 2: Author skein.macros.queries defquery

**Document ID:** `TASK-Srm-002`

## TASK-Srm-002.P1 Scope

Type: AFK

Add `skein.macros.queries` beside `skein.macros.patterns`: a `defquery` macro that fuses a named query's var, docstring, its conventions usage string, and its `:where`/params data into one block, remembering it per-namespace for an `install-queries!` that registers it through the blessed query API. Mirror the `defpattern` remember-then-install shape exactly. Do not convert any config file — that is task 5.

## TASK-Srm-002.P2 Must implement exactly

- **TASK-Srm-002.MI1:** New file `.skein/spools/macros/src/skein/macros/queries.clj`, namespace `skein.macros.queries` with an `ns` docstring. Model structure on `.skein/spools/macros/src/skein/macros/patterns.clj`: a `defonce ^:private` registry atom, a `remember-query!` fn, an `install-queries!` fn (no-arg uses `(ns-name *ns*)`, plus an explicit-`ns-sym` arity), and the `defquery` macro.
- **TASK-Srm-002.MI2:** `defquery` signature carries a name symbol, a docstring, an options map, and the query definition (a `:where` vector or a full `{:params [...] :where [...]}` map). The options map carries at least `:usage` (the `strand ... --query <name>` string used by `devflow-conventions`). It expands to a real top-level `def` of the query var and a `remember-query!` call recording `{:name <registered-name> :query <def> :usage <usage> :doc <docstring>}`.
- **TASK-Srm-002.MI3:** Choose the var/registration naming so a converted `config.clj` keeps its current registered query names (`feature-active`, `work`, ...) AND its current var names where another form reads them — specifically `branches-op` reads the `work-query` var. Document the chosen convention in the macro docstring. The registered name passed to `register-query!` must be a simple symbol matching today's names.
- **TASK-Srm-002.MI4:** `install-queries!` resolves the runtime via `skein.api.current.alpha/current`, registers each remembered query through `skein.api.weaver.alpha/register-query!`, and returns registration metadata shaped like today's `register-query-map!` result (a map of registered-name to the `register-query!` return) so `config/install!` can keep its `:queries` return value. Remembering is ordered so `install-queries!` and any conventions derivation see entries in author order (task 7 depends on this).
- **TASK-Srm-002.MI5:** Expose the remembered entries for a namespace (e.g. an `installed-queries`/`remembered-queries` accessor returning ordered `{:name :usage}` data) so task 7 can derive the `devflow-conventions` `:queries` listing without re-reading source.
- **TASK-Srm-002.MI6:** Fail loudly at macroexpansion: a non-symbol name, a missing/non-string docstring, or a missing `:usage` throws an `ex-info` naming the query. Follow the `defpattern` guard style.
- **TASK-Srm-002.MI7:** Unit tests in `test/skein/macros/queries_test.clj` (register the namespace in the test runner as needed): a `defquery` block defines the expected var and remembers the entry with the right name/usage/def; `install-queries!` registers into an isolated runtime under `:publish? false` and the registered query definition round-trips; the fail-loud guards throw.

## TASK-Srm-002.P3 Done when

- **TASK-Srm-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green, including the new tests.
- **TASK-Srm-002.DW2:** The macro expands to a real, greppable `def` and a `remember-query!` call; no query registration happens at macroexpansion time (registration is deferred to `install-queries!`).
- **TASK-Srm-002.DW2a:** Duplicate-name contract (PLAN-Srm-001.V4): re-evaluating the same `defquery` replaces the remembered entry (reload-friendly, matching `defpattern`); duplicates are not a macroexpansion error.
- **TASK-Srm-002.DW3:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-002.P4 Out of scope

- **TASK-Srm-002.OS1:** Editing `config.clj` or any conventions derivation (tasks 5 and 7).
- **TASK-Srm-002.OS2:** `defop` and `defrule` (tasks 3 and 4).

## TASK-Srm-002.P5 References

- **TASK-Srm-002.REF1:** [PLAN-Srm-001.PH2](../skein-readability-macros.plan.md), PLAN-Srm-001.A1/A3, PLAN-Srm-001.TC2/TC3.
- **TASK-Srm-002.REF2:** `.skein/spools/macros/src/skein/macros/patterns.clj` (shape to mirror); `register-query!` in `src/skein/api/weaver/alpha.clj`; today's queries and `register-query-map!` in `.skein/config.clj`.
- **TASK-Srm-002.REF3:** RFC-020.P6.1 authoring sketch; RFC-020.Q3 (per-namespace remembering).
