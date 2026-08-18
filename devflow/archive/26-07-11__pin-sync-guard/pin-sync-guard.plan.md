# Pin sync guard plan

**Document ID:** `PLAN-Psg-001`

**Feature:** `pin-sync-guard`

**Proposal:** [proposal.md](./proposal.md) (`PROP-Psg-001`)

**RFC:** none

**Root specs:** none

**Feature specs:** none (test-only guard; no durable contract change)

**Status:** Reviewed

**Last Updated:** 2026-07-11

## PLAN-Psg-001.P1 Goal and scope

Collapse the two hand-written spool-pairing tests in `test/skein/config_test.clj` into one data-driven guard over a declared list of `[spools.edn key, deps.edn key]` pairs, and add a completeness assertion so a new external spool cannot ship paired in neither file (`PROP-Psg-001.G1`–`G3`). This is one small test-refactor slice: no production code, no config coordinates, and no specs change. See the proposal for why the drift matters.

## PLAN-Psg-001.P2 Approach

- **PLAN-Psg-001.A1:** One `deftest` iterates a declared vector of pairs. Each pair is `{:spools-key <sym> :deps-key <sym>}` (e.g. `{:spools-key 'codethread/devflow :deps-key 'io.github.codethread/devflow.spool}`). For each pair the test looks up both entries, asserts both are present, then branches on shape exactly as the current kanban test does: both `:git/sha` → string equality; both `:local/root` → canonical-path equality (`io/file ".skein" root` vs `io/file "." root`, `.getCanonicalFile`); anything else → a failing assertion naming the mixed shape and both entries (an assertion, not a `throw`, so one bad pair does not stop the remaining pairs from being checked). Use `testing` with the pair to keep failures attributable. This preserves the two current tests' behaviour verbatim while removing the duplication (`PROP-Psg-001.G1`, `G2`).
- **PLAN-Psg-001.A2:** Keep the pairs list hand-declared, guarded for completeness — do not mechanically derive it from `spools.edn`. The deps.edn key (`io.github.codethread/devflow.spool`) is not a mechanical function of the spools.edn key (`codethread/devflow`): the `io.github` prefix and the `.spool` suffix are tools.deps conventions, not present on the weaver-side symbol. A derived list would have to encode that naming convention and would silently mis-map any spool that broke it. Instead the list stays explicit and readable, and a second assertion enforces that it is exhaustive.
- **PLAN-Psg-001.A3:** Completeness = set difference over the external spools (`PROP-Psg-001.G3`). Read `.skein/spools.edn`, take the `:spools` entries whose value carries `:git/url` (the marker of an externally-distributed spool; local spools use `:local/root` only and are carried on `:extra-paths`, not paired into `deps.edn`). Assert the set of those keys equals the set of `:spools-key` values in the declared pairs list. A new external spool added to `spools.edn` without a declared pair fails this assertion loudly, with the missing key(s) in the message — routing the author to the one enumeration site. This is the structural replacement for "remember to hand-write a second test," which is the exact failure the kanban guard suffered.

## PLAN-Psg-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Psg-001.AA1 | `test/skein/config_test.clj` | Replace the two `deftest`s (`devflow-spool-sha-...`, `kanban-spool-coordinate-...`, ~217–270) with one data-driven guard plus the completeness assertion. |

## PLAN-Psg-001.P4 Contract and migration impact

- **PLAN-Psg-001.CM1:** None. No shipped contract, CLI, data model, or config coordinate changes; the two spool coordinates and their `spools.edn`/`deps.edn` comments are untouched. Test-only refactor.

## PLAN-Psg-001.P5 Implementation phases

### PLAN-Psg-001.PH1 Data-driven pairing guard

Outcome: the two hand-written pairing tests are replaced by one `deftest` over a declared pairs vector that reproduces the `:git/sha` / `:local/root` / mixed-shape behaviour, and by a completeness assertion over the `:git/url`-carrying `spools.edn` entries. Both current pairs (devflow, kanban) pass; a deliberately dropped pair or a one-sided coordinate edit fails.

## PLAN-Psg-001.P6 Validation strategy

- **PLAN-Psg-001.V1:** Full locked suite green: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`. `skein.config-test` is an add-libs shard namespace (shard `C` in `test/skein/test_runner.clj`), and focused mode rejects shard members, so the full suite is the only cold gate that runs this slice's touched namespace.
- **PLAN-Psg-001.V2:** Negative checks confirmed manually before commit: (a) temporarily desync one coordinate → the pairing assertion fails; (b) temporarily drop a pair from the declared list → the completeness assertion fails naming the missing key. Revert both before landing.
- **PLAN-Psg-001.V3:** `make fmt-check lint reflect-check` clean for the edited test file.

## PLAN-Psg-001.P7 Risks and open questions

- **PLAN-Psg-001.R1:** The `:git/url` marker mis-classifies a spool. Mitigation: it is the same signal that already distinguishes the two external spools from the `:local/root` locals in `spools.edn`; a local spool that later gains a `:git/url` _should_ enter the guard, which is the intended behaviour, not a false positive.

## PLAN-Psg-001.P8 Task context

- **PLAN-Psg-001.TC1:** All work is in `test/skein/config_test.clj`. The two tests to replace and the exact shape-branching to preserve are ~lines 217–270; the kanban test already carries the `:git/sha` / `:local/root` / mixed-shape `cond` to reuse. The declared pairs today are `codethread/devflow` ↔ `io.github.codethread/devflow.spool` and `codethread/kanban` ↔ `io.github.codethread/kanban.spool`; both are `:git/sha` shape at time of writing. Read `.skein/spools.edn :spools` and `deps.edn :aliases :test :extra-deps` for the live keys.

## PLAN-Psg-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
