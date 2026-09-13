# Task 2: Bootstrap gitignore returns config.json to VCS

**Document ID:** `TASK-Guild-002`

## TASK-Guild-002.P1 Scope

Type: AFK

Fix the stale gitignore contract per DELTA-Cli-002.CC3: bootstrap-generated `.skein/.gitignore` stops ignoring `config.json` (a leftover from when it stored the machine-local source checkout path, pre-mill) and ignores `config.local.json` instead. Apply the same fix to this repo's own `.skein/.gitignore`.

## TASK-Guild-002.P2 Must implement exactly

- **TASK-Guild-002.MI1:** In the bootstrap workspace-file generation (`cli/internal/config/bootstrap.go`): remove `config.json` from the generated `.gitignore` content and add `config.local.json`. All other ignored entries (local overlays, `state/`, `data/`, `weaver.*`, SQLite artifacts) stay.
- **TASK-Guild-002.MI2:** Bootstrap still never overwrites existing user files, including existing `.gitignore` files — verify no code path rewrites an existing `.gitignore` and keep it that way.
- **TASK-Guild-002.MI3:** Update Go tests asserting generated `.gitignore` contents.
- **TASK-Guild-002.MI4:** Edit this repository's `.skein/.gitignore` in place: drop the `config.json` line, add `config.local.json`.

## TASK-Guild-002.P3 Done when

- **TASK-Guild-002.DW1:** `(cd cli && go test ./...)` passes.
- **TASK-Guild-002.DW2:** A fresh `strand init` workspace's `.gitignore` does not ignore `config.json` and does ignore `config.local.json` (asserted in a bootstrap test).
- **TASK-Guild-002.DW3:** `git check-ignore .skein/config.json` exits non-zero in this repo (config.json is trackable).

## TASK-Guild-002.P4 Out of scope

- **TASK-Guild-002.OS1:** Migrating other existing repos' generated `.gitignore` files (user-owned, TEN-000@1).
- **TASK-Guild-002.OS2:** The `"name"` config key itself (task 1).

## TASK-Guild-002.P5 References

- **TASK-Guild-002.REF1:** [cli delta](../specs/cli.delta.md) CC3/D1.
- **TASK-Guild-002.REF2:** `cli/internal/config/bootstrap.go`; root spec clause being amended at finish: SPEC-002.C14a in `devflow/specs/cli.md`.
