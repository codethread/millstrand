# Blessed note primitive: core notes relation, note/* shape, batteries surface

**Document ID:** `PROP-Np-001` **Last Updated:** 2026-07-09 **Related brief:** [brief.md](./brief.md) (scope is the contract) **Related epic:** `kaans` (cards `ah5vu`, `7azzl`, `41pna`, `2mp13`); this is F3, card `7azzl`

**Predecessors:** `agent-layer-rename` (`PROP-Alr-001`, card `26o9g`, landed `c79abb6`) and `agent-engine-primitives` (`PROP-Aep-001`, card `ah5vu`, landed `3b99997`; `serves` relation live) **Related root specs:** [Strand Model](../../specs/strand-model.md), [Alpha Surface](../../specs/alpha-surface.md), [CLI Surface](../../specs/cli.md)

**Related sources:** `spools/agent-run/src/skein/spools/agent_run.clj`, `spools/delegation/src/skein/spools/delegation.clj`, `spools/kanban/src/skein/spools/kanban.clj`, `spools/src/skein/spools/batteries.clj`, `src/skein/api/relations/alpha.clj`, `src/skein/core/db.clj`

**Reading context:** this proposal assumes the Skein vocabulary (strands, edges, relations, spools, batteries surface) defined in `docs/skein.md` and the spool READMEs, and the devflow document chain: brief (scope contract) → this proposal's C-clauses (design contract) → `PLAN-Np-001` slices (sequencing) → `TASK-Np-*` files (execution contracts). Every point ID is a grepable anchor. Line numbers are as verified in the `note-primitive` worktree (forked from `main@3b99997`).

## PROP-Np-001.P1 Problem

Annotation is not yet a graph concept. "A note attached to strand X" is written three ways that do not compose, so the answer to "what notes does X carry" depends on which writer you ask.

- **Agent notes** write a `notes` edge plus `note/*` attributes on a closed strand. `note!` (`agent_run.clj:1933`) stamps `note/for`, `note/text`, `note/at`, optional `note/by`/`note/round`, and a `notes` edge to the target (`agent_run.clj:1950-1955`). But the _reader_ `notes` (`agent_run.clj:1958`) walks the `note/for` _attribute_, not the edge (`agent_run.clj:1962-1964`). Two encodings of one fact, and only one is authoritative on read.
- **Kanban notes** write a different shape entirely: `note!` (`kanban.clj:332`) creates a closed child strand carrying `kanban/note "true"`, `kind "note"`, and the text in `body` (`kanban.clj:345-350`), attached with a `parent-of` edge (`kanban.clj:352`). The reader `card-subtree` (`kanban.clj:414`) walks the `parent-of` subgraph and filters `note-strand?` on `kanban/note` (`kanban.clj:372`); `compact-note` reads `body`/`kanban/handover` (`kanban.clj:357-365`). So kanban overloads `parent-of` for annotation — the same overload F2 removed from serving.
- **Readers are per-writer.** `kanban card` shows only kanban notes; `agent notes` shows only agent notes. This is the `m630j` false-loss: a note "disappears" when read through the other surface because neither reader walks a shared relation.

Two data defects ride along, both confirmed read-only against the live canonical world (reproducible recipe: resolve the db with `./bin/mill weaver status --workspace <canonical>` → `database_path`, open as an `immutable=1` snapshot):

- **Cascade divergence (67 dangling).** `strand_edges.to_strand_id` carries `ON DELETE CASCADE` (`db.clj:177`), so burning a note's target auto-strips the `notes` edge; but the `note/for`/`shuttle/note-for` attribute lives on the _note_ strand and only cascades when the note itself is burned (`db.clj:167`). Burn a target and the attribute survives pointing at a missing strand while the edge is gone. Exactly 67 shuttle-era notes are in this state, and the arithmetic closes: 1588 `notes` edges = (1600 `shuttle/note-for` − 67 dangling) + 55 post-F1 `note/for`.
- **Historical unreadability.** 1600 shuttle-era notes keep old keys (`shuttle/note`, `shuttle/note-for`, `shuttle/note-by`) and 356 kanban notes keep the `body`/`parent-of` shape. A unified read surface that walks the blessed relation would show none of them — recreating the `m630j` false-loss on every pre-cutover note.

