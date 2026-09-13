# Alpha surface delta for spec-projection

**Document ID:** `DELTA-Spj-001`

**Root spec:** [alpha-surface.md](../../specs/alpha-surface.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-29

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version. Prefix every nested point ID with the full document ID.

## DELTA-Spj-001.P1 Summary

`skein.api.spec.alpha` joins the blessed spool-facing API: the shared spec-over-wire documentation projection every discovery and failure surface uses to describe a registered clojure.spec contract.

## DELTA-Spj-001.P2 Contract changes

- **DELTA-Spj-001.CC1:** SPEC-005.C2's blessed list gains `spec` (`skein.api.spec.alpha`), following accretion-based compatibility within its subnamespace like every other listed module. Its promised surface is specified by the SPEC-003 clause this feature adds (see the repl-api delta).
