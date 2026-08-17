# Promote Specs and Validate

**Document ID:** `TASK-006`

## TASK-006.P1 Scope

Type: AFK

Promote shipped runtime transformation primitive contracts into root specs, reconcile docs, and run full validation.

## TASK-006.P2 References

- **TASK-006.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-006.R2:** Feature spec deltas in `../specs/`
- **TASK-006.R3:** Root specs: `devflow/specs/daemon-runtime.md`, `devflow/specs/repl-api.md`, `devflow/specs/cli.md`
- **TASK-006.R4:** `devflow/prd/runtime-transformations.md`, `devflow/README.md`

## TASK-006.P3 Implementation notes

- **TASK-006.I1:** Merge durable shipped behavior from feature-local spec deltas into root specs. Keep root specs canonical and avoid duplicating implementation task detail.
- **TASK-006.I2:** Mark feature-local deltas merged if that is the local convention used in this repo for shipped features.
- **TASK-006.I3:** Update `devflow/prd/runtime-transformations.md` only if implementation choices changed the PRD examples or first-slice assumptions.
- **TASK-006.I4:** Update `devflow/README.md` active/archive notes only if finishing/archive is part of the current workflow; otherwise leave final archive movement to devflow finish.
- **TASK-006.I5:** Run full validation:
    - `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`
    - `(cd cli && go test ./...)`
    - `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`
- **TASK-006.I6:** Confirm `git status --short` does not show generated SQLite, socket, runtime metadata, or temp config artifacts.

## TASK-006.P4 Done when

- **TASK-006.D1:** Root specs reflect the implemented runtime transformation primitive behavior.
- **TASK-006.D2:** Full validation passes.
- **TASK-006.D3:** Generated artifacts are cleaned up.
- **TASK-006.D4:** Feature plan Developer Notes include final validation summary and any cut/deferred scope.
