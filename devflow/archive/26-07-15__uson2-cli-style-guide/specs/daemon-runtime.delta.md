# Weaver runtime delta for uson2-cli-style-guide

**Document ID:** `DELTA-Ucs-002` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Ucs-001`) **Status:** Reviewed **Last Updated:** 2026-07-14

## DELTA-Ucs-002.P1 Summary

`SPEC-004.C63b` currently transports a registered operation handler's return value unchanged. This feature changes that result boundary for declared subcommand ops: map results carry a dispatch-owned `:operation` label derived from the registered op and complete resolved subcommand path.

## DELTA-Ucs-002.P2 Contract changes

- **DELTA-Ucs-002.CC1:** After a declared arg-spec selects a subcommand and its handler returns a map, registered-op dispatch sets `:operation` to `"<op-name> <full-subcommand-path>"`. The path includes a parsed nested `:action` when present. Flat arg-spec ops, raw-envelope ops, non-map results, thrown failures, and the dispatch-level help alias retain their current behavior.
- **DELTA-Ucs-002.CC2:** If the returned map already contains `:operation`, dispatch accepts it only when it equals the derived label. A different value, including explicit nil, fails loudly with expected and actual labels; it is never overwritten or transported as a conflicting claim.

## DELTA-Ucs-002.P3 Design decisions

### DELTA-Ucs-002.D1 Preserve equal labels and reject disagreements

- **Decision:** An absent label is stamped, an equal label is preserved, and a disagreeing label fails.
- **Rationale:** Equal-label tolerance lets existing handlers move off their duplicated stamps on fix-on-touch. Rejecting disagreement follows TEN-003 and keeps the registered dispatch context authoritative.
- **Rejected:** Silently overriding a handler label, and allowing handler-owned labels to take precedence over the dispatch-derived value.

## DELTA-Ucs-002.P4 Open questions

None.
