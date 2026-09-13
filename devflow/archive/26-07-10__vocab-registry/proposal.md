# Vocabulary registry: spools declare attr namespaces and edge types as runtime-owned guidance

**Document ID:** `PROP-Vr-001` **Last Updated:** 2026-07-10 **Related brief:** [brief.md](./brief.md) (scope is the contract) **Related epic:** `kaans` (cards `ah5vu`, `7azzl`, `41pna`, `2mp13`); this is F4, card `41pna`

**Predecessors:** `agent-engine-primitives` (`PROP-Aep-001`, card `ah5vu`, landed `3b99997`; `serves` relation live) and `note-primitive` (`PROP-Np-001`, card `7azzl`, landed `bd49eb2`; `notes` relation + `note/*` shape + `skein.api.notes.alpha` live) **Related root specs:** [Strand Model](../../specs/strand-model.md), [Alpha Surface](../../specs/alpha-surface.md), [CLI Surface](../../specs/cli.md) **Related sources:** `src/skein/api/relations/alpha.clj`, `src/skein/api/runtime/alpha.clj`, `spools/src/skein/spools/selvage.clj`, `spools/src/skein/spools/carder.clj`, `spools/src/skein/spools/batteries.clj`, `spools/agent-run/src/skein/spools/agent_run.clj`, `spools/delegation/src/skein/spools/delegation.clj`, `spools/kanban/src/skein/spools/kanban.clj`, `src/skein/core/db.clj`

**Reading context:** this proposal assumes the Skein vocabulary (strands, edges, relations, spools, batteries surface, spool-state, install-time activation) defined in `docs/skein.md`, `docs/writing-shared-spools.md`, and the spool READMEs, and the devflow document chain: brief (scope contract) → this proposal's C-clauses (design contract) → `PLAN-Vr-001` slices (sequencing) → `TASK-Vr-*` files (execution contracts). Every point ID is a grepable anchor. Source citations name a stable site — a function or op (e.g. `spawn-run!` in `skein.spools.agent-run`) — and treat that name as the load-bearing reference. Any `file:line` is secondary: as verified at authoring in the `vocab-registry` worktree (forked from `main@bd49eb2`; F1–F3 landed) and not durable, since line numbers move as nearby code moves.

## PROP-Vr-001.P1 Problem

Attribute namespaces and edge types are conventions with no machine-readable record. Three costs follow, all of them already anticipated by the strand-model spec, which states that "ownership is registered in the runtime, not encoded in the key" (`strand-model.md:34`) — a registry that does not yet exist.

- **Discovery is doc-first.** Finding that `review/*`, `gate/*`, or `panel/*` exists requires reading the right spool doc first. The spool sources write them — `review/target`/`review/roster`/`review/pass` (`delegation.clj:1071-1073`), `panel/seat`/`panel/turn` (`delegation.clj:1320-1321`), `gate/run-id`/`gate/delivered` (`subagent.clj:98,103`) — but nothing enumerates the settled families for a cold agent.
- **Strays live undetected.** A key belonging to no declared vocabulary (the pre-F3 bare `notes`/`note`/`verify-note` shapes are the worked example, resolved by `PROP-Np-001`) can sit in live data for weeks. Carder reports stale, orphan, and failure-blocked strands (`carder.clj:191`) but has no section for "this attribute belongs to no declared vocabulary," so a stray namespace is invisible to hygiene.
- **Nothing guards ownership.** Two spools can claim the same namespace with different meanings and nothing objects at install. The engine already fails loudly when a second process double-publishes the ambient runtime; there is no equivalent guard for a namespace claim.

