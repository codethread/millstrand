# Agent-layer vocabulary rename Plan

**Document ID:** `PLAN-Alr-001` **Feature:** `agent-layer-rename` **Proposal:** [proposal.md](./proposal.md) (`PROP-Alr-001`) **RFC:** none (feature 1 of epic `kaans`; the mechanical rename precedes the behavior features) **Root specs:** [strand-model.md](../../specs/strand-model.md) (`SPEC-001`), [daemon-runtime.md](../../specs/daemon-runtime.md) (`SPEC-004`), [alpha-surface.md](../../specs/alpha-surface.md) (`SPEC-005`) **Feature specs:** [specs/strand-model.delta.md](./specs/strand-model.delta.md) (`SPEC-Alr-001`), [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md) (`SPEC-Alr-002`), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md) (`SPEC-Alr-003`) **Contract:** [brief.md](./brief.md) — the exhaustive rename table is authoritative; this plan never restates it. **Status:** Shipped **Last Updated:** 2026-07-23

## PLAN-Alr-001.P1 Goal and scope

Apply the brief's rename table across the live tree — spool namespaces, spool directories, durable attribute keys, doc triads, `.skein` config, bench/chime/dash consumers, and the three feature spec deltas — as one atomic landing with **no behavior change** (`PROP-Alr-001.NG1`). The `shuttle`/`agents`/`treadle`/`reed` vocabularies become concept-named `agent-run`/`delegation`/`executors.subagent`/`executors.shell`; the durable attribute keys riding strand data move to `agent-run/*`, `review/*`, `panel/*`, `note/*`, `gate/*`, and `workflow/outcome-notes`. The trained-vocabulary surface stays frozen (`PROP-Alr-001.G5`). Because renamed code changes what attribute keys the running canonical weaver reads, the landing is paired with a rehearsed one-shot cutover that rewrites the **active** strands' keys, ending at a user-signed weaver restart. Why it matters: see `PROP-Alr-001.P1` — a clean mechanical rename now buys clean behavioral diffs in F2+.

## PLAN-Alr-001.P2 Approach

- **PLAN-Alr-001.A1:** Sweep by token class, not by file (`PROP-Alr-001.P4`, `PROP-Alr-001.P5.H1`). The unit of safety is the token class: within each phase, rewrite one class of occurrence at a time and `grep -n` the old token across touched files before moving on. Class 1 is `ns`/`require` forms and fully-qualified symbols (the required namespace symbol moves; the local `:as` alias is a free contributor choice and is **never** rewritten to an attribute name). Class 2 is quoted-string and keyword attribute literals — the only forms the rename table's attribute rows apply to. **No blind `sed`**: `treadle/install!`, `shuttle/run-summary`, `:treadle/engine`, `treadle/on-event` are symbols/event-kws with no rename-table row and must not be corrupted into `gate/*`.
- **PLAN-Alr-001.A2:** Namespace/directory moves are compile-coupled and land first (PH1), serialized. Once the tree compiles and per-namespace tests are green, the attribute-string sweep (PH2) fans out by file family with disjoint scopes. Docs (PH3), `.skein`+consumers (PH4), and spec deltas (PH5) follow. The cutover script (PH6) is authored last and rehearsed, never run against the canonical world by a worker.
- **PLAN-Alr-001.A3:** Direct moves, no shims. `git mv` the directories and rewrite `ns` forms in place; no alias namespaces, no dual-read compatibility (`PROP-Alr-001.NG2`, TEN-000@1). A test or doc still on an old name is updated to the new contract, never bridged.
- **PLAN-Alr-001.A4:** Namespace family ≠ distribution tier (`PROP-Alr-001.P5.H8`). One `executors.*` prefix, two physical relocations on two trust tiers: `executors.shell` (reed) stays classpath-shipped and moves within `spools/src` to `spools/src/skein/spools/executors/shell.clj`; `executors.subagent` (treadle) stays approved-local-root and joins the `executors/` grouping inside its current spool root at `spools/agent-run/src/skein/spools/executors/subagent.clj`. No new trust surface, no `spools.edn` approval added for the shell executor.
- **PLAN-Alr-001.A5:** Markers are renamed, not dropped (`PROP-Alr-001.P5.H7`). `shuttle/run`→`agent-run/run`, `shuttle/serves`→`agent-run/serves`, `treadle/gate`→`gate/step` and companions are pure string swaps in F1; the logic changes that retire them belong to F2 and are out of scope.
- **PLAN-Alr-001.A6:** Prefer the focused validation gates during the sweep — the in-process per-namespace runner (`clojure -M:test <ns...>`) and the warm runner (`scripts/test-warm`, landing with the tiered-validation queue this feature rebases over). The full locked suite (`flock -w 3600 /tmp/skein-test.lock clojure -M:test`) runs only at the acceptance gate, alongside `go test`, smoke, and the quality gates.

