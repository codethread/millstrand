# Task 2: Delegation `--max-concurrent` flag + group stamping (PH2)

**Document ID:** `TASK-Foc-002`

**Slice:** `PLAN-Foc-001.PH2` **Type:** AFK

**Depends on:** TASK-Foc-001

## TASK-Foc-002.P1 Scope

Type: AFK

Add the per-fan-out intent surface that feeds the PH1 window: `--max-concurrent <K>` on the three multi-run verbs, each minting one shared `fanout-group` id and stamping `agent-run/fanout-group` / `agent-run/fanout-cap` on every run it creates. Delegation expresses intent only; it does not police concurrency — enforcement is PH1's window (`PROP-Foc-001.G2`, `C3`). This slice is serial after Task 1: the window that reads these attributes must already exist so the stamped runs are actually bounded.

**Owned files (disjoint):**

- `spools/delegation/src/skein/spools/delegation.clj`
- `test/skein/delegation_test.clj`

## TASK-Foc-002.P2 Must implement exactly

- **TASK-Foc-002.MI1 — `--max-concurrent` on the three arg-specs.** Add `--max-concurrent <K>` where each verb already parses flags: `op-review` (`delegation.clj:1786`), `op-council` (`:1812`), `op-delegate` (`:651`). Parse `K` as a positive integer; an absent flag stamps no cap (governed by `W` alone) (`PROP-Foc-001.C3`, `PLAN-Foc-001.A5`).
- **TASK-Foc-002.MI2 — mint one group per fan-out, stamp via `:attrs`.** Each verb mints one fresh `fanout-group` id shared by all runs of that fan-out and stamps `agent-run/fanout-group <id>` + `agent-run/fanout-cap <K>` through the existing `spawn-run!` `:attrs` slot (`agent_run.clj:1849`, merge at `:1901-1913`) — no new `spawn-run!` argument. Thread the group through each verb's single spawn seam:
    - `agent review` → `review!`'s `spawn-spec!` closure (`delegation.clj:1606`), the one call that spawns both reviewers and the synthesizer.
    - `agent council` → `council!` (`:1645`) / `panel!` (`:1435`) seat spawns.
    - `delegate --ready` → `delegate-task`'s `spawn-run!` (`:621`), one group across the classified batch. The synthesizer already `:depends-on` its reviewers (`delegation.clj:1629`), so carrying it in the same group is harmless and keeps group accounting whole (`PROP-Foc-001.C3`, `PLAN-Foc-001.A5`, `TC4`).
- **TASK-Foc-002.MI3 — no group on non-fan-out runs.** Raw `spawn`, single `delegate <task>`, and crash-recovery respawns carry no group — they are governed by the workspace ceiling `W` alone (`PROP-Foc-001.C3`, `D2`). Do not stamp a group where a single command creates a single run.
- **TASK-Foc-002.MI4 — headless-only, no new interactive path.** `delegate --ready` is already headless-only and rejects interactive flags (`delegation.clj:656-657`); the group runs are always headless, so the fan-out verbs never mint interactive runs into a group. Add no interactive branch (`PROP-Foc-001.D1`, `PLAN-Foc-001.TC4`).
- **TASK-Foc-002.MI5 — stamping tests (V2).** In `test/skein/delegation_test.clj`, assert each of `agent review` / `agent council` / `delegate --ready` parses `--max-concurrent` and that every spawned run of one invocation carries the _same_ `agent-run/fanout-group` and the requested `agent-run/fanout-cap`; assert raw `spawn` and single `delegate` carry no group. Where a case needs pre-stamped runs as fixtures, set them via the `spawn-run!` `:attrs` path (`PLAN-Foc-001.V2`, synthesis note `vo7pb` item 4).

## TASK-Foc-002.P3 Done when

- **TASK-Foc-002.DW1:** `agent review`, `agent council`, and `delegate --ready` accept `--max-concurrent` and stamp `agent-run/fanout-group` / `agent-run/fanout-cap` on every run they create; the synthesizer/seat runs carry the same group; raw `spawn` and single `delegate` carry none (`PROP-Foc-001.DW3`).
- **TASK-Foc-002.DW2:** Cold gate green: `clojure -M:test skein.delegation-test` (`skein.delegation-test` is a parallel namespace; a focused ns run is its valid gate). Warm output never satisfies this gate.
- **TASK-Foc-002.DW3:** `make fmt-check lint reflect-check` pass; `git status --short` shows no generated SQLite/runtime artifacts.
- **TASK-Foc-002.DW4:** One atomic commit on `fanout-cap`, why-focused HEREDOC message. No push, no merge.

## TASK-Foc-002.P4 Out of scope

- **TASK-Foc-002.OS1:** The window, ceiling setter, `state-version`, and any `agent_run.clj` change — Task 1 owns them (`PLAN-Foc-001.PH1`). PH2 only stamps attributes the PH1 window already enforces.
- **TASK-Foc-002.OS2:** README / `*.api.md` prose and api-docs regen — Task 3 owns them (`PLAN-Foc-001.PH3`). Ship informative op docstrings here, but do not run `make api-docs` or hand-edit `*.api.md`.
- **TASK-Foc-002.OS3:** Any SPEC-002 (CLI surface) edit — `--max-concurrent` is a userland spool op flag; the dispatcher ships argv verbatim and per-command userland contracts are not root-spec (`PLAN-Foc-001.CM2`, `PROP-Foc-001.C6`).
- **TASK-Foc-002.OS4:** Cross-group fairness or admission ordering — the window admits in weaver readiness order; a wide group first can fill the ceiling before a narrow one (`PLAN-Foc-001.Q1`, `PROP-Foc-001.Q1`).

## TASK-Foc-002.P5 References

- **TASK-Foc-002.REF1:** `PLAN-Foc-001.PH2`, `A5`, `A6`, `AA3`, `AA5`, `V2`, `TC4` (delegation seam anchors: `op-review`/`op-council`/`op-delegate` → `review!`/`spawn-spec!`, `council!`/`panel!`, `delegate-task`).
- **TASK-Foc-002.REF2:** `PROP-Foc-001.C3` (per-fan-out intent, the two attributes), `C4` (precedence `min(W, K)`), `D1`/`D2` (interactive exemption + scope), `DW3`.
- **TASK-Foc-002.REF3:** The landed Task 1 window that reads `agent-run/fanout-group` / `agent-run/fanout-cap` off ready runs; `spawn-run!` `:attrs` merge (`agent_run.clj:1849`).
