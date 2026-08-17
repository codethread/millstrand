# Brief: m5u47-kanban-note-docs

Feature card `m5u47` under epic `3o7le` (Spool CLI consistency). Closes out superseded card `a6nw3`.

## Problem

Verified 2026-07-14 on the live board: `kanban note` ALREADY resolves `:stdin` and `:payload/<name>` — `kanban-op` consumes parsed args and `skein.api.cli.alpha` `resolve-payloads` runs across all parsed values (live proof: note `ce3gj` on task `8kd6l` was written via `strand --stdin kanban note <task> :stdin`). So this is documentation plus a fix-on-touch alignment, not a feature build.

## Where the work lands

The kanban spool is external: the `codethread/kanban.spool` repo (local checkout at `~/dev/projects/kanban.spool`; note its main has moved to `ct.spools` namespaces). Changes there, then a pin bump in skein-src `.skein/spools.edn` AND `deps.edn` — kept exactly in sync, `skein.config-test` enforces the pairing. This skein-src branch carries the pin bump and any in-repo doc references.

## Scope

1. Document payload references in the kanban spool docs: `kanban.md`, the cookbook, and the prime/about note examples — long or code-bearing note text should show the `--stdin` form.
2. While touching the note op, align attribution with core note: accept `--by`. DECIDED (coordinator, per the epic's settled flag lexicon: `--by` for attribution; TEN-000@1 no aliases): `--author` DROPS, replaced by `--by`; the removed flag fails loudly as unknown. Update every in-repo caller/example (skein-src docs, .skein config, spool cookbooks) to `--by` in the pin-bump commit.
3. Fix-on-touch rides (handed off from feature uson2-cli-style-guide's plan): while touching kanban.spool, migrate its hand-rolled `:operation` stamps ONLY where the touched code already changes — no repo-wide sweep. (The uson2 feature lands dispatch auto-stamping with rule: stamp absent / preserve equal / fail loud on disagreement, so existing equal stamps remain valid; do not block on uson2.)

## Constraints

- Kanban spool has its own test suite; skein-src's `make spool-suite-gate` runs the pinned suite against the checkout — the new pin must be green there.
- TEN-000@1: no compatibility aliases anywhere.

Design record: card `1dw6d` notes (synthesis `ce3gj`); epic `3o7le` body.
