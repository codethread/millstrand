# Task 13: batteries classpath exception + require-fallback removal + bootstrap

**Document ID:** `TASK-usc-013` **Slice:** `PLAN-usc-001.PH4` **Harness:** build **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-013.P1 Scope

Type: AFK

The classpath-flip slice: `spools/src` leaves `:paths`, `batteries` becomes the one classpath spool loaded by an explicit `require`, the loader `require` fallback is deleted, and a fresh `mill init` world still gets its command surface (`PLAN-usc-001.PH4`, `PROP-usc-001.C2`/`.C4`). Lands **last** (after every other spool is off `spools/src`, Tasks 3–11) so fallback removal cannot strand a still-classpath spool (`A1`/`R3`). Because it flips the classpath and bootstrap, it earns the heavy validation tier.

**Owned files:**

- `spools/src/skein/spools/batteries.clj` → `spools/batteries/src/skein/spools/batteries.clj` (`git mv`)
- `deps.edn` (`:paths`: add `spools/batteries/src`, **remove `spools/src`**; add `spools/batteries/src` to the `:format`/`:lint` arg lists)
- `scripts/generate_api_docs.clj` (batteries `:source`)
- `.skein/init.clj` (explicit require above the batteries `use!`)
- `cli/internal/config/bootstrap.go` (`DefaultInitCLJ`)
- `src/skein/core/weaver/spool_sync.clj` (`load-synced-namespace!` fallback + docstring)
- `dev/skein/smoke.clj` (clean/dirty bootstrap needles)
- `cli/integration_test.go` (bootstrap assertions)

## TASK-usc-013.P2 Must implement exactly

- **TASK-usc-013.MI1:** `git mv batteries.clj` to `spools/batteries/src/skein/spools/batteries.clj`; add `spools/batteries/src` to `deps.edn :paths` and to the `:format`/`:format/fix`/`:lint/clj-kondo`/`:lint/splint` arg lists; **remove `spools/src` from `:paths`** (now empty of spools). Repoint the `batteries` entry in `scripts/generate_api_docs.clj` (`PROP-usc-001.C2`/`C5`, `PLAN-usc-001.AA4`).
- **TASK-usc-013.MI2:** Add `(require 'skein.spools.batteries)` above the `:skein/spools-batteries` `use!` in both `.skein/init.clj` and `DefaultInitCLJ` (`bootstrap.go`); the batteries `use!` keeps **no** `:spools` guard (documented exception). Because the ns is then already loaded, `load-synced-namespace!` short-circuits at its `find-ns` guard (`PROP-usc-001.C4`, `DELTA-usc-repl-001.CC2`).
- **TASK-usc-013.MI3:** Delete the `(require ns-sym)` fallback branch in `load-synced-namespace!` (`spool_sync.clj`, the `try`/`catch` around `~558-560`) so a `:ns` with no synced source throws the loud "Could not locate namespace source in synced spool roots" directly; update the `load-synced-namespace!` docstring (`spool_sync.clj:544-549`) to drop the fallback description (`DELTA-usc-repl-001.CC1`).
- **TASK-usc-013.MI4:** Update `dev/skein/smoke.clj` clean/dirty bootstrap needles and `cli/integration_test.go` bootstrap assertions for the added explicit-require line; leave the clean-bootstrap `{:spools {}}` `spools.edn` assertion **unchanged** (the point of the exception, `PROP-usc-001.C2`, `PLAN-usc-001.AA8`/`AA9`).

## TASK-usc-013.P3 Done when

This is the classpath-flip slice, so it runs the heavy tier (`PLAN-usc-001.PH4`/`V2`/`V3`):

- **TASK-usc-013.DW1:** Full locked suite green: `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (serialize across agents; bare `flock` on PATH).
- **TASK-usc-013.DW2:** `(cd cli && go test ./...)` green.
- **TASK-usc-013.DW3:** `clojure -M:smoke` green.
- **TASK-usc-013.DW4:** A fresh `mill init` world in a disposable `mktemp -d` `--workspace` boots with `{:spools {}}` and gets the batteries command surface (`PROP-usc-001.G5`, `PLAN-usc-001.V3`). Use the repo-local `./bin/mill`; guard every workspace expansion with `${ws:?}`; never touch the canonical `.skein`.
- **TASK-usc-013.DW5:** `make fmt-check lint reflect-check` pass; `deps.edn :paths` no longer lists `spools/src`.

## TASK-usc-013.P4 Out of scope

- **TASK-usc-013.OS1:** Docs restructure, spec-delta promotion, and `make api-docs` regen (Task 14). Only the `generate_api_docs.clj` batteries `:source` path changes here.
- **TASK-usc-013.OS2:** Landing/merge (coordinator-only); this slice stops at implemented + committed with green gates.

## TASK-usc-013.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-013.P6 References

- **TASK-usc-013.REF1:** `PLAN-usc-001.PH4`, `A1` (classpath-last), `AA4`/`AA5`/`AA8`/`AA9`, `V2`/`V3`, `R3`/`R4`, `TC4`.
- **TASK-usc-013.REF2:** `PROP-usc-001.C2` (batteries exception), `C4` (fallback removal); `DELTA-usc-repl-001.CC1`/`.CC2`; `DELTA-usc-dr-001.CC1` (`SPEC-004.C50a`).

## TASK-usc-013.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice. Never restart the canonical weaver; all validation runs in disposable `mktemp -d` worlds (`PLAN-usc-001.TC4`). Kill any stuck JVM by PID only.

## TASK-usc-013.P8 Worker note

- Do **not** run the full locked suite concurrently with a sibling full suite; hold `/tmp/skein-test.lock` (`flock -w 3600`). `SKEIN_TEST_AWAIT_SCALE` multiplies await budgets on slow hosts.
