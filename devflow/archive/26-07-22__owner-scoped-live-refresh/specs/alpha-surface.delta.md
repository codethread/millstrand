# Alpha Surface delta for owner-scoped live refresh

**Document ID:** `DELTA-OlrAlpha-001` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-22

## DELTA-OlrAlpha-001.P1 Summary

This feature deliberately rewrites the pre-v1 runtime loading contract in place under TEN-000@1. It also authorizes one coordinated in-place compatibility break for the three versioned peer spool families currently consumed only by this workspace.

## DELTA-OlrAlpha-001.P2 Contract changes

- **DELTA-OlrAlpha-001.CC1:** `skein.api.runtime.alpha` remains in the blessed tier but replaces its sync/use/reload lifecycle with module declaration, live refresh, joined status, and advanced code-only reload as specified by DELTA-OlrRepl-001. Removed vars receive no deprecated aliases because the project has not reached Skein v1 and maintaining both lifecycles would preserve the wrong abstraction.
- **DELTA-OlrAlpha-001.CC2:** The explicit-owner changes to blessed per-entry registry mutation functions are part of the same sanctioned rewrite. All first-party callers move atomically; generated API references describe only the new signatures.
- **DELTA-OlrAlpha-001.CC3:** Reference spools in the Skein tree adopt stable module ownership and complete-owner replacement where they publish declarations. Their behavior contracts remain in their spool docs; this feature does not move their tier.
- **DELTA-OlrAlpha-001.CC4:** `agent-harness.spool` v7, `kanban.spool` v4, and `devflow.spool` v2 remain immutable and reproducible at their published SHAs. By explicit repository-owner direction, their coordinated successors may break the normal accretion-under-one-name rule to adopt the new Skein lifecycle in place because this workspace is their only current consumer. The next markers are v8, v5, and v3 respectively unless another release lands first.
- **DELTA-OlrAlpha-001.CC5:** Each peer release runs its previous-marker compatibility alarm and records the expected failures caused by the authorized lifecycle break. The release note names the old and new marker, affected roots and public names, sole known consumer, Skein pin update, and absence of a migration shim. Unrelated old-contract failures remain blockers.
- **DELTA-OlrAlpha-001.CC6:** The workspace updates each peer tag and peeled SHA together only after the new Skein checkout and all peer roots pass their owner suites in disposable worlds. Existing consumers that do not update their immutable pins are unchanged.
- **DELTA-OlrAlpha-001.CC7:** This exception is feature-scoped and does not repeal the general vN accretion and compatibility-alarm discipline for later peer releases. A future break requires another explicit root-spec decision.
- **DELTA-OlrAlpha-001.CC8:** `skein.api.registry.alpha` joins the blessed API tier as the shared kind-declaration and owner-partition primitive for first-party and external spool domains. Declared kinds are published uniformly by the refresh kernel per DELTA-OlrRepl-001.CC5/CC13; the core owner-registry implementation remains internal. (Amended 2026-07-21 with the open-kind contract, DELTA-OlrDrt-001.D5.)

## DELTA-OlrAlpha-001.P3 Design decisions

### DELTA-OlrAlpha-001.D1 In-place pre-v1 reset

- **Decision:** Rewrite `runtime.alpha` under its current namespace and update every owned consumer instead of introducing a second lifecycle namespace.
- **Rationale:** TEN-000@1 exists to remove alpha abstractions before v1. Two blessed lifecycles would enlarge the surface and leave users choosing the same phases the feature is meant to hide.
- **Rejected:** Permanent forwarding aliases, a `beta` sibling namespace, or preserving old sync/use/reload behavior behind deprecation metadata.

### DELTA-OlrAlpha-001.D2 Explicit peer exception

- **Decision:** Publish the peer rewrites at their next markers under current roots and names, with an explicit expected compatibility break record.
- **Rationale:** The repository owner has confirmed this workspace is the only current consumer and prefers one coordinated pre-Skein-v1 model over new peer roots created solely to retain an obsolete lifecycle.
- **Rejected:** Claiming the peers are unversioned, silently ignoring their compatibility alarms, or resetting marker numbering.

## DELTA-OlrAlpha-001.P4 Open questions

None.
