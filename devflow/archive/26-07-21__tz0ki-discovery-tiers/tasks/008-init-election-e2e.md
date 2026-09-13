# Task 8: init.clj transform election + discovery E2E

**Document ID:** `TASK-Dtf-008`

## TASK-Dtf-008.P1 Scope

Type: AFK

Elect the batteries reference transformer in the canonical world's trusted config and prove the full discovery surface end-to-end in a disposable workspace. Touches `.skein/init.clj`.

## TASK-Dtf-008.P2 Must implement exactly

- **TASK-Dtf-008.MI1:** `.skein/init.clj` registers the batteries reference transformer via `register-default-help-transform` after capturing the runtime, and registers any repo-owned glossary outcomes in load order (before referencing ops). Config-election only — never from `install!`. Per DELTA-Dtf-002.CC1/CC7 / PLAN-Dtf-001.PH5c/AA7.
- **TASK-Dtf-008.MI2:** Smoke the config change in a **disposable** `--workspace` world first (guard `${ws:?}`), never the canonical `.skein`.
- **TASK-Dtf-008.MI3:** A disposable-world end-to-end check exercises `strand help`, `help <op>`, `help <op> <verb>`, `about <op>`, `prime <op>` for batteries under both the elected transform and `--json` raw.

## TASK-Dtf-008.P3 Done when

- **TASK-Dtf-008.DW1:** The disposable-world E2E passes for batteries discovery (elected + `--json`).
- **TASK-Dtf-008.DW2:** `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check` green; `git status --short` shows no runtime/SQLite artifacts.

## TASK-Dtf-008.P4 Out of scope

- **TASK-Dtf-008.OS1:** The `agent` family (Tasks 9–11); do not restart the canonical weaver to pick up config (use `runtime/reload!` in a disposable world).

## TASK-Dtf-008.P5 References

- **TASK-Dtf-008.REF1:** DELTA-Dtf-002.CC1/CC7; PLAN-Dtf-001.PH5c; `.skein/init.clj` header.
