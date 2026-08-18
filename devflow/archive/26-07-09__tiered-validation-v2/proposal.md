# Tiered Test Validation Proposal

**Document ID:** `PROP-Ttv-001`

**Last Updated:** 2026-07-09

**Related RFCs:** [RFC-016 Test Concurrency and Multi-Runtime JVM Support](../../rfcs/2026-07-03-test-concurrency.md), [RFC-Dtt-001 Deterministic Test Time and Async Quiescence](../../archive/26-07-09__deterministic-test-time/rfcs/2026-07-09-deterministic-test-time.md) **Related root specs:** None

## PROP-Ttv-001.P1 Problem

Two landings since this proposal was first drafted shipped part of its own scope, so the problem has shifted. A blessed focused entrypoint now exists: focused runner v1 (commit 2bf5f80) accepts `clojure -M:test <ns...>` and runs the named namespaces in-process on the correct `:test`-alias classpath, validating each against the runner's declared island sets (`validate-focused!`) and failing loudly on an unknown or shard-only namespace. Deterministic test time (commit a11c915, RFC-Dtt-001) then shrank the serial island: the full suite is now ~40s wall, the serial island is ~18.8s across 5 namespaces, and the floor is the add-libs shard A subprocess at 35-45s.

The remaining problem is twofold. First, the focused entrypoint and the fast suite both exist, yet the standing guidance surfaces agents copy from — AGENTS.md/CLAUDE.md testing rules, the agents spool policy prose, and the pending `attr-scaling-ship-now` queue — still prescribe the full locked suite per slice (the archived deterministic-test-time plan already used focused per-slice runs, but nothing durable prescribes that convention). AGENTS.md prescribes `flock -w 3600 /tmp/skein-test.lock clojure -M:test` as the safe test command, so plan and task authors rationally copy it into every AFK slice's Done-when block. A serial queue of N slices then pays N full-suite runs under a lock contended by sibling features, when a focused run of the touched namespaces would gate the slice correctly. Second, even the focused run pays JVM boot every invocation, so there is no warm loop for sub-second iteration while a slice is in flight.

Evidence (point-in-time observations, 2026-07-08/09; timings measured on the development host and expected to drift): macros tasks 001/002 Done-when blocks, plan PLAN-Srm-001.V1 ("full suite after every slice"), session timeline analysis on kanban card `n7aya`; the currently pending `attr-scaling-ship-now` queue repeats the same full-suite-per-slice prescription.

## PROP-Ttv-001.P2 Goals

- **PROP-Ttv-001.G1:** (shipped) A blessed focused test entrypoint exists: agents run a chosen subset of test namespaces with the correct `:test`-alias classpath while the runner keeps honoring parallel/serial island placement. This is stated as the baseline the remaining scope builds on, not proposed.
- **PROP-Ttv-001.G2:** (shipped) A focused run of an undeclared test namespace fails loudly (TEN-003), so island placement stays the single source of truth; add-libs shard namespaces are likewise rejected from focused mode in v1. The error carries the known namespaces in its ex-data, routing authors of a new test namespace to the declaration site.
- **PROP-Ttv-001.G3:** A documented tiered validation convention: a warm REPL to iterate, a cold focused run (`clojure -M:test <ns...>`) as the slice Done-when gate, and the full locked suite exactly once at queue acceptance and again at land `merge-local-verify`. Warm results never stand in for the cold focused gate.
- **PROP-Ttv-001.G4:** Guidance that plan/task authors actually copy prescribes the tiers: the AGENTS.md/CLAUDE.md testing rules, the agents spool `:task-sizing`/policy prose (with `make api-docs` regen), and the pending `attr-scaling-ship-now` task queue.
- **PROP-Ttv-001.G5:** The tiers compose with locking without reintroducing one-suite-at-a-time serialization. The full locked suite runs under `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (bare `flock` is on PATH via nix); focused and warm iteration do not take that lock.
- **PROP-Ttv-001.G6:** A warm test REPL gives sub-second focused iteration, scoped to the worktree as the ownership unit and reusing the cold runner's namespace validation so warm and cold reject identically.

## PROP-Ttv-001.P3 Non-goals

- **PROP-Ttv-001.NG1:** No change to what the full suite covers, its island assignments, or its wall-clock profile; RFC-016's structure is consumed, not revised.
- **PROP-Ttv-001.NG2:** No per-var/per-test selection or test frameworks; namespace granularity only (TEN-004).
- **PROP-Ttv-001.NG3:** The land workflow's `merge-local-verify` full gate and CI are unchanged; tiers apply to per-slice validation, not to landing.
- **PROP-Ttv-001.NG4:** No changes to the external devflow.spool or the devflow plugin's authoring references — neither prescribes full-suite runs; the prescriptions live in this repo's guidance.

## PROP-Ttv-001.P4 Proposed scope

- **PROP-Ttv-001.S2:** The tiered validation convention is documented where agents read testing rules (AGENTS.md/CLAUDE.md), stating when a slice uses a warm or cold focused run, when the full locked suite is required, and the lock command for the full tier.
- **PROP-Ttv-001.S3:** The agents spool manual's `:task-sizing`/policy prose prescribes tiered validation for delegated task contracts (regen the `about` manual with `make api-docs`).
- **PROP-Ttv-001.S4:** The pending `attr-scaling-ship-now` task queue is swept to the tiers: per-slice Done-when blocks become focused namespace runs; the full locked suite stays only in its `005-validation-sweep` task; stale `flock` paths are corrected.
- **PROP-Ttv-001.S5:** A warm test REPL per worktree: a script entry that probes an existing REPL or boots one, idle self-termination, and cleanup on worktree removal. The warm side reuses the runner's namespace validation, and a warm run never counts as a slice gate — the cold focused run is the Done-when gate.
- **PROP-Ttv-001.S6:** Optionally, advisory visibility of live warm REPLs in the coordination world, as convenience only — never an authoritative record of validation.

## PROP-Ttv-001.P5 Open questions

- **PROP-Ttv-001.Q2:** Whether runner v2 should add shard-focused selection so a focused run can target an add-libs shard's namespaces (rejected outright in v1) — pursued only if cheap, since the add-libs shard tier shrinks under separate card `vk8aa` regardless.
