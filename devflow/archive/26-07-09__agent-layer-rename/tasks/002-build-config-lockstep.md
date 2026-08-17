# Task 2: Build-config lockstep (deps.edn + spools.edn + Makefile)

**Document ID:** `TASK-Alr-002` **Phase:** `PLAN-Alr-001.PH1` (a) **Harness:** build **Type:** AFK **Depends on:** TASK-Alr-001

## TASK-Alr-002.P1 Scope

Point every load-bearing build-config file at the post-move roots so the PH1 dir/ns moves land without a broken classpath (`PLAN-Alr-001.AA6`, `PROP-Alr-001.P5.H4/H6/D5`). This task edits config _text_ to its final value; the physical `git mv`s and `ns` rewrites the new paths point at land in Tasks 3–5. **PH1 is one compile-coupled serial unit:** the Clojure tree does not fully compile between this task and Task 5 — that is expected and by design. Do not run `clojure -M:test` as a gate here; its green comes back as each move lands.

**Owned files (disjoint — no other PH1 task edits these):**

- `deps.edn` — `:test` **and** `:reflect-check` `:extra-paths` (`spools/shuttle/src` → `spools/agent-run/src`; `spools/agents/src` → `spools/delegation/src`).
- `.skein/spools.edn` — spool keys/roots (`skein.spools/shuttle` → `skein.spools/agent-run` root `../spools/agent-run`; `skein.spools/agents` → `skein.spools/delegation`).
- `Makefile` — the `docs-check` pathspec widened to descend into `spools/executors/` (`PROP-Alr-001.P5.H6`), and the `dash` target reference pointed at `scripts/agent-dash`.

## TASK-Alr-002.P2 Must implement exactly

- **TASK-Alr-002.MI1:** In `deps.edn`, rewrite both the `:test` and the `:reflect-check` `:extra-paths` vectors: `spools/shuttle/src` → `spools/agent-run/src`, `spools/agents/src` → `spools/delegation/src`. Both aliases, or `make reflect-check` fails at PH1 exit (`PROP-Alr-001.P5.H4`). `reed`/`executors.shell` lives under `spools/src` and needs no extra-path change.
- **TASK-Alr-002.MI2:** In `.skein/spools.edn`, rename the two spool map entries: `skein.spools/shuttle {:local/root "../spools/shuttle"}` → `skein.spools/agent-run {:local/root "../spools/agent-run"}`, and `skein.spools/agents` → `skein.spools/delegation` with root `../spools/delegation`. Leave all other spool entries untouched.
- **TASK-Alr-002.MI3:** In the `Makefile`, widen the `docs-check` pathspec so it descends into the new nested `spools/executors/` doc outfiles (`PROP-Alr-001.P5.H6`), and repoint the `dash` target at `scripts/agent-dash`. Note: `make dash` will not _resolve_ until Task 16 renames the script dir — that is accepted; no gate runs `make dash` before Task 16.

## TASK-Alr-002.P3 Validation / Done when

- **TASK-Alr-002.DW1:** `deps.edn` and `.skein/spools.edn` are valid EDN; the `Makefile` parses (`make -n docs-check` / `make -n dash` resolve syntactically).
- **TASK-Alr-002.DW2:** `make build` (the Go CLI — independent of Clojure spool paths) is green.
- **TASK-Alr-002.DW3:** No `ns`/source/test/doc file is moved or renamed in this task; a `git diff` shows only the three config files.

## TASK-Alr-002.P4 Out of scope

- **TASK-Alr-002.OS1:** Any `git mv` of a spool dir/source, any `ns`/require rewrite (Tasks 3–5).
- **TASK-Alr-002.OS2:** `clojure -M:test` / `make reflect-check` as gates — the tree is mid-move; those are the PH1-exit gate on Task 5.
- **TASK-Alr-002.OS3:** `scripts/agent-dash` dir rename and its attr strings (Task 16).

## TASK-Alr-002.P5 Commit

- Atomic single commit over the three config files, devflow message, **no push**.

## TASK-Alr-002.P6 References

- **TASK-Alr-002.REF1:** `PLAN-Alr-001.PH1`, `PLAN-Alr-001.AA6`, `PROP-Alr-001.P5.H4/H6`, `PROP-Alr-001.D5`.
- **TASK-Alr-002.REF2:** brief "Spool dir moves" row; `deps.edn:11,44`; `.skein/spools.edn:1-2`.
