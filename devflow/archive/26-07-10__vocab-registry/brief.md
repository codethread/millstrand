# Brief: vocabulary registry — spools declare attr namespaces and edge types as guidance

Kanban: card `41pna`, feature 4 of epic `kaans` (agent-layer redesign). Source: the card body (epic design recorded 2026-07-09). F1–F3 are landed and the canonical world is cut over to all three (F3 squash `bd49eb2`; `serves` and `notes` relations live, `note/*` blessed shape shipped).

## Problem

Attribute namespaces and edge types are conventions with no machine-readable record. Discovering that `review/*` or `gate/*` exists requires reading the right doc first; a stray key (the pre-F3 bare `notes`/`note`/`verify-note` shapes) can live in the data for weeks before anyone notices; and nothing stops two spools claiming the same namespace with different meanings.

## Scope (the contract)

Guidance, not enforcement (TEN-002):

1. **Declaration at install**: spools declare the attribute namespaces and edge types they write — owner, keys, doc.
2. **Queryable**: `strand vocab` (or similar) lists all declared vocabularies — solves "you need to know the other things exist first."
3. **Consumers**: selvage lints opt-in against declarations; a carder-style hygiene report flags attrs in live data belonging to NO declared vocabulary (would have caught the bare-`notes` strays within a day).
4. **The one hard edge**: registering a namespace another spool owns fails loudly at install.
5. **Third-party spools**: convention to qualify with a project prefix, documented in `docs/writing-shared-spools.md`.
6. **Seed** with the vocabularies settled by F1–F3: `agent-run/*`, `review/*`, `panel/*`, `gate/*`, `note/*`, `kanban/*`, `workflow/*`, `devflow/*`, `roster/*`, and the kept treadle-era survivors.

## Deliberately not built

- No enforcement beyond the duplicate-owner install failure: undeclared attrs still write fine (TEN-002/TEN-004); the hygiene report surfaces them, nothing blocks them.

## Migration

None. Purely additive — no cutover, no restart, no data rewrite.

## Related

- F5 (`2mp13`) follows in the epic (includes the devflow.spool sha re-pin and downstream notes-repo work).
- `bat6m` (refinement): serves one-target-per-run invariant — adjacent relation-hygiene concern, not in scope.