The adjacent surfaces that _look_ like a registry are not one. `skein.api.relations.alpha` ships a source-visible advisory catalog of edge relations — `catalog` data plus `relation`/`operational-relations`/`annotation-relations` lookups (`alpha.clj:8,72,77,82`) — but it is a static `def`, edge-types only, with no owner field and no install-time claim. Selvage ships a per-runtime vocabulary registry (`defvocab!`/`vocabs`, `selvage.clj:97,111`) but it lints attribute _values_ against `:enum`/`:kind`/`:required-with` checks and replaces on duplicate name; it records no ownership and raises nothing on a collision. Neither answers "who owns `review/*`, and does this live attribute belong to any declared vocabulary."

## PROP-Vr-001.P2 Goals

- **PROP-Vr-001.G1:** One runtime-owned registry of vocabulary declarations — attribute namespaces and edge types — each carrying an owner, its keys/direction, and a doc. Guidance, not a schema: it records what is declared, it does not gate writes (TEN-002/TEN-004).
- **PROP-Vr-001.G2:** Spools declare at install through their existing `install!` path, so the registered surface is derived from installed code and can never drift from a hand-maintained list.
- **PROP-Vr-001.G3:** One read verb — `strand vocab` — lists every declared vocabulary, solving "you need to know the other things exist first."
- **PROP-Vr-001.G4:** Two opt-in consumers reuse the declarations without new enforcement: selvage can cross-check its value-vocabularies against declared ownership, and a carder hygiene section flags attributes in live data belonging to no declared vocabulary — the check that would have surfaced the bare-`notes` strays within a day.
- **PROP-Vr-001.G5:** Exactly one hard edge (TEN-003): registering a namespace another owner already holds fails loudly at install, mechanically like the runtime's double-publish guard.
- **PROP-Vr-001.G6:** Purely additive. No migration, no data rewrite, no cutover, no weaver restart; a `.skein` config change is picked up by `reload!` per the pickup ladder (PROP-Vr-001.C12).

## PROP-Vr-001.P3 Non-goals

