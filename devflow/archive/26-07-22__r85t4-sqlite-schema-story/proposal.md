# SQLite schema story proposal

**Document ID:** `PROP-Sss-001`

**Last Updated:** 2026-07-22

**Related RFCs:** None

**Related root specs:** [strand-model.md SPEC-001.P8](../../specs/strand-model.md), [daemon-runtime.md SPEC-004.C91b/C16b](../../specs/daemon-runtime.md)

**Spec deltas:** [specs/strand-model.delta.md](specs/strand-model.delta.md) (`DELTA-Sss-001`), [specs/daemon-runtime.delta.md](specs/daemon-runtime.delta.md) (`DELTA-Sss-002`)

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID so references are globally grepable and do not clash across documents.

## PROP-Sss-001.P1 Problem

Kanban card `r85t4`, from the `ffh75` pre-alpha decision sweep; blocks the release-identity card `rapv5`.

The on-disk `data/skein.sqlite` carries no version identity: no `PRAGMA user_version`, `application_id`, or schema ledger. There is no migration machinery. `skein.core.db/init!` validates an existing database through structural introspection. Its compatibility checks read columns through `PRAGMA table_info` and read `sqlite_master` SQL to detect the legacy `strand_edges` edge-type constraint. Together they reject the two schemas already migrated away from, then throw on mismatch with no forward path.

The release `rapv5` will tighten TEN-000@1 into an alpha-to-alpha compatibility promise for `api.*`. Without a written schema story, the first release that ships a structural change bricks every existing coordination world (kanban/devflow/delegation/notes) while the API surface stays nominally stable. The skew is asymmetric today:

- **New weaver, old db:** detected and thrown, but only for _known-past_ shapes; the throw cannot tell the user which release can still read the data.
- **Old weaver, newer db:** undetectable in general. Structural introspection only rejects shapes the old binary knew to forbid, so an old weaver can open a future-schema world and misbehave silently, violating TEN-003.

The repo has already used two forms of schema evolution. Additive tables (`acyclic_relations`, `scheduler_*`) flow into existing worlds silently via `CREATE TABLE IF NOT EXISTS` (attr-scaling delta CC9/CC10). The EAV cutover (`9e77780`) shipped a trusted, opt-in, parity-verified migrate op that was later struck from main (EAS-DELTA-003.D2). That break predates released users and happened while the schema was still changing rapidly, so it does not set the policy for released Skein. Once releases create user-owned worlds, every supported old generation needs a maintained forward path.

Schema history is hot: five DDL events landed in the repo's first 18 days. "Structure has stopped changing" is not a safe premise. What exists today is an implicit policy with a throw and no stamp or lasting forward path; this feature makes the release-era policy explicit.

## PROP-Sss-001.P2 Goals

- **PROP-Sss-001.G1:** A written persistence-evolution contract in the root specs that `rapv5` can cite when it stamps the release: what may change silently, what constitutes a breaking schema change, and what a breaking change owes existing worlds.
- **PROP-Sss-001.G2:** For generation-aware weavers, both skew directions fail loudly and diagnosably: a weaver opening a database from a _newer_ schema generation must refuse it, and an older-generation failure must name the maintained forward-migration path. Binaries released before schema stamps are outside this guarantee because they cannot retroactively inspect `PRAGMA user_version`.
- **PROP-Sss-001.G3:** Users of a released Skein generation can migrate forward through every later generation without checking out historical code or discarding their workspace. The maintained steps also provide a readable schema history in one dedicated place.
- **PROP-Sss-001.G4:** The ladder stays behind the storage boundary: physical schema and migration mechanics remain implementation details, while the attribute map remains the contract under TEN-007.

## PROP-Sss-001.P3 Non-goals