## PLAN-Alr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Alr-001.AA1 | `spools/shuttle/` → `spools/agent-run/` | Dir move; `skein.spools.shuttle` → `skein.spools.agent-run` ns + `agent-run/*` attrs. Also hosts the relocated subagent executor source. |
| PLAN-Alr-001.AA2 | `spools/shuttle/src/.../treadle.clj` → `spools/agent-run/src/skein/spools/executors/subagent.clj` | `skein.spools.executors.subagent` ns + `gate/*` attrs (incl. `treadle/gate`→`gate/step`). First nested-segment spool ns. |
| PLAN-Alr-001.AA3 | `spools/src/.../reed.clj` → `spools/src/skein/spools/executors/shell.clj` | `skein.spools.executors.shell` ns; stays classpath-shipped. |
| PLAN-Alr-001.AA4 | `spools/agents/` → `spools/delegation/` | Dir move; `skein.spools.delegation` ns + `review/*`, `panel/*`, `note/*` attrs (per-key split, not a suffix swap). |
| PLAN-Alr-001.AA5 | `spools/src/.../workflow.clj`, `loom.clj`, `carder.clj`, `bench.clj` | Read-side projections, marker checks, and `workflow/notes`→`workflow/outcome-notes`. |
| PLAN-Alr-001.AA6 | `deps.edn`, `.skein/spools.edn`, `Makefile` | Load-bearing lockstep: `:test` **and** `:reflect-check` extra-paths (`PROP-Alr-001.P5.H4`), `spools.edn` keys/roots (`PROP-Alr-001.D5`), `docs-check` pathspec widening (`PROP-Alr-001.P5.H6`), `make dash` target. |
| PLAN-Alr-001.AA7 | `test/` suites + `test/skein/surface_baseline.edn` + `test_runner.clj` | Rename four suite files/fixtures, flip attr assertions, update ns paths. `surface_baseline.edn` is hand-authored golden EDN — deliberate edit, not regen. |
| PLAN-Alr-001.AA8 | `spools/*.api.md`, `*.cookbook.md`, contract READMEs, `mkdocs.yml` | Doc-triad `git mv` + internal link fixes + nested `spools/executors/*` outfiles; `make api-docs` regen. |
| PLAN-Alr-001.AA9 | `.skein/{init,config,attention,harnesses,reviewers,workflows,nvd_scan}.clj` | Activation wiring, query predicates, chime rules, spool refs. `nvd_scan.clj` verified clean = no-op. |
| PLAN-Alr-001.AA10 | `scripts/shuttle-dash/` → `scripts/agent-dash/`, `scripts/generate_api_docs.clj`, `scripts/quality/reflect_check.clj` | Dir rename + `data.ts`/`tabs/*.tsx` attr strings; explicit spool-doc/spool-list vectors. |
| PLAN-Alr-001.AA11 | `devflow/specs/{strand-model,alpha-surface,daemon-runtime}.md` | Apply the three spec deltas; mark deltas Merged. |

