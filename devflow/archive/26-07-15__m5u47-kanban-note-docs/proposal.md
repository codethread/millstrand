# Kanban note documentation proposal

**Document ID:** `PROP-KanbanNoteDocs-001` **Last Updated:** 2026-07-14

**Related RFCs:** None **Related root specs:** None

## PROP-KanbanNoteDocs-001.P1 Problem

`kanban note` accepts payload references today, but its public documentation only shows note text as shell arguments. The declared `note` subcommand treats text as a required variadic positional (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1230`), and the handler receives the parsed text through `:op/args` (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1266`). Skein resolves `:stdin` and `:payload/<name>` across every parsed value before dispatch (`src/skein/api/cli/alpha.clj:324`, `src/skein/api/cli/alpha.clj:348`, `src/skein/api/cli/alpha.clj:398`, `src/skein/api/weaver/alpha.clj:425`). Long notes therefore work through payloads without a kanban behavior change, but users cannot discover that path from the kanban docs.

The same surface uses `--author`, while the settled spool CLI vocabulary uses `--by` for attribution. The flag is declared in the note arg-spec and translated back into the stored `:author` decoration (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:561`, `~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1230-1232`). TEN-000@1 permits dropping the old flag without a migration alias (`devflow/TENETS.md:3`), and TEN-003 requires the removed spelling to fail loudly (`devflow/TENETS.md:10`).

This feature crosses repository ownership. The contract, cookbook, operation, embedded manuals, and attribution tests live in `kanban.spool`. Skein consumes that repo by a SHA recorded in both `.skein/spools.edn` and `deps.edn` (`.skein/spools.edn:20`, `deps.edn:25-27`), with a test enforcing that the two coordinates match (`test/skein/config_test.clj:225`).

## PROP-KanbanNoteDocs-001.P2 Goals

- **PROP-KanbanNoteDocs-001.G1:** Make `:stdin` and named `:payload/<name>` note text discoverable in the kanban contract, cookbook, and `prime`/`about` manuals. Long or code-bearing examples use `strand --stdin kanban note <target> :stdin`.
- **PROP-KanbanNoteDocs-001.G2:** Replace the public note attribution flag `--author` with `--by`, while preserving the stored `:author` attribute and note projection.
- **PROP-KanbanNoteDocs-001.G3:** Update every active example and caller to the new flag. The discovery sweep found eight `--author` examples in three kanban doc-bearing files and no active skein-src caller or example under `src/`, `docs/`, `.skein/`, or `spools/`.
- **PROP-KanbanNoteDocs-001.G4:** Publish the kanban change first, then move skein-src's paired pin and SHA-specific documentation links to the merged revision, with the pinned spool suite green against skein-src.

## PROP-KanbanNoteDocs-001.P3 Non-goals

- **PROP-KanbanNoteDocs-001.NG1:** No change to note storage, relations, attribution attributes, note kinds, target validation, or card/task projections.
- **PROP-KanbanNoteDocs-001.NG2:** No `--author` compatibility alias. After the cutover it is an unknown flag.
- **PROP-KanbanNoteDocs-001.NG3:** No new kanban payload resolver or special-case parsing. The existing declared-argument path already resolves payload references.
- **PROP-KanbanNoteDocs-001.NG4:** No repo-wide removal of hand-written `:operation` fields. Only fields in functions changed by this feature are in scope.
- **PROP-KanbanNoteDocs-001.NG5:** No unrelated kanban command, cookbook, or Skein spool-pin cleanup.

## PROP-KanbanNoteDocs-001.P4 Proposed scope

### PROP-KanbanNoteDocs-001.S1 kanban.spool

- Change the note arg-spec from `:author` to `:by` and keep `--by` mapped to the existing `:author` decoration (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:561`, `~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1230-1232`). Unknown `--author` calls fail in the declared parser.
- Update the contract's note examples and command synopsis (`~/dev/projects/kanban.spool/kanban.md:68`, `~/dev/projects/kanban.spool/kanban.md:90`). Keep short decision and handover text inline; add the payload forms and use `--stdin` for long or code-bearing note text.
- Update the cookbook's three note calls. Its multi-line progress report is the existing example that should move to `--stdin`; its short decision and handover notes remain inline (`~/dev/projects/kanban.spool/kanban.cookbook.md:35`).
- Update both embedded manuals. `about` and `prime` currently show `--author`, and their bulk-note guidance is where the `--stdin` form belongs (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1077`, `~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1158`).
- Update the two test vars containing the three attribution calls to use `--by`, retain coverage of stored author attribution, and cover the loud rejection of `--author` (`~/dev/projects/kanban.spool/test/ct/spools/kanban_test.clj:268`, `~/dev/projects/kanban.spool/test/ct/spools/kanban_test.clj:301`).
- Apply the adjacent fix-on-touch rule to the hand-written operation stamps in the three functions this feature changes: `note`, `about`, and `prime` (`~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:566`, `~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1046`, `~/dev/projects/kanban.spool/src/ct/spools/kanban.clj:1125`). Other operation stamps remain.

### PROP-KanbanNoteDocs-001.S2 skein-src

- After the kanban.spool change is merged, set the same merged SHA in `.skein/spools.edn` and `deps.edn` (`.skein/spools.edn:20`, `deps.edn:25-27`). The coordinate-pair test remains the guard against drift (`test/skein/config_test.clj:225`).
- Move the SHA-specific kanban documentation links in `spools/kanban.md` and `spools/README.md` to that revision (`spools/kanban.md:3`, `spools/README.md:52`).
- The active-tree caller sweep found no skein-src `kanban note ... --author` invocation to rewrite. Archived devflow records and this feature's decision record are historical evidence, not callers.
- Run `make spool-suite-gate` after the pin bump. The target reads kanban's URL and SHA from `deps.edn`, materializes that revision, and runs its own `clojure -M:test` against the invoking skein-src checkout (`Makefile:121`, `Makefile:134`, `Makefile:167`, `Makefile:178`).

## PROP-KanbanNoteDocs-001.P5 Decisions and planning question

- **PROP-KanbanNoteDocs-001.D1:** The coordinator chose `--by` and no alias. This proposal records that settled decision rather than reopening it (`devflow/feat/m5u47-kanban-note-docs/brief.md:22`).
- **PROP-KanbanNoteDocs-001.D2:** Payload support is existing behavior, confirmed by the parser and dispatch paths above and by the live proof recorded in the brief (`devflow/feat/m5u47-kanban-note-docs/brief.md:6`).
- **PROP-KanbanNoteDocs-001.D3:** The design record is card `1dw6d`, note `ce3gj`, and epic `3o7le` (`devflow/feat/m5u47-kanban-note-docs/brief.md:44`).
- **PROP-KanbanNoteDocs-001.Q1:** How will the plan stage the two repositories without pinning an unpublished commit? It must create the kanban.spool branch, merge it to that repo's `main`, take the reachable merge SHA, then bump both skein-src pins and SHA-specific links before running the cross-repo gate.
