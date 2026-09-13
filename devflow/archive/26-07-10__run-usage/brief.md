# Brief: run-usage — cost, tokens, wall-time on agent-run records

Kanban: card `2ms8c`, under epic `kaans` (agent-layer redesign). Source: 2026-07-05 coordinator retrospective (strand `i9rab`, retro/core-friction + what-good-looks-like, top ask) plus the coordinator sequencing note `pc2xl` on the card. The epic ships as a numbered feature sequence: F1–F4 are landed and live (F4 is the vocab registry, at `d9bc478`); this one, run-usage, is sequenced after F4 so its new keys become the first post-seed vocabulary addition.

## Problem

No machine-readable cost or duration exists on agent-run records — spend was only visible inside error JSON. Budget-aware coordination and any spend query are impossible; the retro ranked harness seats partly by cost/incident with no data to back it. (A _harness_ is a provider integration — the adapter that runs an agent against a given backend, e.g. `pi`, `claude`, `raw`; each emits a different output format.)

## Scope (the contract)

1. **First-class cost and token data on every agent-run record**, captured at run completion as new `agent-run/*` attributes. Wall time is _not_ a new attribute: it is derived at query time from the `agent-run/started-at` / `agent-run/finished-at` timestamps the engine already records, so no new duration attribute is stored.
2. **Capture per harness output format** (per note `pc2xl`, corrected by the empirical resolution in card note `8fcaa`):
    - `:pi-json` — pi's `--mode json` event stream emits a per-assistant-message `usage` map `{input, output, cacheRead, cacheWrite, reasoning, totalTokens, cost {input, output, cacheRead, cacheWrite, total}}`. The maps are per-turn deltas, so the run total is their sum; `cost` is a nested map, so run cost is the sum of each message's `cost.total`. Extend `parse-pi-json` (seam recently reworked by the g28j2 fix, commit `c3c1092`, already on main at branch point).
    - `:claude-json` — the result JSON carries cost fields; capture them.
    - `:raw` (codex) — no structured usage; wall-time only, from the derivation in item 1.
3. **Spend/usage query** over the recorded data (e.g. a registered query or CLI surface) so coordinators can see spend by run/harness/period.
4. **Vocabulary discipline**: declare any new `agent-run/*` keys through the F4 registry. This is the first vocabulary addition since the registry was seeded, so it doubles as the registry's first real customer — the team's own "dogfood" test that the declaration path works.

## Deliberately not built

- No budget enforcement or routing logic — this records the data that makes budget-aware routing possible.
- No backfill of historical runs: records are additive from capture-time forward.

## Migration

None. Purely additive — no cutover, no restart, no data rewrite; live pickup via targeted loads + `reload!`.

## Related

- F5 (`2mp13`) runs in parallel (downstream devflow.spool + notes-workspace updates); declared parallel-safe in note `pc2xl`.
