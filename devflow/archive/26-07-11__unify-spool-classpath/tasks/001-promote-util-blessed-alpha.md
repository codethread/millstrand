# Task 1: promote util to blessed skein.api.spool.alpha (+test rename)

**Document ID:** `TASK-usc-001` **Slice:** `PLAN-usc-001.PH1` Slice 1a **Harness:** build **Type:** AFK **Branch:** `unify-spool-classpath`

## TASK-usc-001.P1 Scope

Type: AFK

Promote `skein.spools.util` out of the spool family into the blessed authoring-helper home `skein.api.spool.alpha` under base `src/`, so no blessed `skein.api.*` namespace requires a `skein.spools.*` namespace (`PROP-usc-001.C1`, `DELTA-usc-as-001.CC1`, `PLAN-usc-001.PH1` Slice 1a). This is a **deliberate compat commitment**, not a mechanical move: landing `util` in the accretion-compatible blessed tier freezes its helper surface. The tree stays green because both `util` (now) and every other spool (still on `spools/src`) remain reachable.

**Owned files:**

- `spools/src/skein/spools/util.clj` → `src/skein/api/spool/alpha.clj` (`git mv`)
- `src/skein/api/vocab/alpha.clj` (repoint require; remove the tier-inversion comment at `:22`)
- `.skein/workflows.clj` (util require at `:18`)
- every `skein.spools.util` requirer surfaced by `grep -rln 'skein.spools.util'` at slice start (proposal cites 22 — **re-derive, do not trust the count**, `PLAN-usc-001.TC5`)
- `test/skein/spools/util_test.clj` → `test/skein/api/spool_test.clj` (`git mv`)
- `test/skein/test_runner.clj` (`parallel-namespaces` entry at `:18`)

## TASK-usc-001.P2 Must implement exactly

- **TASK-usc-001.MI1:** `git mv spools/src/skein/spools/util.clj src/skein/api/spool/alpha.clj`; change its `ns` to `skein.api.spool.alpha` with a docstring; freeze the helper set `fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until-deadline!` (`DELTA-usc-as-001.CC1`).
- **TASK-usc-001.MI2:** Enumerate requirers with `grep -rln 'skein.spools.util'` and repoint every `:require` from `skein.spools.util` to `skein.api.spool.alpha` — including `src/skein/api/vocab/alpha.clj`, `.skein/workflows.clj:18`, every per-spool root, and every classpath spool (`PLAN-usc-001.PH1` Slice 1a, `AA1`/`AA2`).
- **TASK-usc-001.MI3:** Remove the tier-inversion comment at `src/skein/api/vocab/alpha.clj:22` (`PLAN-usc-001.AA2`).
- **TASK-usc-001.MI4:** `git mv test/skein/spools/util_test.clj test/skein/api/spool_test.clj`; change its `ns` from `skein.spools.util-test` to `skein.api.spool-test` and its internal require of the moved ns (`PLAN-usc-001.PH1` Slice 1a, P6).
- **TASK-usc-001.MI5:** Rename the `test/skein/test_runner.clj:18` `parallel-namespaces` entry `skein.spools.util-test` → `skein.api.spool-test`, so the Done-when command names a namespace that exists at slice completion.

## TASK-usc-001.P3 Done when

- **TASK-usc-001.DW1:** Cold focused gate green (`PLAN-usc-001.V1`) — every test namespace of a root the repoint touches; **re-derive from the slice-start grep and extend if it finds more**: `clojure -M:test skein.api.spool-test skein.vocab-test skein.spools.workflow-test skein.spools.batteries-test skein.spools.carder-test skein.spools.loom-test skein.spools.text-search-test skein.spools.bobbin-test skein.spools.selvage-test skein.guild-test skein.roster-test skein.delegation-test skein.kanban-test skein.chime-test skein.cron-test skein.spools.executors.shell-test skein.executors.subagent-test`
- **TASK-usc-001.DW2:** `make fmt-check lint reflect-check` pass.
- **TASK-usc-001.DW3:** No `skein.spools.util` requirer remains (`grep -rln 'skein.spools.util'` empty).

## TASK-usc-001.P4 Out of scope

- **TASK-usc-001.OS1:** Deleting `skein.spools.format` (Task 2) and moving any activatable spool root (Tasks 3–11) — `spools/src` stays on `:paths` here.
- **TASK-usc-001.OS2:** Any `deps.edn :paths`/alias-root change; the `:format`/`:lint` alias args still name `spools/src` and keep working until PH2/PH4.
- **TASK-usc-001.OS3:** Docs and spec promotion (Task 14).

## TASK-usc-001.P5 Commit

- One atomic commit for this slice on branch `unify-spool-classpath`, conventional message, why-focused, **no push**.

## TASK-usc-001.P6 References

- **TASK-usc-001.REF1:** `PLAN-usc-001.PH1` (Slice 1a), `AA1`, `AA2`, `A1`/`A4`, `V1`/`V5`, `TC5`.
- **TASK-usc-001.REF2:** `PROP-usc-001.C1` (util/format disposition); `DELTA-usc-as-001.CC1`/`.CC3` (blessed home, frozen surface).

## TASK-usc-001.P7 Worker contract

- Set `--attr status=implemented` only when the DW gate above is green; never close this strand; never mutate sibling or parent strands; commit only your own slice.
