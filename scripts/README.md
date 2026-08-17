# Repository scripts

These scripts support development and coordination in this repository. Run them from a Millstrand checkout built with `make build`; agent scripts use the repo-local `bin/strand`.

## Kanban tree

`kanban-tree` prints one kanban card, an epic or a feature, as a terminal tree. The shape of the work reads at a glance: what the card contains, and the order it has to happen in. It is a Go program under `tools/kanban-tree`, built into `./bin/kanban-tree` by `make build` (or `make kanban-tree` on its own). It is repo-local development tooling in its own Go module: no Millstrand release or spool.

```sh
./bin/kanban-tree <epic-id>            # the epic and its feature cards
./bin/kanban-tree <epic-id> --tasks    # features with their tasks nested
./bin/kanban-tree <feature-id>         # a feature and its tasks
./bin/kanban-tree <epic-id> --open     # hide subtrees that are entirely closed
```

It reads the board through `strand kanban-export`, so it sees whatever workspace the strand binary beside it resolves. `--tasks` only applies to an epic; a feature already shows its tasks and passing the flag there is refused rather than ignored.

Containment and dependency are drawn as two different edges. A plain elbow means the parent contains the child (an epic contains its features, a feature contains its tasks); an arrow elbow means the child is blocked by the line above it, so reading down a branch is reading the order the work unblocks in. Each line opens with a status glyph, then the id and title, and closes with a detail column pinned to the right margin.

```
○ `df2f` Update the cli to go                             epic · 1/3 · pending
├── ✓ `owf2d` Scan code for compat                                 feat · done
│   └─▶ ○ `ffwf` …
└── ◐ `9duw` Check conflicts                        feat · 0/1 · claimed @opus
    ├── ● `t1` Read the go docs                                   task · ready
    └─▶ ○ `ffwf` …

── blocked by `owf2d` + `9duw` ───────────────────────────────────────────────
└─▶ ○ `ffwf` Release                                            feat · pending

5 items · ○ 2 pending · ◐ 1 claimed · ✓ 1 done · ● 1 ready
└── contains   └─▶ blocked by the line above   … expanded below
```

Work that several siblings block belongs to no single branch. It appears as an id stub (`` `ffwf` …``, carrying its own status glyph) under each of its blockers, and is expanded once below the tree under the blocker set that gates it. Dependencies that cross containers, like a task waiting on a card elsewhere in the epic, cannot be a branch at all, so they land in the detail column as `needs <id>`.

The glyphs are the derived status: `✓` done, `✗` abandoned, `○` pending, `●` ready, `⊘` blocked, `◐` claimed, `◈` in review, `◌` in refinement. A card takes its lane while it is live and its recorded outcome once it closes; a live task is `ready` or `blocked` depending on whether its blockers still stand. The detail column also carries a non-default priority, the closed/total count of children, and the owner of a claimed card. The tally under the tree counts what was drawn.

Titles give way before the detail column does: a line too narrow for both is clipped from the title end, and a line with no room for the detail at all keeps the title. `--width` sets the budget (default: the terminal's width, 0 turns clipping off).

`--ascii` swaps the box drawing and glyphs for plain ASCII, which also happens on its own when the locale does not claim UTF-8. `--all` keeps the execution strands (agent runs, workflow steps) that normally get filtered out, `--no-color` drops the colours, `--workspace` selects a non-default strand world, `--strand` overrides the binary it reads through, and `--json <file>` renders a saved `kanban-export` payload instead of calling strand at all.
