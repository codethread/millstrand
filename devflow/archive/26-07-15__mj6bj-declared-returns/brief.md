# Brief: mj6bj-declared-returns

Feature card `mj6bj` under epic `3o7le` (Spool CLI consistency). Supersedes card `rre9j`, which scoped the question to the kanban spool — the convention is core-wide (rationale trail: card `1dw6d` notes).

## Problem

Ops are IO. Inputs already get declared arg-specs — parsed, loudly validated, rendered as docs by `strand help` — while outputs are hand-rolled maps whose only documentation (returns shapes in about manuals, api docs) is unverified prose.

## Deliverables

1. **Declared `:returns` shape on the op declaration**, symmetric with the arg-spec:
    - rendered by `strand help <op>`;
    - exercised by a CI test helper that validates each op's test outputs against it so drift fails loudly in CI;
    - NOT consulted on the live serving path.
2. **Runtime validation only at real spool->spool seams** — the agent-run result consumed by workflow gates is the worked example.
3. **A shared entity-projection constructor** for the canonical `{id,title,state,attributes}` shape so entity payloads are right by construction rather than validated after.

## Settled design constraints (do not relitigate)

- Symmetry with arg-specs is the design center: declaration lives beside the arg-spec, docs render from it, tests check it.
- No live-path output validation: the serving path stays untouched; drift is caught by the CI test helper against test outputs.
- Runtime output validation exists only at genuine spool->spool consumption seams.
- Shipped-behavior change: root spec updates required (namespace tiers are contractual, SPEC-003.C19; op declaration surface likely touches devflow/specs/cli.md and/or daemon-runtime.md — the proposal should name the exact spec deltas).

Design record: epic `3o7le` body; card `1dw6d` notes (synthesis note `ce3gj`, counsel review-dump notes `r7i1f`/`ch0kz` on task `8kd6l`).
