# Org-prefix convention for external spool source namespaces Proposal

**Document ID:** `PROP-Sop-001` **Last Updated:** 2026-07-15 **Related RFCs:** None **Related root specs:** [SPEC-003](../../specs/repl-api.md) (C19 namespace tiers)

## PROP-Sop-001.P1 Problem

`docs/spools/writing-shared-spools.md` documents a project-prefix convention for vocab claims (the "Namespace claims" section) but says nothing about Clojure _source_ namespaces. Epic xl7ph moved every codethread external spool to `ct.spools.*`, so the convention is live in the index (`spools/README.md`) and in shipped spools, but the rule itself is written down nowhere: the two authoritative tier statements — SPEC-003.C19 and the doc's "Namespace tiers (why this split exists)" section — enumerate only `skein.*` tiers. A new external-spool author has no stated rule for naming their source namespaces, and `skein.*`'s lives-in-the-skein-checkout meaning is only implied.

## PROP-Sop-001.P2 Goals

- **PROP-Sop-001.G1:** The org-prefix rule for external/shared spool source namespaces (`ct.spools.<name>` for codethread; org prefix generally) is stated in `docs/spools/writing-shared-spools.md` where tier/namespace guidance already lives.
- **PROP-Sop-001.G2:** The complementary meaning of `skein.*` — source that lives in the skein checkout, per the SPEC-003.C19 tiers — is stated alongside it.
- **PROP-Sop-001.G3:** SPEC-003.C19 carries the contractual form of the rule (namespace tiers are contractual per repo convention), as an additive sentence — no rewording of existing tiers.
- **PROP-Sop-001.G4:** The three naming axes stay clearly distinct for the reader: source namespace (`ct.spools.*` vs `skein.*`), vocab/attribute namespace prefix (`acme/priority`, existing "Namespace claims" rule), and the `spools.edn` coordinate symbol (`codethread/<name>`).

## PROP-Sop-001.P3 Non-goals

- **PROP-Sop-001.NG1:** No renaming or moving of any code; every spool already follows the convention.
- **PROP-Sop-001.NG2:** No change to the vocab-claims prefix rule or the registry duplicate-owner check.
- **PROP-Sop-001.NG3:** No change to coordinate-symbol conventions in `.skein/spools.edn`.

## PROP-Sop-001.P4 Proposed scope

- **PROP-Sop-001.S1:** Extend the "Namespace tiers (why this split exists)" section of `docs/spools/writing-shared-spools.md` with the external-spool source-namespace rule and the lives-in-checkout meaning of `skein.*`.
- **PROP-Sop-001.S2:** Add a short cross-reference so the "Namespace claims" section cannot be misread as covering source namespaces.
- **PROP-Sop-001.S3:** Add an additive sentence to SPEC-003.C19 stating that external/shared spool source is namespaced under an org prefix rather than `skein.*`.

## PROP-Sop-001.P5 Open questions

- **PROP-Sop-001.Q1:** None.
