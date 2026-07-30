# Task 2: Batteries cutover and repo config migration

**Document ID:** `TASK-Srs-002`

## TASK-Srs-002.P1 Scope

Type: AFK

Implement `PLAN-Srs-001.PH2` as one atomic cutover: remove batteries from the production base classpath, approve it through the new source-root coordinate, guard its module on that approval, migrate this repo's shipped-spool roots, and update the Clojure/config/smoke assertions that encode the old exception.

## TASK-Srs-002.P2 Must implement exactly

- **TASK-Srs-002.MI1:** In `deps.edn`, remove `spools/batteries/src` from base `:paths`; add it to the `:test` alias `:extra-paths` because `batteries_test` directly requires it, and to `:reflect-check :extra-paths` because that alias does not compose `:test`. Confirm it remains explicitly present in the `:format`, `:lint`, and `:splint` paths (`SPEC-004-D006.C7`).
- **TASK-Srs-002.MI2:** In `.skein/spools.edn`, seed `skein.spools/batteries {:skein/source-root "spools/batteries"}` and replace the four shipped-spool `../spools/*` local roots for workflow, text-search, chime, and cron with their `:skein/source-root` coordinates. Leave `skein.macros/macros {:local/root "spools/macros"}` unchanged (`SPEC-004-D006.C1`, `SPEC-004-D006.C3`, `SPEC-004-D006.C7`).
- **TASK-Srs-002.MI3:** In `.skein/init.clj`, remove the bare `(require 'skein.spools.batteries)` and make the batteries declaration an ordinary `:spools`-guarded `module!`. Preserve the module's contribution/reconciliation behavior. Reword the batteries classpath claim in `.skein/module_adapters.clj` (`SPEC-004-D006.C7`, `SPEC-003-D006.C0`, `SPEC-003-D006.C1`, `SPEC-005-D001.C1`).
- **TASK-Srs-002.MI4:** Update the bootstrap-clean, bootstrap-dirty, and `startup-transformation-forms` fixtures/assertions in `dev/skein/smoke.clj` from empty-spools/bare-require assumptions to the seeded coordinate and guarded-module model. A hand-written `{:spools {}}` world has no batteries ops; the seeded entry is the visible opt-in and deleting it is the supported opt-out (`SPEC-004-D006.C7`, `SPEC-003-D006.C1`, `SPEC-002-D007.C1`, `SPEC-005-D001.C1`).
- **TASK-Srs-002.MI5:** Update the batteries-specific assertions in `test/skein/spools_test.clj` and the batteries guard exemption in `test/skein/config_test.clj`. Keep direct test/REPL requires legitimate and distinguish the test-alias classpath from shipped production behavior (`SPEC-004-D006.C5`, `SPEC-004-D006.C7`, `SPEC-003-D006.C0`, `SPEC-003-D006.C1`).

Decision (resolving the P7 subtlety at planning time): the ambient-JVM classpath-ownership assertion in `spools_test` (~1983–1995) is KEPT but reworded as a test-tooling artifact of the `:test` alias extra-path (per SPEC-004-D006.C7); synced-provider and fresh-generation-absence behavior are asserted only from disposable worlds (PH5 e2e smoke), never the ambient `:test` classpath.

- **TASK-Srs-002.MI6:** Land the classpath removal, coordinate, guard, config migration, fixtures, and assertions together. Do not leave an intermediate state in which batteries has neither its former classpath activation nor the approved source-root path (`SPEC-004-D006.C7`, `SPEC-003-D006.C1`, `SPEC-005-D001.C1`).

## TASK-Srs-002.P3 Done when

- **TASK-Srs-002.DW1:** The exact cold gate `clojure -M:test skein.spools-test skein.config-test` passes. Warm-REPL output does not satisfy this gate.
- **TASK-Srs-002.DW2:** After `make build`, the exact gate `clojure -M:smoke` passes using only its smoke-owned disposable `--workspace` worlds and branch-local binaries. It must not use the canonical `.skein` world.
- **TASK-Srs-002.DW3:** `git status --short` shows no generated artifacts.

## TASK-Srs-002.P4 Out of scope

- **TASK-Srs-002.OS1:** Own `deps.edn`, `.skein/spools.edn`, `.skein/init.clj`, `.skein/module_adapters.clj`, `dev/skein/smoke.clj`, `test/skein/spools_test.clj`, and `test/skein/config_test.clj`. Do not edit Task 1's core source or dedicated test file, `cli/`, root specs, or prose docs.
- **TASK-Srs-002.OS2:** Do not perform PH5's fresh-generation acceptance assertion or sibling-repo sweeps. Do not record fresh-generation classpath-ownership absence from the ambient test JVM.
- **TASK-Srs-002.OS3:** Every runtime experiment must use a disposable world created with `mktemp -d`, an explicit guarded `${ws:?}` `--workspace` path, and repo-local `./bin/strand` and `./bin/mill` built by `make build`. Never start, stop, restart, or refresh the canonical weaver. Kill only a verified PID, never by process-name or pattern. Never run `make install`.

## TASK-Srs-002.P5 References

- **TASK-Srs-002.REF1:** [PLAN-Srs-001](../source-root-spools.plan.md), especially PH2, P6, P7, and P8.
- **TASK-Srs-002.REF2:** [SPEC-004-D006](../specs/daemon-runtime.delta.md), especially C1, C3, C5, and C7.
- **TASK-Srs-002.REF3:** [SPEC-003-D006](../specs/repl-api.delta.md), clauses C0 and C1; [SPEC-002-D007.C1](../specs/cli.delta.md); [SPEC-005-D001.C1](../specs/alpha-surface.delta.md).
- **TASK-Srs-002.REF4:** [Brief consumer sweep](../brief.md#scope), including repo config, smoke, `spools_test`, and `config_test`.
- **TASK-Srs-002.REF5:** Blocked on Task 3 as well as Task 1: `clojure -M:smoke` builds and drives the Go `mill`/`strand` binaries (`dev/skein/smoke.clj` builds `cli/bin/*`), so the DW2 gate needs Task 3's seeded bootstrap in place.
