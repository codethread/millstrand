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

`state="active"` is the only lifecycle state that participates in readiness and can block dependents. Generic create/update paths accept `active` and `closed`; `replaced` is reserved for the core supersession operation. There is no core `status`, `kind`, `type`, `outcome`, `reason`, retention flag, or final-status taxonomy. Worlds that need task-like outcomes, notes, pages, scratch concepts, or workflow categories store those concepts in attributes.

## SPEC-001.P3 Lifecycle and retention

Active strands have `state="active"`. Closing a strand sets `state="closed"`. Replacing a strand sets `state="replaced"` only through the supersession operation.

Burning a strand explicitly deletes the strand and all incident edges. Burn operations are raw deletion primitives intended for trusted workflows and userland composition. Every burn path — single-op and batch — writes a durable tombstone in the same transaction: a record of the burned strand's core row, its full attribute map (hot and archived), and its incident edges at deletion time, plus when it was recorded.

Tombstones are forensic and support hand-recovery; they are not an undo mechanism. Recovery is operator-driven: the batch graph mutation primitive is ref-addressed, so replaying a tombstone requires assembling a payload from the recorded content (binding refs, choosing which edges to re-create). A recovered strand gets a new id, and edges from unburned strands to the burned id are not restored automatically.

## SPEC-001.P4 Attributes

Attributes are userland strand fields such as priority, owner, estimates, due dates, external references, categories, or outcomes. They are not core lifecycle metadata. Attribute values must encode to a JSON object; omitted or nil attributes normalize to `{}`.

Attribute map keys are Clojure keywords or strings. Keyword keys serialize to their full `namespace/name` text when a namespace is present, and JSON attribute reads keywordize object keys. Namespaced userland vocabularies such as `workflow/*` and `agent-run/*` therefore round-trip without collapsing distinct namespaces onto the same local name.

Attribute namespaces name concepts, not owners. A namespace segment identifies the concept the attribute describes (`agent-run/…`, `review/…`, `panel/…`, `note/…`, `gate/…`), never the spool that happens to write it. Names that ride durable strand data or worker prompts must be self-describing compound nouns a cold reader can decode from `strand show` alone; contributor-internalized names — namespaces, directories, local `:as` aliases — may stay short. Third-party spools qualify their attribute namespaces with a project prefix to avoid collisions with core conventions.

Attribute reads have two tiers. The full tier returns every attribute value verbatim. The lean tier returns attribute values whose JSON-encoded UTF-8 byte length exceeds the fixed 1024-byte floor as an omission descriptor instead of the value; values at or below the floor pass through unchanged. Small metadata keys — the keys queries filter on — are therefore never omitted.

The omission descriptor is the typed map `{:millstrand/omitted true :bytes N}`, where `:millstrand/omitted` is literally `true` and `:bytes` is the non-negative JSON-encoded UTF-8 byte length of the omitted value. It is storage-neutral: it means "omitted from this read surface", not "stored elsewhere". No storage mechanism is claimed or implied.

The descriptor's single source of truth is the `millstrand.core.specs` contract `::specs/omitted-attribute-descriptor` (`s/keys :req [:millstrand/omitted] :req-un [::bytes]`, `:millstrand/omitted` = `#{true}`, `:bytes` = `nat-int?`). Lean-tier emitters construct values that conform to this spec, and consumers discriminate against the spec rather than ad hoc map-shape checks.

The lean tier is the default only for CLI/agent list-style read surfaces (`list`, `ready`, and query-backed listing). Those same surfaces are result-capped by default before attribute assembly, so a large match fails loudly instead of returning a partial set. Point reads (`show`) and all trusted in-process reads default to the full, unbounded tier. The canonical trusted spool reader `attr-get` fails loudly if it is handed an omission descriptor where a raw value is expected; its `ex-info` ex-data is `{:key <attribute-key> :strand-id <strand-id> :recovery "show <strand-id>"}` so the operator can identify the omitted key and fetch the full row.