- **PROP-Sss-001.NG1:** No decision yet on whether migrations run automatically or through an explicit command. The forward path is required; its invocation belongs to planning when the first real migration exists.
- **PROP-Sss-001.NG2:** No changes to attribute storage representation or the attribute-map contract (TEN-007). Attribute-level evolution remains the path for domain change and needs no schema ceremony.
- **PROP-Sss-001.NG3:** No release/version scheme, changelog ritual, or TEN-000@1 rewording. That is `rapv5`, which depends on this card.
- **PROP-Sss-001.NG4:** No conflation with weaver config-generation or spool-state versioning (`:version`/`:migrate-fn` in `skein.api.runtime.alpha`). That is a different axis.
- **PROP-Sss-001.NG5:** No backup/export tooling; workspaces stay plain SQLite files the user can copy.

## PROP-Sss-001.P4 Proposed scope

Adopt a release-era policy of **schema generations, maintained forward migrations, and cheap additive evolution**.

- **PROP-Sss-001.S1 (contract):** SPEC-001.P8 and SPEC-004 gain an explicit persistence-evolution contract: (i) the validated core schema is fixed within a schema generation; (ii) additive auxiliary tables and indexes created with `IF NOT EXISTS` flow into existing worlds without a generation bump; (iii) domain evolution goes through the attribute bag (TEN-007); and (iv) a structural change to the validated core is a generation bump.
- **PROP-Sss-001.S2 (stamp):** Each database records its schema generation as a monotonically increasing integer in SQLite's native `PRAGMA user_version`. The weaver refuses a database stamped with a newer generation than it understands. Databases created before the stamp that match the current shape are adopted as generation 1 by stamping them in place; no row data is touched. Before adoption, a canonical validator must compare Skein-owned tables and indexes with a freshly constructed generation-1 schema. The comparison covers exact columns, order, declared types, nullability, defaults, primary keys, foreign keys, CHECK constraints, index columns and order, uniqueness, and partial-index predicates, rejecting extra columns on managed tables. Validation is two-stage (refined during spec-plan review, `DELTA-Sss-002.CC2a`): pre-DDL screening requires every baseline object present and exact — only additive-since-baseline auxiliary objects may be absent, since the `IF NOT EXISTS` DDL creates them next — and refuses without writing the stamp on any mismatch; the stamp is written only after post-DDL full-equality validation.
- **PROP-Sss-001.S3 (migration ladder):** Every generation bump adds a maintained, versioned migration step. The steps remain available in later releases and compose in order, so a user can migrate from any released generation to the current one. Each step must apply its DDL, data changes, and `user_version` update in one SQLite transaction so failure rolls the whole step back. A step that cannot be transactional must instead define durable resume or rollback semantics and test recovery from every persisted checkpoint before it can ship. The dedicated migration space is also the readable history of physical schema changes.
- **PROP-Sss-001.S4 (error contract):** Incompatible-generation failures report the found and expected generations. Newer generations are refused; older generations point to the maintained migration path instead of today's bare "use a new database or migrate it explicitly".

## PROP-Sss-001.P5 Open questions

- **PROP-Sss-001.Q1:** Adoption write on open: is the metadata-only stamp of an unstamped current-shape database at weaver boot acceptable, or must adoption be an explicit trusted op? Proposed answer: stamp at boot only after the canonical comparison in S2 proves that every managed table and index matches generation 1. Refuse without stamping on any mismatch. A successful adoption is idempotent and touches no row data.
- **PROP-Sss-001.Q2:** Generation numbering: start the stamped era at generation 1, or retro-number pre-release schema breaks? Proposed answer: start at 1. Earlier history stays in git and the feature archive; the maintained ladder begins with released Skein.
- **PROP-Sss-001.Q3:** Where should migration code live? Proposed answer: mill-side Go code, because it can migrate a stopped weaver's database and supports a clean `mill weaver migrate` surface that can migrate all required tables in one pass, explicitly or automatically. JVM-side code remains an option. Defer the decision until the first real migration makes the runtime constraints concrete.
- **PROP-Sss-001.Q4:** What failure contract must a migration step provide? Proposed answer: make the step and its generation-stamp update one SQLite transaction. Admit a non-transactional step only when it has an explicit durable resume or rollback protocol and failure-injection tests prove recovery from each persisted checkpoint.
