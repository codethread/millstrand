# Reload preflight plan

**Document ID:** `PLAN-Rpf-001`

**Feature:** `reload-preflight`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** none

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md)

**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)

**Status:** Shipped

**Last Updated:** 2026-07-23

## PLAN-Rpf-001.P1 Goal and scope

Stop `runtime/reload!` from wedging the weaver when the on-disk spool config cannot sync: refuse pre-mutation instead of clearing registries and then discovering the refusal. Why: [proposal](./proposal.md) P1; contract: [delta](./specs/daemon-runtime.delta.md) CC1/CC2.

## PLAN-Rpf-001.P2 Approach

- **PLAN-Rpf-001.A1:** Add `skein.core.weaver.spool-sync/preflight-approved-sync!`: read approved config, materialize families, run per-root phase-1 validation, classify the structural non-additive diff, resolve the Maven universe, classify the full diff — `sync-approved-spools`' classification sequence, sharing its private helpers, without its state resets (sync clears public state before classifying; the preflight clears nothing). On a non-additive diff it calls the existing `fail-non-additive-diff!` (records the pending generation — the one permitted mutation — and throws); other failures (invalid config, floors, atomic resolution) propagate as they would from `sync!`. It never touches the classloader. The 1-arity resolves the running release marker exactly as `skein.api.runtime.alpha/sync!` does, so declared-floor refusals preflight identically.
- **PLAN-Rpf-001.A2:** `skein.core.weaver.runtime/reload-config!` calls the preflight as its first act, before `clear-reload-state!`. No other reload behavior changes; the existing catch (keep-what-loaded, resume, rethrow) still governs post-preflight failures.
- **PLAN-Rpf-001.A3:** Amend SPEC-004.C46 and C96 in the root spec per the delta, and merge the delta on landing.

## PLAN-Rpf-001.P3 Non-goals

Per proposal NG1–NG4: no registry snapshot/restore, no per-root `:failed` refusals, no overlay schema change, no in-JVM non-additive application.

## PLAN-Rpf-001.P4 Contract impact

- **PLAN-Rpf-001.CM1:** `reload!` behavior change only on the refusal path (previously wedged, now aborts pre-clear). API signatures unchanged; `preflight-approved-sync!` is a core (non-contract) namespace addition.

## PLAN-Rpf-001.P5 Implementation phases

### PLAN-Rpf-001.PH1 preflight-and-wire

Outcome: `preflight-approved-sync!` in spool-sync, called by `reload-config!` pre-clear; root spec C46/C96 amended.

### PLAN-Rpf-001.PH2 regression-lock

Outcome: tests proving (a) a non-additive config makes `reload!` throw `:non-additive-sync-diff` with queries, module-use state, and public sync state intact and the pending generation recorded; (b) an invalid spools.edn aborts the reload pre-clear the same way; (c) an unchanged config reloads exactly as before (existing reload tests stay green).

## PLAN-Rpf-001.P6 Validation strategy

- **PLAN-Rpf-001.V1:** Cold focused run of `skein.spools-test` (the reload/sync suites) is the slice gate; `make fmt-check lint reflect-check docs-check` and the full CI matrix gate landing.

## PLAN-Rpf-001.P7 Risks and open questions

- **PLAN-Rpf-001.R1:** Double materialization/resolution per reload (preflight + real sync). Both hit warm caches (git cache dir, Maven local repo, tools.deps subprocess); accepted. Mitigation if it ever bites: thread the preflight result into the sync.
- **PLAN-Rpf-001.R2:** A config edited between preflight and init.clj's sync! could still wedge. The window is one process's milliseconds and the pre-existing C96 contract still bounds the damage; accepted.

## PLAN-Rpf-001.P8 Task context

- **PLAN-Rpf-001.TC1:** Key code: `src/skein/core/weaver/spool_sync.clj` (sync-approved-spools ~1300, non-additive-diff 1236, fail-non-additive-diff! 1291, materialize-families 1077, resolve-spool-maven-libs 1010), `src/skein/core/weaver/runtime.clj` (reload-config! 277, clear-reload-state! ~267). Tests: `test/skein/spools_test.clj` (with-runtime/write-spools!/write-local-lib! fixtures; reload tests ~1740; non-additive fixtures ~1180). Incident forensics on card w77oj.

## PLAN-Rpf-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-Rpf-001.DN1 Redefinition interaction — 2026-07-19

- The preflight also refuses reloads while a local-root source bump's redefinition diff (SPEC-004.C44c) is outstanding — previously such a reload succeeded blindly in worlds whose init.clj never syncs, and wedged in worlds whose init.clj does. `reload-synced-spool-picks-up-bumped-source-that-reload-and-require-miss` updated to expect the refusal. Root cause of the outstanding diff surviving a completed hot bump: `reload-synced-spool!` does not refresh the fingerprint baseline; follow-up card raised for that (see kanban).
