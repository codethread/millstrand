# Agent-layer vocabulary rename proposal

**Document ID:** `PROP-Alr-001` **Last Updated:** 2026-07-09 **Related brief:** [brief.md](./brief.md) (rename table is the contract) **Related epic:** `kaans` (cards `ah5vu`, `7azzl`, `41pna`) **Related root specs:** [Alpha Surface](../../specs/alpha-surface.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Strand Model](../../specs/strand-model.md)

## PROP-Alr-001.P1 Problem

The shuttle/agents/treadle cluster grew three vocabularies whose names encode owners rather than concepts, and those names ride durable strand attributes and worker prompts. A cold agent hitting `strand show` mid-task cannot tell what `shuttle/phase` or `treadle/run` means; the `agents` spool writes `shuttle/*` attrs (review and panel data, the `superseded` phase) onto strands the shuttle spool never touches; and `workflow/notes` reads like the note concept while it is really a gate-outcome string.

Epic `kaans` fixes the behavior. This feature does not. It is the **purely mechanical rename** that runs first, so that when behavior does change in later features the diffs review clean against a codebase already speaking the new vocabulary. Doing the mechanical rename first has two benefits: a rename diff that a reviewer can confirm by reading the rename table (no logic to trace), and a behavior diff in F2+ that carries no incidental renaming noise.

## PROP-Alr-001.P2 Goals

- **PROP-Alr-001.G1:** Apply the brief's rename table across the live tree — spool namespaces, durable attribute names, spool directories, and doc triads — with no behavior change.
- **PROP-Alr-001.G2:** Institutionalize the naming rule: attribute namespaces name concepts, not owners; names riding durable data or prompts are self-describing compound nouns.
- **PROP-Alr-001.G3:** Land the rename atomically — sources, tests, `.skein` config, bench, chime recipes, docs, `mkdocs.yml`, `scripts/agent-dash`, and `make api-docs` regen in one landing — so no intermediate commit leaves the tree half-renamed.
- **PROP-Alr-001.G4:** Rewrite the **active** strands' attributes in the canonical world as a rehearsed one-shot cutover, so the running coordination world speaks the new vocabulary the moment the renamed code loads.
- **PROP-Alr-001.G5:** Keep the trained-vocabulary surface frozen: `strand agent ...` verbs, the `agent-plan` pattern, the `agent-failures` query name, and the `:subagent` waiter value do not move.

## PROP-Alr-001.P3 Non-goals

- **PROP-Alr-001.NG1:** No behavior change of any kind. Same tests pass modulo renamed symbols and attrs; any behavioral fix discovered during the sweep is carded against `kaans`, never folded in.
- **PROP-Alr-001.NG2:** No dual-read or compatibility shim that accepts both old and new attribute names (TEN-000@1: alpha, rename without migration).
- **PROP-Alr-001.NG3:** No `serves` edge, no `serves`/lineage semantics — that is F2, which also owns dropping the `agent-run/run` and `gate/run`/`gate/step`/`gate/run-id`/`gate/superseded-by` markers.
- **PROP-Alr-001.NG4:** No note-primitive semantics (F3) and no registry work (F4).
- **PROP-Alr-001.NG5:** No `devflow/archive/*` edits — the archive is the historical record and stays in the old vocabulary.
- **PROP-Alr-001.NG6:** No change to `skein`/`strand`/`weaver`/`mill` names, harness/alias/backend terms, seat names, or kanban/roster/devflow vocabularies.

## PROP-Alr-001.P4 Approach: sweep by category

The rename table (brief) is the contract. The sweep walks the recon inventory (card `26o9g`, note `sg84p`) category by category. The unit of safety is the **token class**, not the file: within a category, replace one token class at a time and grep-verify before moving to the next (see PROP-Alr-001.P5.H1).

