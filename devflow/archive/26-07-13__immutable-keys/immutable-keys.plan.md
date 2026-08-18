# Immutable Keys Plan

**Document ID:** `PLAN-Immut-001`

**Feature:** `immutable-keys`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** none

**Root specs:** [strand-model.md](../../specs/strand-model.md)

**Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md)

**Status:** Reviewed

**Last Updated:** 2026-07-12

## PLAN-Immut-001.P1 Goal and scope

Registered attribute keys become write-once per strand, enforced in `skein.core.db` below every API tier; the shipped registration is `note/text` + `note/at`. See [PROP-Immut-001](./proposal.md) and [DELTA-Immut-001](./specs/strand-model.delta.md).

## PLAN-Immut-001.P2 Approach

- **PLAN-Immut-001.A1:** Registry mirrors `acyclic_relations`: an `immutable_keys` table (single `key` column), bootstrap seeding of the two note keys at storage init alongside the shipped acyclic relations, and a cached-per-call membership check. Registration surface stays private — no userland declare op in this feature.
- **PLAN-Immut-001.A2:** One enforcement choke point. All attribute mutations funnel through a small set of private helpers in `skein.core.db`; a single guard fn (given the datasource, strand id, and the proposed key/value change) is called from: `write-attribute-rows!` (covers direct writes, patch value-sets, and the batch update path, which reuse it), `replace-attribute-rows!` (must check BEFORE its delete-all — a replace that drops or changes an immutable key rejects; one that carries the identical value through is legal), `patch-attribute-rows!` nil-entry deletion, and `archive-attributes-in-transaction!` (archiving a registered key rejects; the same helper serves unarchive, which stays legal — a value-preserving visibility restore and the recovery path for rows archived before enforcement existed). Batch and create paths route through these helpers indirectly (batch update → `update-strand!` → `patch-attribute-rows!`; create → `insert-strand!` → `replace-attribute-rows!`), so they need no separate guards — and `replace-attribute-rows!` currently has only the create-path caller, where every write is a first write; its guard exists for future callers, not current behavior.
- **PLAN-Immut-001.A3:** Idempotence by value equality on the decoded JSON value (compare decoded Clojure values, not raw text, so key-order or whitespace differences in encoding do not create false violations). For patch merges the comparison is existing value vs the post-merge patched result for that key, since that is what would be stored.
- **PLAN-Immut-001.A4:** Error shape via `ex-info` with `{:key <attribute-key> :strand-id <id> :existing <value> :attempted <value>}` (attempted `nil` distinguishes deletion/archive attempts), message stating the key is write-once.

## PLAN-Immut-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Immut-001.AA1 | `src/skein/core/db.clj` | `immutable_keys` schema entry + bootstrap; guard fn; call sites in the four mutation helpers |
| PLAN-Immut-001.AA2 | `test/` (db tests) | Write-once coverage across every mutation path incl. batch and idempotent rewrite |
| PLAN-Immut-001.AA3 | `devflow/specs/strand-model.md` | Merge DELTA-Immut-001 at ship |

## PLAN-Immut-001.P4 Contract and migration impact

- **PLAN-Immut-001.CM1:** Additive `CREATE TABLE IF NOT EXISTS` + init-time seeding; existing databases pick both up on open, no migration. Existing worlds may hold rows that already violate write-once history — enforcement is forward-only from upgrade, which is acceptable and not detected retroactively.
- **PLAN-Immut-001.CM2:** Risk: external sha-pinned spools (kanban, devflow) rewriting note attrs would start failing loudly. In-tree survey found no such writer; `make spool-suite-gate` is the detection gate, and a hit means a coordinated spool fix, not a weakening of enforcement.

## PLAN-Immut-001.P5 Implementation phases

- **PLAN-Immut-001.PH1:** Registry + guard + enforcement at all four helpers + db tests (single vertical slice — the enforcement semantics are one coherent contract; splitting per-path would ship a bypassable half-guarantee).
- **PLAN-Immut-001.PH2:** Spec promotion (merge DELTA-Immut-001 into SPEC-001) + notes-contract docstring touch-ups in `skein.api.notes.alpha` if its prose claims need the enforced wording + `make api-docs` regen if docstrings changed.

## PLAN-Immut-001.P6 Validation strategy

Cold focused `clojure -M:test skein.core.db-test skein.notes-test` per slice (notes-test exercises the birth write against enforcement); full locked suite + go tests + smoke + spool-suite-gate + quality gates at land merge-local-verify. The spool-suite-gate run doubles as the external-spool rewrite-risk probe (CM2).

## PLAN-Immut-001.P7 Task context

Read this plan plus DELTA-Immut-001 before code. The guard must read the existing row inside the same transaction as the mutation it guards. Idempotence compares decoded values (A3). Note strands are born closed and carry `note/text`/`note/at` at insert — insert of a NEW strand with immutable keys is a first write and always legal; `insert-strand!` needs no guard beyond what `write-attribute-rows!` provides. TEN-003 applies: reject loudly, never skip silently.

## PLAN-Immut-001.P8 Developer Notes

(append-only)

- **Task 1 (2026-07-12):** Registry + guard + enforcement landed in `skein.core.db`. `immutable-key?` is decoded-value based: guards compare `<-json` of the stored text against `<-json` of `attr-value->json` of the proposed value, so JSON key-order/whitespace never fakes a violation and a keyword vs string that store identically compare equal. `<-json` decodes any JSON value (not just objects), so it serves single attribute values too.
- Enforcement funnels through three private guards before their writes: `guard-immutable-write!` (per-key, in `write-attribute-rows!` — covers direct writes, patch value-sets with the POST-MERGE value, and the batch update path via `update-strand!` → `patch-attribute-rows!`), `guard-immutable-drop!` (nil-patch deletion in `patch-attribute-rows!` and archive in `archive-attributes-in-transaction!`, `:attempted nil`), and `guard-immutable-replace!` (BEFORE the delete-all). Confirmed the only current `replace-attribute-rows!` caller is `insert-strand!` (fresh strand, no rows), so its guard is forward-facing; the batch path routes through `update-strand!` at `apply-batch-in-transaction!`, not a separate write.
- Store the immutable-key text in its stored `json-key` form (`note/text`, `note/at`); guards `json-key` the proposed key before membership checks.
- Done-when gate green: `clojure -M:test skein.core.db-test skein.notes-test` (52 + 11 tests). `make fmt-check lint reflect-check` clean.
- **Task 2 (2026-07-12):** DELTA-Immut-001 merged into `SPEC-001` (P4 write-once contract, P8 `immutable_keys` persistence sentence beside `acyclic_relations`, P10 deferred list); delta marked Merged. `skein.api.notes.alpha` ns + `note!` docstrings now state `note/text`/`note/at` are storage-enforced write-once (SPEC-001.P4), not conventional; `make api-docs` regen (`docs/api/notes.api.md`) committed and idempotent. Cold `skein.notes-test` green (11 tests / 48 assert).
- MI3 disposable-world e2e (own `ws`, never `.skein`): the agent-run contract forbids starting weavers, so enforcement was exercised in-process against a fresh temp world via `with-runtime` through the trusted REPL/weaver.alpha surface (same `skein.core.db` mutation paths the CLI routes to). Against one `note!`-written note (`note/text` "remember this"): update-patch rewrite, nil-patch delete, and `archive!` of `note/text` were each REJECTED with ex-data `{:key ... :strand-id ... :existing ... :attempted ...}` (`:attempted` nil for delete/archive); `note/at` rewrite likewise REJECTED; re-asserting the identical `note/text` value was legal (idempotent). Final `note/text`/`note/at` unchanged.