## PLAN-Alr-001.P4 Contract and migration impact

- **PLAN-Alr-001.CM1:** Breaking alpha rename, no migration for inactive data. The rename table (brief) is the cutover contract; a key absent from it is left in the old vocabulary by the cutover script.
- **PLAN-Alr-001.CM2:** Durable contract changes are staged in the three feature deltas (`SPEC-Alr-001`/`-002`/`-003`) and promoted to root specs only after validation (PH5). The edits are token swaps plus two additive statements total: `SPEC-Alr-001.CC2` (the naming rule) and `SPEC-Alr-002.CC3` (the frozen trained-vocabulary surface); `SPEC-Alr-003` is edit-only. No behavior contract moves.
- **PLAN-Alr-001.CM3:** Active strands in the canonical world carry old keys until the one-shot cutover (PH6) rewrites them per key. There is no dual-read bridge; the code landing and the cutover are one operation separated only by the user-signed restart.
- **PLAN-Alr-001.CM4:** The frozen trained-vocabulary surface (`strand agent …` verbs, `agent-plan` pattern, `agent-failures` query, `:subagent` waiter) is recorded as a new contract statement in `SPEC-Alr-002.CC3`; workers must not rename it.

## PLAN-Alr-001.P5 Implementation phases

Each phase names its validation gate. Phases are serialized where compile-coupled and fan out where scopes are disjoint (see `PLAN-Alr-001.P8` for the AFK task-queue sketch and delegation seams). The token-class grep-verify gate (`PLAN-Alr-001.A1`) runs **within** and **across** phases — it is a whole-sweep discipline, not a per-phase step.

### PLAN-Alr-001.PH0 Pre-flight: rebase and shard reconciliation

Outcome: the feature branch is rebased over the landed tiered-test-validation queue (`scripts/test-warm` and the per-namespace runner present and green) and over `vk8aa`'s shard-tier cleanup, with no residual `shuttle_test` sync deftest or stale shard ordering left to conflict with the suite renames. No mutating sweep starts until this gate is clean.

Gate: `make build` green on the rebased base; `scripts/test-warm` (or `clojure -M:test <touched-ns>`) runs; `git status --short` clean.

### PLAN-Alr-001.PH1 Namespace and directory moves + build lockstep (serialized)

Outcome: the four spool families are moved and renamed at the `ns`/`require`/symbol class only (token class 1); `deps.edn` (`:test` + `:reflect-check`), `.skein/spools.edn`, and the test-suite file/`test_runner.clj` paths move in lockstep so `make build` and the focused per-namespace suites pass. Attribute-key strings are untouched in this phase (they still read old values and still pass, because tests assert unchanged strings). This phase blocks every later phase.

Gate: `make build`; focused per-namespace runs for the four moved suites + `config_test` + `test_runner` load; `make reflect-check` (proves both extra-paths landed, `PROP-Alr-001.P5.H4`); grep confirms no class-1 survivors except free `:as` aliases.

### PLAN-Alr-001.PH2 Attribute-key string sweep (fan-out by file family)

Outcome: token class 2 (quoted-string and keyword attribute literals) is rewritten per the brief table across spool sources and their tests together — `agent-run/*` run attrs and markers, `gate/*` (incl. `treadle/gate`→`gate/step`), the `review/*`/`panel/*`/`note/*` per-key split, `workflow/notes`→`workflow/outcome-notes`, and the two event-type keywords via `events/register-handler!`. Family scopes are disjoint (agent-run vs subagent vs delegation vs workflow/consumers) so they fan out in parallel after PH1.

Gate: focused per-namespace suites for each touched family green; `surface_baseline.edn` diff reconciled (hand-edited, not regenerated); grep confirms the only surviving old attr strings are in `devflow/archive/*`.

