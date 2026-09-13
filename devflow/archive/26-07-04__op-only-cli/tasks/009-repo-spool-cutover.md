# Task 9: Repo spool and config cutover off the op prefix

**Document ID:** `TASK-Ooc-009`

## TASK-Ooc-009.P1 Scope

Type: AFK

Move every in-repo consumer of the old CLI surface to the new one: local spools, `.skein` config, and repo scripts. After this task nothing in the repo invokes `strand op <name>`, the removed builtins, or `strand init`/`strand weaver *`.

## TASK-Ooc-009.P2 Must implement exactly

- **TASK-Ooc-009.MI1:** Sweep: `grep -rn 'strand op \|strand init\|strand weaver ' --include='*.clj' --include='*.md' --include='*.sh' --include='*.nu' --include='*.yml'` (and any other hit-bearing extensions) across `spools/`, `.skein/`, `scripts/`, `Makefile`, excluding `devflow/archive/` and `devflow/feat/` history. Update every live invocation/emission: `strand op <name>` → `strand <name>`; `strand weaver *` → `mill weaver *`; `strand init` → `mill init`. Old builtin data-command examples (`strand add ...`) keep working as batteries ops — verify flag shapes against `spools/batteries.md` and fix any that used removed flags (`--attr-file`, `--attr-stdin`, `--attributes-stdin` → payload-flag forms).
- **TASK-Ooc-009.MI2:** Spool code that _emits_ CLI guidance strings (the agents spool `about` manual, shuttle worker-contract preambles, treadle instructions, chime rule bodies, workflow/devflow-conventions text, backlog helper output) — update emitted text, not just call sites. The external devflow.spool is task 11; do not touch `.skein/spools.edn` pins here.
- **TASK-Ooc-009.MI3:** `.skein/init.clj`: activate `skein.spools.batteries` alongside the existing spools so the repo's own coordination world has the shipped surface at next weaver restart. Do **not** reload or restart the canonical weaver (plan R1); the change is picked up whenever the user next restarts it.
- **TASK-Ooc-009.MI4:** Spool tests asserting on emitted guidance strings updated accordingly.

## TASK-Ooc-009.P3 Done when

- **TASK-Ooc-009.DW1:** The MI1 sweep returns zero live hits (archive/feature-history excluded).
- **TASK-Ooc-009.DW2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` and `(cd cli && go test ./...)` green.

## TASK-Ooc-009.P4 Out of scope

- **TASK-Ooc-009.OS1:** CLAUDE.md, `.agents/skills/strand/SKILL.md`, `docs/`, smoke suite (task 10); external devflow.spool (task 11).

## TASK-Ooc-009.P5 References

- **TASK-Ooc-009.REF1:** plan A6/PH5/R1; `spools/shuttle/`, `spools/agents/`, `spools/chime/`, `spools/backlog/`, `.skein/`; `docs/skein.md` (read before changing `.skein` config).
