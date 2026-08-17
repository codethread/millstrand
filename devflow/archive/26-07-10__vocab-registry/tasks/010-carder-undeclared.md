# Task 10: carder undeclared-namespace hygiene section (carder.clj)

**Document ID:** `TASK-Vr-010` **Slice:** `PLAN-Vr-001.S5` **Harness:** build **Type:** AFK **Depends on:** TASK-Vr-001

## TASK-Vr-010.P1 Scope

Type: AFK

Add the hygiene section that surfaces undeclared-namespace strays: flag active strands whose attribute namespace is declared by nobody (`PROP-Vr-001.C8`, `G4`). Carder mutates nothing. Disjoint file from the S2 seeds and from S3/S4 — parallel after Task 1 (`PLAN-Vr-001.A3`).

**Owned files (disjoint):**

- `spools/src/skein/spools/carder.clj`

## TASK-Vr-010.P2 Must implement exactly

Per `PROP-Vr-001.C8`:

- **TASK-Vr-010.MI1:** Add an `undeclared` report section in the shape of the existing `report` sections: read the declared set via `vocab/declarations runtime {:kind :attr-namespace}`, walk `active-strands`, and flag each strand → attribute key whose _namespace segment_ is absent from the declared set.
- **TASK-Vr-010.MI2:** Flag by _namespace_, not exact key (`PROP-Vr-001.C1`, `R3`): `review/newfield` under declared `review/*` is clean, while a bare `verify-note` or an unowned `frobnicate/*` is flagged.
- **TASK-Vr-010.MI3:** Join `report` as a fourth section beside `stale`/`orphans`/ `blocked-by-failure`; carder still mutates nothing (the read-only carder contract, `NG1`).

## TASK-Vr-010.P3 Done when

- **TASK-Vr-010.DW1:** `report` carries an `undeclared` section flagging active strands with an attribute in no declared namespace, flagged by namespace not exact key; no write is blocked (`PROP-Vr-001.C8`, `NG1`, `DW4`).
- **TASK-Vr-010.DW2:** Cold focused run `clojure -M:test skein.spools.carder-test` green (focused-runnable, `PLAN-Vr-001.TC4`). The `PROP-Vr-001.R3` false-positive avoidance is proven here: a new key under a declared namespace is clean, a bare unowned namespace is flagged (`PLAN-Vr-001.V4`).
- **TASK-Vr-010.DW3:** `make fmt-check lint reflect-check` pass. `make api-docs` regen is deferred to Task 15.

## TASK-Vr-010.P4 Out of scope

- **TASK-Vr-010.OS1:** selvage (Task 9), batteries (Task 8), the S2 seeds (Tasks 2–7) — disjoint files.
- **TASK-Vr-010.OS2:** The `spools/carder.md` Surface-table entry (Task 13, depends on this task).
- **TASK-Vr-010.OS3:** Any write-time block — the section surfaces strays, it never blocks them (`PROP-Vr-001.NG1`, `C13`).

## TASK-Vr-010.P5 Commit

- Atomic single commit, devflow message, **no push**.

## TASK-Vr-010.P6 References

- **TASK-Vr-010.REF1:** `PLAN-Vr-001.S5`, `PLAN-Vr-001.A3`, `PLAN-Vr-001.AA9`, `PLAN-Vr-001.V4`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-010.REF2:** `PROP-Vr-001.C8` (undeclared hygiene section), `R3`, `NG1`; the landed Task 1 `declarations`.
