# Shell gate script files proposal

**Document ID:** `PROP-Wgs-001`
**Last Updated:** 2026-07-26
**Related RFCs:** None
**Related root specs:** None
**Design record:** Kanban card `u77o8`

**Configuration identification:** `PROP-Wgs-001` is the first proposal for the shell gate script files feature. Nested point IDs use the full document ID.

## PROP-Wgs-001.P1 Problem

The feature CI workflow and land merge continuation store their shell gates as escaped Clojure strings. The scripts are hard to read, cannot be checked or run as standalone files, and obscure the boundary between workflow configuration and shell behavior.

## PROP-Wgs-001.P2 Goals

- **PROP-Wgs-001.G1:** Keep the feature CI watch and PR merge scripts as readable, executable shell files.
- **PROP-Wgs-001.G2:** Preserve the scripts and their arguments in each poured gate so an in-flight run remains immutable.
- **PROP-Wgs-001.G3:** Exercise both extracted scripts against deterministic fake GitHub CLI behavior.

## PROP-Wgs-001.P3 Non-goals

- **PROP-Wgs-001.NG1:** Do not move the small canonical-main pull script out of the workflow file. Keep it as the documented small-inline exemplar.
- **PROP-Wgs-001.NG2:** Do not convert the main CI watcher to Clojure or change shell executor behavior.
- **PROP-Wgs-001.NG3:** Do not add a shared shell library or change shipped spool contracts.

## PROP-Wgs-001.P4 Proposed scope

- **PROP-Wgs-001.S1:** Move the feature CI watch and PR merge scripts into executable files under `.millstrand/scripts/`.
- **PROP-Wgs-001.S2:** Load each file at its existing workflow site while keeping the persisted `shell/argv` shape.
- **PROP-Wgs-001.S3:** Add focused execution coverage for both extracted scripts.

## PROP-Wgs-001.P5 Open questions

- **PROP-Wgs-001.Q1:** None.