## PROP-Np-001.P2 Goals

- **PROP-Np-001.G1:** One declared `notes` relation (core-owned): a closed strand attached to a target is append-only memory. It removes the kanban `parent-of` overload, consistent with F2's structure-only `parent-of`.
- **PROP-Np-001.G2:** One blessed `note/*` shape and one write/read primitive that every writer routes through — core owns the shape and relation; spools own decorating meaning (`kanban/handover`, `review/pass`, `note/round`).
- **PROP-Np-001.G3:** A batteries read/write surface — `strand note <id> "text"` and `strand notes <id>` — whose read walks the declared relation regardless of writer, resolving `m630j`.
- **PROP-Np-001.G4:** Make the cascade divergence structurally impossible, not merely repaired once.
- **PROP-Np-001.G5:** Land the code atomically (relation, shape, primitive, batteries verbs, kanban/delegation migration, cascade fix, specs, docs), then run a separate signed one-shot HISTORY rewrite so every pre-cutover note is readable through the unified surface.

## PROP-Np-001.P3 Non-goals

- **PROP-Np-001.NG1:** No dual-read or compatibility shim on the live path (TEN-000@1: alpha, change without migration). The unified reader walks one encoding; historical notes are made readable by the rewrite (PROP-Np-001.C10), not by a reader that understands the old shapes.
- **PROP-Np-001.NG2:** No structured review findings. Tags-in-text (53% of notes) stays as-is; if it graduates, the decorating-attr slot on the note strand is where it lands (PROP-Np-001.C14).
- **PROP-Np-001.NG3:** No large-attribute scaling work. `kbcjt` (306KB max note text) stays adjacent and out of scope (PROP-Np-001.C14).
- **PROP-Np-001.NG4:** No `devflow/archive/*` edits — the archive is the historical record and keeps its old vocabulary.
- **PROP-Np-001.NG5:** No change to the kanban card model, delegation flags, or review/panel/council flows beyond routing their note writes/reads through the primitive.

## PROP-Np-001.P4 Approach

The work is a set of design clauses (C1..C14). C1–C4 build the core relation, shape, and primitive; C5 adds the batteries surface; C6–C7 migrate the two live writers onto the primitive; C8 fixes the cascade divergence; C9 records spec/doc deltas; C10–C11 cover the HISTORY rewrite and historical compatibility; C12 records the alpha-surface disposition; C13 covers atomic landing and the separate signed cutover; C14 records what is deliberately not built. Each clause names the exact call sites it changes so the plan can be verified against the tree.

## PROP-Np-001.C1 — the `notes` relation

- **Semantics.** `notes` is a core-owned relation, note → target, meaning "this closed strand is append-only memory attached to that strand." It is the single durable encoding of the attachment. It already exists as an undeclared edge written by `note!` (`agent_run.clj:1955`); this feature declares it.
- **Acyclicity.** Declared acyclic, added to `shipped-acyclic-relations` (`db.clj:217`, currently `#{"depends-on" "parent-of" "supersedes" "serves"}`), so bootstrap declares it in `acyclic_relations` (`db.clj:267`). A note is born closed and points at its subject; notes never form a cycle in normal use, and a note-of-a-note thread is a DAG. Declaring it acyclic makes a malformed self- or cyclic note-edge fail loudly rather than corrupt a relation-scoped traversal, matching `serves`/`supersedes`. This is the one genuine design judgment — see PROP-Np-001.Q3.
- **Family.** `notes` is an _operational_ battery (it has a shipped read surface that walks it), not a behavior-free annotation convention. That distinction drives the catalog entry (PROP-Np-001.C2) and the acyclicity choice above. TEN-005 ("annotation edges carry no acyclicity guarantee") does not bind here: `notes` is not being classified as an annotation relation, it joins the shipped operational set.

## PROP-Np-001.C2 — relation catalog entry

