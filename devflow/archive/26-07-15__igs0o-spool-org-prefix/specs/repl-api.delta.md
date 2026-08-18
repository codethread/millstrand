# REPL API delta for igs0o-spool-org-prefix

**Document ID:** `DELTA-Sop-001`

**Root spec:** [repl-api.md](../../../specs/repl-api.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Merged

**Last Updated:** 2026-07-15

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `SPEC-Dwr-001` for v1 and `SPEC-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `DELTA-Sop-001.P1`, so references are globally grepable and do not clash across documents.

## DELTA-Sop-001.P1 Summary

SPEC-003.C19's contractual namespace tiers enumerate only `skein.*` namespaces, all of which name source shipped by the skein checkout. External shared spools live outside the checkout and already follow an org-prefix source-namespace convention (`ct.spools.<name>` for codethread spools), but no spec clause states it. This delta adds the rule to C19 without rewording the existing tiers.

## DELTA-Sop-001.P2 Contract changes

- **DELTA-Sop-001.CC1:** Append to SPEC-003.C19: `skein.*` source namespaces are reserved for code shipped by the skein checkout; external/shared spool source namespaces use the author's org prefix instead (codethread spools use `ct.spools.<name>`), keeping them disjoint from every `skein.*` tier.

## DELTA-Sop-001.P3 Design decisions

### DELTA-Sop-001.D1 Additive sentence in C19 rather than a new clause

- **Decision:** The org-prefix rule rides in C19, the clause that already declares namespace tiers contractual.
- **Rationale:** The rule is the missing complement of the existing tier list — where source lives when it is _not_ a `skein.*` tier. A separate clause would split one boundary across two IDs.
- **Rejected:** A new SPEC-003 clause; a standalone spools spec (the convention is a namespace-tier fact, and `docs/spools/writing-shared-spools.md` carries the authoring guidance).

## DELTA-Sop-001.P4 Open questions

- **DELTA-Sop-001.Q1:** None.
