# Task 6: Cron docs and API regen (PH4)

**Document ID:** `TASK-cron-on-scheduler-006`

## TASK-cron-on-scheduler-006.P1 Scope

Type: AFK

Document cron's new durable, at-least-once contract and regenerate its API doc (`PLAN-cron-on-scheduler-001.PH4`). The durable contract change lives in the cron spool docs: cadence is now durable across restart/reload, delivery is at-least-once (handlers must be idempotent), and `:initial-delay-fn` is gone. Depends on the final `register!`/`fire-wake` shape (task 3) so docstrings and the regenerated API doc are stable.

## TASK-cron-on-scheduler-006.P2 Must implement exactly

- **TASK-cron-on-scheduler-006.MI1:** Rewrite `spools/cron/README.md` to state the at-least-once delivery contract, the wake-delivery vs job-completion split (`PROP-cron-on-scheduler-001.S5`), duplicate-tolerance guidance for job authors (handlers idempotent; a duplicate delivery replaces the next wake and re-offloads — `PLAN-cron-on-scheduler-001.R2`), and the scheduler as the single timing view (cron's listing is a job-status projection, not a second timing surface — `PROP-cron-on-scheduler-001.G3`).
- **TASK-cron-on-scheduler-006.MI2:** Update `spools/cron.cookbook.md` worked examples to registration-over-wakes, drop the `initial-delay-fn` recipe, and show `cron/await-idle!` in test recipes (`PLAN-cron-on-scheduler-001.AA3`).
- **TASK-cron-on-scheduler-006.MI3:** Regenerate `spools/cron.api.md` with `make api-docs` (never hand-edit it). Ensure `cron.clj` docstrings — edited inline during tasks 2/3 — read cleanly first; adjust docstrings if the regen surfaces stale `initial-delay-fn`/timer wording.

## TASK-cron-on-scheduler-006.P3 Done when

- **TASK-cron-on-scheduler-006.DW1:** README and cookbook describe the at-least-once contract, the S5 split, duplicate tolerance, and scheduler as the single timing view; no `initial-delay-fn` or cron-owned-timer wording remains.
- **TASK-cron-on-scheduler-006.DW2:** `make docs-check` is clean and `git status --short` shows the regenerated `spools/cron.api.md` as the only generated artifact, nothing else (`PLAN-cron-on-scheduler-001.PH4` gate).
- **TASK-cron-on-scheduler-006.DW3:** Prose passes the human-facing docs style bar (plain, factual; no LLM tells) — the `docs-style` gate applies to README and cookbook, not the generated api-doc.

## TASK-cron-on-scheduler-006.P4 Out of scope

- **TASK-cron-on-scheduler-006.OS1:** No source/behavior changes to `cron.clj` beyond docstring wording; no test changes.
- **TASK-cron-on-scheduler-006.OS2:** Do not hand-edit `spools/cron.api.md`; it is generated.
- **TASK-cron-on-scheduler-006.OS3:** Do not promote the `SPEC-004` delta here (task 7).

## TASK-cron-on-scheduler-006.P5 References

- **TASK-cron-on-scheduler-006.REF1:** `PLAN-cron-on-scheduler-001.PH4`, `.AA2`/`.AA3`/`.AA4`, `.CM1`, `PROP-cron-on-scheduler-001.S3`/`.S5`/`.G3`/`.G4`, `PLAN-cron-on-scheduler-001.R2`.
- **TASK-cron-on-scheduler-006.REF2:** `spools/cron/README.md`, `spools/cron.cookbook.md`, `spools/cron.api.md`, `spools/README.md` (index), `docs/writing-shared-spools.md`.
- **TASK-cron-on-scheduler-006.REF3:** `make api-docs`, `make docs-check`.
