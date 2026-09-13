# daemon-runtime delta for w92pn-sync-diff-classification

**Document ID:** `DELTA-Sdc-001`

**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)

**Status:** Draft

**Last Updated:** 2026-07-12

## DELTA-Sdc-001.P1 Summary

`sync!` classifies the new approved-spool universe against the running weaver generation before mutating the spool classloader. Additive roots still apply in-JVM through the existing stateless resolver path. Non-additive diffs are recorded as a pending generation and fail loudly; the change takes effect only at the next mill-supervised weaver generation with user sign-off.

## DELTA-Sdc-001.P2 Contract changes

- **DELTA-Sdc-001.CC1** (extends SPEC-004.C44): `sync!` diffs the newly materialized approved roots against the runtime's currently successful sync state before adding source roots or Maven jars. A root whose coordinate has never successfully synced in this generation is additive and may be added to the existing spool `DynamicClassLoader`. A still-approved coordinate that fails materialization or validation in the current sync is a per-root `:failed` outcome, not a non-additive removal.

- **DELTA-Sdc-001.CC2** (extends SPEC-004.C44): Non-additive diffs are refused in-JVM. The refused classes are: a previously successful root removed from approval, a previously successful coordinate now resolving to a different source root, and changed source for a root that already has loaded namespaces in this generation. The failure is an `ExceptionInfo` with `:reason :non-additive-sync-diff`, the classified `:diff`, and the remedy text: `recorded; takes effect at the next weaver generation (mill-supervised restart, user sign-off)`.

- **DELTA-Sdc-001.CC3** (extends SPEC-004.C44): When a non-additive diff is refused, the runtime records an in-memory `:pending-generation` map visible through `syncs` and subsequent successful `sync!` returns. The record includes `:status :pending`, the current runtime generation id, the classified diff, the approved coordinate set, and the remedy. This is runtime state, not durable package metadata; it is enough for the coordinator to inspect why the next process generation matters and stays within the existing runtime-state surface.

- **DELTA-Sdc-001.CC4** (extends SPEC-004.C44/C47): A weaver generation is a process boundary. The spool `DynamicClassLoader` is minted at weaver boot and is never swapped by `sync!`; `sync!` does not remove namespaces or unload code.

- **DELTA-Sdc-001.CC5** (extends SPEC-004.C44): `sync!` reports, but does not refuse, spool-state entries tagged with a generation different from the current runtime generation. The report is returned as `:retained-spool-state` with the state key, retained generation, and current generation. Entries that cannot carry generation metadata are reported with `:generation :unknown` and `:reason :untagged`. Versioned spool-state entries and spool-owned threads may legitimately survive config reloads; this report is visibility only.

## DELTA-Sdc-001.P3 Deferred work

- Maven version changes for already-loaded coordinates are intentionally not classified here. The version-bump guard belongs to follow-up card ypy3h and should slot into the same diff-classification seam.
