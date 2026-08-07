# Task 4: Promote spec deltas and sweep source-root spool docs

**Document ID:** `TASK-Srs-004`

## TASK-Srs-004.P1 Scope

Type: AFK

Implement `PLAN-Srs-001.PH4`: fold the four binding deltas into the root specs, replace batteries' classpath-exception story with the ordinary approved-spool model, sweep every named consumer document, and regenerate batteries API docs from the changed docstrings.

## TASK-Srs-004.P2 Must implement exactly

- **TASK-Srs-004.MI1:** Promote `devflow/feat/source-root-spools/specs/daemon-runtime.delta.md` into `devflow/specs/daemon-runtime.md`: amend C42 for the closed three-kind set; C48@2/C49@2 for non-acquiring source-root resolution; C44 outcome and C44c root-identity/cutover wording; preserve C44f; add C50b as the source-checkout resolution authority; replace C50a with the no-spool-on-production-classpath and batteries-as-ordinary-spool contract; amend C94a and C94a.1 for allowed Maven deps and forbidden nested `:skein/source-root` (`SPEC-004-D006.C1` through `SPEC-004-D006.C9`).
- **TASK-Srs-004.MI2:** Promote `devflow/feat/source-root-spools/specs/repl-api.delta.md` into `devflow/specs/repl-api.md`: remove batteries from the classpath-module example adjacent to C62 while retaining the form for genuinely classpath-owned namespaces, and rewrite C63 around the seeded approval consent edge and `:spools`-guarded module (`SPEC-003-D006.C0`, `SPEC-003-D006.C1`, `SPEC-004-D006.C7`).
- **TASK-Srs-004.MI3:** Promote `devflow/feat/source-root-spools/specs/cli.delta.md` into `devflow/specs/cli.md` C14a, including the guarded generated module, seeded relative coordinate, visible opt-out, no-persist-source guarantee, create-only-missing behavior, and unchanged bootstrap file set (`SPEC-002-D007.C1`, `SPEC-004-D006.C3`, `SPEC-004-D006.C7`).
- **TASK-Srs-004.MI4:** Promote `devflow/feat/source-root-spools/specs/alpha-surface.delta.md` into `devflow/specs/alpha-surface.md`: remove the batteries carve-out so every reference spool loads opt-in through an approved coordinate and `:spools`-guarded module; batteries is seeded by `mill init` (`SPEC-005-D001.C1`, `SPEC-004-D006.C7`, `SPEC-002-D007.C1`).
- **TASK-Srs-004.MI5:** Replace the "Classpath exception: batteries" section in `spools/README.md` with the shipped-spool source-root coordinate story. Rewrite `spools/batteries.md`, `spools/batteries/README.md`, and the batteries namespace and `contribute` docstrings in `spools/batteries/src/skein/spools/batteries.clj` to describe ordinary approved loading, the seeded opt-in, and the supported deletion opt-out (`SPEC-004-D006.C7`, `SPEC-003-D006.C1`, `SPEC-005-D001.C1`).
- **TASK-Srs-004.MI6:** Sweep every remaining named consumer: `docs/spools/customisation.md`, `docs/spools/testing.md`, `docs/spools/writing-shared-spools.md`, `docs/reference.md`, `spools/chime/README.md`, and `spools/cron/README.md`. Remove stale claims that batteries ships on the classpath or that shipped Skein spools should use layout-dependent `../spools/*` local roots. Keep genuine workspace-local `:local/root` and externally published git-pinned spool guidance unchanged (`SPEC-004-D006.C1`, `SPEC-004-D006.C2`, `SPEC-004-D006.C7`, `SPEC-003-D006.C0`, `SPEC-005-D001.C1`).
- **TASK-Srs-004.MI7:** Run `make api-docs` after the batteries docstring edits and commit the regenerated `spools/batteries.api.md`. Mark the four feature deltas merged and update any required spec index/status metadata without renumbering stable clause IDs (`SPEC-004-D006.C7`, `SPEC-005-D001.C1`).

## TASK-Srs-004.P3 Done when

- **TASK-Srs-004.DW1:** The exact documentation gate `make docs-check` passes.
- **TASK-Srs-004.DW2:** The exact generation check `make api-docs` leaves no diff beyond the committed generated `spools/batteries.api.md`; a second `make api-docs` is clean.
- **TASK-Srs-004.DW3:** `git status --short` shows no generated artifacts.

## TASK-Srs-004.P4 Out of scope

- **TASK-Srs-004.OS1:** Own root specs and human-facing docs only, plus the batteries namespace/`contribute` docstrings and their generated `spools/batteries.api.md`. Do not change runtime behavior, Clojure tests, `deps.edn`, `.millstrand/`, smoke behavior, or `cli/`.
- **TASK-Srs-004.OS2:** Do not run PH5 queue acceptance, perform the coordinator-owned fresh-generation smoke, land the branch, or sweep sibling repositories.
- **TASK-Srs-004.OS3:** Every runtime experiment must use a disposable world created with `mktemp -d`, an explicit guarded `${ws:?}` `--workspace` path, and repo-local `./bin/strand` and `./bin/mill` built by `make build`. Never start, stop, restart, or refresh the canonical weaver. Kill only a verified PID, never by process-name or pattern. Never run `make install`.

## TASK-Srs-004.P5 References

- **TASK-Srs-004.REF1:** [PLAN-Srs-001](../source-root-spools.plan.md), especially PH4, P6, P7, and P8.
- **TASK-Srs-004.REF2:** [SPEC-004-D006](../specs/daemon-runtime.delta.md), clauses C1 through C9.
- **TASK-Srs-004.REF3:** [SPEC-003-D006](../specs/repl-api.delta.md), clauses C0 and C1; [SPEC-002-D007.C1](../specs/cli.delta.md); [SPEC-005-D001.C1](../specs/alpha-surface.delta.md).
- **TASK-Srs-004.REF4:** [Brief consumer sweep](../brief.md#scope), specs/docs entries.
