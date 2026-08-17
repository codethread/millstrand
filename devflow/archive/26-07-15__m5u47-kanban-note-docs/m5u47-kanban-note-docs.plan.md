# Kanban note documentation plan

**Document ID:** `PLAN-Knd-001` **Feature:** `m5u47-kanban-note-docs` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** none; no-delta judgment in PLAN-Knd-001.CM2 **Feature specs:** none; the public contract belongs to `kanban.spool` **Status:** Implemented **Last Updated:** 2026-07-14

## PLAN-Knd-001.P1 Goal and scope

Document the payload forms already accepted by `kanban note`, replace its attribution flag from `--author` to `--by`, and publish the change through the external `kanban.spool` repository before moving skein-src to the merged revision. The stored `:author` decoration stays unchanged. The work also removes hand-written `:operation` stamps only from the kanban functions changed by this feature. The approved scope and decisions are in [PROP-KanbanNoteDocs-001](./proposal.md).

## PLAN-Knd-001.P2 Approach

- **PLAN-Knd-001.A1:** Treat `kanban.spool` as the contract owner. Build its docs, declared CLI surface, handler, and tests together on branch `m5u47-note-payload-by` in `~/dev/projects/kanban.spool`. Examples use `strand --stdin kanban note <target> :stdin` for long or code-bearing notes and keep short notes inline. The handler maps parsed `--by` to the existing stored `:author` attribute. There is no compatibility alias.
- **PLAN-Knd-001.A2:** Apply the adjacent fix-on-touch rule in the same external-repo change. Remove hand-written `:operation` stamps only in the changed `note`, `about`, and `prime` functions. Do not sweep other kanban operations.
- **PLAN-Knd-001.A3:** Publish before consuming. The coordinator merges the external feature branch into `kanban.spool` `main`, records the reachable merged SHA, and hands that exact SHA to the skein-src pin-bump slice. Skein-src never pins an unpublished worker commit.
- **PLAN-Knd-001.A4:** Move both skein-src coordinates together: `codethread/kanban` in `.skein/spools.edn` and `io.github.codethread/kanban.spool` in `deps.edn`. Update the SHA-specific links in the forwarding kanban docs in the same slice. Re-run the active caller/example sweep and replace any live `kanban note ... --author` use found there; historical decision records retain the old spelling when discussing its removal.
- **PLAN-Knd-001.A5:** Keep each delivery slice within one worker context. External implementation and its standalone test suite are one AFK slice. The merge and SHA handoff are one coordinator-only checkpoint. The paired skein-src pin, links, caller sweep, generated-doc check, and consumer gates are one AFK slice after that checkpoint.

## PLAN-Knd-001.P3 Affected areas

- **PLAN-Knd-001.AA1 — `kanban.spool` contract and cookbook:** Add `:stdin` and named `:payload/<name>` note forms, use stdin for long or code-bearing examples, and replace `--author` examples with `--by`.
- **PLAN-Knd-001.AA2 — `ct.spools.kanban` operation and manuals:** Declare and consume `--by`, preserve stored `:author`, reject `--author`, and remove touched manual operation stamps.
- **PLAN-Knd-001.AA3 — `kanban.spool` tests:** Preserve attribution coverage through `--by`, add removed-flag rejection coverage, and retain note behavior coverage.
- **PLAN-Knd-001.AA4 — `.skein/spools.edn`, `deps.edn`:** Advance the paired kanban coordinates to the same merged SHA.
- **PLAN-Knd-001.AA5 — `spools/kanban.md`, `spools/README.md`:** Move SHA-specific external contract links to the merged revision.
- **PLAN-Knd-001.AA6 — active skein-src callers and generated docs:** Replace any remaining live `--author` caller/example and regenerate API docs only if the pin/docstring change affects generated output.

## PLAN-Knd-001.P4 Contract and migration impact

- **PLAN-Knd-001.CM1:** This is an intentional breaking change to the external kanban spool CLI. `kanban note --by <name>` writes the same stored `:author` decoration as before. `--author` is removed and fails as an unknown flag. Payload resolution, note storage, relations, kinds, target validation, and projections do not change.
- **PLAN-Knd-001.CM2:** No skein-src root spec or feature-local delta changes. All five root specs in `devflow/specs/` were checked. `SPEC-005.C4` places kanban in userland, outside the shipped alpha surface, and says userland spool docs own their contracts. This feature changes that external spool-doc contract without moving its tier, so `SPEC-005` stays accurate. `SPEC-004.C42`, C48, and C49 own generic exact-SHA spool approval; advancing one valid SHA does not change those contracts. No root spec owns the repo-specific rule that `.skein/spools.edn` and the test JVM's `deps.edn` coordinate must match. That integration invariant is already executable in `skein.config-test/external-spool-coordinates-are-synced-across-spools-edn-and-deps-edn` and needs no prose spec. Therefore no `*.delta.md` is warranted.
- **PLAN-Knd-001.CM3:** There is no data migration or compatibility period. Consumers update the flag when taking the new pin. Archived feature records may still mention `--author` as historical evidence; they are not active callers.

