# Task 4: Phase A ADR, root specs, authoring docs, and API docs

**Document ID:** `TASK-Dsp-004`

## TASK-Dsp-004.P1 Scope

Type: AFK

After the core and in-tree code is integrated, merge the approved feature deltas into truthful Phase A root contracts, record the C19 exception under delegated authority, document the transitional legacy-key window, and regenerate API docs. No implementation or test changes. Tracked on strand `m47vr`.

Owned files (provisioned after code integration): the ADR-003 successor record under `devflow/adrs/`; `devflow/specs/repl-api.md` and `devflow/specs/daemon-runtime.md`; `docs/spools/testing.md`, `docs/spools/writing-shared-spools.md`, `docs/spools/customisation.md`; generated `docs/api/*` and `spools/*.api.md`; proposal and deltas only as needed for merge status.

## TASK-Dsp-004.P2 Must implement exactly

- **TASK-Dsp-004.MI1:** Merge `DELTA-Dsp-001` and `DELTA-Dsp-002` into truthful Phase A root contracts: `::module-opts` keys optional in Phase A, the new `::spool` spec, and the public `spool` name reservation.
- **TASK-Dsp-004.MI2:** Record the exact SPEC-003.C19 exception wording as a staged pending-removal obligation under the coordinator's delegated sign-off (note `dp90p`); do not promote it into the root spec — Phase C does that (`PLAN-Dsp-001.CM1`).
- **TASK-Dsp-004.MI3:** Write the ADR-003 successor documenting the activation-lifecycle supersession, and document the transitional per-key precedence window.
- **TASK-Dsp-004.MI4:** Explain `def spool` as the grep-friendly public surface owned by spool authors and shrink the consumer init examples to a source target and world policy.
- **TASK-Dsp-004.MI5:** Preserve the invariant in prose that runtime lifecycle semantics did not change; apply the docs-style skill; run `make api-docs`.

## TASK-Dsp-004.P3 Done when

- **TASK-Dsp-004.DW1:** `make api-docs` regenerates cleanly with no drift left in `git status`.
- **TASK-Dsp-004.DW2:** `make docs-check` passes.
- **TASK-Dsp-004.DW3:** `git diff --check` is clean and each merged delta is marked to its Phase A state.

## TASK-Dsp-004.P4 Out of scope

- **TASK-Dsp-004.OS1:** Implementation and test files (Tasks 1–3).
- **TASK-Dsp-004.OS2:** Promoting the C19 exception into the root spec, or completing the grammar removal — that is Phase C (Task 9).

## TASK-Dsp-004.P5 References

- **TASK-Dsp-004.REF1:** `PLAN-Dsp-001.AA6`, `.CM1`, `.CM2`, `.V2`; `DELTA-Dsp-001.CC5`; notes `dp90p`; ADRs `0002`/`0003`.
- **TASK-Dsp-004.REF2:** Strand `m47vr`, spec-delta run `mo25j`, worktree `codex/uwnzl-phase-a-specs`; integration branch `codex/uwnzl-def-spool-convention`.