- **PROP-Alr-001.S1:** Spool sources (the mechanical bulk). `spools/shuttle/src/.../shuttle.clj` → `agent-run` ns + `agent-run/*` attrs; `.../treadle.clj` → `skein.spools.executors.subagent` ns + `gate/*` attrs; `spools/agents/src/.../agents.clj` → `skein.spools.delegation` ns + the `review/*`, `panel/*`, and `note/*` attrs. Those three families are not a suffix swap: apply the per-key transforms `shuttle/review-target` → `review/target`, `shuttle/review-pass` → `review/pass`, `shuttle/review-roster` → `review/roster`, `shuttle/review-focus` → `review/focus`, `shuttle/review-synthesis` → `review/synthesis`; `shuttle/panel-seat` → `panel/seat`, `shuttle/panel-turn` → `panel/turn`, `shuttle/fresh-prompt` → `panel/fresh-prompt`, `shuttle/role` → `panel/role`; `shuttle/note-for` → `note/for`, `shuttle/note` → `note/text`, `shuttle/note-by` → `note/by`, `shuttle/round` → `note/round`, `shuttle/at` → `note/at` (brief rename table is the full list). Also `spools/src/.../reed.clj` → `skein.spools.executors.shell`; `spools/src/.../workflow.clj` → `workflow/outcome-notes`; `loom.clj`/`carder.clj`/`bench.clj` read-side projections and marker checks.
- **PROP-Alr-001.S2:** Tests. Rename the four suite files (`shuttle_test`, `treadle_test`, `agents_test`, `reed_test`) and their fixtures; flip `workflow/notes` assertions; update `test_runner.clj` namespace paths. `test/skein/surface_baseline.edn` is hand-authored golden EDN (drives the `config_test` diff), not generated — it gets a deliberate edit, not a regen.
- **PROP-Alr-001.S3:** `.skein` config (all seven `.clj` concern files plus `spools.edn`, single coordination world). `init.clj` activation wiring and the `skein.spools.executors.subagent/install!` fully-qualified symbol; `config.clj` and `attention.clj` query predicates and chime rules keyed on the renamed attrs; `harnesses.clj`/`reviewers.clj`/`workflows.clj` spool references. `nvd_scan.clj` is verified clean of shuttle/treadle/reed/agents tokens (grep at plan time returns nothing), so it is a no-op in the sweep — named here only so the inventory covers every file. `spools.edn` keys and roots move per PROP-Alr-001.D5. Smoke-test in a disposable world before the canonical world sees any of it.
- **PROP-Alr-001.S4:** Docs (~28 files + `mkdocs.yml`). The doc set per spool is split: the generated `.api.md`/`.cookbook.md` sit flat under `spools/`, the human contract doc lives inside the spool dir. `git mv` + internal link fixes: `spools/shuttle.api.md`/`spools/shuttle.cookbook.md`/`spools/shuttle/README.md` → `spools/agent-run.api.md`/`spools/agent-run.cookbook.md`/`spools/agent-run/README.md`; `spools/treadle.api.md`/`spools/treadle.cookbook.md`/`spools/shuttle/treadle.md` → `spools/executors/subagent.api.md`/`spools/executors/subagent.cookbook.md`/`spools/agent-run/executors/subagent.md`; `spools/reed.api.md`/`spools/reed.cookbook.md`/`spools/reed.md` → `spools/executors/shell.api.md`/`spools/executors/shell.cookbook.md`/`spools/executors/shell.md`; `spools/agents.api.md`/`spools/agents.cookbook.md`/`spools/agents/README.md` → `spools/delegation.api.md`/`spools/delegation.cookbook.md`/`spools/delegation/README.md`. Judgment-level prose rewrites: `spools/README.md` index table and tier prose, `devflow/specs/alpha-surface.md` (SPEC-005.C4), `daemon-runtime.md` (SPEC-004.C74b), `strand-model.md`, and the `mkdocs.yml` nav (PROP-Alr-001.P5.H2).
- **PROP-Alr-001.S5:** bench, chime, Go prose, scripts. `spools/bench` marker/prose; `spools/chime/README.md` comparison prose; `cli/cmd/mill/prime/skein.md` embedded prime-doc prose (the only `cli/` hit — zero `.go` source references, CLI is JSON-only). `scripts/generate_api_docs.clj` and `scripts/quality/reflect_check.clj` spool lists; `scripts/shuttle-dash/` → `scripts/agent-dash/` directory rename.
- **PROP-Alr-001.S6:** Makefile / deps.edn / spools.edn lockstep. These are load-bearing: if they lag, `make build` and `clojure -M:test` do not run. `Makefile` `dash` target prose and `scripts/agent-dash` paths, plus the `docs-check` pathspec, which currently reads `spools/*.api.md` and must widen to also cover the new nested `spools/executors/*.api.md` outfiles (PROP-Alr-001.P5.H6); `deps.edn` `:extra-paths` in **both** `:test` and `:reflect-check` (PROP-Alr-001.P5.H4); `.skein/spools.edn` keys and roots (PROP-Alr-001.D5).
- **PROP-Alr-001.S7:** Regenerate. `make api-docs` after the docstring/source moves, then `make fmt-check lint reflect-check docs-check` at zero findings.

## PROP-Alr-001.P5 Hazard handling

