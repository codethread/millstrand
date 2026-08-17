# Notes-writer + kanban task tier Proposal

**Document ID:** `PROP-Nwt-001` **Last Updated:** 2026-07-10 **Related RFCs:** None (prior feature context: [`devflow/feat/note-primitive/`](../26-07-10__note-primitive/) — the `notes` relation and `note!` this builds on) **Related root specs:** [`devflow/specs/strand-model.md`](../../specs/strand-model.md) (SPEC-001.P5 Edges — the `notes` relation); [`devflow/specs/alpha-surface.md`](../../specs/alpha-surface.md) (SPEC-005.C2, the blessed `skein.api.*.alpha` tier)

## PROP-Nwt-001.P1 Problem

A 2000-word review dump landed on a kanban card's notes stream, burying the card's curated resume view under one reviewer's raw output. The dump is a symptom; the structural causes are what this feature addresses:

- **Emitters choose their own note targets.** Every emitting site — the kanban `note!` op, the delegation agent-note verb, the review and synthesis prompts — decides for itself which strand to annotate. There is no seam through which a composition site can redirect where notes land, so noise accretes wherever the emitter happened to point.
- **Review conflates the strand-under-review with the findings destination.** A review pass reads one strand and, lacking anywhere better, writes its findings back onto that same strand — so detail meant for a task lands on the feature card.
- **Handover is preemption-fragile.** Handover is a contract a worker must fulfil _before_ it dies; it therefore fails at exactly the moment it is needed most — preemption — leaving no resume point and pushing workers to pre-emptively dump context.
- **The kanban surface references devflow.** Kanban's own docstrings, `about`, and `prime` text name devflow/agent-plan/delegation, coupling an implementation-agnostic tracking surface to one swappable methodology and blurring where routing decisions belong.

## PROP-Nwt-001.P2 Goals

- **PROP-Nwt-001.G1 (keystone):** A notes _writer_ value in `skein.api.notes.alpha` — emitting spools take a writer instead of choosing note targets. Everything else hangs off this.
- **PROP-Nwt-001.G2:** A kanban task tier (epic > feature > task) whose task status is _derived_ from the strand graph, never stored.
- **PROP-Nwt-001.G3:** Retire the handover primitive; task activity notes plus a derived resume point subsume it, keeping the note-as-you-go _discipline_.
- **PROP-Nwt-001.G4:** Purge devflow/agent-plan/delegation references from the kanban surface so kanban stays implementation-agnostic.
- **PROP-Nwt-001.G5:** A glue wiring convention — stage-keyed writer wiring lives in the composition sites (this repo's `.skein/` config files and the root `CLAUDE.md` coordination guidance), not in kanban or devflow.

## PROP-Nwt-001.P3 Non-goals

- **PROP-Nwt-001.NG1:** _Not_ a ctx-dispatch strategy port. A route fn cannot be rendered into a prompt, and at the spawn boundary every design collapses to a concrete ref anyway; a ctx-schema case-fallback would silently re-land dumps on the card — the original bug, reintroduced by its own fix. This is a **settled decision** (2026-07-10 design review), not an open question.
- **PROP-Nwt-001.NG2:** No change to `note!`/`notes` signatures. `skein.api.notes.alpha` is blessed alpha (SPEC-005.C2): accretion only; the writer wraps the untouched low-level primitive.
- **PROP-Nwt-001.NG3:** No enforcement of routing. Routing is guidance, not a gate (TEN-002): a remote agent can still note any id it knows; the writer shapes prompts and centralizes the convention.
- **PROP-Nwt-001.NG4:** No historical note rewrite. Surfaces stop _projecting_ kanban/handover decoration; the immutable data stays as-is — no rewrite ceremony.
- **PROP-Nwt-001.NG5:** No structured-findings schema. This feature gives findings a _home_, not a shape.

## PROP-Nwt-001.P4 Proposed scope

- **PROP-Nwt-001.S1:** Treat writer _value_, serialized _ref_, and _prompt rendering_ as ONE shared surface beside `note!`/`notes`. A writer carries a target (or a thunk resolved at ref/call time, since the target may not exist at wiring time), a default decoration, and an author; a ref serializes it for shipping into subprocesses; a single renderer turns a ref into the "append notes with …" instruction fragment.
- **PROP-Nwt-001.S2:** CLI decoration passthrough on the note ops is **load-bearing**. Remote writers are LLMs driving the CLI, so `strand note` / `agent note` need an accretive decoration flag surface; without it writer-refs break at exactly the process boundary they exist for.
- **PROP-Nwt-001.S3:** A kanban task tier with four _derived_ statuses computed from the **pure graph + core attrs only**. The litmus: no reads of delegation or agent-run vocabulary. Derivation — `done` ⟸ closed; `blocked` ⟸ active with an unmet dep; `doing` ⟸ active, deps met, owner stamped; `ready` ⟸ active, deps met, unowned. The same `depends-on` edges that compute blocked/ready are the concurrency DAG.
- **PROP-Nwt-001.S4:** The handover primitive is retired, while the note-as-you-go discipline moves — with the same force — into worker contracts, kanban `prime`, and the land-workflow instruction text. The derived resume point (doing-task plus its latest note) replaces handover; the degenerate no-notes case still yields the task's body, deps, and lane position — strictly more than a missing handover.
- **PROP-Nwt-001.S5:** The kanban surface speaks only of **execution strands** — every devflow/agent-plan/delegation name is replaced. Litmus: delete devflow, kanban untouched.
- **PROP-Nwt-001.S6:** New vocabulary (task-tier attrs, `note/kind`, any new edge use) is declared in the F4 vocab registry; kanban's registration is the natural home.

The writer/ref contract detail and the `note/kind` value set are specified in [`specs/strand-model.delta.md`](./specs/strand-model.delta.md) (`DELTA-Nwt-001`), which also records why the CLI decoration passthrough and kanban task tier land in spool docs rather than the root specs.

## PROP-Nwt-001.P5 Open questions

Both questions were resolved in the spec/plan stage (2026-07-10, recorded in [`specs/strand-model.delta.md`](./specs/strand-model.delta.md) `DELTA-Nwt-001.J2`); the writer/ref contract detail and the `note/kind` value set are specified there too.

- **PROP-Nwt-001.Q1 (resolved):** Task authoring surface — kanban gains `task add`/ `task list` subcommands stamping declared attrs plus `parent-of`, with bare `strand add` still valid; the card view projects a tasks lane with derived statuses and board claimed/in_review lanes surface the doing-task title.
- **PROP-Nwt-001.Q2 (resolved):** The glue wires writers for `:implementation` and `:review` initially — a names-only enum, extensible in glue without spool changes.
