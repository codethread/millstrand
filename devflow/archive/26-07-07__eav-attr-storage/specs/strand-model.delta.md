# Strand Model delta for eav-attr-storage

**Document ID:** `EAS-DELTA-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-07

## EAS-DELTA-001.P1 Summary

This delta replaces the single `strands.attributes` JSON document with one attribute **row per (strand, key)** (EAV), plus an `archived` flag that gives a cold-payload tier without a second table. It is governed by one hard invariant (`PROP-EavAttrStorage-001` acceptance invariant): **the attribute-map contract is byte-identical on the wire to today's for full-fidelity reads, and no non-archived key is ever less capable or slower than today on hot query paths.** The storage change is invisible above `skein.core.*` (TEN-007).

- **EAV storage (SPEC-001.P8).** An `attributes` table stores one JSON-encoded value per `(strand_id, key)`; the `strands.attributes` document column is removed. Every key becomes independently addressable, patchable, and indexable.
- **Archive tier as a flag (SPEC-001.P4/P8).** An `archived` flag plus `WHERE archived = 0` partial indexes gives a cold tier that hot query paths structurally cannot see. Trusted `archive!` / `unarchive!` primitives move rows across the tier per strand and per explicit key set; archive **policy** is userland.
- **Uniform query capability (SPEC-001.P9).** `[:attr k]` predicates compile to an `EXISTS`/join over non-archived rows with full predicate capability for **every** key and cross-key self-joins. Leaf negation matches today's document form; composite negation over `:and` / `:or` is tracked on kanban card `2yic2`.
- **L0b removal.** The declared-key indexing machinery promoted at `5595fe7` (the `indexed_attr_keys` table, `::specs/indexed-attr-key`, and literal-path emission in `query.clj`) is **removed**; rows make every non-archived key uniformly indexable by construction, so its coverage folds into the new uniform-capability gates.
- **Preserved.** The L0a storage pragmas (WAL/mmap/cache) and the L1 lean-read wire contract (byte floor, `::specs/omitted-attribute-descriptor`, the `attr-get` guard) survive unchanged, because they are storage-neutral (pragmas) or a wire concern (L1), not a document-shape concern.

## EAS-DELTA-001.P2 Contract changes — SPEC-001.P4 Attributes

- **EAS-DELTA-001.CC1:** The attribute-map contract is unchanged above storage. A strand still _has_ a userland JSON object; the keyword/string key semantics, the `namespace/name` round-trip for namespaced vocabularies (`workflow/*`, `shuttle/*`), and the `{}` normalization of omitted/nil attributes are exactly as SPEC-001.P4 states. Only the physical representation moves from one document to one row per key (TEN-007).
- **EAS-DELTA-001.CC2:** Writes validate the whole assembled attribute map against the existing `skein.core.specs` `::specs/attributes` contract before persistence, exactly as today — the map-shape contract is unchanged. Each stored row additionally carries a DB-level `CHECK (json_valid(value))` backstop for the migration, trusted, and direct-SQL paths (`EAS-DELTA-001.D6`); the two layers are complementary, not a relaxation of `::specs/attributes`.
- **EAS-DELTA-001.CC3:** The two read tiers (SPEC-001.P4) are unchanged. The full tier returns every visible attribute value verbatim; the lean tier returns the `::specs/omitted-attribute-descriptor` for values above the fixed 1024-byte floor. The L1 lean tier operates over the **assembled** attribute map at the CLI/agent read surface — the byte floor, the omission descriptor, the caller split (lean `list`/`ready`/query; full `show` and trusted point/lookup reads), and the `attr-get` fail-loud guard operate over the assembled map exactly as they did over the document. The ready path may apply this projection in storage-backed SQL so oversized values are not fetched only to be omitted. Archive visibility is orthogonal to leanness and is fixed by `EAS-DELTA-001.CC4`.
- **EAS-DELTA-001.CC4:** Attribute rows carry an `archived` flag. Full-fidelity point/lookup reads (`show`, trusted reads by id, and subgraph row loads) transparently include archived values — a correct-but-slower cold read is acceptable (TEN-003 forbids _wrong_, not _slow_). Hot list-style paths (`list`, `ready`, query-backed listing, and every query predicate) structurally exclude archived rows by contract (`EAS-DELTA-001.CC12`), both for predicate matching and returned maps. The query author sees no declaration or threshold, but archive policy authors must understand that archiving a key removes it from hot-path membership tests such as `:exists`/`:missing`. Archived keys are the single explicit exception to CC1's byte-identity: they are present in full point reads and absent from hot query paths.
- **EAS-DELTA-001.CC5:** Assembling attribute rows back into the wire JSON object emits keys in **lexicographic key order** (`EAS-DELTA-001.D4`). The reassembled value is a JSON object; object key order is not semantically significant, and consumers must not depend on any order beyond it being deterministic.

## EAS-DELTA-001.P3 Contract changes — SPEC-001.P8 Persistence

- **EAS-DELTA-001.CC6:** An `attributes` table stores strand attributes as one JSON-encoded value per `(strand_id, key)`, shaped roughly `(strand_id TEXT REFERENCES strands(id) ON DELETE CASCADE, key TEXT, value TEXT NOT NULL CHECK (json_valid(value)), archived INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (strand_id, key))`. The primary key covers per-strand assembly as a `strand_id`-prefixed read. `ON DELETE CASCADE` keeps burn/supersede/batch lifecycle automatic exactly as the document column did. The `strands.attributes` document column is **removed**.
- **EAS-DELTA-001.CC7:** A `(key, value)` **partial** index `WHERE archived = 0` makes every non-archived key uniformly filterable and indexable, with no declaration, threshold, or hot/cold split visible to the query author. The exact remaining secondary-index set (for example whether an assembly-path `(strand_id) WHERE archived = 0` partial index earns its keep) is finalized by the benchmark gate (`EAS-DELTA-001.Q1`).
- **EAS-DELTA-001.CC8:** Dropping the `strands.attributes` column is an incompatible core-schema change. `ensure-current-schema!` fails loudly on the old document schema exactly as it does today for any incompatible layout; there is no silent auto-migration (TEN-000@1, TEN-003). Cutover for existing worlds is the explicit trusted migrate op (`EAS-DELTA-003.CC4`).
- **EAS-DELTA-001.CC9:** The declared-key indexing machinery is **removed**: the `indexed_attr_keys` registry table, the per-key `json_extract(attributes, '$."<key>"')` expression indexes, and the `::specs/indexed-attr-key` spec that gated literal SQL emission. Rows make every non-archived key first-class and indexable by construction, so the declared-key concept has no place. The uniform-capability gates (`EAS-DELTA-001.CC12`, and the plan's validation strategy) subsume the coverage the old undeclared-key invariant test carried.
- **EAS-DELTA-001.CC10:** The L0a storage pragmas are preserved unchanged: the weaver datasource opens every world with `journal_mode=WAL`, a non-zero `mmap_size`, and an enlarged `cache_size`. They are storage-neutral open-time settings and change no schema or read shape (SPEC-001.P8, `EAS-DELTA-003.CC1`).
- **EAS-DELTA-001.CC11:** Edge attribute storage is unchanged: `strand_edges.attributes` stays a single JSON `TEXT` document (`EAS-DELTA-001.D3`). EAV applies to strand attributes only. No JSONB assumptions are introduced; strand attribute values stay JSON `TEXT`.

## EAS-DELTA-001.P4 Contract changes — SPEC-001.P9 Query fields

- **EAS-DELTA-001.CC12:** A `[:attr k]` predicate compiles to an `EXISTS`/join over the **non-archived** attribute rows for that strand and key. Full predicate capability is preserved for **every** key — `:=`, `:!=`, `:<`/`:<=`/`:>`/`:>=`, `:in`, `:exists`, `:missing`, and logical composition. Leaf negation has result semantics identical to today's document form. `:not` around `:and` / `:or` composites of attribute predicates can still diverge on missing or archived keys; unification is tracked on kanban card `2yic2`. This is where `PROP-EavAttrStorage-001.G3` is enforced structurally, not by benchmark: no non-archived key is ever less capable than today, because there is no longer a declared/undeclared distinction to make one key less capable than another.
- **EAS-DELTA-001.CC13:** Cross-key predicates compile to self-joins over the `attributes` table (one joined row per referenced key, each constrained to `archived = 0`). The exact self-join SQL shape is a plan-level mechanic validated for plan-parity by the benchmark gate and the uniform-capability regression tests (`EAS-DELTA-001.Q4`).
- **EAS-DELTA-001.CC14:** The bound-parameter-vs-literal-path split from SPEC-001.P9 is **removed** along with the declared-key registry (`EAS-DELTA-001.CC9`). Attribute keys are never spliced literally into SQL text; the query compiler binds keys and values as parameters and gains index usability from the partial index on the rows, not from per-key expression indexes over a document. The old clause requiring re-validation of a declared key against `::specs/indexed-attr-key` before literal emission no longer applies.
- **EAS-DELTA-001.CC15:** Queryable core fields are otherwise unchanged (`:id`, `:title`, `:state`, `:created_at`, `:updated_at`, and attribute paths); removed lifecycle fields stay rejected.

## EAS-DELTA-001.P5 Design decisions

### EAS-DELTA-001.D1 EAV rows, no key interning

- **Decision:** Store the key as repeated `TEXT` in each attribute row. Do **not** add a separate keys table with interned key ids and a join.
- **Rationale:** Metadata keys are short (measured 4–110B) and low-cardinality per strand; interning saves marginal space against a mandatory join on every assembly and scan, plus a keys table and its garbage-collection concern (TEN-004). Interning stays a future measured optimization behind the same map contract; nothing here precludes adding it later.
- **Rejected:** A `strand_attr_keys(id, key)` interning table joined on every read (`PROP-EavAttrStorage-001.D1`).

### EAS-DELTA-001.D2 Archive is a flag, not a second table

- **Decision:** The cold tier is the `archived` flag plus `WHERE archived = 0` partial indexes on one `attributes` table — not a second table, a second attribute concept, or a declaration registry.
- **Rationale:** Hot query paths exclude archived rows via the partial index and an `archived = 0` predicate; full point reads include them. One table with a flag is the least surface that delivers cold-payload isolation (TEN-004), and it is the uniform replacement for the obsoleted L2 overflow tier — every key can be archived, nothing must be declared.
- **Rejected:** A parallel `strand_attr_archive` table, an artifacts-as-second-concept model, or a declared cold-key registry (`PROP-EavAttrStorage-001.S2`, `NG1`/`NG2`).

### EAS-DELTA-001.D3 Edge attributes stay a JSON document

- **Decision:** EAV applies to strand attributes only; `strand_edges.attributes` stays a single JSON `TEXT` document.
- **Rationale:** Edge attributes are measured empty in practice, so there is no scaling pressure to justify a second EAV table; the deep-module contract this feature codifies (TEN-007) is the _strand_ attribute map. Applying EAV to edges would be surface for its own sake (TEN-004).
- **Rejected:** A parallel `edge_attributes` EAV table this feature (`PROP-EavAttrStorage-001.D2`).

### EAS-DELTA-001.D4 Deterministic lexicographic reassembly

- **Decision:** When assembling attribute rows into the wire JSON object, emit keys in lexicographic key order.
- **Rationale:** Reassembling from rows must choose an order; a deterministic one keeps CLI JSON output, golden-file tests, and agent-visible diffs stable across reads. Lexicographic order needs no ordering column and no write-side bookkeeping. Non-determinism would be a silent reproducibility regression (TEN-003 spirit).
- **Rejected:** Insertion-order preservation (needs an ordering column — storage and write surface for no contract need) and undefined row-scan order (non-reproducible output) (`PROP-EavAttrStorage-001.D3`).

### EAS-DELTA-001.D5 L0b machinery is removed, not preserved beside EAV

- **Decision:** Remove the `indexed_attr_keys` table, `::specs/indexed-attr-key`, and the literal-path emission in `query.clj`; fold their coverage into the uniform-capability gates (`EAS-DELTA-001.CC12`).
- **Rationale:** L0b's declared-key registry exists to make a _hand-declared subset_ of keys index-usable over a JSON document. EAV makes **every** non-archived key index-usable by construction, so the registry, its late-declaration-free trusted ops, its spec, and its literal-SQL-splice safety mechanism all guard a problem that no longer exists. Keeping them would be two storage representations and a classification burden the feature exists to dissolve (TEN-004). The former undeclared-key byte-identity invariant is replaced by a uniform-capability invariant: every key has full predicate capability, asserted structurally.
- **Rejected:** Keeping the declared-key literal-path fast path alongside EAV; leaving `::specs/indexed-attr-key` in place as dead surface (`PROP-EavAttrStorage-001.S7`).

### EAS-DELTA-001.D6 App-level `::specs/attributes` plus a row-level JSON backstop

- **Decision:** Keep `::specs/attributes` map validation on the write path as the shape contract, and add a row-level `CHECK (json_valid(value))` on each stored value.
- **Rationale:** `::specs/attributes` preserves the attribute-map shape contract exactly as today at the API boundary; the per-row `CHECK` is a storage-layer backstop that keeps the migration op, trusted lower-level writers, and any direct-SQL path from landing a non-JSON value. They cover different tiers (application shape vs storage validity), so both earn their place — the `CHECK` is the row-level analogue of the document column's old `CHECK (json_valid(attributes))`, not a new constraint on userland shape.
- **Rejected:** Dropping `::specs/attributes` in favor of the row `CHECK` alone (loses the map-shape contract), or dropping the row `CHECK` and trusting every writer (loses the storage backstop the document column had) (`PROP-EavAttrStorage-001.S3`).

## EAS-DELTA-001.P6 Open questions

Plan-level mechanics, not contract questions; none block sign-off.

- **EAS-DELTA-001.Q1:** The exact secondary-index set beyond `(key, value) WHERE archived = 0` (e.g. whether a `(strand_id) WHERE archived = 0` assembly-path partial index earns its keep), finalized by the benchmark gate.
- **EAS-DELTA-001.Q2:** The internal SQL shape for archive/unarchive remains an implementation mechanic. The trusted-surface tier, semantic inputs, result shape, and fail-loud behavior for `archive!` / `unarchive!` are specified in `EAS-DELTA-003.CC4`.
- **EAS-DELTA-001.Q3:** The migrate op's implementation mechanics shipped as `migrate-attribute-storage!` for the document→rows cutover, then were struck from main after the recorded real-world worlds were migrated; see `EAS-DELTA-003.CC4`.
- **EAS-DELTA-001.Q4:** The precise self-join SQL shape for cross-key predicates and the assembly query shape, validated for plan-parity by the benchmark gate and the uniform-capability regression tests.