## PLAN-Knd-001.P5 Implementation phases

### PLAN-Knd-001.PH1 External spool contract and implementation

Outcome: branch `m5u47-note-payload-by` in `kanban.spool` contains the contract, cookbook, embedded manual, flag, handler, test, and touched-stamp changes as one reviewable commit series. The spool's standalone `clojure -M:test` suite passes against the sibling skein-src checkout.

### PLAN-Knd-001.PH2 Publish kanban.spool

Outcome: the coordinator merges PH1 to `kanban.spool` `main`, confirms the merged commit is reachable, and records its 40-character SHA for the consumer update. This is coordinator-only; no AFK worker merges or chooses the published SHA.

### PLAN-Knd-001.PH3 Skein consumer cutover

Outcome: this skein-src branch pins the PH2 SHA in both coordinate files, points its forwarding docs at the same revision, contains no active old-flag caller/example, and has refreshed generated API docs if the generator reports a change. The focused coordinate test, pinned external suite, and docs gate all pass.

## PLAN-Knd-001.P6 Validation strategy

- **PLAN-Knd-001.V1:** Gate PH1 in `~/dev/projects/kanban.spool` with `clojure -M:test`, the exact standalone suite documented by that repository's README, Makefile `test` target, and `deps.edn` `:test` alias. The suite must prove `--by` attribution and loud `--author` rejection as well as the unchanged note behavior.
- **PLAN-Knd-001.V2:** Before PH3, confirm the recorded SHA is reachable from merged `kanban.spool/main`. Review the pin/link diff as one unit so no unpublished or mixed revision can enter skein-src.
- **PLAN-Knd-001.V3:** Gate PH3 with cold `clojure -M:test skein.config-test`, then `make spool-suite-gate` against the new pin, then `make docs-check`. The config test proves both coordinate files agree; the spool gate proves the published kanban suite against this checkout; the docs gate proves the links and prose surface remain valid.
- **PLAN-Knd-001.V4:** Run the generated API-doc workflow only if the new pin or external docstrings feed generated output. Commit any resulting legitimate change and require a clean regeneration diff. Confirm `git status --short` contains no runtime or SQLite artifacts after validation.

## PLAN-Knd-001.P7 Risks and open questions

- **PLAN-Knd-001.R1:** Pinning before publication would leave another checkout unable to materialize the revision. PH2 is a hard dependency for PH3, and only the coordinator records the consumable SHA.
- **PLAN-Knd-001.R2:** A broad text replacement could rewrite historical rationale or unrelated `--author` flags. Limit the sweep to active kanban note callers/examples identified by context; preserve decision records that describe the removed spelling.
- **PLAN-Knd-001.R3:** Removing every manual `:operation` stamp would turn a small alignment into an unrelated cleanup. The external slice owns only stamps in `note`, `about`, and `prime`, where its feature diff already changes code.

There are no direction-level open questions. The flag spelling, compatibility policy, repository order, and validation boundary are settled by the approved proposal.

## PLAN-Knd-001.P8 Task context

- **PLAN-Knd-001.TC1:** Read [brief.md](./brief.md) and [proposal.md](./proposal.md) first. Payload references already resolve across parsed CLI values; do not add a kanban-specific resolver.
- **PLAN-Knd-001.TC2:** Slice S1 is AFK and owns PH1 in the external repository, including `clojure -M:test`. Slice S2 is coordinator-only and owns PH2: review/merge to external `main` and durable SHA recording. Slice S3 is AFK, depends on S2, and owns PH3 plus V2-V4 in this skein-src branch. Each slice fits one worker context; there is no cross-repo worker slice.
- **PLAN-Knd-001.TC3:** The approved inventory found eight documentation examples and three test calls using `--author` in `kanban.spool`, and no active skein-src caller/example under `src/`, `docs/`, `.skein/`, or `spools/`. Re-run the sweep because the trees may advance before execution.
- **PLAN-Knd-001.TC4:** Keep `.skein/spools.edn` and `deps.edn` byte-for-byte equal at the kanban `:git/sha` value. The comments and `skein.config-test` explain why both copies exist.

## PLAN-Knd-001.P9 Developer Notes

### PLAN-Knd-001.DN1 Task 5679g: spec and plan authoring — 2026-07-14

- No feature-local spec delta was created. PLAN-Knd-001.CM2 records the root-spec review and the existing executable pin-pair contract.
- The standalone `kanban.spool` suite command is `clojure -M:test`; README, Makefile, and `deps.edn` agree.
- Planned delivery is three serial slices: S1 external AFK implementation, S2 coordinator-only merge and SHA handoff, S3 skein-src AFK consumer cutover.