### PLAN-Alr-001.PH3 Docs sweep + api-docs regen + mkdocs

Outcome: doc triads are `git mv`'d with internal links fixed (nested `spools/executors/*` outfiles created), cross-rename prose chains rewritten whole-clause (`PROP-Alr-001.P5.H3`), the `mkdocs.yml` nav paths updated (`PROP-Alr-001.P5.H2`), the `docs-check` pathspec widened to descend into `spools/executors/` (`PROP-Alr-001.P5.H6`), and `make api-docs` regenerated cleanly after the docstring/source moves.

Gate: `make docs-check` at zero findings (with widened pathspec); `mkdocs build` (or docs-check dry run) clean; `make api-docs` regen shows only the expected renamed outfiles in `git status --short`; grep of docs tree clean of anchored old-surface tokens outside archive — the namespace-qualified `skein.spools.(shuttle|agents|treadle|reed)`, the `spools/shuttle`/`spools/agents` doc paths, and the `shuttle/`/`treadle/` attribute prefixes — never bare words like `agents` or `reed`, which stay legitimate prose in the renamed tree.

### PLAN-Alr-001.PH4 `.skein` config + bench/chime/dash consumers

Outcome: the seven `.skein/*.clj` concern files and `spools.edn` speak the new vocabulary (activation `install!` symbol, query/chime predicates on renamed attrs, spool refs; `nvd_scan.clj` verified clean = no-op); `scripts/shuttle-dash/` is renamed to `scripts/agent-dash/` with its `data.ts`/`tabs/*.tsx` attr strings and the `make dash` target updated; bench/chime consumer prose and markers reconciled. Config changes are smoke-tested in a **disposable** world first — never the canonical world.

Gate: `clojure -M:smoke`; `.skein` config loads in a `mktemp -d` disposable world (`--workspace`, guarded `${ws:?}`); `make dash` resolves; grep of `.skein/` + `scripts/` clean outside archive.

### PLAN-Alr-001.PH5 Spec-delta application

Outcome: `SPEC-Alr-001`/`-002`/`-003` edits are applied to the three root specs — the token-swap example and inventory-sync edits across all three deltas, plus two additive contract statements: the naming rule (`SPEC-Alr-001.CC2`) and the frozen trained-vocabulary surface (`SPEC-Alr-002.CC3`). `SPEC-Alr-003`'s off-lane example is an edit, not an add. Assign the next free `SPEC-005.Cn` for the `SPEC-Alr-002.CC3` addition at edit time; the three deltas are marked Merged.

Gate: `make docs-check`; each delta's old/new fragments verified against the edited root spec; deltas' Status flipped to Merged.

### PLAN-Alr-001.PH6 Cutover script + rehearsal doc

Outcome: a one-shot cutover script exists that reads the brief rename table as its mapping source and rewrites **active** strands' attribute keys explicitly per key (never a generic `shuttle/*` rule that would mis-map the review/panel/note families; handles the `shuttle/handle.<key>` prefix rewrite; renames markers, does not drop them). A rehearsal doc records the recipe: copy the canonical world's `data/skein.sqlite` into a `mktemp -d` disposable workspace, run the renamed code + script there, confirm smoke — the rehearsal never touches the canonical world. The doc ends the documented ceremony at the user-signed weaver restart (hard stop) followed by the `PROP-Alr-001.C5` post-cutover smoke.

Gate: script rehearsed against a SQLite copy in a disposable world (`PROP-Alr-001.C2`/`DW3`); rehearsal smoke checks pass; ceremony doc reviewed. The canonical cutover itself is **not** a worker task — it is coordinator-run after explicit user sign-off (`PROP-Alr-001.C4`/`DW4`).

### PLAN-Alr-001.PH7 Acceptance / atomic landing gate

Outcome: the whole sweep is proven green in one place before landing — the full locked suite and all quality gates pass together, confirming atomicity (`PROP-Alr-001.DW2`).

