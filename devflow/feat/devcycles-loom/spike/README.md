# Spike: shape of devcycles.spool and its consumers

Illustrative only — nothing here is loaded by any world, and gates/instructions are
deliberately terse. The point is the seams from PROP-Dcl-001.S3:

- `devcycles.spool/` — the shared spool repo's shape: `fix` and `land` as exported unbound
  templates plus default-bound registrations, params for repo-varying values, gate commands
  as bindings data, defer points with devcycles-shipped `:call` targets, the tracker module with
  a real `:removed` branch, and attention rules as an owner contribution.
- `consumers/skein-src.init.clj` — this repo consuming the devcycles spool: scripts stay local and ride
  in as binding overrides; the `:validate` defer is re-bound by shadowing the registered
  name from the workspace layer; the `main-ci-watch` shim keeps old poured gates resolving.
- `consumers/agent-harness.init.clj` — a sibling consuming a subset: same definitions,
  `make quality` as the validation style, no devflow/tracker.

Two ways a consumer changes a style of work, both visible below:

1. Per-run: the devcycles spool binds a defer to an allowlist of several `:call` targets; the driving
   worker picks one with `workflow defer <run-id> <workflow> ...` at run time.
2. Per-world: a workspace module re-registers the exported template with its own
   `bind-defers` and declared `:overrides`, shadowing the devcycles spool's default registration.
