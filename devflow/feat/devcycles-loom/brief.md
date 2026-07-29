# Brief: devcycles.loom — lift the shared dev-cycle composition out of `.skein` (pdfmr)

The user's request (2026-07-29): this repo's `.skein` config is where the workflow, kanban,
and devflow spools are composed into a working development cycle, but that composition is
trapped as skein-src-local config. Lift everything shareable into a new sibling spool
repository, `devcycles.loom`, then import it back into skein-src and into the other sibling
spool repos — including devcycles.loom itself where possible.

## Explicit user constraints

- **`.skein/scripts/` and the dash TUI stay in skein-src for now.** Everything else that is
  shareable gets shared.
- **Workflows must work in different consumer contexts.** The lifted definitions may need to
  lean on more `defer` calls — runtime-selected returning procedures — so each consumer
  world plugs in its own style of work (repo-specific merge/CI steps, review rosters, card
  handling) instead of the definitions baking in skein-src-specific gates and shell scripts.

## Current shape (what composes what)

`.skein/init.clj` stages the module graph; the shareable weight sits in:

- `workflows.clj` (~1800 lines): `land` (+ the `land` policy op and merge lock), `story`,
  `explore`, `fix`, `spool-bump` definitions, and the `delegate-pipeline` weave pattern.
- `kanban_tracker.clj`: the devflow↔kanban tracker binding.
- `attention.clj`: chime attention rules over workflow/agent-run/gate/kanban vocabulary.
- `config.clj`: named queries plus the validation helpers workflows.clj reuses.
- The `init.clj` ordering knowledge for all of the above (a reusable activation recipe).

Likely staying as repo policy (proposal confirms the cut): `harnesses.clj` seats,
`reviewers.clj` rosters, `nvd_scan.clj`, `module_adapters.clj`, `init.local.clj`, scripts.

## Success shape

- A published sibling spool repo (per `docs/spools/writing-shared-spools.md`: family entry,
  roots, `v<int>` markers) holding the shared composition.
- skein-src's `.skein` consumes it; what remains locally is thin repo policy.
- Sibling spool repos (devflow.spool, kanban.spool, agent-harness.spool, …) can activate the
  same dev cycle in their own `.skein` worlds.
- devcycles.loom dogfoods itself where possible.

## Open questions for scoping

- The exact seam between shared definitions and per-repo policy: which steps become defers,
  which become params, which become executor/registry lookups. `land`'s gates reference
  skein-src's `scripts/` — those scripts stay here, so the shared `land` must reach them
  through a consumer-supplied seam.
- Naming and coordinates: `.loom` suffix vs the existing `.spool` convention; family key and
  root layout.
- Release/pinning: devcycles.loom sits above codethread/devflow, codethread/kanban, and
  ct.spools/* — floors, and who pins what.
- How a sibling repo bootstraps a `.skein` world that consumes devcycles.loom.
- Relation to refinement card fjsff (cross-repo change protocol): adjacent, not blocking.