Gate: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test`; `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` at zero findings; `make api-docs` clean; `git status --short` clear of generated SQLite/runtime artifacts; the `PROP-Alr-001.DW1` grep sweep returns only intended survivors (alias locals, `devflow/archive/*`).

## PLAN-Alr-001.P6 Validation strategy

- **PLAN-Alr-001.V1:** Prefer focused gates during the sweep — `clojure -M:test <ns...>` per touched namespace and `scripts/test-warm` — so a broad rename does not pay full-suite latency on every step. The full locked suite runs once, at PH7.
- **PLAN-Alr-001.V2:** The token-class grep-verify gate (`PLAN-Alr-001.A1`) is the primary correctness proof for the mechanical sweep: after each class, grep old tokens across touched files and confirm only expected other-class survivors remain.
- **PLAN-Alr-001.V3:** `make reflect-check` at zero findings proves both `deps.edn` extra-paths moved (`PROP-Alr-001.P5.H4`); the widened `docs-check` pathspec proves the nested `spools/executors/*.api.md` outfiles are current (`PROP-Alr-001.P5.H6`).
- **PLAN-Alr-001.V4:** The `PROP-Alr-001.DW1` whole-tree grep is the done-when proof, and every pattern is anchored so it cannot match legitimate prose: the `shuttle/`, `treadle/`, and `workflow/notes` attribute/marker prefixes and the namespace-qualified `skein.spools.(shuttle|agents|treadle|reed)` — grouped so the `skein.spools.` prefix binds every alternative, never the bare word `agents`/`reed`/`treadle`. Each returns only alias locals and `devflow/archive/*`.
- **PLAN-Alr-001.V5:** Cutover is rehearsed against a **copy** in a disposable world before any canonical mutation; the canonical restart is gated on explicit user sign-off.

## PLAN-Alr-001.P7 Risks and open questions

- **PLAN-Alr-001.R1:** Rebase-over-in-flight-landings risk. Implementation is blocked (`PROP-Alr-001.R1`) behind the tiered-test-validation queue (in flight now — it lands `scripts/test-warm` and the shard tiers the focused gates depend on) and the shard-tier cleanup landing (deletes the redundant `shuttle_test` sync deftest and reworks shard ordering — exactly the suite files this feature renames). Mitigation: **PH0 is a hard gate** — rebase over both and reconcile before any mutating phase; do not rename suite files a still-open shard-tier landing is editing. The live queue/card ids for these blockers are tracked in this feature's strand notes, not restated here.
- **PLAN-Alr-001.R2:** Sibling-file overlap. `workflow-shell-gates` builds the reed/`:shell` executor in the file this feature moves to `executors/shell.clj`; the harness-alias-registries landing split the shuttle harness defs. A rename over a live edit is a rebase conflict, not just a naming clash (`PROP-Alr-001.R2`). Mitigation: land after the siblings or coordinate ordering with their owners; the sweep is a whole-tree cutover and does not interleave cleanly.
- **PLAN-Alr-001.R3:** Adjacent carded work touches the renamed surface and must be sequenced, not folded in. Carded behavior items (run-usage records, treadle gate-advance enforcement) mutate `shuttle/*`/`treadle/*` behavior, and a carded `scripts/shuttle-dash` synthesis-arrow fix edits the very directory this feature renames. Sequencing notes are already on those cards. Mitigation: none of these land inside this feature's window; each is behavior (`kaans` F2+) or waits for the dir rename. Any behavior discovered mid-sweep is carded, never folded (`PROP-Alr-001.NG1`). The live card ids are tracked in this feature's strand notes.
- **PLAN-Alr-001.R4:** Atomicity. A half-renamed intermediate breaks `make build` (deps.edn paths) or the doc site (`mkdocs.yml`). Mitigation: single landing, all categories, gated by PH7 before the cutover (`PROP-Alr-001.R3`).
- **PLAN-Alr-001.Q1:** None blocking task generation. Every recon gotcha resolved to a decision in `PROP-Alr-001.P5`; the only external dependency is scheduling behind PH0's blockers.

## PLAN-Alr-001.P8 Task context

- **PLAN-Alr-001.TC1:** The brief rename table is the single source of truth for every mapping — sources, tests, docs, `.skein`, and the cutover script all read from it. Task authors and AFK workers must not restate or re-derive it; a mapping not in the table is out of scope.
- **PLAN-Alr-001.TC2:** Delegation seams. PH1 is serialized (ns moves are compile-coupled; they block everything). PH2 fans out by disjoint file family (agent-run / executors.subagent / delegation / workflow+consumers). PH3–PH4 fan out by disjoint concern (doc triad vs judgment prose vs mkdocs; `.skein` config vs dash vs bench/chime). Never place two mutators in the same file scope; serialize compile-coupled edits, parallelize disjoint ones. Build/worker delegates get one file family each.
- **PLAN-Alr-001.TC3:** AFK task-queue sketch (tasks are authored in the tasks stage; counts are estimates for sizing the queue):

    | Phase | Sketch | ~Tasks |
    | --- | --- | --: |
    | PH0 | Rebase over tiered-test-validation + `vk8aa`; reconcile shard/suite state | 1 |
    | PH1 | (a) `deps.edn`+`spools.edn`+`Makefile` path lockstep; (b) agent-run + executors/subagent dir/ns move; (c) delegation dir/ns move; (d) executors/shell move + suite-file renames + `test_runner` paths | 4 |
    | PH2 | (a) `agent-run/*` run attrs+markers; (b) `gate/*` incl. `gate/step`; (c) `review/*`/`panel/*`/`note/*` split; (d) `workflow/outcome-notes` + workflow/consumer markers; (e) event-type kw rename | 5 |
    | PH3 | (a) doc-triad `git mv` + link fixes; (b) judgment prose (README/quality-inventory/bench/chime/cli prime); (c) `mkdocs.yml` + `docs-check` pathspec; (d) `make api-docs` regen | 4 |
    | PH4 | (a) `.skein` config sweep + disposable-world smoke; (b) `scripts/agent-dash` rename + attr strings + `make dash`; (c) bench/chime consumer reconcile | 3 |
    | PH5 | Apply `SPEC-Alr-001`/`-002`/`-003` to root specs; mark deltas Merged | 1 |
    | PH6 | (a) cutover script (per-key, active-only); (b) rehearsal recipe + ceremony doc | 2 |
    | PH7 | Full locked suite + go + smoke + quality gates + DW1 grep acceptance | 1 |

    Total: **~21 tasks**. PH0 and PH1 are strictly serial; PH2 tasks parallelize after PH1; PH6's cutover script and PH7's acceptance are coordinator-adjacent (the canonical cutover is coordinator-run after user sign-off, not a worker task).

- **PLAN-Alr-001.TC4:** Cutover script specifics (PH6). Mapping source = brief rename table. Scope = **active** strands only (archived/inactive strands are memory, not authority — `PROP-Alr-001.C1`). Transform is explicitly per-key, never a generic prefix rule: `shuttle/*` run attrs → `agent-run/*`, `treadle/*` → `gate/*` (incl. `treadle/gate`→`gate/step`), review/panel/note families split per key, plus `workflow/notes`→`workflow/outcome-notes` and the `shuttle/handle.<key>` prefix rewrite. Markers are renamed, not dropped. Rehearsal = copy canonical `data/skein.sqlite` into a `mktemp -d` `--workspace` world (guard every expansion with `${ws:?}`; never the canonical world, never a shared scratch path), run the renamed code + script, confirm smoke. Ceremony ends at the user-signed weaver restart (hard stop) then the `PROP-Alr-001.C5` post-restart smoke.

## PLAN-Alr-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
