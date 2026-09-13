# Task 4: Pickup-ladder guidance for reload-spool!

**Document ID:** `TASK-shr-004`

## TASK-shr-004.P1 Scope

Type: AFK

Correct the pickup-ladder rung in `CLAUDE.md` and `docs/skein.md` so it names `runtime/reload-spool!` as the reload rung for opt-in synced spools, replacing the ineffective bare `(require 'the.ns :reload)` for that case. Human-facing guidance prose — run it through the docs-style gate.

## TASK-shr-004.P2 Must implement exactly

- **TASK-shr-004.MI1:** In `CLAUDE.md`'s pickup ladder (the "already-loaded Clojure namespaces need a targeted `(require 'the.ns :reload)`" rung), name `runtime/reload-spool!` as the correct reload rung for opt-in _synced_ spools — a bare `require :reload` is classloader-blind to per-spool synced roots and reloads nothing useful for them (PROP-shr-001.P1). Keep the existing rungs (Go CLI → `make build`; config/startup → `runtime/reload!`; already-loaded base-classpath namespaces → `require :reload`; JVM → restart) intact; this narrows the already-loaded rung for the synced-spool case.
- **TASK-shr-004.MI2:** Apply the same correction to the corresponding pickup-ladder text in `docs/skein.md`.
- **TASK-shr-004.MI3:** Note (where the docs describe the two reload verbs) that `reload-spool!` reloads spool _code_ and `reload!` re-runs startup files to re-register — the complementary halves of a hot bump (`DELTA-shr-001.CC3`) — so the code-bump sequence is `reload-spool! coord` then a targeted re-`use!` (or a full `reload!` when the bump crosses config registrations).
- **TASK-shr-004.MI4:** Run the prose through the `docs-style` gate (strip LLM-writing tells; plain, warm, factual voice).

## TASK-shr-004.P3 Done when

- **TASK-shr-004.DW1:** `make docs-check` reports zero findings.
- **TASK-shr-004.DW2:** The `CLAUDE.md` and `docs/skein.md` pickup-ladder edits read cleanly through the `docs-style` gate.
- **TASK-shr-004.DW3:** `git status --short` shows only the intended `CLAUDE.md` and `docs/skein.md` changes (no generated SQLite/runtime artifacts).

## TASK-shr-004.P4 Out of scope

- **TASK-shr-004.OS1:** The DELTA-shr-001 → `devflow/specs/repl-api.md` merge — a land-time promotion step, not this implementation slice (`PLAN-shr-001.AA6`, `DELTA-shr-001.Q1`).
- **TASK-shr-004.OS2:** Any `alpha-surface.md` / `daemon-runtime.md` edit — confirmed unchanged beyond reaffirmation (`PLAN-shr-001.CM2`/`CM3`); add no spurious SPEC-005.C2 edit or daemon-runtime cross-reference.
- **TASK-shr-004.OS3:** `make api-docs` regeneration — done in Task 3 when the docstring lands.

## TASK-shr-004.P5 References

- **TASK-shr-004.REF1:** `PLAN-shr-001.PH4` (spec, guidance, api-docs), `PLAN-shr-001.AA6`/`AA7`, `PLAN-shr-001.CM2`/`CM3` — [../spool-hot-reload.plan.md](../spool-hot-reload.plan.md).
- **TASK-shr-004.REF2:** `PROP-shr-001.C6` (spec/doc deltas) and `PROP-shr-001.C4` (compose boundary) — [../proposal.md](../proposal.md).
- **TASK-shr-004.REF3:** `DELTA-shr-001.CC3`/`CC4` (the two verbs' division of labour) — [../specs/repl-api.delta.md](../specs/repl-api.delta.md).
- **TASK-shr-004.REF4:** Blocked by Task 3 (`TASK-shr-003`), which delivers the `runtime/reload-spool!` verb the guidance names.
