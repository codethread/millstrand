# Task 1: Config-declared weaver name with local overlay and mill resolution

**Document ID:** `TASK-Guild-001`

## TASK-Guild-001.P1 Scope

Type: AFK

Extend the Go alpha config format with an optional portable weaver `"name"` and a machine-local `config.local.json` overlay, and make mill resolve the effective friendly name from them at weaver launch, per DELTA-Cli-002.CC1/CC2/CC4 and DELTA-DaemonRuntime-002.CC5/CC6.

## TASK-Guild-001.P2 Must implement exactly

- **TASK-Guild-001.MI1:** In `cli/internal/config/config.go`: add `"name"` to `allowedKeys` and a `Name string` field to `Config`. A present `"name"` must be a non-blank JSON string; blank or non-string values fail loudly with an actionable message. `configFormat` validation is unchanged.
- **TASK-Guild-001.MI2:** Support an optional `config.local.json` beside `config.json` as a shallow overlay of the optional keys (only `"name"` today; it must not carry `configFormat` — reject it loudly if present). Missing overlay contributes nothing; malformed present overlay (bad JSON, unknown keys, wrong types) fails loudly. Overlay `"name"` wins over `config.json` `"name"`.
- **TASK-Guild-001.MI3:** In `cli/cmd/mill/lifecycle.go` `friendlyName`: when `requested` is empty, resolve from the selected workspace's effective configured name before falling back to the workspace basename. Explicit `requested` (from `weaver start --name`) remains the top override.
- **TASK-Guild-001.MI4:** Go tests covering: name from `config.json` only; overlay override; explicit `--name` beating both; basename fallback when neither declares a name; blank/non-string name rejection; overlay carrying `configFormat` rejection. Follow existing test styles in `cli/internal/config/config_test.go` and `cli/mill_test.go`.

## TASK-Guild-001.P3 Done when

- **TASK-Guild-001.DW1:** `(cd cli && go test ./...)` passes.
- **TASK-Guild-001.DW2:** Starting a weaver for a workspace whose `config.json` declares `"name": "shop-fe"` (no `--name`) publishes `"name": "shop-fe"` in that weaver's `weaver.json` metadata — proven by a Go test at the mill/lifecycle level (integration test if the existing harness supports it, otherwise unit coverage of `friendlyName` + `weaverArgs` composition).

## TASK-Guild-001.P4 Out of scope

- **TASK-Guild-001.OS1:** Bootstrap `.gitignore` changes (task 2).
- **TASK-Guild-001.OS2:** Any Clojure-side code; root spec edits (deltas are already staged and merge at finish).

## TASK-Guild-001.P5 References

- **TASK-Guild-001.REF1:** [cli delta](../specs/cli.delta.md), [daemon-runtime delta](../specs/daemon-runtime.delta.md) CC5/CC6/D3.
- **TASK-Guild-001.REF2:** `cli/internal/config/config.go` (`allowedKeys`, `Config`, `Load`), `cli/cmd/mill/lifecycle.go` (`friendlyName`, `weaverArgs`).