Attribute keys may be declared immutable, making them write-once per strand. Any strand may gain a row for an immutable key, but once the row exists its value cannot be changed, deleted, or archived on any mutation path — full attribute replacement, patches (a `nil` patch entry is a deletion and is rejected), archive operations, and batch strand updates all enforce it. Re-asserting the identical value is not a violation, so full-map updates that carry the existing value through remain legal; for patch merges the comparison is against the post-merge result. Unarchiving an immutable key is legal — it restores visibility without changing the value, and is the recovery path for rows archived before enforcement existed — while archiving is rejected because it would hide write-once history. Burning a strand that carries immutable keys remains legal: burn is the explicit destruction escape hatch, and burn tombstones durably record what it deletes. Violations fail loudly with `ex-data` naming the attribute key, the strand id, and the existing and attempted values.

Immutability is declared per exact key, never per namespace prefix, so note strands stay open to writer-owned decorating attributes. The shipped declarations are `note/text` and `note/at`, making the note memory contract ("append-only", SPEC-001.P5) storage-enforced rather than conventional.

## SPEC-001.P5 Edges

Strand edges connect `from_strand_id` to `to_strand_id`, have an `edge_type` relation name, and have JSON object attributes. Relation names are valid strings matching `[a-z0-9][a-z0-9._/-]*`; valid names outside Millstrand's shipped operational batteries are accepted as userland annotation relations.

A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.

The `serves` relation is an engine-owned operational edge from a run to the strand whose own work that run carries out (run `--serves-->` served-target); it is the single durable encoding of that delegation and is declared acyclic. A run may serve at most one target — a single outgoing `serves` edge, or none. Writes that would add a second target fail and name the existing target; initialization fails if legacy storage contains a run with multiple targets. `parent-of` expresses structural hierarchy and placement only — a reader never infers serving from a `parent-of` edge — so a run placed structurally beneath a strand and a run serving that strand are recorded by distinct relations.

The `notes` relation is a core-owned operational edge from a closed note strand to the target it annotates (note `--notes--> target`), recording append-only memory attached to that target; it is the single durable encoding of that attachment and is declared acyclic. A note's content lives in self-describing `note/*` attributes, never in a target-pointing attribute, so the edge is the sole linkage and nothing else names the target. The blessed shape is `note/text` (the content) and `note/at` (the write time), with optional `note/by` and `note/round`; note strands are born closed and stay open to decorating attributes owned by their writers.

Self-edges fail for every relation. Writes to declared acyclic relations fail when they introduce a cycle within that same relation. Undeclared annotation relations may form non-self cycles.

The blessed `millstrand.api.relations.alpha` namespace ships a source-visible advisory catalog of this relation vocabulary for agents, config, and REPL workflows: `catalog` data, each entry carrying the relation's family (operational battery vs behavior-free annotation), direction gloss, declared-acyclicity flag, and help text. It is documentation-only — not a storage allowlist or runtime relation-semantics registry — so relation names outside the catalog remain valid userland annotations. The `millstrand.api.vocab.alpha` registry reflects this catalog as owned `:edge` declarations (owner `:millstrand/core`) so the shipped edge vocabulary has one source and never forks. As an `millstrand.api.*.alpha` namespace it carries accretion-based compatibility within the subnamespace.

## SPEC-001.P6 Batch graph mutation

Core storage exposes a transactional batch graph mutation primitive for trusted workflows. A batch payload may contain top-level `:refs`, `:strands`, `:edges`, and `:burn` entries and commits atomically: every valid strand, edge, and burn mutation commits, or no graph mutation commits.

Batch-local refs are unqualified, non-blank keywords. Top-level `:refs` bind local refs to existing durable strand ids, and each existing strand id may be bound by at most one ref in a payload. Missing bound ids, duplicate aliases, invalid ref names, duplicate strand refs, combined update/burn of one ref, and references to unknown refs fail loudly before mutation.

