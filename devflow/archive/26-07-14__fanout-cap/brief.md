# Brief: cap concurrent runs in delegation fan-outs

**Card:** 0bxfh · **Requested:** 2026-07-14

## User ask

Large roster fan-outs need a concurrency cap. The cap should be a smart default with user config overrides and runtime overrides by agents — e.g. 4 by default, user config might allow 6, and the agent can limit to 2 at runtime if it chooses. Precedence: config replaces the default as the workspace ceiling; a runtime override can only tighten below the effective ceiling, never exceed it.

## Incident that motivated it

On 2026-07-14 a coordinator fanned out three change-review rosters at once — 18 reviewer runs plus 3 synthesizers. Every run started immediately, and the workspace write queue stalled past `skein.core.db`'s 5-second busy timeout: two run-finalization writes failed `[SQLITE_BUSY]` (runs `tljyb`/`xdj2y`) even though both worker processes had completed their reviews and written their findings. The runs had to be superseded and retried.

## Ownership finding (from source, recorded as card note)

Today neither spool throttles. Delegation creates N unblocked runs (`spools/delegation/src/skein/spools/delegation.clj:621`); agent-run's `scan!` starts every ready pending run on an unbounded cached thread pool (`spools/agent-run/src/skein/spools/agent_run.clj:1703-1711`).

Direction for the proposal: **agent-run enforces, delegation expresses intent.** The pending→running transition and the in-flight registry already live at agent-run's `claim!` seam, run completion already re-fires `scan!` via `on-event` (deferred pendings wake for free), and agent-run is the only layer that sees all run sources (rosters, councils, `delegate --ready`, raw spawns, retries). Delegation verbs surface the runtime override (e.g. `--max-concurrent`) by stamping a fan-out group and cap on the runs they create; agent-run enforces `min(workspace ceiling, group cap)`.

## Open questions for proposal

- Do interactive/hitl sessions consume window slots? (They are long-lived; likely exempt.)
- Scope of the runtime override: roster review only, or all multi-run delegation verbs (council, delegate --ready)?
- Where the workspace ceiling lives in trusted config, and its spec coverage.

## Constraints

- Shipped-behavior change: root spec update required (SPEC-003.C19 tier discipline; the agent-run/delegation contracts live in devflow/specs and the spool contract docs).
- The scheduler contract ("depends-on readiness is the only scheduler") should survive: the cap is back-pressure at run start, not new graph semantics.
