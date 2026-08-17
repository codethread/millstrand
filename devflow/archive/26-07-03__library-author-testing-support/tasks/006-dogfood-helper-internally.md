# Dogfood helper internally

## TASK-006.P1 Scope

Type: AFK

Dogfood `skein.test.alpha` in Skein's own library-author-style tests. Keep lower-level weaver/storage tests on lower-level APIs where they provide better precision.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH6`
- [REPL API delta](../specs/repl-api.delta.md)
- [Smoke feasibility spike](../spikes/2026-06-26-smoke-memory-feasibility.md)

## TASK-006.P2 Implementation notes

- Migrate the selected dogfood targets from `test/skein/spools_test.clj` to use `skein.test.alpha`:
    - `daemon-init-runs-with-spool-classloader-after-sync`
    - `connected-client-use-executes-in-daemon-runtime`
- Keep tests that need direct runtime/storage manipulation on lower-level helpers.
- Add or confirm focused in-repo coverage for both `:storage :sqlite-file` and `:storage :sqlite-memory` through the public helper.
- Keep canonical `clojure -M:smoke` file-backed. Do not replace smoke with in-memory storage.
- If a small optional in-memory smoke-like test path is added, keep it supplemental and clearly named; do not make it a public CLI feature.
- Update feature-local specs or plan Developer Notes if implementation reveals a cut or adjusted scope.

## TASK-006.P3 Done when

- The selected `spools_test.clj` dogfood cases use `skein.test.alpha` and still assert the same author-visible behavior.
- Lower-level tests still cover storage/runtime internals directly.
- In-repo tests cover the helper with both file-backed and in-memory storage modes.

## TASK-006.P4 Validation

Run focused Clojure validation for the touched tests, or the full Clojure suite if practical:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

Record any validation that cannot be run and why in the plan Developer Notes.
