# Strand Model

**Document ID:** `SPEC-001` **Status:** Implemented **Last Updated:** 2026-08-06 **Code:** `src/millstrand/core/db.clj`

## SPEC-001.P1 Purpose

The strand model defines the durable local data contract for the Millstrand graph: strand records, core state lifecycle, explicit burn deletion, open-ended JSON attributes, named strand-to-strand relations, durable acyclic relation declarations, and readiness semantics.

## SPEC-001.P2 Strand records

A strand has:

- `id` — generated unique text id.
- `title` — non-blank strand title.
- `state` — lifecycle state: `active`, `closed`, or `replaced`.
- `attributes` — userland JSON object projected from row-backed attribute storage.
- `created_at` — set on insert.
- `updated_at` — changed on strand update.

`state="active"` is the only lifecycle state that participates in readiness and can block dependents. Generic create/update paths accept `active` and `closed`; `replaced` is reserved for the core supersession operation. Other strand concepts belong in attributes rather than additional core fields.

## SPEC-001.P3 Lifecycle and retention

Active strands have `state="active"`. Closing a strand sets `state="closed"`. Replacing a strand sets `state="replaced"` only through the supersession operation.

Burning a strand explicitly deletes the strand and all incident edges. Burn operations are raw deletion primitives intended for trusted workflows and userland composition. Every burn path — single-op and batch — writes a durable tombstone in the same transaction: a record of the burned strand's core row, its full attribute map (hot and archived), and its incident edges at deletion time, plus when it was recorded.

Tombstones support forensic inspection and hand-recovery, not undo. Recovery creates a strand with a new id and requires the operator to choose which recorded edges to re-create; edges from unburned strands to the burned id are not restored automatically.

## SPEC-001.P4 Attributes

Attributes are userland strand fields such as priority, owner, estimates, due dates, external references, categories, or outcomes. They are not core lifecycle metadata. The attribute map must encode as a JSON object; omitted or nil attributes normalize to `{}`.

Attribute map keys are Clojure keywords or strings. Keyword keys serialize to their full `namespace/name` text when a namespace is present, and JSON attribute reads keywordize object keys. Namespaced userland vocabularies such as `workflow/*` and `agent-run/*` therefore round-trip without collapsing distinct namespaces onto the same local name.

Attribute reads have two tiers. The full tier returns every attribute value verbatim. On the shipped CLI/agent list surfaces, the lean tier replaces any value whose JSON-encoded UTF-8 byte length exceeds 1024 bytes with an omission descriptor; values at or below the floor pass through unchanged. Omission depends only on the encoded value size, not the key's name or use.

The omission descriptor is the typed map `{:millstrand/omitted true :bytes N}`, where `:millstrand/omitted` is literally `true` and `:bytes` is the non-negative JSON-encoded UTF-8 byte length of the omitted value. It is storage-neutral: it means "omitted from this read surface", not "stored elsewhere". No storage mechanism is claimed or implied.

Lean reads are the default only for CLI/agent list-style surfaces (`list`, `ready`, and query-backed listing). Those surfaces cap results before attribute assembly and fail instead of returning a partial set. Point reads (`show`) and trusted in-process reads default to the full, unbounded tier. The trusted spool reader `attr-get` fails loudly when given an omission descriptor and identifies the key, strand id, and `show` recovery command.

Attribute keys may be declared immutable, making them write-once per strand. Any strand may gain an immutable key, but once stored its value cannot be changed, deleted, or archived through full replacement, patches, archive operations, or batch updates. Re-asserting the same value is legal. A patch compares each patched top-level immutable key with its result after recursive JSON merge. Unarchiving restores visibility without changing the value and remains legal; archiving is rejected. Burn remains the explicit destruction path, and its tombstone records the deleted value. Violations fail loudly and identify the key, strand id, existing value, and attempted value.

Immutability is declared per exact key, never per namespace prefix. Storage ships `note/text` and `note/at` as immutable keys; other attributes on the same strand remain mutable. This enforcement does not make a strand a Batteries note or constrain its state and edges.

## SPEC-001.P5 Edges

Strand edges connect `from_strand_id` to `to_strand_id`, have an `edge_type` relation name, and have JSON object attributes. Relation names are valid strings matching `[a-z0-9][a-z0-9._/-]*`; valid names outside Millstrand's shipped operational batteries are accepted as userland annotation relations.

A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.

The `serves` relation is an engine-owned operational edge from a run to the strand whose own work that run carries out (run `--serves-->` served-target); it is the single durable encoding of that delegation and is declared acyclic. A run may serve at most one target — a single outgoing `serves` edge, or none. Writes that would add a second target fail and name the existing target; initialization fails if legacy storage contains a run with multiple targets. `parent-of` expresses structural hierarchy and placement only — a reader never infers serving from a `parent-of` edge — so a run placed structurally beneath a strand and a run serving that strand are recorded by distinct relations.

Storage declares the `notes` relation acyclic but does not impose note shape, lifecycle, or cardinality. The blessed notes API and Batteries operations create a closed note strand with `note/text` (content), `note/at` (write time), optional `note/by` and `note/round`, and an outgoing `notes` edge to its target (note `--notes--> target`). In that convention the edge is the sole target linkage: no target-pointing attribute duplicates it. Writers may add decorating attributes, which remain mutable unless separately declared immutable.