A strand entry addressed to a bound ref updates that strand. Supported patch fields are `:title`, `:state`, and `:attributes`. A strand entry addressed to an unbound ref creates a strand, requires a non-blank `:title`, accepts optional `:state` and `:attributes`, and extends the final ref table with the generated durable id.

Burn entries address existing bound refs only. Burning a newly created ref, burning an unknown ref, or burning a ref patched in the same payload fails loudly. Successful burns physically delete strands and incident edges using normal burn semantics.

Batch edge entries are explicit operations addressed by local refs, one of two closed ops. `{:op :upsert ...}` carries `:from`, `:to`, `:type`, and optional `:attributes`; it inserts a missing edge or replaces attributes on an existing matching `(from, to, type)` edge, and either endpoint may be a ref created earlier in the same payload. `{:op :remove ...}` carries exactly `:from`, `:to`, and `:type` besides `:op` — no `:attributes`, no other keys — and deletes the exact `(from, to, type)` edge; both endpoints must be top-level pre-bound `:refs`, never a ref created earlier in the same payload. Edge attributes are neither a selector nor a precondition for removal. Every edge op validates endpoint refs, a valid relation name, and that neither endpoint is burned in the same payload; upsert additionally validates target existence after create/update resolution, JSON object attributes, universal self-edge prohibition, and declared-relation acyclicity, none of which a remove can violate because deleting a row only shrinks the edge set. A remove whose exact edge is absent — including a wrong direction or a wrong relation type — fails after a clean post-lock lookup with `ex-data` exactly `{:from submitted-from :to submitted-to :from-id resolved-from-id :to-id resolved-to-id :type submitted-type}` and rolls the whole batch back; absence is strict, never idempotent, with no ignore-missing flag. Edge ops execute in submitted `:edges` order inside the one batch transaction, so an ordered `:remove` then `:upsert` of one identity is a deterministic program. Omitted edges do not imply deletion or replacement; unsupported edge operations fail loudly.

The public `millstrand.api.batch.alpha/apply!` result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes. Each edge outcome is one transition with exactly `:op`, `:from`, `:to`, `:type`, `:before`, and `:after`: `:from` and `:to` are the submitted local refs and `:type` the submitted relation text. `:before` and `:after` are each either `nil` or a normalized edge row with exactly `:from_strand_id`, `:to_strand_id`, `:edge_type`, and a decoded-map `:attributes` — durable ids and the full attribute map, never storage JSON. `apply!` normalizes the storage-shaped core result before the hook, returned result, and event. An upsert carries the pre-image row (or `nil` when the edge is new) in `:before` and the written row in `:after`; a remove carries the removed row in `:before` and `nil` in `:after`. Outcomes stay aligned to submitted `:edges` order, and the result `:edges`, the pre-commit hook's `:batch/edge-ops`, and the `:batch/applied` event's `:batch/edges` are equal ordered vectors of these transitions, with no `:edge` alias.

## SPEC-001.P7 Readiness

A ready strand is a strand with `state="active"` and no direct `depends-on` dependency whose target strand has `state="active"`. Closed and replaced strands do not block readiness.

## SPEC-001.P8 Persistence

The `strands` table stores lifecycle state as columns. Attribute values live in the row-backed `attributes` table: one `(strand_id, key, value, archived)` row per attribute, with each `value` stored as JSON `TEXT` and archived rows excluded from hot query/list paths while full point reads can still project the complete attribute map. Writing an archived key resets that key to hot data and does not change any other archived key on the strand. The `strand_edges` table stores relation names and edge attributes as JSON `TEXT`. The `acyclic_relations` table stores durable per-relation acyclicity declarations. The `immutable_keys` table is its attribute-side counterpart, storing durable per-key write-once declarations, seeded at storage init with the shipped note memory keys (`note/text`, `note/at`); enforcement lives in the storage layer's mutation paths, below every API tier. The default weaver-owned database filename is `millstrand.sqlite`. JSONB assumptions are not part of this contract.