The recon surfaced twelve gotchas. Each resolves below to a decision; the four coordinator decisions folded into the brief close gotchas 2, 5, 6, 8, and 11.

- **PROP-Alr-001.P5.H1:** Alias-qualified fn calls are not attributes (gotcha 1). Most `shuttle/xxx` and `treadle/xxx` occurrences in `.clj` files are Clojure symbols calling functions through a `:as shuttle` / `:as treadle` require alias (`shuttle/run-summary`, `treadle/install!`, `treadle/on-event`), **not** attribute keywords or strings. A blind `sed 's/treadle\//gate\//g'` corrupts these into `gate/install!`. **Decision: no blind sed.** Replace token-class-by-token-class with a grep-verify gate between classes:
    1. `ns`/`require` forms and fully-qualified symbols first — update the required namespace symbol; the local alias name is a free contributor choice and can stay `shuttle`/`treadle` or be renamed, but is never rewritten to an attribute name.
    2. Quoted-string and keyword attribute literals second (`"shuttle/run"`, `:shuttle/phase`, `[:attr "treadle/error"]`) — these are the only forms the rename table's attribute rows apply to.
    3. After each class, `grep -n` the old token across the touched files and confirm the only survivors are the expected other-class forms. `treadle/engine` (event-type kw) and `treadle/on-event` (fn ref) are internal identifiers with no rename-table row — they follow the namespace/alias rename mechanically, not the attribute rename.
