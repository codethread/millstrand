# Brief: uson2-cli-style-guide

Feature card `uson2` under epic `3o7le` (Spool CLI consistency). Successor to epic 9wnuc's spin-offs: each spool coined CLI nomenclature on the fly; the design review (card `1dw6d`: inventory, draft, opus+deepseek counsel, synthesis note `ce3gj`) settled the direction — fix outlier issues only, no rename-only churn, and rely on a style guide + shared code affordances

- an advisory review lens for future surface.

## Deliverables (three slices, all in skein-src)

1. **Style-guide section in `docs/spools/writing-shared-spools.md`**
    - Role-based verb sets:
        - entity-lifecycle: `start` / `finish --outcome` / `abort` only with real teardown / `status <id>` / `list`
        - workflow-step: `start` / `next` / `complete` / `choose` / `status`
        - process: `spawn` / `kill` / `retry` / `await` / `logs` / `ps`
    - Shared flag lexicon: `--by` for attribution; flags that stamp attributes are named after the attribute (`--owner`/`--branch`/`--worktree`/`--feature`); durations unit-suffixed seconds-first (`--timeout-secs`); `--outcome` for closing state.
    - Collection split: `list` = live filterable work entities; plural noun = fixed catalog (e.g. `harnesses`/`suites`/`backends`).
    - Op shape rule stated positively: cohesive multi-verb domain -> one op with declared subcommands; single-purpose projections and config-registered ops stay flat.
    - The one MUST: any text-bearing flag or positional rides the declared arg-spec parser so `:stdin`/`:payload/<name>` resolve — hand-rolled parsing that loses payload references is a bug (correctness, not style).
    - Policy: fix-on-touch, no rename-only churn.
    - Anchor the section to the existing about/prime/help discovery-tier docs rather than restating them.

2. **Composable arg-spec fragment helpers in `skein.api.spool.alpha`** (which already hosts `fail!`/`require-valid!` etc): note-surface, work-root, timeout-secs, and outcome fragments as plain data a spool author merges into a declared arg-spec.

3. **Auto-stamp operation labels from the full subcommand path.** The subcommand dispatch layer owns `:operation`, so authors stop hand-rolling it.

## Settled design constraints (do not relitigate)

- Fragment library lives in the existing `skein.api.spool.alpha`; no parser-level flag type system.
- Payload-reference plumbing is the one correctness MUST; everything else in the guide is advisory (PHILOSOPHY: prose guides, code decides — but the MUST is enforceable at review).
- Keep the `:operation` result key; stamp it from dispatch rather than hand-rolling.
- No compatibility aliases anywhere (TEN-000@1).

Design record: card `1dw6d` notes (synthesis note `ce3gj`; full opus/deepseek counsel as review-dump notes `r7i1f`/`ch0kz` on task `8kd6l`).