Self-edges fail for every relation. Writes to declared acyclic relations fail when they introduce a cycle within that same relation. Undeclared annotation relations may form non-self cycles.

The blessed `millstrand.api.relations.alpha` namespace publishes an advisory relation catalog with each relation's family, direction, declared-acyclicity flag, and help text. The catalog is documentation, not a storage allowlist or runtime semantics registry; valid relation names outside it remain userland annotations.

## SPEC-001.P6 Batch graph mutation

Core storage exposes a transactional batch graph mutation primitive for trusted workflows. A batch payload may contain top-level `:refs`, `:strands`, `:edges`, and `:burn` entries and commits atomically: every valid strand, edge, and burn mutation commits, or no graph mutation commits.

Batch-local refs are unqualified, non-blank keywords. Top-level `:refs` bind them one-to-one to existing durable strand ids. Strand entries update a bound ref or create an unbound ref; updates may patch `:title`, `:state`, and `:attributes`, while creates require a non-blank `:title` and may set `:state` and `:attributes`. Burn entries accept only bound refs. A ref cannot be updated and burned in one payload, and a newly created ref cannot be burned. Invalid bindings, duplicate refs, unknown refs, and conflicting operations fail before mutation.

Edge entries are ordered `:upsert` or `:remove` operations addressed by local refs. An upsert carries `:from`, `:to`, `:type`, and optional `:attributes`; it creates the edge or replaces the attributes of the matching `(from, to, type)` edge, and may use refs created by the batch. A remove carries exactly `:op`, `:from`, `:to`, and `:type`; both endpoints must be top-level bound refs. It deletes that exact edge identity, regardless of attributes. Removing an absent edge is a strict failure that rolls back the batch. Edge operations execute in submitted order, so a remove followed by an upsert of the same identity has deterministic replacement semantics. Omitted edges are unchanged.

The public `millstrand.api.batch.alpha/apply!` result contains the final ref table and normalized summaries of created, updated, and burned strands plus ordered edge transitions. Each transition has exactly `:op`, the submitted `:from`, `:to`, and `:type`, and `:before`/`:after` values containing either `nil` or a normalized edge row with durable endpoint ids, relation text, and decoded attributes. Upserts report the previous and written rows; removes report the removed row and `nil`. The returned `:edges`, pre-commit hook `:batch/edge-ops`, and `:batch/applied` event `:batch/edges` are the same ordered transition vector.

## SPEC-001.P7 Readiness

A ready strand is a strand with `state="active"` and no direct `depends-on` dependency whose target strand has `state="active"`. Closed and replaced strands do not block readiness.

## SPEC-001.P8 Persistence

Persistence presents every strand's attributes as one public map while keeping the physical representation private. Full reads include hot and archived values; list, readiness, and query reads exclude archived values. Writing an archived key makes that key hot without changing other archived keys. Relation acyclicity and exact-key immutability declarations are durable and enforced on every storage mutation path. The shipped immutable declarations are `note/text` and `note/at`.

Each burn writes a tombstone atomically with deletion. It preserves the core strand row, every attribute with its archive state, all incident edges, and the recording time. Tombstones have no retention or garbage-collection policy. Trusted core and REPL readers can list recent tombstones and look them up by burned strand id; no public alpha API or CLI surface exposes them.

All attribute keys have the same query capability; callers do not register hot keys or depend on storage indexes. Physical schema identity uses a monotonically increasing schema generation. The validated core schema is fixed within a generation, additive auxiliary objects may reach existing worlds without a bump, and structural changes require a maintained forward migration. SPEC-004.C91b–C91d define generation classification, adoption, validation, and migration mechanics; SPEC-004.C92 defines the default file-backed database location.

## SPEC-001.P9 Query fields

Queryable core fields include `:id`, `:title`, `:state`, `:created_at`, `:updated_at`, and attribute paths. Removed lifecycle fields such as `:active`, `:inactive_at`, `:status`, and `:final_at` are not accepted by the core query compiler.

Attribute predicates operate on the candidate strand's non-archived value for the requested key. Comparison and membership predicates, and `:exists`, do not match an absent or archived attribute row, JSON `null`, or a missing nested path. `:missing` matches all four cases. Attribute `:not` distributes through `:and` and `:or`; it negates comparison and membership within a present value, while presence and edge predicates negate existence.

Every attribute key has the same predicate capability: `:=`, `:!=`, `:<`/`:<=`/`:>`/`:>=`, `:in`, `:exists`, `:missing`, and logical composition. The grammar also supports parameters, edge predicates, and conjunction with `ready`. It selects strands but does not shape or aggregate results or order by an attribute. Trusted Clojure callers may instead compose read operations with `millstrand.api.graph.alpha` helpers.

## SPEC-001.P10 Deferred

Parent-scoped lifecycle rules, attribute-level metadata, per-attribute timestamps, and category/outcome taxonomies are not part of the current model. Deletion tombstones now ship (SPEC-001.P3, SPEC-001.P8); still deferred are a tombstone retention policy, an undo/restore operation, and any programmatic (api-tier or CLI) tombstone surface. Immutable attribute keys now ship (SPEC-001.P4, SPEC-001.P8); still deferred are userland registration of additional immutable keys, whole-strand seals, and edge-attribute immutability.