- **PROP-Alr-001.P5.H2:** `mkdocs.yml` nav is off the beaten path (gotcha 4). It hardcodes six shuttle/treadle doc-triad paths outside `spools/` and `devflow/`, and no test catches a stale nav. **Decision: in atomic-landing scope** (coordinator); the nav paths change in lockstep with the doc-triad `git mv`s in PROP-Alr-001.S4, verified by a `mkdocs build` (or `docs-check`) dry run in the landing gate.
- **PROP-Alr-001.P5.H3:** Cross-rename prose chains (gotcha 3). Sentences like "treadle puts `shuttle/result` in `workflow/notes`" chain three renames in one clause across `treadle.cookbook.md`, `shuttle/treadle.md`, and the reed docs. **Decision:** rewrite the whole clause per occurrence, then grep each old token (`workflow/notes`, `shuttle/result`, `treadle`) across the docs tree to catch half-updated sentences before the docs gate.
- **PROP-Alr-001.P5.H4:** `deps.edn` extra-paths move in lockstep (gotcha 7). `:test` and `:reflect-check` both list `spools/shuttle/src spools/agents/src`; a one-sided edit silently drops `reflect-check` coverage of the renamed sources. **Decision:** both aliases change together to `spools/agent-run/src spools/delegation/src`; `reflect-check` at zero findings is the proof both landed.
- **PROP-Alr-001.P5.H5:** `quality-inventory.md` is hand-maintained (gotcha 9). No script references it (verified: no generator in `scripts/` or `Makefile`). **Decision:** it gets a manual prose pass in the docs category; if a generator is discovered at plan time, regen instead.
- **PROP-Alr-001.P5.H6:** First nested spool namespace segment (gotcha 12). `skein.spools.executors.{shell,subagent}` are the first spool namespaces with a nested segment, implying `skein/spools/executors/{shell,subagent}.clj` and doc outfiles under `spools/executors/`. Decision: verified `make api-docs` handles it. `scripts/generate_api_docs.clj` is a hand-maintained explicit `spool-docs` vector of `{:name :source :outfile}` — no auto-discovery or namespace munging — so nested paths work by editing the four entries (`agent-run`, `executors/subagent`, `executors/shell`, `delegation`) to their new source/outfile strings. Plan-stage check: confirm the outfile writer creates the `spools/executors/` parent dir (or create it in the sweep) before running `make api-docs`. Second plan-stage check: the `docs-check` gate diffs `git diff --exit-code -- 'spools/*.api.md'`, a pathspec that does not descend into `spools/executors/`, so a stale nested api-doc would pass the gate unnoticed; widen the pathspec (PROP-Alr-001.S6) so the gate actually proves the nested outfiles.
- **PROP-Alr-001.P5.H7:** Marker attrs stay in F1 (gotcha 2, coordinator). The `shuttle/run` boolean marker is **kept** in F1, renamed `agent-run/run`; dropping it (and rewriting its 6+ call sites to an `agent-run/phase` presence predicate) is a logic change deferred to F2. So in F1 every marker site — `carder.clj`, `.skein/config.clj` "work" query, `.skein/attention.clj`, `bench.clj`, `surface_baseline.edn`, `agents.clj` `agent-failures` — is a **pure string swap** `shuttle/run`→`agent-run/run`, `shuttle/phase`→`agent-run/phase`. Likewise `shuttle/serves`→`agent-run/serves` (boolean survives to F2's `serves` edge).
- **PROP-Alr-001.P5.H8:** Distribution tiers unchanged (gotchas 5 and 6, coordinator). Namespace family ≠ distribution tier. `executors.shell` (reed) stays on the shipped classpath: its file moves within `spools/src` to `spools/src/skein/spools/executors/shell.clj`, no `spools.edn` approval added. `executors.subagent` (treadle) stays approved-local-root: it joins the `executors/` grouping inside its current spool root, i.e. `spools/agent-run/src/skein/spools/executors/subagent.clj`, still gated by the `agent-run` local-root entry. One namespace prefix, two physical relocations on two tiers — no new trust surface.
- **PROP-Alr-001.P5.H9:** `scripts/shuttle-dash` in scope (gotcha 8, coordinator). Its data layer reads the renamed attrs and breaks at cutover. **Decision:** dir renames to `scripts/agent-dash`, with the `make dash` target reference and any hardcoded attr-name strings in `data.ts`/`tabs/*.tsx` updated in the same landing.
- **PROP-Alr-001.P5.H10:** `treadle/gate` → `gate/step` (gotcha 11, coordinator). The brief's positional row resolves to `treadle/gate` (on a run: the gate step it fulfills) → `gate/step`; the inline `gate/gate-of` was a rejected alternative. Companion markers `gate/run`, `gate/run-id`, `gate/superseded-by` rename now and are deleted in F2.

## PROP-Alr-001.P6 Decisions closed at design time

- **PROP-Alr-001.D5:** `spools.edn` keys and roots follow the rename. The brief moves the directories (`spools/shuttle` → `spools/agent-run`, `spools/agents` → `spools/delegation`), so `.skein/spools.edn`'s `skein.spools/shuttle {:local/root "../spools/shuttle"}` and `skein.spools/agents {:local/root "../spools/agents"}` become `skein.spools/agent-run {:local/root "../spools/agent-run"}` and `skein.spools/delegation {:local/root "../spools/delegation"}`. The map key is the registered spool name; no external consumer pins it, and TEN-000@1 forbids a migration shim, so the key moves with its directory and namespace.

## PROP-Alr-001.P7 Sequencing and risks

- **PROP-Alr-001.R1:** Blocked for implementation. The brief blocks implementation on the in-flight tiered-validation-v2 queue and `vk8aa` (shared doc/test files). Design and planning stages may proceed; the mutating sweep waits for that block to clear.
- **PROP-Alr-001.R2:** In-flight sibling features (verified read-only 2026-07-09). `git ls-tree main:devflow/feat` and the working tree both list `workflow-shell-gates` and `harness-alias-registries` under `devflow/feat/` (each with `proposal.md`, a `.plan.md`, and a `tasks/` dir); neither appears in `devflow/archive/` or in `main`'s archive. **Finding: both are still in flight, neither has landed.** They overlap this feature's exact files: `workflow-shell-gates` builds the reed/`:shell` executor in `spools/src/skein/spools/reed.clj` (the file this feature renames to `executors/shell.clj`), and `harness-alias-registries` touches `.skein/harnesses.clj` and the shuttle harness defs. Renaming those files mid-flight risks a rebase conflict, not just a naming clash. **Mitigation:** this feature's sweep should land after both siblings, or coordinate an ordering with their owners; the rename is a whole-tree cutover and does not interleave cleanly with in-progress edits to the same files.
- **PROP-Alr-001.R3:** Atomicity risk. A half-renamed intermediate commit leaves `make build` broken (deps.edn paths) or the doc site broken (`mkdocs.yml`). Mitigation: single landing, all categories, gated by the full validation suite before the cutover.

## PROP-Alr-001.P8 Cutover plan

Renaming the code changes what attribute names the running canonical weaver reads. Active strands in the canonical world still carry the old attribute keys, so the code landing must be paired with a one-shot rewrite of those live attributes. No dual-read shim bridges the gap (TEN-000@1, PROP-Alr-001.NG2).

- **PROP-Alr-001.C1:** One-shot rewrite script. A script that rewrites **active** strands' attribute keys per the brief rename table, which is the authoritative old→new key list. The transform is not a uniform prefix swap: the `shuttle/*` run attrs and markers map `shuttle/…` → `agent-run/…`, `treadle/*` maps `treadle/…` → `gate/…` (including `treadle/gate` → `gate/step`), and the review/panel/note families split off their own namespaces per key — `shuttle/review-*` → `review/*` (`shuttle/review-pass` → `review/pass`, etc.), `shuttle/panel-seat`/`shuttle/panel-turn`/`shuttle/fresh-prompt`/`shuttle/role` → `panel/seat`/`panel/turn`/`panel/fresh-prompt`/`panel/role`, `shuttle/note-for`/`shuttle/note`/`shuttle/note-by`/`shuttle/round`/`shuttle/at` → `note/for`/`note/text`/`note/by`/`note/round`/`note/at` — plus `workflow/notes` → `workflow/outcome-notes` and the `shuttle/handle.<key>` prefix rewrite. The script rewrites explicitly per key, never through a generic `shuttle/*` rule that would mis-map the review/panel/note attrs. Markers are renamed, not dropped (PROP-Alr-001.P5.H7). Archived/inactive strands are memory, not authority (PHILOSOPHY: the code wins) — the script scopes to active work.
- **PROP-Alr-001.C2:** Rehearse against a copy. Copy the canonical world's `data/skein.sqlite` into a disposable `mktemp -d` workspace, run the renamed code and the rewrite script there, and confirm the smoke checks pass. The rehearsal never touches the canonical world.
- **PROP-Alr-001.C3:** Planned quiet-board cutover. Land the code, quiesce the board (no in-flight shuttle runs or open gates mid-transition), run the rewrite script against the canonical SQLite.
- **PROP-Alr-001.C4:** Weaver restart requires explicit user sign-off — hard stop. The renamed namespaces need a fresh weaver load; restarting the canonical weaver tears down live shuttle runs and registries other agents depend on. This is a human decision: stop and ask, never restart autonomously.
- **PROP-Alr-001.C5:** Post-cutover smoke. After the signed-off restart: `strand agent status`, `strand ready --query stalled-gates`, and `strand kanban board` all render clean against the new vocabulary, and `strand list --query agent-failures` returns without error (its query body now reads `agent-run/*`).

## PROP-Alr-001.P9 Validation gates

From the brief, all green before cutover:

- `make build`
- `flock -w 3600 /tmp/skein-test.lock clojure -M:test`
- `(cd cli && go test ./...)`
- `clojure -M:smoke`
- `make fmt-check lint reflect-check docs-check` (held at zero findings; `docs-check`'s pathspec is widened in PROP-Alr-001.S6 so it also diffs the new nested `spools/executors/*.api.md`)
- `make api-docs` — clean regen, `git status --short` shows only the expected renamed `spools/*.api.md` (incl. new `spools/executors/*.api.md`)
- `git status --short` clean of generated SQLite and runtime metadata artifacts

## PROP-Alr-001.P10 Done-when

- **PROP-Alr-001.DW1:** Every rename-table row is applied across the live tree; `grep` for each old token (`shuttle/`, `treadle/`, `workflow/notes`, `skein.spools.shuttle|agents|treadle|reed`) returns only intended survivors — alias locals and `devflow/archive/*`.
- **PROP-Alr-001.DW2:** All PROP-Alr-001.P9 gates green in one atomic landing; no behavior change (NG1).
- **PROP-Alr-001.DW3:** The cutover script has been rehearsed against a SQLite copy in a disposable world (PROP-Alr-001.C2).
- **PROP-Alr-001.DW4:** The canonical cutover is executed only after explicit user sign-off on the weaver restart, and the PROP-Alr-001.C5 smoke checks pass.

## PROP-Alr-001.P11 Out of scope

- **PROP-Alr-001.OOS1:** Any behavior change — carded against `kaans`, not folded in (NG1).
- **PROP-Alr-001.OOS2:** `serves` edge and lineage semantics — F2.
- **PROP-Alr-001.OOS3:** Note-primitive semantics — F3.
- **PROP-Alr-001.OOS4:** Registry — F4.
- **PROP-Alr-001.OOS5:** `devflow/archive/*` historical record — never edited.

## PROP-Alr-001.P12 Open questions

- **PROP-Alr-001.Q1:** None. Every recon gotcha resolves to a decision above; the four coordinator decisions closed gotchas 2, 5, 6, 8, and 11, and the remaining gotchas (1, 3, 4, 7, 9, 12) are handled in PROP-Alr-001.P5. The only external dependency is sequencing behind the in-flight blockers and the two live sibling features (PROP-Alr-001.R1, PROP-Alr-001.R2), which is a scheduling constraint, not an open design question.
