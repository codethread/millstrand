# Task 004: Root-spec merge (AA8) + batteries contract + docs-check (ASSN-PLAN-001.PH4)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`. **Depends on Task 003.**

Read first: `attr-scaling-ship-now.plan.md` (ASSN-PLAN-001 `PH4`, `A7`, `AA8`, `V6`) and all three deltas. Note the DN2 corrections (`ASSN-PLAN-001.DN2`): the `show` point read is the real op name — merge `show`, never a phantom `get`.

## Scope

Land the contract prose now that code exists (`AA8`, `A7`). No behavior change.

- Merge the three feature-spec deltas into the root specs:
    - `strand-model.delta.md` (ASSN-DELTA-001) → `devflow/specs/strand-model.md` (SPEC-001.P4 read tiers, P8 persistence, P9 query fields).
    - `cli.delta.md` (ASSN-DELTA-002) → `devflow/specs/cli.md`.
    - `daemon-runtime.delta.md` (ASSN-DELTA-003) → `devflow/specs/daemon-runtime.md` (SPEC-004.P3a storage model, P4 API boundary). Merge faithfully as the shipped contract (`TC1` — read as if lean reads, declared indexing, and WAL storage were the original design), preserving the richer-than-`require-valid-relation-name!` ex-data wording for both the `::indexed-attr-key` rejection (`{:key :spec :allowed-pattern}`) and the `attr-get` guard (the canonical literal `{:key <attribute-key> :strand-id <strand-id> :recovery "show <strand-id>"}`, exactly as implemented in Task 002).
- Land the per-op lean-read wording in the `skein.spools.batteries` contract: `spools/batteries.md` — `list`/`ready`/query-backed listing lean-by-default above the 1 KiB floor, `show` full, no hydration flag (ASSN-DELTA-002.CC1–CC3).
- Keep generated api-docs in sync: run `make api-docs` (or `make docs-check`) after touching any batteries docstring so `spools/*.api.md` does not drift (`V6`). Follow the human-facing docs style for prose you author.

## Acceptance

- Three deltas merged verbatim-in-spirit into the three root specs; `spools/batteries.md` carries the per-op lean wording; no phantom `get` op text.
- `make docs-check` passes (regenerates `spools/*.api.md`, no drift, docs site builds).

## Validation

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
make docs-check
make fmt-check && make lint
git status --short
```

## Guardrails

- Docs/spec merge only — no code changes here beyond docstring wording already shipped in Tasks 002/003. Never touch/restart the canonical weaver. Never `--no-verify`.