Add a `notes` operational entry to the advisory catalog `catalog` (`src/skein/api/relations/alpha.clj:8`), beside `serves` (`alpha.clj:26`):

```clojure
{:relation "notes"
 :family :operational
 :direction "note --notes--> target"
 :declared-acyclic? true
 :help "Append-only memory: a closed note strand attached to its target."}
```

The catalog is documentation-only (not a storage allowlist), so this is a source-visible advisory addition; any catalog-set assertion in the relations test updates with it.

## PROP-Np-001.C3 — the blessed `note/*` shape

Core owns the shape; spools decorate. A note strand is:

- **Born closed** (it is memory, not work), title truncated from the text (`agent_run.clj:1945-1946`).
- **Content attributes (self-describing, never a pointer):** `note/text` (the note body), `note/at` (sub-second timestamp — the core `created_at` column is seconds-only and cannot order a burst, `agent_run.clj:1947-1949`), optional `note/by` (author run/agent id), optional `note/round` (council/review round).
- **Linkage:** the `notes` edge (C1) is the _sole_ encoding of "for which target." `note/for` is **removed** from the blessed shape (PROP-Np-001.C8 explains why: a target-pointing attribute is exactly what dangles on target deletion).
- **Open to decoration:** spools layer their own meaning as additional attributes — `kanban/note`, `kanban/handover`, `review/pass` — without the core primitive knowing that vocabulary.

## PROP-Np-001.C4 — the note primitive and its home

Today the primitive lives inside the agent-run spool (`agent_run.clj:1933` write, `:1958` read). It is now cross-spool (kanban and delegation both call it), so it moves to a blessed spool-facing home: a new `skein.api.notes.alpha` namespace exposing `note!` and `notes`. See PROP-Np-001.Q2 for the placement decision.

- **`note! [target-id text {:by :round & decorating-attrs}]`** — validates non-blank text (`agent_run.clj:1941-1942`) and target existence (`agent_run.clj:1943-1944`), creates the closed note with the C3 content attrs plus any caller-supplied decorating attrs, and writes the `notes` edge (`agent_run.clj:1955`). It writes no `note/for`.
- **`notes [target-id {:round}]`** — walks _incoming_ `notes` edges to the target (the declared relation), loads each note strand, orders by `note/at` then `created_at`/`id` (`agent_run.clj:1966`), optionally filters `note/round`, and projects `{:id :note :at :by? :round?}` (`agent_run.clj:1968-1972`). This is the behavioral change: the reader stops filtering the `note/for` attribute (`agent_run.clj:1962-1964`) and walks the edge instead, so it returns notes from every writer that used the primitive, regardless of decorating attrs. The agent-run spool re-exports or requires these so its existing callers keep working.

## PROP-Np-001.C5 — the batteries surface

`strand note` and `strand notes` register as two new batteries ops, giving annotation a CLI verb at the root. They join `op-registrations` (`batteries.clj:428-440`):

- `['note note-arg-spec :mutating 'skein.spools.batteries/note-op]` — positionals `id`, `text`; flags `--by`, `--round`; delegates to `skein.api.notes.alpha/note!`.
- `['notes notes-arg-spec :read 'skein.spools.batteries/notes-op]` — positional `id`; flag `--round`; delegates to `skein.api.notes.alpha/notes`.

Both read the runtime from `:op/runtime` like every other batteries op (`batteries.clj:206`). JSON output shapes match the primitive so agent tooling is stable:

- `strand note` → `{"id": "<note-id>", "target": "<target-id>"}` — `target` is an output-only projection derived from the `notes` edge, never a stored attribute (C8). The old `note-for` wire name dies with the attribute; the delegation `agent note` output adopts the same projection (its contract is the spool README, rewritten in C9).
- `strand notes` → an ordered array of `{"id", "note", "at", "by"?, "round"?}` (mirrors `agent_run.clj:1968-1972`).

Batteries is a classpath-shipped reference spool whose contract is `spools/batteries.md` (SPEC-005.C3), so the new verbs are in-contract through that doc; the per-command contract text is added there (PROP-Np-001.C9).

