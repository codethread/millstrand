# Task 2: SPEC-001 delta promotion, notes docstring alignment, api-docs regen

**Document ID:** `TASK-Immut-002`

## TASK-Immut-002.P1 Scope

Type: AFK

Contract promotion and in-repo prose alignment for write-once keys, per [PLAN-Immut-001](../immutable-keys.plan.md) PH2.

## TASK-Immut-002.P2 Must implement exactly

- **TASK-Immut-002.MI1:** Merge DELTA-Immut-001 (`devflow/feat/immutable-keys/specs/strand-model.delta.md`) into `devflow/specs/strand-model.md`: P4 gains the write-once contract (declaration model, exact-keys-not-prefixes, all-paths enforcement, idempotent rewrite incl. post-merge comparison, unarchive-legal / archive-rejected, fail-loud ex-data shape, burn escape hatch); P8 gains the `immutable_keys` persistence paragraph beside the `acyclic_relations` one; P10/Deferred lists userland registration, strand seals, and edge immutability. Update root spec Last Updated; mark the delta Merged.
- **TASK-Immut-002.MI2:** Align `skein.api.notes.alpha` docstrings where they describe note memory as append-only by convention: state that `note/text`/`note/at` are storage-enforced write-once. Keep the change to docstrings only. Run `make api-docs` and commit the regenerated `docs/api/*.api.md` / `spools/*.api.md` alongside.
- **TASK-Immut-002.MI3:** Verify enforcement end-to-end in a disposable workspace (own `ws=$(mktemp -d)`, `${ws:?}` guards, never the canonical .skein world): create a note via the batteries note verb, attempt a rewrite/patch-delete/archive of its `note/text` through the CLI/REPL surface, confirm loud failures and unchanged content. Append the transcript summary to the plan's Developer Notes.

## TASK-Immut-002.P3 Done when

- **TASK-Immut-002.DW1:** `make fmt-check lint reflect-check docs-check` clean; `git status --short` shows no unexpected generated artifacts.
- **TASK-Immut-002.DW2:** Root spec + delta statuses per MI1; api-docs regenerated with no residual diff (`make api-docs` idempotent after commit).
- **TASK-Immut-002.DW3:** Cold focused run green on any touched test namespaces (docstring-only code changes need no new tests).
- **TASK-Immut-002.DW4:** Committed on branch `iv22r-immutable-keys`; stop at implemented+committed.

## TASK-Immut-002.P4 Out of scope

- The wider docs sweep (docs/skein.md, spool contract docs, cookbooks) — that is epic card 2la9m, a separate feature.

## TASK-Immut-002.P5 References

- The delta, plan, and `devflow/specs/strand-model.md`.
- `src/skein/api/notes/alpha.clj` docstrings; `make api-docs`.
