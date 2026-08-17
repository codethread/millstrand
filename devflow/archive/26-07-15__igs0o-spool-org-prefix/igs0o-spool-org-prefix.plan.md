# Org-prefix convention for external spool source namespaces Plan

**Document ID:** `PLAN-Sop-001` **Feature:** `igs0o-spool-org-prefix` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [repl-api.md](../../specs/repl-api.md) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md) **Status:** Reviewed **Last Updated:** 2026-07-15 **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PLAN-Dwr-001` for v1 and `PLAN-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PLAN-Sop-001.P1`, so references are globally grepable and do not clash across documents.

## PLAN-Sop-001.P1 Goal and scope

Write down the already-live org-prefix convention for external/shared spool source namespaces (`ct.spools.<name>`) and the complementary lives-in-the-skein-checkout meaning of `skein.*`, in the two authoritative namespace-tier statements. Docs-only; no code moves. Why: [PROP-Sop-001](./proposal.md).

## PLAN-Sop-001.P2 Approach

- **PLAN-Sop-001.A1:** One documentation pass touching two files. In `docs/spools/writing-shared-spools.md`, extend the "Namespace tiers (why this split exists)" list with the external-spool org-prefix rule and add a one-line cross-reference in "Namespace claims" so the vocab-prefix rule cannot be misread as covering source namespaces; keep the three naming axes distinct (source namespace `ct.spools.*`/`skein.*`, vocab prefix `acme/priority`, coordinate symbol `codethread/<name>`). In `devflow/specs/repl-api.md`, apply DELTA-Sop-001.CC1 as an additive sentence at the end of SPEC-003.C19 and mark the delta Merged.

## PLAN-Sop-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Sop-001.AA1 | `docs/spools/writing-shared-spools.md` | Tier-list addition + namespace-claims cross-reference |
| PLAN-Sop-001.AA2 | `devflow/specs/repl-api.md` | Additive sentence in SPEC-003.C19 |

## PLAN-Sop-001.P4 Contract and migration impact

- **PLAN-Sop-001.CM1:** Contractual wording of SPEC-003.C19 grows by one additive sentence (staged in [DELTA-Sop-001](./specs/repl-api.delta.md)). No behavior, API, or data change; every existing spool already conforms.

## PLAN-Sop-001.P5 Implementation phases

### PLAN-Sop-001.PH1 Document the convention

Outcome: both files updated, delta marked Merged, docs gates green.

## PLAN-Sop-001.P6 Validation strategy

- **PLAN-Sop-001.V1:** `make docs-check fmt-check lint` pass (docs-check is the CI-blocking gate for doc changes); prose reads as a rule statement, not a changelog.

## PLAN-Sop-001.P7 Risks and open questions

- **PLAN-Sop-001.R1:** Conflating the three naming axes would make the docs more confusing than silence; mitigated by naming each axis explicitly in the new text.

## PLAN-Sop-001.P8 Task context

- **PLAN-Sop-001.TC1:** Authoritative tier statements: SPEC-003.C19 (`devflow/specs/repl-api.md:62`) and `docs/spools/writing-shared-spools.md` "Namespace tiers (why this split exists)" (~line 512). Vocab-claims section ~line 107. Practice examples: `spools/README.md` index rows for `ct.spools.kanban`/`ct.spools.devflow`; `.skein/spools.edn` coordinates use `codethread/<name>`, a separate axis from the source namespace.

## PLAN-Sop-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