## PROP-Np-001.C6 — kanban migration

Kanban notes move onto the blessed shape; the decorating attrs stay.

| Site | Today | Replacement |
| --- | --- | --- |
| `kanban/note!` write `kanban.clj:332-355` | closed child strand with `kanban/note`+`kind "note"`+`body`, attached by `parent-of` (`:352`) | Call `notes.alpha/note!` on the card with `text`, passing decorating attrs `kanban/note`/`kanban/handover` (on `--handover`). Text → `note/text`; `notes` edge replaces `parent-of`; `kind "note"` kept as a decorating attr. |
| `note-strand?` `kanban.clj:372-375` | `= "true" (attr kanban/note)` | Unchanged — `kanban/note` stays a decorating marker. |
| `card-subtree` `kanban.clj:414-431` | splits the `parent-of` subgraph into `notes` (note-strand?) and `work` | Notes come from incoming `notes` edges to the card (the primitive's read), so notes no longer ride `parent-of`; `work` stays the `parent-of` subgraph. This is the overload removal (G1). |
| `compact-note` `kanban.clj:357-365` | reads `:body`, `kanban/handover` | Reads `note/text` (falling back to nothing pre-rewrite — rewrite guarantees it, C10), keeps `kanban/handover`. |
| `card` / `latest-handover-for` / `handover-line` `kanban.clj:434-448,494-500,603-608` | filter kanban notes by `kanban/handover`, read `body` | Filter the primitive's notes by the `kanban/handover` decorating attr; read `note/text`. Behavior unchanged; source relation changed. |

The kanban card model, epic hierarchy, and all non-note `parent-of` traversal (`card-subtree` `work`, epic subgraph `kanban.clj:488`) are untouched.

## PROP-Np-001.C7 — delegation note verbs

`agent note`/`agent notes` already delegate to the primitive; the only change is the primitive's new home. `op-note` (`delegation.clj:1618-1624`) and `op-notes` (`delegation.clj:1627-1634`) call `agent-run/note!`/`agent-run/notes`; they now call `skein.api.notes.alpha/note!`/`notes` (directly or via the agent-run re-export, C4). The dispatch (`delegation.clj:1946-1947`), arg-spec (`delegation.clj:1883-1888`), and the review/panel/council note-posting flows that read through `agent notes` (`delegation.clj:1021,1092,1254-1257`) are otherwise unchanged — they inherit the unified read for free.

## PROP-Np-001.C8 — the cascade-divergence fix

**Invariant:** the `notes` edge is the _sole_ linkage encoding; nothing else points a note at its target. Because linkage is encoded once, target deletion cannot make two encodings disagree — there is no second encoding.

**Enforcement point:** the writer (`note!`, C4) stops writing `note/for`, and the reader (`notes`, C4) walks the edge, not the attribute. On target burn, the FK `ON DELETE CASCADE` (`db.clj:177`) removes the `notes` edge and the note becomes correctly unreachable through the relation; its surviving attributes are all self-describing content (`note/text`/`note/at`/`note/by`), none of them a pointer, so none can dangle. This is the TEN-003 fail-loud posture by construction: there is no stale pointer for a reader to trust, and no reconciliation code to get wrong. It also matches F2's no-dual-encoding tenet (NG1).

This is the minimal fix and the reason C3 removes `note/for` rather than keeping it. The alternative — keep `note/for` and cascade-burn note strands when their target burns, enforced in `delete-strands!`/`burn-by-ids!` (`db.clj:887-903`) — also closes the divergence but adds destructive cascade semantics to `burn` and more code. See PROP-Np-001.Q1.

## PROP-Np-001.C9 — spec and doc deltas

- **`devflow/specs/strand-model.md`** (relations section, ~L46-54): add `notes` to the named relation vocabulary as a core-owned operational relation (note → target, append-only memory — the C1 classification) and to the shipped declared-acyclic set alongside `depends-on`/`parent-of`/`supersedes`/`serves` (`db.clj:217`, spec L48).
- **`docs/skein.md`** (Edges and readiness, L213/L227): the relation prose and declared-acyclic list there still predate F2 — `serves` is missing. Rewrite the list once to the full shipped set (`depends-on`/`parent-of`/`supersedes`/`serves`/`notes`), folding in the F2 omission, with a one-line gloss of the batteries `note`/`notes` verbs.
- **`src/skein/api/relations/alpha.clj`**: the catalog entry (C2).
- **`spools/batteries.md`**: per-command contracts for `note` and `notes` (C5), following the existing command entries.
- **`spools/kanban.md`** (the kanban spool contract doc — there is no `spools/kanban/README.md`) and **`spools/delegation/README.md`**: reconcile note prose to "notes are the blessed `notes` relation; kanban keeps `kanban/note`/`kanban/handover` as decoration" — repo-local userland docs, their own cadence (SPEC-005.C4).
- **`devflow/specs/alpha-surface.md`**: SPEC-005.C2 gains `skein.api.notes.alpha` in the enumerated blessed set (PROP-Np-001.C12).

## PROP-Np-001.C10 — one-shot HISTORY rewrite and rehearse-on-copy ceremony

Unlike F1/F2 (active-only), this rewrite touches **history** — because the unified reader walks the relation regardless of state, any un-rekeyed pre-cutover note would be invisible (the `m630j` false-loss on every historical note). This divergence from the active-only rule is deliberate and is the brief's recorded exception.

**Scope, stated once:** every note strand in the database — active or closed — whose target still exists. Nothing else. Non-note strands (closed runs and their `shuttle/*` execution residue) keep the F1/F2 old-shape treatment, and the `devflow/archive/*` _documents_ are files, not strands (NG4, C11).

- **PROP-Np-001.C10.1 — the script** `scripts/cutover/note_primitive.clj`, beside `agent_engine_primitives.clj`/`agent_layer_rename.clj`. It re-keys, per the counts measured _at cutover time_ (not hardcoded — the world is live, see the drift note in P1 and Q4):
    - **Shuttle-era notes with a live target** (~1533: `shuttle/note-for` present, target exists, `notes` edge already present): `shuttle/note` → `note/text`, `shuttle/note-by` → `note/by`, timestamp → `note/at`; drop `shuttle/note-for` (the edge already carries linkage). The `notes` edge is left in place.
    - **Kanban notes** (~356: `kanban/note "true"`): `body` → `note/text`, `parent-of` edge → `notes` edge, synthesize `note/at`/`note/by` from `created_at`/author where available; keep `kanban/note`/`kanban/handover` (and `kind`) as decorating attrs.
    - Every insert refuses a self- or cyclic `notes` edge (declared acyclic, C1), so a malformed derivation fails loudly on the rehearsal copy rather than corrupting the live graph.
    - The db target is explicit (`--db`, or `--workspace` resolved through `mill weaver status`); the script refuses to guess a canonical world, exactly like the F2 script.
- **PROP-Np-001.C10.2 — rehearse against a copy** (F2 ceremony, `scripts/cutover/agent_engine_primitives.clj` header). Resolve the canonical `database_path` from `mill weaver status --workspace <canonical>` (the live file is under the weaver state dir, not workspace-local `data/`). Create the disposable world with `ws=$(mktemp -d)` then `mill init --workspace "${ws:?}"`, resolve _that_ world's own `database_path` (it resolves before any weaver starts), copy the canonical file there, run the script with that explicit `--db`, start the disposable weaver, and confirm the smoke checks render clean: `strand notes <a re-keyed target>`, `strand kanban card <a card with handovers>`, `strand agent notes <a target>`, `strand kanban board`. The rehearsal never touches the canonical world.
- **PROP-Np-001.C10.3 — quiet-board live cutover.** Land the code (C13), quiesce the board (no in-flight note writers mid-transition), back up, run the script against the canonical `database_path`.

## PROP-Np-001.C11 — historical-shape compatibility for what the rewrite skips

- **The 67 dangling shuttle notes** have no live target (it was burned) and therefore no `notes` edge to re-point. The rewrite **skips** them: there is nothing to attach them to. They stay as closed strands carrying old `shuttle/*` keys — inert memory, read by nothing after cutover (the unified reader walks the relation, which they no longer participate in). This matches F2's policy: historical strands are memory, not authority; the code wins.
- **Non-note history keeps its old shape.** Closed run strands' `shuttle/*` execution residue stays as-is per the F1/F2 active-only policy — this feature's history-wide scope applies to note strands only (C10). The `devflow/archive/*` _documents_ are files, not strands; their prose keeps the old vocabulary (NG4).
- **No dual-read** (NG1): no live code understands `shuttle/*` or `body`-as-note-text after cutover. The rewrite is the one and only bridge; anything it skips is unreachable-but-harmless memory.

## PROP-Np-001.C12 — alpha-surface disposition

- **`skein.api.notes.alpha`** (the primitive, C4) is a new blessed spool-facing namespace — added to SPEC-005.C2's enumerated set (alpha-surface.md, C9), accretion-compatible within its subnamespace.
- **The `notes` relation catalog entry** (C2) touches `skein.api.relations.alpha`, already blessed alpha surface (SPEC-005.C2); its test assertion updates with it.
- **`strand note`/`strand notes`** are batteries ops, in-contract via `spools/batteries.md` (SPEC-005.C3), not the alpha index.
- **kanban and delegation changes** (C6/C7) are repo-local userland (SPEC-005.C4); their READMEs are their own contracts.
- **The `notes` relation declaration** in `db.clj` is `skein.core.*` internal storage init — no alpha-surface entry, it is surfaced through the strand-model spec and the catalog.

## PROP-Np-001.C13 — atomic landing and separate signed cutover

- **PROP-Np-001.R-atomic — one landing.** The relation declaration, catalog entry, `note/*` shape, `notes.alpha` primitive, batteries verbs, kanban/delegation migration, the cascade fix, specs, and docs land together. A half-landing (edge-walking reader without the writer dropping `note/for`, or kanban migrated but the primitive still attribute-reading) would make writers and readers disagree. Single landing, gated by the full validation suite (PROP-Np-001.P6) before any cutover.
- **PROP-Np-001.C13.1 — the HISTORY rewrite is a separate, signed step** after the code lands, exactly as F1/F2 ran their cutovers separately. Code landing and the canonical-world rewrite are not the same event.
- **PROP-Np-001.C13.2 — weaver restart under recorded pre-authorization.** The rewired note surface needs a fresh weaver load. The restart runs under the standing pre-authorization recorded as strand `cu3wz` (card `ah5vu`, notes `u9jtn`/`fls7n` — the durable record of the user grant and its F2 exercise), so it does not re-ask for sign-off — **but it remains a ceremony hard stop:** quiet board, backup, rehearsal-on-copy passed, and post-cutover smoke are all mandatory. Pre-authorization removes the question, not the discipline.
- **PROP-Np-001.C13.3 — post-cutover smoke.** After restart: `strand notes <target>` returns notes from every writer, `strand kanban card <card>` shows its handovers, `strand agent notes <target>` agrees with `strand notes`, and `strand kanban board` renders clean.

## PROP-Np-001.C14 — deliberately not built

- **Structured review findings.** Reviewers prefix notes with tags in text (53% of notes); that stays. If it graduates to structured findings, the landing spot is the decorating-attr slot on the note strand (C3), not a new relation or a parser (NG2).
- **Large-attribute scaling.** `kbcjt` (306KB max note text) stays adjacent. The primitive does not bound or chunk `note/text`; that is a separate storage concern (NG3).

## PROP-Np-001.P5 Sequencing and risks

- **PROP-Np-001.R1:** Atomicity (C13). Writer, reader, and both migrated spools must land in lockstep, or a note written one way is unreadable the other way mid-landing.
- **PROP-Np-001.R2:** The HISTORY rewrite is the widest-blast-radius step in the epic — it edits closed and archived note strands, not just active ones. The mitigation is the F2 rehearse-on-a-copy ceremony (C10.2): the derivation runs against a copy and is smoke-verified before it touches the canonical world.
- **PROP-Np-001.R3:** Count drift. The brief's figures (1408 shuttle / 308 kanban) are already stale against the live world (1600 / 356 measured 2026-07-09); the canonical world accrues notes continuously. The script must count and act at cutover time and never hardcode a total (C10.1, Q4).
- **PROP-Np-001.R4:** Acyclicity of `notes`. Declaring it acyclic means a malformed rewrite edge (a self- or cyclic note) fails loudly on the rehearsal copy rather than corrupting a traversal live (C1, C10.1).

## PROP-Np-001.P6 Validation gates

All green before cutover:

- `make build`
- `flock -w 3600 /tmp/skein-test.lock clojure -M:test`
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings)
- `make api-docs` — clean regen; `git status --short` shows only the expected `spools/*.api.md` changes for the touched spools
- `git status --short` clean of generated SQLite and runtime metadata artifacts

