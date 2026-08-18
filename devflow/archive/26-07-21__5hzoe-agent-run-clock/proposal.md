# Agent-run clock-aware awaiting proposal

**Document ID:** `PROP-Arc-001`

**Last Updated:** 2026-07-21

**Related root specs:** [REPL API](../../specs/repl-api.md) (`SPEC-003.C17b`), [Weaver Runtime](../../specs/daemon-runtime.md) (`SPEC-004.C1a`), and [Alpha Surface](../../specs/alpha-surface.md) (`SPEC-005.C5a`)

## PROP-Arc-001.P1 Problem

`ct.spools.agent-run/await-runs` in `agent-harness.spool` still measures timeouts with the system clock and waits with `Thread/sleep`. Manual-clock tests cannot drive that timeout deterministically, so they either wait on wall time or do not exercise the installed-clock contract. The shared `skein.api.spool.alpha/poll-until!` already owns the relative-timeout and Clock behavior, which avoids keeping a second polling implementation in the agent-run root.

## PROP-Arc-001.P2 Goals

- `PROP-Arc-001.G1` makes `await-runs` use the runtime-owned Clock for its timeout and polling wait.
- `PROP-Arc-001.G2` keeps the existing result shape and probes interactive sessions every eighth 250 ms poll, so a dead session is reported while an interactive await is still in progress rather than after its full timeout.
- `PROP-Arc-001.G3` proves the timeout path in `test/ct/spools/agent_run_test.clj` with `skein.test.alpha/manual-clock`; run `clojure -M:test ct.spools.agent-run-test` from `agent-harness.spool`.

## PROP-Arc-001.P3 Non-goals

- `PROP-Arc-001.NG1` makes no change to agent-run lifecycle, process, or session semantics.
- `PROP-Arc-001.NG2` makes no change to Skein's Clock or `poll-until!` contract.
- `PROP-Arc-001.NG3` adds no root-spec delta because this feature adopts the published surfaces cited above.

## PROP-Arc-001.P4 Proposed scope

- `PROP-Arc-001.S1` releases the `agent-harness.spool` repository with the change in its `ct.spools/agent-run` root. In `agent-run/src/ct/spools/agent_run.clj`, `await-runs` passes `(runtime/clock runtime)` to `poll-until!`; the helper derives its deadline from the relative timeout.
- `PROP-Arc-001.S2` updates `.skein/spools.edn` together: `ct.spools/agent-run`'s `:git/tag` moves to the next annotated release tag and `:git/sha` records that tag's peeled commit SHA. Verify it with `git rev-parse 'vN^{}'` in `agent-harness.spool`, then use that output for `:git/sha`.
