# Task 002: L1 lean reads, descriptor spec, boundary guard (ASSN-PLAN-001.PH2)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`. **Depends on Task 001.**

Read first: `attr-scaling-ship-now.plan.md` (ASSN-PLAN-001 `PH2`, `A1`, `A2`, `AA2`, `AA4`, `AA5`, `AA6`, `AA7`, `TC2`, `TC3`, `R1`) and the deltas: `strand-model.delta.md` (ASSN-DELTA-001.CC1–CC7, `D1`, `D3`), `cli.delta.md` (ASSN-DELTA-002.CC1–CC4, `D1`), `daemon-runtime.delta.md` (ASSN-DELTA-003.CC6, CC7).

## Scope

L1 lean-by-default list reads at the CLI/agent op boundary, the single owned descriptor spec, and the fail-loud trusted-reader guard.

- `src/skein/core/specs.clj` (`AA2`): add `:skein/omitted` (`#{true}`), `::bytes` (`nat-int?`), `::omitted-attribute-descriptor` (`s/keys :req [:skein/omitted] :req-un [::bytes]`), and an `omitted-attribute-descriptor?` predicate. The descriptor is `{:skein/omitted true :bytes N}` where `N` is the JSON-encoded UTF-8 byte length (ASSN-DELTA-001.CC2/CC3, `TC2`). Construct/discriminate **only** through this spec — no ad hoc map-shape checks (`D1`).
- `src/skein/api/weaver/alpha.clj` (`AA4`): keep every trusted in-process read full-fidelity (`show`, `list`, `ready`, `strands-by-ids`, `subgraph`, query execution as consumed in process — ASSN-DELTA-003.CC6). **No** lean transform in `normalize-row` (`TC3`, `R1`). The lean-projection helper is **internal** — implement it privately (`skein.core.*` or private in batteries), not on the accretion-locked `skein.api.weaver.alpha` surface; its only consumer is batteries, and it is promoted to the alpha tier only if a second cross-spool consumer appears.
- `spools/src/skein/spools/batteries.clj` (`AA5`): `list-op`, `ready-op`, and the named-query listing path apply the lean projection above the **fixed 1024-byte (1 KiB)** floor (ASSN-DELTA-001.CC7); values ≤ floor pass through verbatim; `show-op` stays full (ASSN-DELTA-002.CC1/CC2). **No `--hydrate` flag / no hydration lever** (ASSN-DELTA-002.CC3, `D3`). Update batteries docstrings for the per-op lean wording — but the `spools/batteries.md` contract prose and `make docs-check` sync land in Task 004.
- `spools/src/skein/spools/util.clj` (`AA6`): `attr-get` fail-loud guard — reject a value conforming to `::specs/omitted-attribute-descriptor` where a raw value is expected, throwing `ex-info` with the **canonical ex-data** `{:key <attribute-key> :strand-id <strand-id> :recovery "show <strand-id>"}` — exactly these key names, so no later worker invents a divergent shape (ASSN-DELTA-001.CC6, ASSN-DELTA-003.CC7). Richer than `require-valid-relation-name!`'s bare `{:relation relation}`.
- Tests (`AA7`, under `test/skein/**` — e.g. `test/skein/spools/batteries_test.clj`, `test/skein/spools/util_test.clj`, and a specs test): descriptor discrimination (a descriptor is never a plain string/number/boolean), lean-vs-full split by op, the 1 KiB floor boundary (just-under passes, just-over omitted), and the `attr-get` guard's loud rejection including its canonical ex-data (`{:key … :strand-id … :recovery …}`). Use isolated weaver worlds (skein.test.alpha / spools test-support; mirror existing `batteries_test.clj`/`util_test.clj`).

## Hard acceptance bar — undeclared-key invariant (blocking)

**An undeclared attribute key is never slower or less capable than today.** For L1 that means:

- Every value at or below the 1 KiB floor — i.e. all small metadata/filter keys — passes through **verbatim**; only over-floor values become descriptors (ASSN-DELTA-001.CC1).
- The lean transform lives **only** in the batteries `list`/`ready`/named-query handlers, never in `normalize-row` and never on the trusted in-process API path a spool calls (`TC3`, `R1`, ASSN-DELTA-001.CC5, ASSN-DELTA-002.CC4). A trusted reader always receives real values; the `attr-get` guard fails loud if that discipline is ever broken.

## Validation

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test skein.spools.batteries-test skein.spools.util-test skein.core.specs-test
make fmt-check && make lint && make reflect-check
git status --short
```

## Guardrails

- Never start/stop/restart or reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`); use disposable `--workspace` worlds.
- Never `--no-verify`.
