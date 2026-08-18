# Kanban dashboard bin proposal

**Document ID:** `PROP-Kdb-001`

**Status:** Approved

**Approved:** 2026-07-31

**Related RFCs:** None

**Related root specs:** [CLI surface](../../specs/cli.md), [Alpha surface](../../specs/alpha-surface.md)

**Related proposal:** [`PROP-Sbn-001`](../spool-bins/proposal.md), especially `PROP-Sbn-001.S10`; this proposal supersedes S10's `scripts/kanban-export` package and build-cwd detail

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the plan and code.

Skein's [shared-spool contract](../../../docs/spools/writing-shared-spools.md#publishing-a-shared-spool-with-git-distribution) defines git coordinates and ordered release markers. Its [authoring-form contract](../../../docs/spools/writing-shared-spools.md#author-contributions-with-kind-specific-forms) defines `defbin`, `mill bin build`, `mill bin run`, and the `SKEIN_WORKSPACE` environment passed to a bin.

## PROP-Kdb-001.P1 Problem

The interactive kanban dashboard lives under `skein-src/scripts/agent-dash`, even though its main behavior belongs to the kanban spool. Consumers installed from a kanban git coordinate cannot discover or run it. Skein also owns its Bun dependency setup and dashboard tests through `make`, which ties the board UI to one consumer repository.

## PROP-Kdb-001.P2 Goals

- **PROP-Kdb-001.G1:** Publish the interactive board as a kanban spool bin that a consumer can build and run through `mill bin`.
- **PROP-Kdb-001.G2:** Preserve the portable board behavior: active/all cards; epic, feature, and task trees; attribute details; label-filter tabs saved per selected workspace; polling; editor and clipboard actions; arrow and `j`/`k` movement; `Ctrl-D`/`Ctrl-U`, `g`, and `G` paging; enter, right, `l`, escape, left, and `h` detail navigation; tab and shift-tab filter switching; `f`, space, `!`, `m`, `i`, `x`, enter, and escape filter editing; `=`/`-` expansion; `Ctrl-G` editor opening; `y` copying; and `a`, `r`, and `q` shell actions.
- **PROP-Kdb-001.G3:** Make `skein-src` consume a released kanban marker containing the bin and keep `make dash` as a compatibility target that runs the bin's build recipe before opening it.

## PROP-Kdb-001.P3 Non-goals

- **PROP-Kdb-001.NG1:** Do not move Skein repository policy into kanban. Remove the merge-lock query and banner from the migrated dashboard; no other existing dashboard behavior is approved for removal.
- **PROP-Kdb-001.NG2:** Do not add dashboard installation, build-state tracking, or terminal proxying beyond the existing `defbin` and `mill bin` contracts.
- **PROP-Kdb-001.NG3:** Do not tag a Skein source release as part of this work.

## PROP-Kdb-001.P4 Proposed scope

- **PROP-Kdb-001.S1:** Kanban owns and releases the dashboard under its own `scripts/agent-dash` Bun package, including the source, dependency lock, tests, and a `kanban-dash` bin declaration. This replaces S10's earlier assumption that the dashboard would share `scripts/kanban-export` and its lock.
- **PROP-Kdb-001.S2:** The released dashboard uses `SKEIN_WORKSPACE` to select the target world and calls the public `strand kanban board`, `strand kanban card`, and strand detail surfaces needed for its projections.
- **PROP-Kdb-001.S3:** Skein removes its dashboard copy and repository-specific dashboard checks, updates the kanban git tag and peeled commit SHA in `.millstrand/spools.edn`, and makes `make dash` run `mill bin build kanban-dash` followed by `mill bin run kanban-dash`. Both commands run from the Skein checkout and target its selected workspace.
- **PROP-Kdb-001.S4:** The moved dashboard tests are the regression baseline for G2. Verification also covers kanban's test and quality gates, Skein's affected gates, bin discovery/build/run from the consumer, and a supplemental interactive tmux session over the retained controls.

## PROP-Kdb-001.P5 Open questions

None.
