# Brief: JVM pool restart admission

Moving a previously restarted isolated workspace into a live JVM pool can fail after a successful probe and cutover. The new pool host publishes valid member metadata, but Mill reads that metadata through the ordinary status projection. A retained isolated `restart.json` in `running` state then replaces the new member generation with the old isolated generation. Grouped admission rejects the mismatch and terminates the otherwise healthy host. A second start succeeds because failure handling replaces the old record.

Fix the identity boundary rather than adding a retry. Pool admission and discovery must prove the runtime metadata, ready marker, endpoint identity, and spawned PID without lifecycle-history decoration. Historical restart state must not alter runtime identity.

The shipped contract also assigns one restart record to the pool host, while the implementation writes one `restart.json` per member. Bring persistence and status projection back to the host boundary so isolated and pooled lifecycle records cannot collide.

## Required behavior

- A stopped isolated workspace with a retained successful restart record can join a pool on the first replacement attempt.
- Grouped admission and host discovery read undecorated runtime identity while retaining the ready-marker, metadata, endpoint, PID, and ordered-member checks.
- Pooled restart state is written once beneath the pool host directory. Host transition fields project consistently through admitted and pending selections, member runtime fields remain distinct, and pending status does not claim a running runtime.
- Probe failure keeps the old host serving and the newcomer pending. Post-cutover launch failure leaves no host or partial member set admitted and retains one inspectable host-level failure record.
- Successful pooled admission replaces obsolete host transition or failure state with the current running host record without deleting unrelated isolated history.
- Restart-record lookup follows hosting mode in both directions. Existing isolated records cannot alter pooled identity or status, and a pool-host record cannot become isolated member history after a workspace leaves the pool.

## Acceptance

Add a regression that successfully restarts an isolated workspace, stops it, registers it as a pending pool member, and admits it through the first collective restart. Focused tests must also prove that Mill rediscovery uses current runtime evidence, a rejected probe preserves the old host and pending newcomer, a failed post-cutover launch admits nothing, admitted and pending status preserve their distinct semantics, and all pooled diagnostics come from one host-scoped restart record. Run the JVM-pool integration acceptance and the repository's Go, documentation, formatting, lint, and reflection gates.
