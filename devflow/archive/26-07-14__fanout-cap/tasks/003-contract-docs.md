# Task 3: Contract docs + api-docs regen (PH3)

**Document ID:** `TASK-Foc-003`

**Slice:** `PLAN-Foc-001.PH3` **Type:** AFK

**Depends on:** TASK-Foc-002

## TASK-Foc-003.P1 Scope

Type: AFK

Write the userland contracts that describe the PH1 window and the PH2 flag, and regenerate the generated `*.api.md` from the shipped docstrings. `spools/agent-run` and `spools/delegation` are userland reference spools contracted by their own READMEs (`SPEC-005.C4`, `PROP-Foc-001.C6`), so the behavioral contract lands in those READMEs, not a root spec. This is the last slice: PH1 and PH2 have already shipped the code and the primary docstrings; PH3 states the contract and runs `make api-docs`.

**Owned files:**

- `spools/agent-run/README.md`
- `spools/delegation/README.md`
- `spools/agent-run.api.md` (generated — never hand-edited)
- `spools/delegation.api.md` (generated — never hand-edited)

## TASK-Foc-003.P2 Must implement exactly

- **TASK-Foc-003.MI1 — agent-run scheduling section.** In `spools/agent-run/README.md`, amend the scheduling section (the "Readiness is the only scheduling primitive" region) to state the concurrency window as back-pressure at run start _layered over_ readiness — keeping readiness as the sole scheduler. Cover: the ceiling `W`, its default of **4**, the trusted-config setter `set-fanout-ceiling!`, the `min(W, K)` group semantics, and the interactive exemption (`PROP-Foc-001.C6`, `PLAN-Foc-001.AA6`).
- **TASK-Foc-003.MI2 — agent-run state-shape + attribute reference.** Note the `state-version` bump (3 → 4) in the state-shape paragraph, and add `set-fanout-ceiling!` and the `agent-run/fanout-group` / `agent-run/fanout-cap` attributes to the attribute reference (`PROP-Foc-001.C6`, `PLAN-Foc-001.AA6`).
- **TASK-Foc-003.MI3 — delegation README.** In `spools/delegation/README.md`, document `--max-concurrent` on `agent review`, `agent council`, and `delegate --ready`, and the per-fan-out group stamping (`agent-run/fanout-group` / `agent-run/fanout-cap`), in the sections that already describe those verbs (`PROP-Foc-001.C6`, `PLAN-Foc-001.AA7`).
- **TASK-Foc-003.MI4 — api-docs regen only.** Run `make api-docs` to regenerate `spools/agent-run.api.md` and `spools/delegation.api.md` from the docstrings PH1/PH2 shipped. The regen must be clean, showing only the expected diffs (`scan!`, `spawn-run!`, `set-fanout-ceiling!`, the changed delegation ops). Never hand-edit the `*.api.md` files. If the regen surfaces a missing or stale _public_ docstring (e.g. `set-fanout-ceiling!` / `fanout-ceiling` / a delegation op), a minimal docstring fix in the owning source file is permitted to produce an informative api.md — but no behavioral code change (`PLAN-Foc-001.AA8`, `TC7`).
- **TASK-Foc-003.MI5 — prose passes the docs-style gate.** Both READMEs are human-facing prose: keep the voice plain and factual so `make docs-check` holds at zero findings (`PLAN-Foc-001.V5`).

## TASK-Foc-003.P3 Done when

- **TASK-Foc-003.DW1:** `spools/agent-run/README.md` states the window (ceiling, default 4, `set-fanout-ceiling!`, `min(W, K)`, interactive exemption, `state-version` note) and lists the new attributes/setter; `spools/delegation/README.md` documents `--max-concurrent` and group stamping on the three verbs (`PROP-Foc-001.DW4`).
- **TASK-Foc-003.DW2:** Cold gate green: `make api-docs fmt-check lint reflect-check docs-check`, with `git status --short` showing only the expected `*.api.md` diff (plus the two README edits) and no generated SQLite/runtime artifacts.
- **TASK-Foc-003.DW3:** One atomic commit on `fanout-cap`, why-focused HEREDOC message. No push, no merge.

## TASK-Foc-003.P4 Out of scope

- **TASK-Foc-003.OS1:** Any behavioral code change in `agent_run.clj` or `delegation.clj` — Tasks 1 and 2 own them. PH3 touches source only for a minimal public docstring fix needed by `make api-docs`.
- **TASK-Foc-003.OS2:** The SPEC-004.C95 merge into `devflow/specs/daemon-runtime.md` — a land-time promotion step, not this slice (`PLAN-Foc-001.AA9`, `TC7`; `DELTA-Foc-001`).
- **TASK-Foc-003.OS3:** SPEC-002 / SPEC-004-scheduling / SPEC-005 deltas — none exist for this feature by design (`PLAN-Foc-001.CM2`–`CM4`).

## TASK-Foc-003.P5 References

- **TASK-Foc-003.REF1:** `PLAN-Foc-001.PH3`, `AA6`–`AA8`, `V5`, `TC7`.
- **TASK-Foc-003.REF2:** `PROP-Foc-001.C6` (named doc deltas: both READMEs + both `.api.md`, no SPEC-002 delta), `DW4`.
- **TASK-Foc-003.REF3:** `DELTA-Foc-001` (SPEC-004.C95 amendment — merged at land, not here); the `docs-style` skill for README prose.
