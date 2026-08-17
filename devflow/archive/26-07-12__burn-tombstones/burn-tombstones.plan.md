# Burn Tombstones Plan

**Document ID:** `PLAN-Tomb-001` **Feature:** `burn-tombstones` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [strand-model.md](../../specs/strand-model.md) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) **Status:** Reviewed **Last Updated:** 2026-07-12

## PLAN-Tomb-001.P1 Goal and scope

Every burn durably records what it deleted, atomically with the deletion, and a coordinator can inspect and hand-recover burned data from the REPL. See [PROP-Tomb-001](./proposal.md) for why and [DELTA-Tomb-001](./specs/strand-model.delta.md) for the contract change.

## PLAN-Tomb-001.P2 Approach

- **PLAN-Tomb-001.A1:** Single choke point. Both burn paths already funnel through the private strand-deletion helper inside a transaction in `skein.core.db`; the tombstone insert lands there, so no caller changes and no path can skip it. Assemble the tombstone (core row, full attribute map including archived rows, incident edges) with reads inside the same transaction, before the deletes.
- **PLAN-Tomb-001.A2:** Batch-payload-shaped content. Store the captured strand and edges as JSON whose shapes map onto the batch graph mutation payload's `:strands` and `:edges` entries. The batch primitive is ref-addressed, so recovery still requires the operator (or a future helper) to assemble the payload — bind refs, pick edges to re-create; the stored shape keeps that assembly mechanical rather than interpretive.
- **PLAN-Tomb-001.A3:** Read tiers stop at the REPL. `skein.core.db` gets tombstone-by-burned-id and recent-tombstones read fns; `skein.repl` gets a thin interactive wrapper. Nothing under `skein.api.*.alpha`, no CLI verb, no Go changes.

## PLAN-Tomb-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Tomb-001.AA1 | `src/skein/core/db.clj` | `burn_history` schema entry; tombstone capture + insert in the deletion helper; read fns |
| PLAN-Tomb-001.AA2 | `src/skein/repl.clj` | Interactive recovery wrapper over the core read fns |
| PLAN-Tomb-001.AA3 | `test/` (db tests) | Tombstone coverage for single-op burn, batch burn, archived attrs, edges |
| PLAN-Tomb-001.AA4 | `devflow/specs/strand-model.md` | Merge DELTA-Tomb-001 at ship |

## PLAN-Tomb-001.P4 Contract and migration impact

- **PLAN-Tomb-001.CM1:** Additive `CREATE TABLE IF NOT EXISTS burn_history` — same class as the scheduler tables; existing databases pick it up on open, no migration. Contract change staged in DELTA-Tomb-001.

## PLAN-Tomb-001.P5 Implementation phases

- **PLAN-Tomb-001.PH1:** Core storage — schema, capture-and-insert in the deletion transaction, read fns, db tests. Independently verifiable via the cold focused db test run.
- **PLAN-Tomb-001.PH2:** REPL wrapper + spec promotion — `skein.repl` convenience fn with docstring, merge DELTA-Tomb-001 into SPEC-001, update the delta status. Verifiable via focused repl-surface tests and docs gates.

## PLAN-Tomb-001.P6 Validation strategy

Cold focused `clojure -M:test` on the db and repl test namespaces per slice; full locked suite + `(cd cli && go test ./...)` + `clojure -M:smoke` + fmt/lint/reflect/docs gates at land merge-local-verify. Manual REPL check of the recovery round-trip (burn → inspect tombstone → batch replay) in a disposable workspace during PH2.

## PLAN-Tomb-001.P7 Task context

Workers read this plan plus DELTA-Tomb-001 before touching code. Tombstone JSON must use the existing `->json`/`<-json` helpers and stay JSON `TEXT` — no JSONB assumptions. The capture read must include archived attribute rows (full tier, not hot-only). Edge attributes are already JSON text on the row — normalize through the existing decode/encode helpers rather than embedding raw text, so the tombstone is one coherent JSON document. Error handling follows TEN-003: a failed tombstone insert fails the burn transaction; never burn without recording.

## PLAN-Tomb-001.P8 Developer Notes

(append-only)

- 2026-07-12 review (run 28wvn): `burn-by-ids!` validates ids before opening its transaction, so a concurrent burn between validation and delete is a pre-existing adjacent gap — out of scope here; tombstone capture must simply tolerate ids already gone inside the tx (delete of a missing row is a no-op; capture what exists at transaction time).
- 2026-07-12 Task 1 (run wb3cn): `burn_history` attributes column stores the full map as `{"<key>": {"value": <decoded>, "archived": <bool>}}` — one coherent JSON document where every key marks its archived flag, so recovery strips to `:value` (dropping/keeping archived keys per operator choice) to feed a batch `:strands` `:attributes`. Edges store `{:from :to :type :attributes}` per edge (durable ids, both directions), decoded via the JSON helpers, mapping onto batch `:edges` upsert entries. Capture lives in the private `capture-burn-tombstone!` called at the top of `delete-strands!` before the deletes, so both `burn-by-ids!` and the batch `:burn` path get it with no caller change; a missing id short-circuits via `when-let` (no tombstone, no error) and no try/catch means a failed insert aborts the burn (MI4/TEN-003). Attribute values and edge attributes are decoded with `<-json` and re-encoded with `->json`/`json/write-str` so nothing is embedded as raw JSON text. The skip path is covered by testing `#'capture-burn-tombstone!` directly on an absent id, since inducing a mid-tx disappearance in a single-threaded test is not practical.
- 2026-07-12 Task 2 (run s93h9): recovery round-trip verified in a disposable workspace via an embedded `:publish? false` runtime bound with `current/with-runtime` (in-process, no daemon). Created `design` + `docs` (`docs --depends-on--> design`), burned `docs`; `(repl/strand docs)` → nil while `(repl/burn-history docs)` returned one tombstone carrying `title "Write docs"`, `attributes {:note/text {:value "keep" :archived false} :owner {:value "agent" :archived false}}`, the `depends-on` edge, and `recorded_at`; `(repl/recent-burns 5)` listed the same burned id. Assembled a batch payload by stripping each attribute to `:value`, binding surviving `design` under `:refs`, and re-creating the edge from the recovered ref, then replayed through `db/apply-batch!`. The recovered strand got a **new id** (`qb1l3` ≠ original `lo1j2`) — new-id caveat confirmed — with attributes and the `depends-on` edge restored. The repl wrappers `burn-history`/`recent-burns` are in-process only: they resolve `current/runtime-or-nil` → `access/ds` → `skein.core.db` reads and throw remediation pointing at `mill weaver repl` when no live runtime is bound (covered by repl-surface tests).
