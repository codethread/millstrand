# Task 9: Repo coordination and pinned-spool declarations

**Document ID:** `TASK-Dcr-009`

## TASK-Dcr-009.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH9`: route and declare repo coordination ops, update both pinned external op suites, and advance synchronized pins after focused validation.

Dispatch precondition: do not start until the coordinator confirms `uson2-cli-style-guide` has landed on main and this branch has been rebased onto that main. The coordinator must also supply the devflow and kanban upstream checkouts/branches and target commit locations. Never patch a tools.deps/gitlibs cache.

Owned files:

- `.skein/spools/macros/src/skein/macros/ops.clj`
- `.skein/config.clj`, `.skein/analytics.clj`, and `.skein/workflows.clj`
- `test/skein/macros/ops_test.clj`
- `test/skein/config_ops_test.clj` and focus-eligible runner inventory
- `deps.edn` and `.skein/spools.edn`
- coordinator-provided devflow and kanban upstream op declarations and owner tests

## TASK-Dcr-009.P2 Must implement exactly

- **TASK-Dcr-009.MI1:** Make workspace `defop` pass `:returns` through the existing registry metadata route and cover macro expansion, installation, and validation behavior.
- **TASK-Dcr-009.MI2:** Declare every production config, analytics, and land op. In `skein.config-ops-test`, discover them by registry provenance, fail on missing declarations, derive required leaves, and check one successful captured value per leaf.
- **TASK-Dcr-009.MI3:** Include existing hand-stamped `:operation` in config-op shapes where present. Omit it for the flat, unstamped `carder-report` and `flow-await` results. Keep the landed dispatch boundary read-only.
- **TASK-Dcr-009.MI4:** In the supplied devflow and kanban checkouts, declare their production ops and apply the same provenance-derived declaration and leaf coverage rule. Avoid broad `:json` subcommand shapes.
- **TASK-Dcr-009.MI5:** Run both external focused suites before advancing the devflow and kanban pins. Keep each coordinate identical between `deps.edn` and `.skein/spools.edn`; never edit the gitlibs cache.

## TASK-Dcr-009.P3 Done when

- **TASK-Dcr-009.DW1:** Repo macro/config/analytics/land and both external spool suites declare and check every production return leaf with the exact operation-field rules above.
- **TASK-Dcr-009.DW2:** Cold focused gates pass: `clojure -M:test skein.macros.ops-test skein.config-ops-test`; in the supplied upstream checkouts, `clojure -M:test ct.spools.devflow-test ct.spools.kanban-test`.
- **TASK-Dcr-009.DW3:** After all synchronized pins advance to their tested commits, `make spool-suite-gate` passes both pinned external suites against this checkout.
- **TASK-Dcr-009.DW4:** `make fmt-check lint reflect-check docs-check` passes. Existing add-libs config tests remain integration coverage and are not a substitute for `skein.config-ops-test`.

## TASK-Dcr-009.P4 Out of scope

- **TASK-Dcr-009.OS1:** Dispatch-boundary edits, local gitlibs-cache patches, output normalization, or treating add-libs integration tests as the focused owner-suite gate.

## TASK-Dcr-009.P5 Commit policy

- Commit tested changes in each supplied upstream checkout first. Then make one atomic conventional commit in this branch, authored with a HEREDOC message, containing repo declarations and both synchronized pin advances. Do not amend, push, or land.

## TASK-Dcr-009.P6 References

- **TASK-Dcr-009.REF1:** `PLAN-Dcr-001.A4/A7`, `PH9`, `V2`, `R1-R4`, `TC4/TC6`.
- **TASK-Dcr-009.REF2:** `DELTA-Dcr-repl-001.CC4/CC7`.
- **TASK-Dcr-009.REF3:** `.skein/config.clj`, `.skein/analytics.clj`, `.skein/workflows.clj`, `.skein/spools/macros/src/skein/macros/ops.clj`, `deps.edn`, `.skein/spools.edn`.
