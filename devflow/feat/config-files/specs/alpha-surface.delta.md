# Alpha Surface delta for config files

- **Document ID:** `DELTA-Cff-003`
- **Root spec:** [Alpha Surface](../../../specs/alpha-surface.md)
- **Feature:** [Proposal](../proposal.md)
- **Status:** Draft
- **Last Updated:** 2026-09-23

## DELTA-Cff-003.P1 Summary

Add the proposed config API to the public alpha index when implementation ships, as required by SPEC-005.C9. This delta adds no independent behaviour.

## DELTA-Cff-003.P2 Contract changes

- **DELTA-Cff-003.CC1:** Add `millstrand.api.config.alpha` to SPEC-005.C2's enumerated blessed namespaces and update the count from the actual shipped namespace set at promotion. The API owns the file-backed authoring family and pure hydration helper specified in [DELTA-Cff-001](repl-api.delta.md). SPEC-003 owns the Clojure boundary; SPEC-004 owns runtime execution through [DELTA-Cff-002](daemon-runtime.delta.md).
- **DELTA-Cff-003.CC2:** Extend SPEC-005.C13's family description to include the config-file lifecycle family without adding it to C12's six registry families. Function-backed declarations retain the exact authored Var and printable protocol descriptor rules. Consumer-local lifecycle `:after` options belong to typed selection, not registry override policy.
- **DELTA-Cff-003.CC3:** Keep filesystem/parser plumbing and retained resource representations internal under SPEC-005.C5/C5b. Source, generated API docs, and boundary tests enumerate the public Vars. External alias/reviewer adapters remain userland under C4.

## DELTA-Cff-003.P3 Design decisions

### DELTA-Cff-003.D1 One indexed public surface

The new namespace joins the existing alpha tier. It does not introduce a configuration-plugin tier, expose lifecycle handles, or redefine external spool contracts.

## DELTA-Cff-003.P4 Open questions

None. The root index remains unchanged until implementation is delivered.
