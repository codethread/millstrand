# Brief: notes-writer task tier

Kanban card: `7rxko` (claimed, branch `notes-writer-task-tier`). The card body is the authoritative design record — it was refined in a 2026-07-10 design review with the user and carries the settled decisions, verified line inventories, and narrowed open questions.

## The user's ask

A 2000-word review dump landed on a kanban card's notes stream. Fix the structural causes, not the symptom:

1. **Notes-writer value in `skein.api.notes.alpha`** (keystone): emitting spools take a writer instead of choosing note targets themselves. Data-first design (settled): a writer is `{target-or-thunk, decoration, by}` with `write!` (per-call decoration merge), a serialized `writer-ref`, and `writer-ref->prompt` — the one renderer that absorbs the 7 hand-built "agent note <id>" prompt sites in delegation/agent-run. Explicitly NOT a ctx-dispatch strategy port (route fns can't render into prompts; silent fallback re-creates the original bug). CLI note ops need accretive decoration passthrough or writer-refs break at the process boundary.
2. **Kanban task tier**: epic > feature > task; task status DERIVED from pure graph + core attrs only (done⟸closed, blocked⟸unmet deps, doing⟸owner stamped, ready⟸deps done + unowned) — never stored, never reads delegation/agent-run vocab.
3. **Retire handover**: task activity notes + derived resume point subsume it. Keep the note-as-you-go discipline in worker contracts/prime/land text; drop the primitive. Cutover care: cards 3tgaj and 1x2zz currently rely on handover resume.
4. **Devflow purge from kanban surface**: replace devflow/agent-plan/delegation mentions with "execution strands" (litmus: delete devflow, kanban untouched).
5. **Glue guidance**: stage-keyed writer wiring lives in `.skein`/CLAUDE.md composition sites, not in kanban or devflow.

## Constraints

- `skein.api.notes.alpha` is blessed alpha: accretion only, `note!` signature untouched.
- New vocabulary (task-tier attrs, `note/kind`) declared in the F4 vocab registry.
- Done-when: cold focused tests green on touched namespaces; go tests + smoke if CLI surface changes; kanban.md + cookbook updated in-change; api-docs regen; fmt/lint/ reflect/docs-check clean; no kanban-namespace devflow mentions remain.

## Open (to resolve in proposal/spec)

- Task authoring surface: `kanban task add/list` subcommands vs bare `strand add` + declared attrs; how card/board views present the task lane.
- The stage-key set the glue wires writers for (names only; coordinates with the external devflow.spool pin).
