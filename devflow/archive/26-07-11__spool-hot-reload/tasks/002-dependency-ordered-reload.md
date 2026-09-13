# Task 2: Dependency-ordered reload via tools.namespace

**Document ID:** `TASK-shr-002`

## TASK-shr-002.P1 Scope

Type: AFK

Make `reload-synced-spool!` reload the root's namespaces in intra-root dependency order (dependencies first) instead of the provisional order from Task 1, so a bumped cross-namespace macro is live for its consumers. Add `org.clojure/tools.namespace` to the weaver `deps.edn` for its battle-tested `ns`-form parser and dependency topo-sort.

## TASK-shr-002.P2 Must implement exactly

- **TASK-shr-002.MI1:** Add `org.clojure/tools.namespace` to the weaver's own `deps.edn` (NOT a spool dep). Confirmed absent at authoring, so this is a real dependency add.
- **TASK-shr-002.MI2:** In `reload-synced-spool!`, parse each discovered source's `ns` form with `org.clojure/tools.namespace` and topologically sort _the namespaces within this root only_. External requires (`clojure.*`, blessed `skein.api.*`, other spools) are edges out of the set and are NOT reloaded. Reload the intra-root namespaces dependencies-first.
- **TASK-shr-002.MI3:** Drive the reload with `load-file` on each located source path under `with-spool-classloader` (unchanged from Task 1). Do NOT use tools.namespace `refresh` (classloader-blind to spool roots — the exact `require :reload` failure mode) and do NOT use `require :reload-all` (reloads transitive non-spool deps). Only tools.namespace's parse + dependency API is used.
- **TASK-shr-002.MI4:** The result map's namespace ordering reflects the dependency-ordered reload order.
- **TASK-shr-002.MI5:** Add cold-focused tests in `test/skein/spools_test.clj`:
    - **Dependency order** (`PLAN-shr-001.V3`): two namespaces in one root, `a` `:require`s and uses a macro from `b`; bump `b`'s macro, `reload-synced-spool!`, assert `a` reflects the new expansion — proving `b` reloads before `a` and that arbitrary order would fail.
    - **Keystone regression** (`PLAN-shr-001.V2`, against the core seam): sync + load a spool whose fn returns `:v1`; rewrite the source to `:v2`; assert `runtime/reload!` and a bare `(require ns :reload)` still see `:v1`; then `reload-synced-spool!` and assert `:v2` plus the result naming the reloaded namespace.

## TASK-shr-002.P3 Done when

- **TASK-shr-002.DW1:** `clojure -M:test skein.spools-test` passes cold, including the dependency-order and keystone-regression tests.
- **TASK-shr-002.DW2:** `make fmt-check lint reflect-check` reports zero findings.
- **TASK-shr-002.DW3:** `git status --short` shows only the intended `deps.edn`, `spool_sync.clj`, and `spools_test.clj` changes (no generated SQLite/runtime artifacts).

## TASK-shr-002.P4 Out of scope

- **TASK-shr-002.OS1:** The blessed `skein.api.runtime.alpha/reload-spool!` verb and its docstring (Task 3).
- **TASK-shr-002.OS2:** tools.namespace `refresh`-style dependency-tracked _unload_ of removed namespaces (PROP-shr-001.NG2/DL5).
- **TASK-shr-002.OS3:** Reloading external (non-intra-root) requires.

## TASK-shr-002.P5 References

- **TASK-shr-002.REF1:** `PLAN-shr-001.PH2` (dependency-ordered reload), `PLAN-shr-001.A4`, `PLAN-shr-001.AA4`, `PLAN-shr-001.V2`/`V3`, `PLAN-shr-001.R1`/`R2` — [../spool-hot-reload.plan.md](../spool-hot-reload.plan.md).
- **TASK-shr-002.REF2:** `PROP-shr-001.C3` (reload order rationale) and `PROP-shr-001.DL3` (why tools.namespace, not hand-rolled parse or `require :reload-all`) — [../proposal.md](../proposal.md).
- **TASK-shr-002.REF3:** Blocked by Task 1 (`TASK-shr-001`), which delivers the core seam this task reorders.