- **PROP-Vr-001.NG1:** No enforcement beyond the duplicate-owner install failure. Undeclared attributes still write fine (TEN-002/TEN-004); the carder hygiene section surfaces them, nothing blocks them. This is the design center — see PROP-Vr-001.C13.
- **PROP-Vr-001.NG2:** No new storage. The registry is in-memory runtime state (like every other registry and like selvage's vocabularies), not a table. Declarations are derived from installed code, not durable user data (PROP-Vr-001.C2).
- **PROP-Vr-001.NG3:** No second edge catalog. The edge-type dimension is seeded from `relations.alpha`'s existing catalog, not re-listed (PROP-Vr-001.C5).
- **PROP-Vr-001.NG4:** No change to selvage's value-linting model. Selvage keeps declaring `:enum`/`:kind`/`:required-with` checks; this feature only lets it _reference_ the ownership registry (PROP-Vr-001.C7).
- **PROP-Vr-001.NG5:** No value validation in the registry itself. It records that a namespace is owned; it does not know or check what values its keys may hold (that is selvage's job).

## PROP-Vr-001.P4 Approach

The work is a set of design clauses (C1..C13). C1 fixes the declaration shape; C2 places the registry; C3 defines `declare!` and the one hard edge; C4 the queryable read; C5 the seed (core-owned edges and `note/*` from `vocab.alpha`'s own init-fn, spool attr namespaces from each owning spool's `install!`); C6 the `strand vocab` verb; C7–C8 the two opt-in consumers; C9 the third-party prefix convention; C10 spec/doc deltas; C11 the alpha-surface disposition; C12 atomic additive landing and the pickup ladder; C13 what is deliberately not built. Each clause names the exact call sites it touches so the plan can be verified against the tree.

## PROP-Vr-001.C1 — the vocabulary declaration shape

A declaration is a data map, modelled on the `relations.alpha` catalog entry shape (`alpha.clj:11-15`) and extended with the two fields that catalog lacks — `:owner` and, for namespaces, `:keys`:

```clojure
{:kind      :attr-namespace          ; or :edge
 :name      "review"                 ; the namespace segment, or the edge-type name
 :owner     :skein/spools-agents     ; declaring module: a spool's init.clj use-key; core-owned uses :skein/core or the owning skein.api.*.alpha ns
 :keys      ["review/target" "review/roster" "review/pass"]  ; known keys, :attr-namespace only
 :doc       "Review pass bookkeeping written by the delegation review flow."}
```

- **`:kind`** is `:attr-namespace` or `:edge`. Namespaces name the concept, never the spool (`strand-model.md:34`); the owner is a separate field, exactly as the spec prescribes.
- **`:owner`** is the declaring module. A spool-owned namespace is owned by the spool's use-key — the keyword `init.clj` registers with, e.g. `:skein/spools-agents`. A core-owned declaration is owned by its declaring core module: the reflected shipped edges by `:skein/core` (the engine relation set from `relations.alpha`), and a core primitive's own namespace by the owning `skein.api.*.alpha` namespace, e.g. `note/*` by `skein.api.notes.alpha`. It is the collision key for the hard edge (C3) and the attribution shown by `strand vocab` (C6).
- **`:keys`** enumerates the known keys of an `:attr-namespace` (advisory — a namespace may grow keys the declaration has not caught up with; the carder consumer flags by _namespace_, not by exact key, so an undeclared key under a declared namespace is not a stray). Omitted for `:edge`, which is a single relation name.
- **`:doc`** is a one-line human note, mirroring `relations.alpha`'s `:help`.

Edge declarations additionally carry the `relations.alpha` fields (`:family`, `:direction`, `:declared-acyclic?`) because they are seeded from that catalog verbatim (C5); the registry adds `:owner` and `:kind :edge` around them.

## PROP-Vr-001.C2 — the registry and its home

A new blessed namespace **`skein.api.vocab.alpha`** owns the registry (Q1, adopted at sign-off). Rationale:

- The declarations are cross-spool guidance data owned by the runtime, not by any one spool: agent-run, delegation, kanban, workflow, and roster each declare into it. Housing it inside a reference spool's internals would force the other spools to depend on that spool's internals — the same argument that placed the note primitive in `skein.api.notes.alpha` rather than in agent-run (`PROP-Np-001.Q2`).
- It reuses the `relations.alpha` advisory-catalog _idiom_ — a vector of declaration maps plus by-name and by-dimension lookups — rather than inventing a second registry shape (the brief's "generalize that pattern" instruction). What it adds over `relations.alpha` is what that static `def` cannot provide: dynamic install-time registration, an owner field, and a duplicate-owner failure.

The backing store is per-runtime state via `runtime/spool-state` (`runtime.alpha.clj:251`), the same mechanism selvage uses for its vocabularies (`selvage.clj:28-29`). It is versioned per the shape-drift discipline (`selvage.clj:16-22`, `docs/writing-shared-spools.md` "Versioned spool state"): a `state-version` next to the `new-state` builder, pinned by a `skein.spools.test-support/assert-state-shape` drift test, so a post-upgrade reload reinits rather than reusing a shape-mismatched map. This is in-memory only — no new table, no migration (NG2). The `new-state` builder is also the seed site (C5): it returns the initial registry already carrying the core-owned declarations, so the seed is reached by the first read or `declare!` on a fresh runtime, with no `install!` hook.

## PROP-Vr-001.C3 — `declare!` and the one hard edge

`(vocab/declare! runtime declaration)` validates the C1 shape (fail loud on unknown/missing keys via `skein.spools.util/reject-unknown-keys!`/`fail!`, the selvage pattern at `selvage.clj:60-95`) and records it under `[:kind :name]`. It carries the sole enforcement in the feature:

- **The hard edge (G5, TEN-003).** If `[:kind :name]` is already held by a _different_ `:owner`, `declare!` throws `ex-info` with `:name`, `:kind`, `:existing-owner`, and `:declaring-owner`. Because each owning spool calls `declare!` from its `install!`, this throw propagates through the module `:call` and aborts activation loudly — mechanically the same posture as the runtime's atomic double-publish guard (a second claim of an owned slot fails, it does not silently win).
- **Idempotent for the same owner.** Re-declaring `[:kind :name]` with the _same_ `:owner` replaces the entry and does not throw. This is required, not incidental: `reload!` re-runs every `install!` (`runtime.alpha.clj:30`), and `spool-state` survives reload (`runtime.alpha.clj:251`), so an owner re-declaring its own namespaces on reload must be a no-op-shaped replace, never a self-collision. The collision is strictly cross-owner. This spool-state reload model is Q2, adopted.

"Fails loudly at install" therefore means: the declaring spool's `install!` raises, the `use!` activation for that module fails, and cold start / `reload!` surfaces the conflicting-owner data — no sensible default, no last-writer-wins.

## PROP-Vr-001.C4 — the queryable read surface

`skein.api.vocab.alpha` exposes the data-first `declarations` read:

- **`(vocab/declarations runtime)`** — all declarations, sorted deterministically (by `[:kind :name]`), each the full C1 map.
- **`(vocab/declarations runtime {:kind :attr-namespace})`** / `{:kind :edge}` — one dimension.
- Callers derive a singular namespace or edge lookup by filtering the full declaration vector. An undeclared namespace remains a valid userland namespace (NG1).

All take `runtime` as the first argument (the shared-spool discipline, `docs/writing-shared-spools.md` rule 1); there is no ambient-singleton path.

## PROP-Vr-001.C5 — the seed

Two sources, no duplication.

- **Edges are seeded from `relations.alpha` (NG3).** The `vocab.alpha` spool-state init-fn (`new-state`) reads `relations.alpha/catalog` and wraps each entry as an `:edge` declaration owned by `:skein/core` (the shipped engine set — the one owner that is not a spool use-key), preserving `:family`/`:direction`/`:declared-acyclic?`. `relations.alpha` stays the single source of shipped-edge truth (depends-on, parent-of, supersedes, serves, notes as operational acyclic; related-to, duplicates, references, implements, verifies, tracks, caused-by as annotation), and `vocab.alpha` reflects it rather than re-listing it. This keeps the edge vocabulary from forking (Q3, adopted).
- **`note/*` is seeded with the edges, core-owned.** The durable writer is `skein.api.notes.alpha/note!` (`notes/alpha.clj:52`) — core F3 code with no spool use-key; the batteries `note` op is one delegating caller, not the owner. So the same `new-state` init-fn that reflects the edges also declares `note/*` as an `:attr-namespace` owned by the owning core namespace `skein.api.notes.alpha`, exactly parallel to the edge seed. It is part of the core seed, not a spool `install!` declaration; nothing in S2 chooses its owner.
- **Attribute namespaces are declared by each owning spool's `install!`.** Each spool that writes a durable namespace calls `vocab/declare!` from its existing `install!`, owned by that module's use-key from `.skein/init.clj`. The confirmed spool seed carries exactly one owner per namespace, each pinned to a real write site in this tree:

    | Namespace | Owner (init.clj use-key) | Declaring module | Write site (stable ref; line as-verified) |
    | --- | --- | --- | --- |
    | `agent-run/*` | `:skein/spools-shuttle` | `skein.spools.agent-run` | `spawn-run!` reserves the control attrs (`agent_run.clj:1611`) |
    | `gate/*` | `:skein/spools-treadle` | `skein.spools.executors.subagent` | `deliver-run!`/`spawn-for-gate!` stamp `gate/*` (`subagent.clj:114,184`) |
    | `review/*` | `:skein/spools-agents` | `skein.spools.delegation` | `roster-review-specs` builds the `review/*` attrs (`delegation.clj:1071-1073`) |
    | `panel/*` | `:skein/spools-agents` | `skein.spools.delegation` | `panel-specs` writes `panel/seat`/`panel/turn` (`delegation.clj:1320-1321`) |
    | `kanban/*` | `:skein/spools-kanban` | `skein.spools.kanban` | `add!` writes `card-attributes` (`kanban.clj:150`; builder `:85`) |
    | `workflow/*` | `:skein/spools-workflow` | `skein.spools.workflow` | `step-strand`/molecule build inside `compile` (`workflow.clj:518,771`) |
    | `roster/*` | `:skein/spools-roster` | `skein.spools.roster` | `track-attributes` builds the entry attrs (`roster.clj:97`) |

    Each owner is the single module whose `install!` declares the namespace. The subagent gate executor (`:skein/spools-treadle`) owns `gate/*` even though its source sits in the agent-run package: the activation module, not the file location, is the owner. `gate/*` is the treadle-era survivor the brief keeps (Q4, adopted); any residual survivor is enumerated from the live tree at implementation, not guessed here. Namespaces that surfaced in a naive grep but are _not_ durable strand attributes — `peer/*` (error codes, `peers/alpha.clj`), `batch/*` and `mutation/*` (event payload keys, `chime.clj`/`graph/alpha.clj`), `handle/*` (in-memory run backend handles written under `agent-run/handle.*`, `agent_run.clj`) — are deliberately excluded from the seed.

    **`devflow/*` is out of scope for this feature — a named cross-feature dependency, not an implementation TODO.** It is not written in this tree: the external `codethread/devflow` spool writes it and this repo only reads it (`roster.clj:420,424`). The pinned spool (see the `codethread/devflow` entry's `:git/sha` in `.skein/spools.edn` for the current pin) has no `vocab/declare!` site, so F4 cannot seed it truthfully. After F4 lands, `devflow/*` shows up in the carder hygiene section (C8) as undeclared — deliberately; that is the report doing its job. The declaration plus the `devflow.spool` sha re-pin belong to F5 (card `2mp13`, which already owns that re-pin); F4 adds no core row for it.

## PROP-Vr-001.C6 — the `strand vocab` read verb

`strand vocab` registers as one new batteries read op, the note/notes precedent (`batteries.clj:462-476`; F3 showed a batteries op needs no `cli.md` delta, `SPEC-Np-003`). It joins `op-registrations`:

- `['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]` — flag `--kind` (`attr-namespace`|`edge`, optional); delegates to `skein.api.vocab.alpha/declarations`. It reads the runtime from `:op/runtime` like every other batteries op (`batteries.clj:330,336`).

JSON output is an ordered array of declaration maps (the C1 shape, string-keyed at the wire boundary), optionally narrowed by `--kind`. Batteries is a classpath-shipped reference spool whose contract is `spools/batteries.md` (SPEC-005.C3), so the verb is in-contract through that doc; the per-command entry is added there (C10). This is the only CLI surface the feature adds — declaration is a trusted install-time action, not a CLI verb (TEN-006). Batteries op, not a `.skein` config op, is Q5, adopted.

## PROP-Vr-001.C7 — selvage consumer (opt-in cross-check)

Selvage keeps its value-linting model unchanged (NG4). This feature adds one opt-in read: selvage can list the declared attribute namespaces (`vocab/declarations runtime {:kind :attr-namespace}`) and cross-check that each vocabulary it lints names a _declared_ namespace — surfacing a selvage vocabulary that lints keys nobody owns, and vice versa. This is a new read-only helper on the selvage surface (e.g. `selvage/undeclared-checks` returning the checks whose `:attr` namespace has no declaration), registered nowhere by default; it is composition sugar over `check`/`vocabs`, not a new enforcement path. It reuses `vocab.alpha` explicit-runtime reads and adds no watch behaviour.

## PROP-Vr-001.C8 — carder consumer (undeclared-namespace hygiene)

Carder gains one report section, in the shape of its existing sections (`carder.clj:191-207`): **undeclared** — active strands carrying an attribute whose namespace segment is declared by nobody. It reads `vocab/declarations runtime {:kind :attr-namespace}` for the declared set, walks active strands (the `active-strands` path, `carder.clj:72-76`), and flags each strand → attribute key whose namespace is absent from the declared set. It flags by _namespace_, not exact key (C1: a declaration's `:keys` are advisory), so `review/newfield` under declared `review/*` is clean while a bare `verify-note` or an unowned `frobnicate/*` is flagged. It is read-only (carder mutates nothing, `carder.clj:8`) and joins `report` as a fourth section alongside `stale`/`orphans`/`blocked-by-failure`. This is the check that would have caught the bare-`notes` strays within a day (brief §3).

## PROP-Vr-001.C9 — third-party prefix convention

The rule that third-party spools qualify their attribute namespaces with a project prefix already exists in prose (`strand-model.md:34`). This feature lands the _authoring_ rule where spool authors read it: a short subsection in `docs/writing-shared-spools.md` stating that a shared spool declares its namespaces (via `vocab/declare!` from its `install!`), qualifies them with a project prefix (`acme/…`) so they never collide with core or another author's namespaces, and that a colliding claim fails loudly at install (C3). No enforcement of the prefix itself — it is convention backed by the duplicate-owner edge, consistent with the guidance-not-enforcement center (NG1).

## PROP-Vr-001.C10 — spec and doc deltas

- **`devflow/specs/strand-model.md`** (`:34`, attribute-namespace prose): the sentence "ownership is registered in the runtime, not encoded in the key" gains a concrete referent — name `skein.api.vocab.alpha` as the runtime registry that records ownership, and note the third-party-prefix rule is backed by its duplicate-owner install failure. The relations advisory-catalog paragraph (`:56`) gains one line noting `vocab.alpha` reflects the edge catalog as owned `:edge` declarations.
- **`docs/writing-shared-spools.md`**: the third-party prefix subsection (C9) and a note that a shared spool declares its vocabulary from `install!`.
- **`spools/batteries.md`**: the per-command contract for `strand vocab` (C6), following the existing command entries.
- **`spools/selvage.md`** and **`spools/carder.md`**: the new opt-in helper (C7) and the undeclared hygiene section (C8) in each spool's Surface table — repo-local reference-spool docs, their own cadence (SPEC-005.C3).
- **`devflow/specs/alpha-surface.md`**: SPEC-005.C2's enumerated blessed set gains `skein.api.vocab.alpha` (C11).

## PROP-Vr-001.C11 — alpha-surface disposition

- **`skein.api.vocab.alpha`** (C2) is a new blessed spool-facing namespace — added to SPEC-005.C2's enumerated set (`alpha-surface.md:12`), accretion-compatible within its subnamespace, exactly the disposition `skein.api.notes.alpha` took in F3 (`PROP-Np-001.C12`, `SPEC-Np-002`).
- **The edge seed from `relations.alpha`** (C5) touches only reads of that already-blessed namespace (SPEC-005.C2); nothing in `relations.alpha` changes, so its catalog test (`relations_test.clj:6`) is untouched.
- **`strand vocab`** (C6) is a batteries op, in-contract via `spools/batteries.md` (SPEC-005.C3), not the alpha index.
- **selvage and carder** (C7/C8) are classpath reference spools in-contract via their docs (SPEC-005.C3); their surfaces accrete one helper each.
- No `skein.core.*` change: the registry is runtime spool-state, not storage init, so there is no `db.clj` delta and no strand-model storage-semantics change (contrast `notes`, which needed a `db.clj` acyclic declaration).

## PROP-Vr-001.C12 — atomic additive landing and the pickup ladder

- **PROP-Vr-001.R-additive — one landing, no cutover.** The namespace, the seed declarations, the `strand vocab` verb, and the two consumer helpers land together. There is no data migration, no rewrite, and no historical concern (the registry reads installed code, not stored strands), so unlike F3 there is no separate signed cutover step.
- **Pickup ladder.** New Clojure namespaces and the batteries op arrive with the JVM/spool load; the Go CLI change for `strand vocab` needs only `make build` (a batteries op is registered arg-spec data, not new Go dispatch). If the `.skein` world's own config declares a namespace (a repo-policy vocabulary), that config change is picked up by `runtime/reload!` — no weaver restart, because nothing changes at the JVM/transport level. Smoke-test any `.skein` config change in a disposable world first.
- **PROP-Vr-001.R-reload.** The one reload subtlety is C3's idempotent-same-owner requirement: `reload!` re-runs every `install!` against a surviving `spool-state` registry, so same-owner re-declaration must replace, not collide. This is a design invariant of `declare!`, tested directly (P6).

## PROP-Vr-001.C13 — deliberately not built

- **No write-time enforcement.** Undeclared attributes and edges still write and read exactly as today. The registry is guidance; the carder section surfaces strays, nothing blocks them (TEN-002/TEN-004, NG1). This is the design center: the system stays malleable, and the only place it refuses is the cross-owner install claim (C3).
- **No value schema.** The registry records ownership of a namespace, not the shape of its values. Value invariants remain selvage's opt-in `:enum`/`:kind`/`:required-with` checks (NG5); the registry and selvage compose (C7) but neither absorbs the other.
- **No durable storage of declarations.** They are in-memory runtime state rebuilt from installed code every load (NG2). A declaration is never authority the way a strand attribute is; the code that declared it is the record (PHILOSOPHY: the code wins).

## PROP-Vr-001.P5 Sequencing and risks

- **PROP-Vr-001.R1 — same-owner reload collision.** The largest correctness risk is C3's idempotency: if `declare!` treated a same-owner re-declaration as a collision, every `reload!` (and the second cold-start pass in some flows) would abort activation. Mitigation: the collision is defined strictly cross-owner and is the first tested case (P6).
- **PROP-Vr-001.R2 — seed/owner accuracy.** A wrong `:owner` on a seed namespace would mislead `strand vocab` and could create a phantom collision if two modules both claim it. Mitigation: each spool namespace is declared from the single spool that writes it, owner = that spool's use-key, with the write site verified (C5 table); the core seed (edges, `note/*`) is declared once from `vocab.alpha`'s init-fn against its owning core module; the treadle-era survivor set is enumerated from the live tree at implementation, not guessed (Q4).
- **PROP-Vr-001.R3 — carder false positives.** Flagging by exact key rather than namespace would flag every legitimate new key under a declared namespace. Mitigation: C8 flags by namespace segment; a declaration's `:keys` are advisory (C1).
- **PROP-Vr-001.R4 — state-shape drift.** `spool-state` survives reload, so a later shape change to the registry map could silently reuse a stale value. Mitigation: the versioned-state discipline (C2) with a `state-version` and an `assert-state-shape` drift test, exactly as selvage does (`selvage.clj:16-22`).

## PROP-Vr-001.P6 Validation gates

All green in one landing:

- `make build`
- `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (new: `vocab.alpha` registry tests — declare/query, cross-owner collision throws, same-owner idempotent, seed reflects `relations.alpha`; selvage cross-check helper; carder undeclared section; the `assert-state-shape` drift test)
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings)
- `make api-docs` — clean regen; `git status --short` shows only the expected `spools/*.api.md` changes for the touched spools (batteries, selvage, carder) plus the new `vocab.api.md` if a `vocab` doc is added
- `git status --short` clean of generated SQLite and runtime metadata artifacts

## PROP-Vr-001.P7 Done-when

- **PROP-Vr-001.DW1:** `skein.api.vocab.alpha` exists and is in SPEC-005.C2; `declare!` records a C1 declaration, throws on a cross-owner claim, and is idempotent for the same owner; `declarations`/`declaration` read it with explicit runtime.
- **PROP-Vr-001.DW2:** The seed is live — `strand vocab` lists the confirmed F1–F3 spool namespaces (agent-run, gate, review, panel, kanban, workflow, roster), each with a single owner, plus the core seed carried by `vocab.alpha`'s init-fn: the edge types reflected from `relations.alpha` and the core-owned `note/*` (owner `skein.api.notes.alpha`). `devflow/*` is out of scope (F5, card `2mp13`) and stays undeclared here by design. The edge set is not duplicated in `vocab.alpha` source (Q3).
- **PROP-Vr-001.DW3:** `strand vocab` exists as a batteries read op with `--kind`, contracted in `spools/batteries.md`.
- **PROP-Vr-001.DW4:** Carder's report carries an `undeclared` section flagging active strands with an attribute in no declared namespace; selvage exposes the opt-in cross-check helper. Neither blocks any write (NG1).
- **PROP-Vr-001.DW5:** The third-party prefix authoring rule is in `docs/writing-shared-spools.md`, backed by the C3 duplicate-owner failure; `strand-model.md:34/:56` name the registry.
- **PROP-Vr-001.DW6:** All P6 gates green in one atomic, additive landing — no migration, no cutover, no weaver restart.

## PROP-Vr-001.P8 Design decisions

Zero open questions: Q1–Q5 were decided at proposal sign-off and folded into the C-clauses named below. This section keeps each question, its adopted resolution, and the rationale for the record.

- **PROP-Vr-001.Q1 — registry home (resolved).** New `skein.api.vocab.alpha`, fold into `relations.alpha`, or keep it as selvage-adjacent spool state? **Resolution (adopted): new `skein.api.vocab.alpha` (folded into C2).** It is cross-spool guidance data owned by the runtime (same argument that gave notes its own namespace), it needs owner-stamped install-time registration and a duplicate-owner failure that `relations.alpha`'s static `def` cannot carry, and folding it into a reference spool would force other spools onto that spool's internals. It reuses `relations.alpha`'s catalog idiom rather than inventing a second one, so "generalize, don't reinvent" is honoured at the API-shape level.
- **PROP-Vr-001.Q2 — reload model: spool-state vs a reload-cleared registry (resolved).** The registry could survive reload (`spool-state`) or be cleared and rebuilt on each `reload!` like the op/view registries. **Resolution (adopted): `spool-state` with versioned shape and idempotent same-owner `declare!` (folded into C2 and C3).** It needs no new core registry surface (Less is More), matches selvage exactly, and the only cost — same-owner re-declaration on reload — is handled by the C3 idempotency rule and a test. A reload-cleared registry would remove that subtlety but adds core surface for an advisory store; not worth it.
- **PROP-Vr-001.Q3 — edge declarations: seed from `relations.alpha` or re-declare in `vocab.alpha`? (resolved).** **Resolution (adopted): seed (reflect) from `relations.alpha` (folded into C5).** One source of shipped-edge truth, no fork; `vocab.alpha` wraps each catalog entry as an owned `:edge` declaration at its install. If a future spool needs to declare a _new_ edge type with ownership, `vocab/declare!` accepts `:kind :edge` directly — the seed path and the declare path coexist.
- **PROP-Vr-001.Q4 — treadle-era survivor seed set (resolved).** The brief keeps "the treadle-era survivors" but names only families by example. **Resolution (adopted): seed the confirmed families (corrected C5 table, chiefly `gate/*` owned by `:skein/spools-treadle`) and enumerate any residual survivor from the live `.skein` world at implementation (folded into C5).** This mirrors F3 measuring its rewrite counts at cutover time rather than hardcoding the brief's stale figures. The planner treats the C5 confirmed seed as the core and the residual sweep as a bounded implementation step.
- **PROP-Vr-001.Q5 — where `strand vocab` lives: batteries op or `.skein` config op? (resolved).** **Resolution (adopted): batteries op (folded into C6)**, the note/notes precedent — the registry is engine-owned guidance data, so its read verb belongs with the shipped reference surface, not repo-local config. `SPEC-Np-003` established a batteries op needs no `cli.md` delta.