The `burn_history` table records a tombstone written atomically with strand deletion. Tombstone content records the burned strand's core fields, attribute map, and incident edges in shapes that map onto the batch graph mutation payload's strand and edge entries, keeping recovery assembly mechanical. No retention or GC policy applies to tombstone rows. The read surface stops at the trusted in-process tier: `millstrand.core.db` exposes tombstone lookup by burned strand id and a recent-burns listing, and `millstrand.repl` wraps these for interactive disaster-recovery sessions. No `millstrand.api.*.alpha` namespace and no CLI surface expose tombstones.

The weaver datasource opens each SQLite database with WAL journaling, a non-zero memory map size, and an enlarged page cache. These pragmas are applied on open for every world and change no schema or read shape.

Storage has no declared hot-key registry. Every hot attribute row is uniformly indexable by `(key, value)` and by `strand_id`, so no userland or API caller declares a key before querying it.

Physical schema identity is versioned by a schema generation: each weaver-owned database records a monotonically increasing integer in SQLite's native `PRAGMA user_version`, starting at generation 1 with released Millstrand (pre-stamp history stays in git and the feature archive). The persistence-evolution contract: the validated core schema is fixed within a schema generation; additive auxiliary tables and indexes created with `IF NOT EXISTS` flow into existing worlds without a generation bump; domain evolution goes through the attribute bag and needs no schema ceremony; and any structural change to the validated core is a generation bump, which owes existing worlds a maintained forward-migration step and folds additive objects into the new generation's baseline (SPEC-004.C91b–C91d). Physical schema and migration mechanics remain implementation details behind the storage boundary; the attribute map remains the userland contract.

## SPEC-001.P9 Query fields

Queryable core fields include `:id`, `:title`, `:state`, `:created_at`, `:updated_at`, and attribute paths. Removed lifecycle fields such as `:active`, `:inactive_at`, `:status`, and `:final_at` are not accepted by the core query compiler.

Attribute predicates compile against the row-backed `attributes` table, not against JSON paths on `strands`. Every predicate uses a correlated `EXISTS` probe over the candidate strand's non-archived row for the requested key; `:missing` negates the probe. Comparison and membership predicates, and `:exists`, are not satisfied by a missing or archived row or by a `NULL` value at the requested JSON path. Attribute `:not` distributes through `:and` / `:or`: comparison and membership leaves negate inside their `EXISTS` probe, while presence and edge leaves negate the probe itself.

Every attribute key has the same predicate capability: `:=`, `:!=`, `:<`/`:<=`/`:>`/`:>=`, `:in`, `:exists`, `:missing`, and logical composition. Archived attributes are excluded from hot query membership.

The query grammar is boundary grammar: it carries named, validated selections for lower-privilege CLI callers while keeping the physical schema private; trusted-side needs route to Clojure read ops composed with `millstrand.api.graph.alpha` helpers.

A proposed grammar form must be needed by a CLI-tier caller that a registered read op (an op registered with `:hook-class :read`, reachable from the CLI like any other op) cannot serve; be selection only, never shaping, aggregation, or ordering by an attribute; and define its semantics across missing attributes, JSON `null`, archived values, negation, parameters, edge predicates, and conjunction with `ready`. It must extend the compile path its domain already owns — attribute-predicate forms go through the uniform correlated `EXISTS` shape above — and never introduce a parallel path for an existing domain: the grammar previously carried two compile shapes for attribute predicates, and composed negation silently diverged from the documented `NULL` semantics until the split was removed.

## SPEC-001.P10 Deferred

Parent-scoped lifecycle rules, attribute-level metadata, per-attribute timestamps, and category/outcome taxonomies are not part of the current model. Deletion tombstones now ship (SPEC-001.P3, SPEC-001.P8); still deferred are a tombstone retention policy, an undo/restore operation, and any programmatic (api-tier or CLI) tombstone surface. Immutable attribute keys now ship (SPEC-001.P4, SPEC-001.P8); still deferred are userland registration of additional immutable keys, whole-strand seals, and edge-attribute immutability.
