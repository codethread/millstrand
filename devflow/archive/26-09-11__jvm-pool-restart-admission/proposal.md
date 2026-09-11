# JVM pool restart admission proposal

**Document ID:** `PROP-Jpra-001`
**Status:** Approved
**Approved:** 2026-09-11
**Related RFCs:** None
**Related root specs:** [`SPEC-004`](../../specs/daemon-runtime.md)

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Jpra-001.P1 Problem

A workspace can retain isolated restart history after its isolated Weaver stops. When that workspace joins a JVM pool, the history can replace its fresh pooled generation identity during grouped admission. Mill then terminates a healthy replacement host after cutover. Retrying happens to work because the first failure rewrites the conflicting history.

The same member-local persistence lets one pool transition compete with isolated lifecycle records even though pooled replacement belongs to the host.

## PROP-Jpra-001.P2 Goals

- **PROP-Jpra-001.G1:** Admit a previously isolated workspace into a JVM pool on the first valid collective replacement.
- **PROP-Jpra-001.G2:** Keep runtime identity proof independent from historical lifecycle presentation while preserving the complete grouped admission proof.
- **PROP-Jpra-001.G3:** Persist and present one restart transition for one pool host while retaining each member's distinct runtime identity.
- **PROP-Jpra-001.G4:** Preserve probe-before-cutover, grouped admission, failure diagnostics, and isolated restart behavior.

## PROP-Jpra-001.P3 Non-goals

- **PROP-Jpra-001.NG1:** Add automatic retries for failed pool admission.
- **PROP-Jpra-001.NG2:** Change pool membership, dependency compatibility, or refresh policy.
- **PROP-Jpra-001.NG3:** Migrate or delete valid isolated restart history merely because a workspace is registered in a pool.

## PROP-Jpra-001.P4 Proposed scope

- **PROP-Jpra-001.S1:** Runtime identity checks use the current runtime's published identity without restart-history overlays. Grouped admission still requires agreement across the ready marker, every member metadata file, every endpoint identity, the spawned PID, and the ordered member set.
- **PROP-Jpra-001.S2:** A pooled transition has one host-owned restart record. Its transition and host-generation fields are shared through every member projection, while member generation, endpoint, database, and runtime fields remain distinct. A pending member can expose the host transition without claiming a running member runtime.
- **PROP-Jpra-001.S3:** Successful pool admission replaces stale host transition or failure state with the current running host record. A failed probe retains the admitted old host and pending newcomer; a failed post-cutover launch admits no host or partial member set and retains one inspectable host-level failure record.
- **PROP-Jpra-001.S4:** Restart-record lookup and projection follow the current hosting mode. An isolated record may remain on disk but cannot alter pooled identity or status, and a pool-host record cannot become an isolated member record after a workspace leaves the pool.

## PROP-Jpra-001.P5 Examples

- **PROP-Jpra-001.E1:** A previously restarted isolated workspace joins a live pool on the first collective restart.

```text
mill weaver stop --workspace notes/.millstrand
mill init --workspace notes/.millstrand --jvm-pool dev
mill weaver restart --workspace notes/.millstrand

Weaver .millstrand running (PID <shared-pool-pid>)
```

- **PROP-Jpra-001.E2:** Status through any member reports the same pool transition rather than separate member transitions.

```text
mill weaver status --details --workspace dots/.millstrand
mill weaver status --details --workspace notes/.millstrand

Both results carry the same pool transition and host generation identities.
```

## PROP-Jpra-001.P6 Open questions

None. The promoted JVM-pool contract already assigns admission identity to current runtime evidence and restart ownership to the pool host.