## PROP-Np-001.P7 Done-when

- **PROP-Np-001.DW1:** `notes` is a declared acyclic relation (`db.clj:217`, catalog, strand-model spec); the note primitive lives in `skein.api.notes.alpha` and its reader walks the `notes` edge, not `note/for`.
- **PROP-Np-001.DW2:** `strand note` / `strand notes` exist as batteries ops; `strand notes <id>` returns notes written by kanban, delegation, and the raw verb alike (m630j resolved). Kanban notes ride the `notes` edge, not `parent-of`.
- **PROP-Np-001.DW3:** No live source writes `note/for` or reads a note's target from an attribute; the `notes` edge is the sole linkage. A grep for `note/for` returns only `devflow/archive/*` and the rewrite script's old→new mapping.
- **PROP-Np-001.DW4:** All P6 gates green in one atomic landing.
- **PROP-Np-001.DW5:** The rewrite script is rehearsed against a SQLite copy (C10.2); the canonical rewrite and weaver restart run under `cu3wz` with full ceremony (C13.2) and the C13.3 smoke checks pass.

## PROP-Np-001.P8 Open questions

All four questions were decided by the coordinator at proposal sign-off (docs-review pass 4484a6be raised no objection to any recommendation); each proposed resolution below is the adopted contract. Count: **0 open questions**.

