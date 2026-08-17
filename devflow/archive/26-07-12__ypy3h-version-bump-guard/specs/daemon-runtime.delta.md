# daemon-runtime delta for ypy3h-version-bump-guard

**Document ID:** `DELTA-Vbg-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Status:** Draft **Last Updated:** 2026-07-12

## DELTA-Vbg-001.P1 Summary

`sync!` refuses to apply an in-JVM Maven version change for a coordinate whose jar URLs were added to the running spool classloader by a previous successful sync in this weaver generation. The refused change is recorded through the existing pending-generation path and takes effect only at the next mill-supervised weaver generation with user sign-off.

## DELTA-Vbg-001.P2 Contract changes

- **DELTA-Vbg-001.CC1** (extends SPEC-004.C44/C44c): After resolving the approved Maven universe and before adding jar URLs or source roots, `sync!` compares the current resolved Maven versions with the runtime's accumulated resolved-Maven state for this weaver generation. A coordinate is considered already loaded when a successful sync in this weaver generation added its resolved jar URLs to the spool classloader. The in-generation root, fingerprint, and Maven baselines accumulate across syncs because the classloader state remains live even when a later sync fails.

- **DELTA-Vbg-001.CC2** (extends SPEC-004.C44c): Any version change for an already-loaded coordinate is a non-additive sync diff. `sync!` refuses the in-JVM application with `ExceptionInfo` ex-data containing `:reason :non-additive-sync-diff`, a `:diff` map with `:maven-version-bumps`, each bumped coordinate, previous version, new version, and the existing remedy text: `recorded; takes effect at the next weaver generation (mill-supervised restart, user sign-off)`.

- **DELTA-Vbg-001.CC3** (extends SPEC-004.C44d): Maven version-bump refusals record the same in-memory `:pending-generation` shape as other non-additive sync diffs, including the current runtime generation id, classified diff, approved coordinate set, and remedy. No jar URL or source root is added before the refusal.

- **DELTA-Vbg-001.CC4** (extends SPEC-004.C47): This alpha rule is conservative. Skein does not attempt to prove whether old instances remain live and never swaps a Maven coordinate in place inside the running weaver process.
