# Reload-spool fingerprint refresh plan

**Document ID:** `PLAN-Rsf-001`

**Feature:** `reload-spool-fingerprint`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** none

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md)

**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)

**Status:** Shipped

**Last Updated:** 2026-07-23

## PLAN-Rsf-001.P1 Goal and scope

Make the documented hot-bump flow converge instead of refusing forever: a completed `reload-spool!` refreshes the redefinition baseline, and a provably clean `sync!` retires the stale pending-generation record. Why: [proposal](./proposal.md) P1; contract: [delta](./specs/daemon-runtime.delta.md) CC1/CC2.

## PLAN-Rsf-001.P2 Approach

- **PLAN-Rsf-001.A1:** In `reload-synced-spool!`, compute `root-fingerprint` over the root's vetted paths immediately before the load loop, and after every namespace load-files successfully, `assoc` it into `:approved-spool-generation-fingerprints` under the root-lib. Any load failure throws before the write, leaving the baseline (and the refusal) intact.
- **PLAN-Rsf-001.A2:** In `sync-approved-spools`' success tail, reset `:pending-spool-generation` to nil only when the per-root `failed` map is empty; a sync with per-root failures keeps the record and keeps returning it in the result.
- **PLAN-Rsf-001.A3:** Amend SPEC-004.C46/C44d per the delta; extend the `reload-spool!`/`sync!` docstrings and the customisation guide's hot-bump paragraph.

## PLAN-Rsf-001.P3 Non-goals

Per proposal NG1–NG3: no C44c relaxation, no preflight mutation, no reload-spool! contract change beyond the recorded fingerprint.

## PLAN-Rsf-001.P4 Contract impact

- **PLAN-Rsf-001.CM1:** Behavior changes on two lifecycles only: post-hot-bump classification (converges) and pending-record retirement (clean sync clears). No API signature changes.

## PLAN-Rsf-001.P5 Implementation phases

### PLAN-Rsf-001.PH1 converge-and-retire

Outcome: both mutations implemented with amended specs and docs.

### PLAN-Rsf-001.PH2 regression-lock

Outcome: tests proving (a) the keystone flow converges — source bump, `reload-spool!`, then `sync!` and `reload!` both pass and pending is cleared; (b) a partial reload failure leaves the baseline untouched and `sync!` still refuses; (c) a sync succeeding with a per-root `:failed` entry does not clear a recorded pending; (d) existing refusal tests stay green.

## PLAN-Rsf-001.P6 Validation strategy

- **PLAN-Rsf-001.V1:** Full locked suite (spools-test is a shard namespace); `make fmt-check lint reflect-check docs-check`; smoke and Go CLI via CI.

## PLAN-Rsf-001.P7 Risks and open questions

- **PLAN-Rsf-001.R1:** Fingerprint computed before load could differ from files read during load if edited mid-window. The recorded value is then conservative: the next sync sees a mismatch and refuses, never silently accepts; accepted.
- **PLAN-Rsf-001.R2:** Clearing pending changes a documented persistence claim; the delta carries the amendment and no test pinned the old persistence.

## PLAN-Rsf-001.P8 Task context

- **PLAN-Rsf-001.TC1:** Key code: `spool_sync.clj` reload-synced-spool! (~1607), sync-approved-spools success tail (~1400), root-fingerprint (1219), non-additive-diff redefinitions (~1249). Tests: `spools_test.clj` keystone `reload-synced-spool-picks-up-bumped-source...` (~2460), `sync-records-pending-generation-for-removed-loaded-root`, reload-preflight tests (~1782+). Prior context: PLAN-Rpf-001.DN1, incident card w77oj.

## PLAN-Rsf-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