- **PROP-Np-001.Q1 (resolved) — cascade-divergence invariant.** Drop `note/for` entirely so linkage is single-encoded (C8), or keep `note/for` and cascade-burn note strands on target burn (`db.clj:887-903`)? **Resolution (adopted): drop `note/for`.** It is the minimal, structurally-divergence-proof fix, adds no destructive cascade semantics to `burn`, and matches F2's no-dual-encoding tenet. Content attrs (`note/text`/`note/at`/`note/by`) never point at a target, so nothing dangles.
- **PROP-Np-001.Q2 (resolved) — primitive namespace.** New `skein.api.notes.alpha`, keep it in the agent-run spool, or fold it into the batteries spool? **Resolution (adopted): `skein.api.notes.alpha`.** The primitive is cross-spool (kanban, delegation, batteries all call it), and the tenet reserves `skein.api.*.alpha` for the blessed spool-facing API; a spool-internal home would force userland spools to depend on another spool's internals.
- **PROP-Np-001.Q3 (resolved) — `notes` acyclicity family.** Declare `notes` acyclic as an operational battery (joining `serves`/`supersedes`), or leave it an annotation relation with no acyclicity guarantee (TEN-005)? **Resolution (adopted): operational, declared acyclic.** It ships a read surface that walks it, notes never cycle in practice, and the declaration makes malformed rewrite/write edges fail loudly. It is classified operational, so TEN-005's annotation carve-out does not apply.
- **PROP-Np-001.Q4 (resolved) — fate of the 67 dangling.** Leave them as inert closed memory (C11), or burn them during the rewrite? **Resolution (adopted): leave them inert.** Their targets are gone, so they carry no live relation and are read by nothing; deleting historical memory is a heavier, irreversible action than the brief asks for, and F2's policy is that historical strands are memory, not authority.
