# Pin sync guard proposal

**Document ID:** `PROP-Psg-001` **Last Updated:** 2026-07-11 **Related RFCs:** None **Related root specs:** None (test-only guard; ships no shipped-tree contract, changes only `test/skein/config_test.clj`)

## PROP-Psg-001.P1 Problem

Two externally-distributed spools are pinned in two files that must agree. `.skein/spools.edn` carries the coordinate the weaver resolves; `deps.edn` `:aliases :test :extra-deps` carries the coordinate `config_test` loads in-process (it loads `.skein/config.clj` on the test JVM classpath). A one-sided sha or root edit silently splits the two processes onto different spool revisions.

Today each pairing is guarded by its own hand-written pairing test in `test/skein/config_test.clj` (`devflow-spool-sha-pin-is-synced-across-spools-edn-and-deps-edn` and `kanban-spool-coordinate-is-synced-across-spools-edn-and-deps-edn`, ~lines 217–270). The two tests are near-duplicates that drifted: kanban's guard exists only because a review caught its absence after kanban was added. The guard is per-spool and opt-in, so the failure mode is structural — a third external spool can ship with its coordinate paired in neither test, and nothing fails. Coverage today depends on each new external spool's author remembering to hand-write a matching test; nothing structural enforces it.

## PROP-Psg-001.P2 Goals

- **PROP-Psg-001.G1:** One data-driven guard covers every external spool pairing. A single declared list of pairs `[spools.edn key, deps.edn key]` is iterated by one test, replacing the two hand-written near-duplicates.
- **PROP-Psg-001.G2:** The guard preserves the existing per-pair shapes: `:git/sha` equality; `:local/root` canonical-path equality (spools.edn roots resolve relative to `.skein/`, deps.edn roots relative to the repo root); and loud failure on a mixed shape (one side `:git/sha`, the other `:local/root`).
- **PROP-Psg-001.G3:** A third external spool cannot ship unguarded. The guard fails loudly when an external (`:git/url`-carrying) entry in `.skein/spools.edn :spools` is absent from the declared pairs list, routing the author of a new external spool to the one enumeration site.

## PROP-Psg-001.P3 Non-goals

- **PROP-Psg-001.NG1:** No single source of truth the two config files derive from (including doc-link shas). Eliminating the duplicated coordinate rather than guarding it is a separate refinement card and is not proposed here.
- **PROP-Psg-001.NG2:** No change to the coordinates themselves, to which spools are external, or to how the weaver and `config_test` resolve them. This guards the existing pairing; it does not restructure it.
- **PROP-Psg-001.NG3:** No guard over local-only (`:local/root`, non-`:git/url`) spools in `.skein/spools.edn`; those are carried on `:extra-paths`, not paired into `deps.edn :extra-deps`, so they have no second coordinate to drift against.

## PROP-Psg-001.P4 Proposed scope

- **PROP-Psg-001.S1:** Replace the two hand-written pairing tests with one data-driven test over a declared list of `[spools.edn key, deps.edn key]` pairs, keeping the `:git/sha` / `:local/root` / mixed-shape behaviour of G2.
- **PROP-Psg-001.S2:** Add a completeness assertion (G3): enumerate the `:git/url`-carrying entries in `.skein/spools.edn :spools` and fail loudly if any is missing from the declared pairs list.

## PROP-Psg-001.P5 Open questions

- **PROP-Psg-001.Q1:** None blocking. The one design choice — a hand-declared pairs list guarded for completeness, rather than a list mechanically derived from `spools.edn` — is settled in the plan (`PLAN-Psg-001.A2`) because the deps.edn key (`io.github.codethread/devflow.spool`) is not a mechanical function of the spools.edn key (`codethread/devflow`).
