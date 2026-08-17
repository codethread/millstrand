# Delta: daemon-runtime (views cut, b8vld)

Merged into `devflow/specs/daemon-runtime.md` when this feature ships. The view mechanism is removed whole; read-class registered ops and named queries are the read surface.

- **SPEC-004.C1:** remove "one in-memory read-only view registry" from the weaver-owned state list.
- **SPEC-004.C16:** remove the `skein.api.views.alpha` ownership sentence.
- **SPEC-004.C27:** remove "view registry operations" from the no-socket-operations list.
- Reload-state enumeration (registry list around line 210): remove views.
- **SPEC-004.C51:** remove `skein.api.views.alpha` from the blessed source-visible namespaces for trusted runtime transformation workflows.
- **SPEC-004.C56–C59:** delete outright (view registry naming, invocation contract, lifetime, and weaver operation names). IDs are retired, not renumbered.
- Scheduler framing (around line 307): reword "pull-based `wake-at` strand attributes plus views" to name named queries as the pull-based read surface.
