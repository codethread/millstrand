# Weaver Runtime delta for reload preflight

**Document ID:** `DELTA-Rpf-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Draft **Last Updated:** 2026-07-19

## DELTA-Rpf-001.P1 Summary

`reload!` gains a pre-mutation refusal: a config the sync phase would throw on as a whole never reaches the registry-clearing phase. SPEC-004.C46 and SPEC-004.C96 gain the contract; no other clause changes.

## DELTA-Rpf-001.P2 Contract changes

- **DELTA-Rpf-001.CC1:** Amend SPEC-004.C46: before clearing any weaver-lifetime state, `reload!` classifies the on-disk approved spool config against the loaded generation exactly as `sync!` would, resolving the same running release marker. When that classification throws — a non-additive diff (SPEC-004.C44c, C44f), an invalid config, a declared-floor refusal, or an atomically failing Maven universe (SPEC-004.C44a, C44b) — `reload!` rethrows it with registries, public sync state, module-use state, and the event system untouched; a refused non-additive diff records the SPEC-004.C44d `:pending-generation` as its only mutation. Per-root `:failed` outcomes are not refusals and proceed.
- **DELTA-Rpf-001.CC2:** Amend SPEC-004.C96: the clearing path of `reload!` is only reachable after that preflight passes, so a config whose classification throws cannot strip the world to built-ins. The "startup file throws midway" allowance still governs everything after the preflight, including a sync whose config changed in the preflight-to-startup window.

## DELTA-Rpf-001.P3 Design decisions

### DELTA-Rpf-001.D1 Preflight classification, not snapshot/restore

- **Decision:** Refuse before mutating, by running the sync classification (materialize, validate, resolve — no classloader mutation, no runtime-state writes except the C44d pending-generation record on refusal) ahead of the clear.
- **Rationale:** The wedge class is exactly "sync would throw"; classifying first removes it without changing the C96 mid-file contract, and every classification step is side-effect-free with respect to runtime state (disk cache and subprocess resolution only) apart from that pending record.
- **Rejected:** Snapshotting and restoring registries around the whole reload — larger blast radius, changes C96's partial-load semantics, and still leaves the world torn down transiently.

## DELTA-Rpf-001.P4 Open questions

None.
