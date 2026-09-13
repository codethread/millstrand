# Task 3: System ops registrar, guild, text-search, Go deep-argv test

**Document ID:** `TASK-Lhc-003`

## TASK-Lhc-003.P1 Scope

Type: AFK

Adopt per-leaf classes for the built-in system ops (`help`, `about`, `prime` — one registrar block in `skein.core.weaver.help`, ownership transferred here after Task 1 closes), the guild surface, and the text-search spool; add the Go dispatcher test for deep argv forwarding. The in-repo workflow/chime/cron spools register **no** CLI ops (verified in review xogz3/6qg5z) and are out of scope; `.skein`-registered wrappers like `flow-await` belong to Task 4.

## TASK-Lhc-003.P2 Must implement exactly

- **TASK-Lhc-003.MI1:** `register-built-in-ops!` declares `help`/`about`/`prime` with `:hook-class :read` / `:deadline-class :standard` in their arg-spec leaves.
- **TASK-Lhc-003.MI2:** Guild's op-constructing API takes **explicit caller-supplied leaf classes** — the constructor signature/spec requires them (no guild-local defaults), tolerated-absent only until Task 5. Update every in-repo guild caller; the kanban.spool caller (`kanban.send.v1`) is Task 7's. State the new constructor contract in guild's docstrings.
- **TASK-Lhc-003.MI3:** Text-search op registrations declare leaf classes matching current behavior (search reads `:read :standard`); its hand-assembled entry constructor (`text_search.clj` region flagged in review e8zr7) carries node classes through without defaults.
- **TASK-Lhc-003.MI4:** Go test in `cli/` proving `strand <op> <verb> <verb> --help` and deep verb argv are forwarded verbatim (extends the existing `<op> --help` coverage); no dispatcher behavior change.

## TASK-Lhc-003.P3 Done when

- **TASK-Lhc-003.DW1:** Cold `clojure -M:test` green on the registrar, guild, and text-search test namespaces; `(cd cli && go test ./...)` green.
- **TASK-Lhc-003.DW2:** `make api-docs` when docstrings changed; `make fmt-check lint reflect-check docs-check` green; clean `git status --short`.

## TASK-Lhc-003.P4 Out of scope / ownership

- **TASK-Lhc-003.OS1:** Batteries (Tasks 1–2), `.skein/` and fixtures (Task 4), smoke (Task 1 owns it), enforcement (Task 5), workflow/chime/cron (no op surface).
- Owns: the `register-built-in-ops!` declaration block in `src/skein/core/weaver/help.clj` (post-Task 1), guild source + tests, `spools/text-search/` + tests, the new Go test file in `cli/`.

## TASK-Lhc-003.P5 References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH2b)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
