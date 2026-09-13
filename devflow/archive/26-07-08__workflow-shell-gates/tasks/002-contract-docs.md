# Task 002: Contract docs, indexes, generated api, delta merge

**Document ID:** `TASK-ShellGates-002` **Feature:** `workflow-shell-gates` (branch `workflow-shell-gates`, worktree `/Users/ct/dev/projects/skein-src__workflow-shell-gates`) **Plan:** `PLAN-ShellGates-001` — this task is `PLAN-ShellGates-001.PH2`. **Blocked by:** Task 001 (reed.clj must exist and be green).

## P1 Scope

Type: AFK

Ship reed's contract-doc triad and wire it into every index, then generate its api doc and merge the alpha-surface delta. No code behaviour changes here — this task documents the spool Task 001 shipped and registers it in the doc/spec indexes so `make docs-check` stays green (`PLAN-ShellGates-001.PH2`, `.AA2`– `.AA7`). Human-facing prose must read plainly: sweep `reed.md`/`reed.cookbook.md` against the checklist at `.claude/skills/docs-style/SKILL.md` (in this worktree) before committing; the generated `reed.api.md` is exempt.

## P2 Must implement exactly

- **MI1 — `spools/reed.md` (new, `PLAN-ShellGates-001.AA2`).** Hand-authored contract doc in the style of `spools/shuttle/treadle.md`: overview; loading; the `shell/*` gate-attribute table (from `PLAN-ShellGates-001.A4` — argv / cwd / timeout-secs inputs, exit-code / output / error recorded); pass / fail / recovery / attention semantics (`A4`–`A7`); a worked example; a see-also. Must match the behaviour Task 001 actually shipped — read `reed.clj` before writing.
- **MI2 — `spools/reed.cookbook.md` (new, `PLAN-ShellGates-001.AA3`).** Worked composition recipes: (a) a `test -s` artifact gate; (b) an explicit `["sh" "-c" …]` multi-file check; (c) the `:subagent`→`:shell` composition (a `:shell` gate `:depends-on` a subagent gate — the `NG1`/option-B case expressed as composition); (d) recovering a stalled `shell/error` gate.
- **MI3 — register reed in the api-doc generator (`PLAN-ShellGates-001.AA4`).** Add a `reed` entry to the `spool-docs` vector in `scripts/generate_api_docs.clj`: `{:name "reed" :source "spools/src/skein/spools/reed.clj" :outfile "spools/reed.api.md"}` (the generator enumerates spools explicitly — a new spool is invisible to `make api-docs` until listed). Place it beside the other classpath spools (`carder`/`roster`/`loom`).
- **MI4 — generate `spools/reed.api.md` (`PLAN-ShellGates-001.AA4`).** Run `make api-docs` to produce it from `reed.clj` docstrings. Never hand-edit the generated file.
- **MI5 — `spools/workflow.md` §3 + §9 note (`PLAN-ShellGates-001.AA5`).** In §3 (Gates), beside the existing treadle `:subagent` note (the "A shipped local-root adapter, `skein.spools.treadle`…" paragraph), add a parallel note that a shipped **classpath** `:shell` executor (`reed`) fulfils `:shell` gates by running the gate's command and closes them with `complete!`; see `reed.md`. In §9 (See also), add a `skein.spools.reed` entry beside the treadle see-also line. §4 executor-registry text is unchanged.
- **MI6 — `spools/README.md` row (`PLAN-ShellGates-001.AA6`).** Add a `skein.spools.reed` row to the **classpath** Index table (with `loom`), linking `reed.md`, `reed.api.md`, `reed.cookbook.md`, with a one-line description. Do **not** add it to the approved-local-root table.
- **MI7 — merge `DELTA-ShellGates-001` into `devflow/specs/alpha-surface.md` (`PLAN-ShellGates-001.AA7`, `DELTA-ShellGates-001.D1`/`.D4`).** Add `reed` to the `SPEC-005.C3` classpath-shipped in-contract spool list (alongside `batteries`/`workflow`/`carder`/`roster`), matching how the other classpath spools are listed. Do **not** touch `SPEC-005.C4` (approved-local-root) — reed is classpath, not local-root (`DELTA-ShellGates-001.D2`). Then mark the delta file `devflow/feat/workflow-shell-gates/specs/alpha-surface.delta.md` **Status: Merged**. (The pre-existing `loom` omission from `SPEC-005.C3` is out of scope — `PLAN-ShellGates-001.P9`; add only `reed`.)

## P3 Done when

- **DW1** `spools/reed.md`, `spools/reed.cookbook.md`, and generated `spools/reed.api.md` exist; `scripts/generate_api_docs.clj` lists `reed`; `spools/workflow.md`, `spools/README.md`, and `devflow/specs/alpha-surface.md` carry the reed entries; the delta file is marked Merged.
- **DW2** docs gates green:
    ```sh
    cd /Users/ct/dev/projects/skein-src__workflow-shell-gates
    make docs-check       # regenerates spools/*.api.md, fails on drift, builds the site
    make fmt-check && make lint && make reflect-check
    ```
    `make docs-check` must pass with no drift — re-run `make api-docs` if `reed.md` docstrings changed.
- **DW3** `git status --short` shows no generated SQLite/runtime artifacts.
- **DW4** one atomic commit on `workflow-shell-gates` with the docs, the generator entry, the generated api doc, the index/spec edits, and the delta status flip. Update this task's `status` to `complete` in `tasks/index.yml` in the same commit.

## P4 Out of scope

- **OS1** Any change to `reed.clj` / `reed_test.clj` behaviour (Task 001). If a docstring edit is needed for a clearer `reed.api.md`, that is allowed, but no logic change.
- **OS2** `.skein/init.clj` activation and `CLAUDE.md`/`AGENTS.md` spool-list entries — Task 003.
- **OS3** Editing the four root behaviour specs (`SPEC-001`..`SPEC-004`): this feature is purely additive to the contract **index** only (`PLAN-ShellGates-001.CM1`, `DELTA-ShellGates-001.D3`).

## P5 References

- `devflow/feat/workflow-shell-gates/workflow-shell-gates.plan.md` (`PLAN-ShellGates-001`) — `AA2`–`AA7` (affected docs/indexes), `A4` (the `shell/*` table to reproduce in `reed.md`), `A5`–`A7` (semantics), `P9` (root-spec-delta reasoning, the `loom`-omission note).
- `DELTA-ShellGates-001` (`devflow/feat/workflow-shell-gates/specs/alpha-surface.delta.md`) — `D1`/`D2`/`D4` for the exact `SPEC-005.C3` edit and merge discipline.
- `spools/shuttle/treadle.md` — the contract-doc style to mirror for `reed.md`.
- `spools/reed.clj` (from Task 001) — the shipped behaviour the docs must match.
- `scripts/generate_api_docs.clj` — the explicit `spool-docs` list to extend.
- `spools/workflow.md` §3 (the treadle `:subagent` note to parallel) and §9 (See also); `spools/README.md` classpath Index table; `devflow/specs/alpha-surface.md` `SPEC-005.C3`.

## Operational constraints (every task)

- NEVER start, stop, restart, or reload the canonical mill or weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`). This is docs; no runtime needed. Any live check uses a disposable `--workspace "$(mktemp -d)"`.
- Work only in the worktree. Commit atomically on `workflow-shell-gates`. Never `--no-verify`. Kill any stray process by PID only.
