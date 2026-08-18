# Task 2: delete skein.spools.format, repoint 6 callers to api.format.alpha

**Document ID:** `TASK-usc-002`

**Slice:** `PLAN-usc-001.PH1` Slice 1b **Harness:** grunt (with a build check on the delete) **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-002.P1 Scope

Type: AFK

`skein.spools.format` is a thin rename of `fill`/`reflow`, which already exist as blessed `skein.api.format.alpha`. Delete it and repoint its callers, removing a redundant namespace outright (`PROP-usc-001.C1`, `PLAN-usc-001.PH1` Slice 1b, TEN-004). The tree stays green; `spools/src` still holds every other spool.

**Owned files:**

- `spools/src/skein/spools/format.clj` (deleted)
- the six callers surfaced by `grep -rln 'skein.spools.format'` — proposal cites `agent-run`, `bench`, `delegation`, `kanban`, `roster`, `workflow`; **verify the set at slice start** (`PLAN-usc-001.TC5`)

## TASK-usc-002.P2 Must implement exactly

- **TASK-usc-002.MI1:** Run `grep -rln 'skein.spools.format'`; repoint each caller's `:require` from `skein.spools.format` to `skein.api.format.alpha` (`fill`/`reflow` are the blessed names, `src/skein/api/format/alpha.clj:10,20`).
- **TASK-usc-002.MI2:** Delete `spools/src/skein/spools/format.clj` (`PROP-usc-001.C1`).

## TASK-usc-002.P3 Done when

- **TASK-usc-002.DW1:** Cold focused gate green (`PLAN-usc-001.V1`): `clojure -M:test skein.agent-run-test skein.bench-test skein.delegation-test skein.kanban-test skein.roster-test skein.spools.workflow-test`
- **TASK-usc-002.DW2:** `make fmt-check lint reflect-check` pass.
- **TASK-usc-002.DW3:** `grep -rln 'skein.spools.format'` is empty and the file is gone.

## TASK-usc-002.P4 Out of scope

- **TASK-usc-002.OS1:** Any per-spool root move or `deps.edn` change (Tasks 3–13). The `:format`/`:lint` alias args still name `spools/src`; they are re-listed in PH2/PH4, not here.
- **TASK-usc-002.OS2:** Docs and spec promotion (Task 14).

## TASK-usc-002.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-002.P6 References

- **TASK-usc-002.REF1:** `PLAN-usc-001.PH1` (Slice 1b), `AA3`, `V1`/`V5`, `TC5`.
- **TASK-usc-002.REF2:** `PROP-usc-001.C1`; `DELTA-usc-as-001.CC3` (`format` deleted in favour of `skein.api.format.alpha`).

## TASK-usc-002.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
