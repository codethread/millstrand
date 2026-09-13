# Task 15: acceptance / atomic-landing gate

**Document ID:** `TASK-Vr-015`

**Slice:** `PLAN-Vr-001.S10` **Harness:** build **Type:** AFK

**Depends on:** TASK-Vr-001..014

## TASK-Vr-015.P1 Scope

Type: AFK

Run the full acceptance gate in one place and record each result (`PROP-Vr-001.P6`, `DW1`–`DW6`). No new source files; regenerates the touched spools' `*.api.md` outfiles. This is the authoritative gate for the full-suite-only `skein.agent-run-test` shard the S2a seed deferred (`PLAN-Vr-001.A5`, `TC4`).

**Owned files:**

- `spools/batteries.api.md`, `spools/selvage.api.md`, `spools/carder.api.md` (regenerated).

No `vocab.api.md`: `vocab.alpha` is a `src/` blessed namespace, outside the `spools/*.api.md` scope `docs-check` diffs (`PLAN-Vr-001.AA14`).

## TASK-Vr-015.P2 Must implement exactly

- **TASK-Vr-015.MI1:** `make api-docs` — clean regen; `git status --short` shows only the expected `spools/batteries.api.md`/`selvage.api.md`/`carder.api.md` changes for the touched spools; commit them (docs-check fails while a regenerated api.md is uncommitted) (`PROP-Vr-001.P6`).
- **TASK-Vr-015.MI2:** Run every P6 gate: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (full locked suite — the authoritative gate for the `skein.agent-run-test` add-libs shard; bare `flock` from PATH, hold the lock, never a vendored absolute path); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings.
- **TASK-Vr-015.MI3:** End-to-end seed check (`PROP-Vr-001.DW2`, `PLAN-Vr-001.V3`): `strand vocab --kind attr-namespace` lists the confirmed F1–F3 spool namespaces (agent-run, gate, review, panel, kanban, workflow, roster), each single-owner, plus the core-owned `note/*` (owner `skein.api.notes.alpha`); `--kind edge` reflects `relations.alpha/catalog` with no duplicate source in `vocab.alpha`. `devflow/*` stays undeclared by design (F5, card `2mp13`).
- **TASK-Vr-015.MI4:** `git status --short` clear of generated SQLite and runtime metadata artifacts.

## TASK-Vr-015.P3 Done when

- **TASK-Vr-015.DW1:** `PROP-Vr-001.DW1`–`DW6` proven — `vocab.alpha` exists and is in `SPEC-005.C2`; the core seed (edges + `note/*`) and the spool seeds are live and single-owner; `strand vocab` is a batteries read op with `--kind`; carder has the `undeclared` section and selvage the opt-in helper, neither blocking a write; the prefix rule is in `writing-shared-spools.md` and `strand-model.md` names the registry (per `SPEC-Vr-001.CC1`/`CC2`); all P6 gates green in one atomic, additive landing — no migration, no cutover, no weaver restart.
- **TASK-Vr-015.DW2:** Results recorded (gate-by-gate) in the task result so the coordinator can verify against ground truth.

## TASK-Vr-015.P4 Out of scope

- **TASK-Vr-015.OS1:** Landing/merging (coordinator land pipeline). There is no HITL cutover and no weaver restart — the landing is purely additive. F4 has no `cli/` Go change, so `make build` is not the pickup path; the canonical world picks the changes up through the pickup ladder after landing — reload each changed already-loaded namespace with `(require 'the.ns :reload)` first, then `runtime/reload!` re-runs activation so the `vocab` op registers and the seeds re-declare (`PROP-Vr-001.C12`, `PLAN-Vr-001.CM4`).
- **TASK-Vr-015.OS2:** Fixing failures beyond mechanical regen: a red gate means stop and report, not improvise scope.

## TASK-Vr-015.P5 Commit

- Atomic single commit for the api.md regen (if changed), devflow message, **no push**.

## TASK-Vr-015.P6 References

- **TASK-Vr-015.REF1:** `PLAN-Vr-001.S10`, `PLAN-Vr-001.V1`/`V3`, `PLAN-Vr-001.AA14`, `PLAN-Vr-001.TC4`.
- **TASK-Vr-015.REF2:** `PROP-Vr-001.P6` (gates), `P7` (`DW1`–`DW6`); the landed Tasks 1–14.
