# EAV Attribute Storage Proposal

**Document ID:** `PROP-EavAttrStorage-001` **Last Updated:** 2026-07-07 **Related RFCs:** None (no existing RFC covers attribute storage representation; adjacent [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md) defines the query language whose attribute predicates this re-implements over rows without changing their surface) **Related root specs:** [Strand Model](../../specs/strand-model.md) (SPEC-001.P4 Attributes, P8 Persistence, P9 Query fields), [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004.P3a storage model, P4 API boundary), [Alpha Surface](../../specs/alpha-surface.md) **Builds on:** [attr-scaling-ship-now](../26-07-06__attr-scaling-ship-now/proposal.md), merged to `main` at `5595fe7`, and the root-spec clauses it promoted from `ASSN-DELTA-001/002/003`. This branch has been rebased onto that post-merge baseline, so every spec delta here is written against the current root specs: L0a pragmas, L1 lean reads, and the L0b indexed-attribute registry are present and in scope for removal or preservation as described below.

**Evidence base:** the large-attribute scaling assessment (kanban card `bvb0g`) held under `/tmp/claude/attr-scaling-ship-now-brief/`: `SYNTHESIS.md` (the four-spike + cross-vendor synthesis whose §5 side-table measurements bound this design's benchmark envelope), `DESIGN.md` (the semantics/contract POC — one decode seam, `json_patch` null-delete, caller-split reads, cascade lifecycle), `NARROWING.md` (GPT design-narrowing review), `FACTCHECK.md` (opus adversarial fact-check), and `RESULTS-baseline.md` / `RESULTS-inline.md` / `RESULTS-sidetable.md` (the benchmark harnesses this feature adapts to the row schema). The decision to adopt EAV-with-archive over the alternatives below was reached in a design dialogue with the user after that assessment.

## PROP-EavAttrStorage-001.P1 Problem

Skein stores every strand's attributes as a single JSON `TEXT` document in `strands.attributes`, guarded by a `CHECK (json_valid(attributes))`, patched with `json_patch`, and filtered with `json_extract(t.attributes, ?)` (`src/skein/core/db.clj`, `src/skein/core/query.clj`). The `attr-scaling-ship-now` assessment measured three scaling failures that follow from this one-document shape (canonical world, 2026-07-06: ~2k strands, 4.67MB attribute bytes, 83% of bytes in four spool-owned payload keys — `shuttle/prompt`, `shuttle/note`, `shuttle/result`, `body` — while query filters only ever touch tiny metadata keys):

- **F1 — row-count scan floor.** No attribute path is indexable in place: `json_extract(t.attributes, ?)` binds the JSON _path_ as a parameter, which SQLite can never match to an expression index (EXPLAIN-verified). Every filtered `list`/`ready`/query full-scans and JSON-parses every row.
- **F2 — payload materialization penalty.** `json_extract` in a `WHERE` clause materializes each scanned row's whole document regardless of the `SELECT` list, so scan cost grows with payload size once the working set outgrows cache (measured non-linear: +34ms at 50k → +1,551ms at 250k on the heavy profile).
- **F3 — write amplification (real today).** A one-field patch rewrites the whole document's overflow-page chain: a `status` flip on a real 40KB `shuttle/note` row physically writes ~35–48KB. This is write-byte churn (WAL/journal volume, throughput under concurrency, SSD wear), not patch latency.

`attr-scaling-ship-now` ships the free, no-storage-change wins against this — L0a pragmas (WAL/mmap/cache), L1 lean-by-default read surfaces, and L0b declared-key literal-path indexing — and explicitly **defers** the one real storage decision (L2, a declared-key overflow side table) pending a re-measurement round. This feature is that decision, resolved. The user chose to change the storage representation itself rather than bolt an overflow tier onto the document: **replace the single JSON document with attribute rows (EAV) plus an archive tier**, so that the failure modes are addressed structurally and uniformly for every key rather than for a hand-declared subset.

The alternatives considered and rejected in the design dialogue:

- **(a) Transparent hot offload (the deferred L2 overflow table).** Rejected: hydrate-by-default measured 2–5× slower on hot rows, and it requires a declared-keys registry with per-key offload semantics — a second storage representation and a classification burden that EAV dissolves. The archive tier below is the uniform replacement for L2's cold-payload isolation.
- **(b) Artifacts as a second first-class concept.** Rejected as dominated: the append/large-content story it targets already exists as child strands, and it would push an attr-vs-artifact classification decision onto every spool author, forever.
- **(c) Event sourcing.** Rejected on philosophy: strands are **not** a source of truth (devflow docs are — `PHILOSOPHY.md`), so history is not the primitive to optimize, and an event log is the wrong storage primitive for a mutable coordination graph.
- **(d) Beads-style fixed attribute columns.** Rejected: fixed columns close the open key space that spools rely on (shuttle invented `shuttle/*` payload keys without touching core; `SYNTHESIS.md` §1).

## PROP-EavAttrStorage-001.P2 Goals

- **PROP-EavAttrStorage-001.G1 — Deep module (the governing goal).** Storage complexity becomes the core's burden; **the attribute map is the contract.** `skein.api.*` consumers, spool authors, the CLI JSON wire format, and the query language keep exactly today's mental model and usage: a strand _has_ an attribute map. Nothing above `skein.core.*` may need to know attributes are stored as rows. This is a simple-interface / complex-implementation (deep module) discipline, and this feature proposes codifying it as a near-tenet (`PROP-EavAttrStorage-001.S8`).
- **PROP-EavAttrStorage-001.G2 — EAV storage.** Replace the single `strands.attributes` document with one attribute **row per (strand, key)**, JSON-encoding each value, so every key is independently addressable, patchable, and indexable.
- **PROP-EavAttrStorage-001.G3 — Uniform query capability.** Every non-archived key — with no declaration, no threshold, no hot/cold split visible to the query author — keeps full predicate capability (`:=`, `:!=`, `:<`/`:<=`/`:>`/`:>=`, `:in`, `:exists`, `:missing`, logical composition, cross-key). No non-archived key is ever less capable or slower than it is today. The L0b declared-key registry problem **dissolves**: rows make every non-archived key first-class and indexable by construction.
- **PROP-EavAttrStorage-001.G4 — Archive tier without a second concept.** An `archived` flag plus partial indexes gives a cold-payload tier that hot query paths structurally cannot see, without introducing a second table, a second attribute concept, or a declaration registry. Trusted core primitives archive and unarchive; policy stays userland.
- **PROP-EavAttrStorage-001.G5 — Preserve the shipped `attr-scaling-ship-now` wins on top of rows.** L0a storage pragmas and the L1 lean-read wire contract (the byte floor, `::specs/omitted-attribute-descriptor`, the `attr-get` guard) survive unchanged, because they are storage-neutral (pragmas) or a wire concern (L1), not a document-shape concern.
- **PROP-EavAttrStorage-001.G6 — Explicit, loud cutover.** `ensure-current-schema!` fails loudly on the old document schema exactly as today; a single trusted migrate op converts an existing world (including the canonical coordination world) from document to rows. No silent auto-migration (TEN-000@1, TEN-003).
- **PROP-EavAttrStorage-001.G7 — Prove it before merge.** An in-feature benchmark gate (adapted from the card-`bvb0g` harnesses) replaces a separate POC and blocks merge on measured write-amp, scan, and assembly-read targets (`PROP-EavAttrStorage-001.S9`).

**Hard acceptance invariant (governs every goal):** the attribute-map contract at `skein.api.*` is **byte-identical on the wire** to today's for full-fidelity reads, and no non-archived key is ever less capable or slower than today on hot query paths. Archived keys are the explicit cold-tier exception: full-fidelity point reads include them, while hot query/list paths exclude them by contract. The storage change is invisible above `skein.core.*`.

## PROP-EavAttrStorage-001.P3 Non-goals

- **PROP-EavAttrStorage-001.NG1:** No transparent hot-value offload / L2-style declared-key overflow table. EAV + archive is the adopted storage direction; the deferred L2 overflow design is **obsoleted**, not built. Hydrate-by-default reads (measured 2–5× slower) stay ruled out.
- **PROP-EavAttrStorage-001.NG2:** No artifacts-as-second-concept and no event-sourcing model. Both are out permanently unless the user reopens them; the append story stays child strands, and strands stay mutable state, not an event log.
- **PROP-EavAttrStorage-001.NG3:** No fixed/blessed attribute columns and no closing of the open key space. Keys stay arbitrary userland strings.
- **PROP-EavAttrStorage-001.NG4:** No archive **policy** in core. What to archive, and when (e.g. a userland gleaner over closed strands, which today hold ~99% of payload bytes), is userland; core ships only the `archive!`/`unarchive!` primitives.
- **PROP-EavAttrStorage-001.NG5:** No change to the CLI/agent read contract's shape beyond storage: the L1 lean tier, the omission descriptor, the byte floor, and the `show`-vs-`list` tier split are inherited from `attr-scaling-ship-now` unchanged.
- **PROP-EavAttrStorage-001.NG6:** No change to edge attribute storage (`strand_edges.attributes` stays a JSON document — `PROP-EavAttrStorage-001.D2`), no key interning table (`PROP-EavAttrStorage-001.D1`), and no JSONB assumptions (values stay JSON `TEXT`).
- **PROP-EavAttrStorage-001.NG7:** No implementation strategy, phase breakdown, migration mechanics, task slicing, or index/DDL specifics here — those belong in the spec deltas and feature plan.

## PROP-EavAttrStorage-001.P4 Proposed scope

The change lives entirely in `skein.core.*` (schema, write path, query compiler, migration) and its `skein.api.*` decode seam. Everything above it — the CLI wire format, spool readers, named queries, events/views — is contract-unchanged.

- **PROP-EavAttrStorage-001.S1 (EAV storage):** Add an `attributes` table shaped roughly `(strand_id TEXT REFERENCES strands(id) ON DELETE CASCADE, key TEXT, value TEXT NOT NULL CHECK (json_valid(value)) /* JSON-encoded per value */, archived INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (strand_id, key))`. The primary key covers per-strand assembly (a `strand_id`-prefixed read). A `(key, value)` **partial** index `WHERE archived = 0` makes every non-archived key uniformly filterable and indexable. The `strands.attributes` document column is removed; per-value JSON encoding keeps a DB-level JSON validity backstop while application-level validation via `::specs/attributes` preserves the attribute-map shape contract. `ON DELETE CASCADE` keeps burn/supersede/batch lifecycle automatic (as the document column did, and as `DESIGN.md` (4) proved for the overflow-table variant).
- **PROP-EavAttrStorage-001.S2 (Archive tier as a flag, not a table):** The archive tier is the `archived` flag plus the `WHERE archived = 0` partial indexes — **not** a second table. Hot query paths structurally cannot see archived rows: the partial index and an `archived = 0` predicate on the assembly/scan paths exclude them. Full-fidelity point reads (`show`) transparently include archived values (correct-but-slower on cold data is acceptable — TEN-003 forbids _wrong_, not _slow_). Core ships trusted primitives `archive!` / `unarchive!` operating per strand and per explicit key set; archive **policy** is userland (`PROP-EavAttrStorage-001.NG4`).
- **PROP-EavAttrStorage-001.S3 (Patch semantics preserved exactly):** `update-strand!` keeps today's `json_patch`-equivalent contract at the API: per-key merge, a `null` value deletes the key (now a row delete rather than a `json_patch` null-removal), the whole patch is one transaction, and whole-map replace is still supported. The existing `::specs/attributes` spec (`src/skein/core/specs.clj`) continues to validate attribute maps before writes, and the row-level `CHECK (json_valid(value))` preserves a storage-layer backstop for migration, trusted, and direct-SQL paths.
- **PROP-EavAttrStorage-001.S4 (Query compiler over rows, uniform capability):** `[:attr k]` predicates compile to an `EXISTS`/join over non-archived attribute rows for that strand and key; full predicate capability is preserved for **every** key, and cross-key predicates become self-joins. Result semantics are identical to today's document form. This is where `PROP-EavAttrStorage-001.G3` is enforced structurally, not by benchmark.
- **PROP-EavAttrStorage-001.S5 (Preserve L0a + L1 on top of rows):** L0a pragmas apply unchanged (storage-neutral open-time settings). The L1 lean read tier is unchanged: it stays a wire-level transform above the core reads — the fixed byte floor, `::specs/omitted-attribute-descriptor`, the caller split (lean `list`/`ready`/query, full `show` and trusted in-process reads), and the `attr-get` fail-loud guard all operate over the assembled attribute map exactly as they do over today's document.
- **PROP-EavAttrStorage-001.S6 (Migration / cutover):** `ensure-current-schema!` fails loudly on the old document schema as it does today (no silent migration — TEN-000@1). The feature ships **one** trusted migrate op that converts an existing world's `strands.attributes` documents into attribute rows, so existing worlds — including the canonical coordination world, which carries real data — cut over explicitly and once.
- **PROP-EavAttrStorage-001.S7 (Conditional L0b removal — design for both outcomes):** The user may descope L0b from the in-flight `attr-scaling-ship-now` feature, since EAV obsoletes it. This proposal does not depend on the outcome:
    - **If L0b shipped:** this feature **removes** it — the `indexed_attr_keys` registry table, the `::specs/indexed-attr-key` spec, the literal-path emission in `query.clj`, and their tests — folding their coverage into the new uniform-capability gates (`PROP-EavAttrStorage-001.G3`, `S4`). The declared-key concept has no place once every key is a uniformly indexable row.
    - **If L0b was descoped:** this feature simply never introduces a declared-key registry. No cleanup is owed.
    - Nothing elsewhere in this proposal depends on which branch holds.
- **PROP-EavAttrStorage-001.S8 (Codify the deep-module discipline):** Propose adding the deep-module storage principle (`PROP-EavAttrStorage-001.G1`) as a near-tenet in `TENETS.md` or a `PHILOSOPHY.md` design implication: storage representation is the core's burden; the attribute map is the contract; no consumer above `skein.core.*` may depend on the physical shape of attribute storage. The exact placement/wording is settled in spec work.
- **PROP-EavAttrStorage-001.S9 (In-feature benchmark gate — replaces a POC):** Adapt the card-`bvb0g` spike harnesses (`RESULTS-*.md` methodology; the side-table results are the closest measured analogue since EAV generalizes the side table to all keys) to the row schema and demonstrate, **pre-merge**:
    - write-amp on a small-key patch of payload-carrying strands reduced **≥5×** vs the document baseline for rows at or above 16 KiB, with payload-size independence near 1.0× between 16 KiB and 256 KiB payloads;
    - filtered scans at 250k synthetic strands **within the measured document-schema envelope or better**; `ready` may be up to **1.7×** the document baseline. The accepted measured result is **1.69×** after the optimization ladder (`ANALYZE`, then batched two-pass assembly). Rationale: the remaining cost is batch assembly on an unbounded synthetic frontier that both schemas fail interactively anyway; the improvement path is bounded frontier queries, tracked by kanban card `ncso4`;
    - assembly reads (a `list` of 500 strands) **no worse than 2× the document baseline** — the primary EAV risk, since assembling a map from N rows per strand replaces one column read (side-table W3 point reads measured +2–32ms, sub-ms-to-low-ms absolute; the gate must confirm the 500-row assembly case, not just single-row point reads).
    - Serialize timed runs behind `/opt/homebrew/opt/util-linux/bin/flock -w 3600 /tmp/skein-bench.lock`.
- **PROP-EavAttrStorage-001.S10 (Sequencing):** Implementation builds off `main` after `attr-scaling-ship-now` has merged. That prerequisite is now satisfied: `attr-scaling-ship-now` merged at `5595fe7`, and this branch has been rebased onto the current `origin/main`. Spec deltas must be written against the post-merge root specs, where L0a pragmas, L1 lean reads, and the L0b registry are already canonical.

## PROP-EavAttrStorage-001.P5 Settled design decisions

These are the decisions the brief required this proposal to settle explicitly rather than let default silently. Mechanics (DDL, op names, exact validation) are confirmed in spec/plan work; the decisions below are made here.

### PROP-EavAttrStorage-001.D1 No key interning this feature

- **Decision:** Store the key as repeated `TEXT` in each attribute row. Do **not** add a separate keys table with interned key ids and a join.
- **Rationale:** Metadata keys are short (measured 4–110B) and low-cardinality per strand; the space saved by interning is marginal against the cost of a mandatory join on every assembly and scan, a keys table, and its garbage-collection concern (TEN-004, least surface). Interning is a future measured optimization if key-text volume ever proves material; nothing in `S1` precludes adding it later behind the same map contract.
- **Rejected:** A `strand_attr_keys(id, key)` interning table joined on every read now.

### PROP-EavAttrStorage-001.D2 Edge attributes stay a JSON document

- **Decision:** EAV applies to **strand** attributes only. `strand_edges.attributes` stays a single JSON `TEXT` document, unchanged.
- **Rationale:** Edge attributes were measured empty in practice, so there is no scaling pressure to justify the migration and the second EAV table; the deep-module contract this feature is about is the _strand_ attribute map. Applying EAV to edges would be surface for its own sake (TEN-004).
- **Rejected:** A parallel `edge_attributes` EAV table in this feature. (Noted explicitly so a reviewer sees it was considered, not overlooked.)

### PROP-EavAttrStorage-001.D3 Deterministic key ordering on reassembly

- **Decision:** When assembling attribute rows back into the wire JSON object, emit keys in lexicographic key order. The wire value stays a JSON object; JSON object key order is not semantically significant, and consumers must not depend on a particular order beyond it being deterministic.
- **Rationale:** Today the single-document order is whatever Clojure map serialization produced; reassembling from rows must choose an order, and a deterministic one keeps CLI JSON output, golden-file tests, and agent-visible diffs stable across reads. Non-determinism would be a silent regression in reproducibility.
- **Rejected:** Insertion-order preservation (would require an ordering column, adding storage and write surface for no contract need) and undefined/row-scan order (non-reproducible output).

### PROP-EavAttrStorage-001.D4 Events/views payload shape unchanged

- **Decision:** Event and view payloads that embed strand attributes carry the **same assembled attribute map** they carry today; their shape does not change.
- **Rationale:** They consume the attribute-map contract, which is byte-identical on the wire (`PROP-EavAttrStorage-001.G1`). Full-fidelity in-process consumers get the full map; read-surface consumers get the L1 lean projection — both exactly as today. The storage change is invisible to them.
- **Rejected:** Any event/view payload reshape driven by the row storage.

### PROP-EavAttrStorage-001.D5 CLAUDE.md SQLite-debug docs are rewritten in-feature

- **Decision:** The "Debugging SQLite state" section of `CLAUDE.md` (which today shows `select id, title, attributes from strands`) is rewritten in this feature to query the new `attributes` table (e.g. `select strand_id, key, value, archived from attributes`), since the old query dies with the document column.
- **Rationale:** Leaving stale debug docs that reference a dropped column would misdirect every future contributor; the doc update is owned here, not deferred.
- **Rejected:** Leaving the CLAUDE.md debug snippet stale, or treating it as out-of-scope docs work.

## PROP-EavAttrStorage-001.P6 Open questions

Genuinely plan-level mechanics, not contract questions — all deferred to spec/plan work, none blocking sign-off:

- **PROP-EavAttrStorage-001.Q1:** The exact secondary-index set beyond the `(key, value) WHERE archived = 0` partial index (e.g. whether a `(strand_id) WHERE archived = 0` assembly-path partial index earns its keep), finalized by the benchmark gate.
- **PROP-EavAttrStorage-001.Q2:** The `archive!` / `unarchive!` trusted-surface tier and exact spec-defined IO shape. The proposal settles the semantic input domain as strand-id plus optional explicit key set; spec work names the functions, schemas the args/results, and chooses the trusted namespace.
- **PROP-EavAttrStorage-001.Q3:** The migrate op's implementation mechanics for cutting the canonical world over. Spec/plan work names the trusted op `migrate-attribute-storage!` and fixes its idempotency, result shape, and parity-mismatch failure contract.
- **PROP-EavAttrStorage-001.Q4:** The precise self-join SQL shape for cross-key predicates in `query.clj` and the assembly query shape, to be validated for plan-parity by the benchmark gate and the uniform-capability regression tests.

Resolved during spec/plan work:

- **PROP-EavAttrStorage-001.Q5.RESOLVED:** The deep-module principle lands in `TENETS.md` as `TEN-007`.
