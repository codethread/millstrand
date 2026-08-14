# Task 3: Go bootstrap seeds batteries as an approved spool

**Document ID:** `TASK-Srs-003`

## TASK-Srs-003.P1 Scope

Type: AFK

Implement `PLAN-Srs-001.PH3` entirely under `cli/`. Fresh `mill init` worlds must receive the batteries source-root approval and a matching guarded module instead of a classpath require.

## TASK-Srs-003.P2 Must implement exactly

- **TASK-Srs-003.MI1:** In `cli/internal/config/bootstrap.go`, rewrite `DefaultInitCLJ` so it still requires `skein.api.current.alpha` and `skein.api.runtime.alpha`, captures `(current/runtime)`, and declares batteries as a normal `:spools`-guarded `contribute`/`reconcile` `module!`. Remove the explicit top-level batteries classpath require (`SPEC-002-D007.C1`, `SPEC-004-D006.C7`, `SPEC-003-D006.C0`, `SPEC-003-D006.C1`, `SPEC-005-D001.C1`).
- **TASK-Srs-003.MI2:** Change the generated `spools.edn` seed from `{:spools {}}` to an approved `skein.spools/batteries {:skein/source-root "spools/batteries"}` entry. Persist only that relative, machine-independent coordinate; never persist the absolute Skein checkout path. Preserve the existing file set, create-only-missing behavior, no-overwrite guarantee, and repo guidance injection (`SPEC-002-D007.C1`, `SPEC-004-D006.C1`, `SPEC-004-D006.C3`, `SPEC-004-D006.C7`).
- **TASK-Srs-003.MI3:** Update the bootstrap assertions in `cli/integration_test.go` to require the seeded source-root coordinate and the guarded batteries module, and to reject the old empty-spools and bare-require needles (`SPEC-002-D007.C1`, `SPEC-003-D006.C1`, `SPEC-005-D001.C1`).

## TASK-Srs-003.P3 Done when

- **TASK-Srs-003.DW1:** The exact cold gate `(cd cli && go test ./...)` passes.
- **TASK-Srs-003.DW2:** `git status --short` shows no generated artifacts.

## TASK-Srs-003.P4 Out of scope

- **TASK-Srs-003.OS1:** Own `cli/` only, specifically `cli/internal/config/bootstrap.go`, `cli/integration_test.go`, and any directly required Go test fixture under `cli/`. Do not edit Clojure source/tests, `deps.edn`, `.millstrand/`, smoke, specs, or docs.
- **TASK-Srs-003.OS2:** Do not run PH5 acceptance or perform sibling-repo sweeps.
- **TASK-Srs-003.OS3:** Every runtime experiment must use a disposable world created with `mktemp -d`, an explicit guarded `${ws:?}` `--workspace` path, and repo-local `./bin/strand` and `./bin/mill` built by `make build`. Never start, stop, restart, or refresh the canonical weaver. Kill only a verified PID, never by process-name or pattern. Never run `make install`.

## TASK-Srs-003.P5 References

- **TASK-Srs-003.REF1:** [PLAN-Srs-001](../source-root-spools.plan.md), especially PH3, P6, and P8.
- **TASK-Srs-003.REF2:** [SPEC-002-D007.C1](../specs/cli.delta.md).
- **TASK-Srs-003.REF3:** [SPEC-004-D006](../specs/daemon-runtime.delta.md), clauses C1, C3, and C7; [SPEC-003-D006](../specs/repl-api.delta.md), clauses C0 and C1; [SPEC-005-D001.C1](../specs/alpha-surface.delta.md).
- **TASK-Srs-003.REF4:** [Brief consumer sweep](../brief.md#scope), Go bootstrap and integration-test entries.
