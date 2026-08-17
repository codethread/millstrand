# Add cli query command group

**Document ID:** `CDP-TASK-003`

## CDP-TASK-003.P1 Scope

Type: AFK

Add the public `strand query` Cobra command group with `list` and `explain <name>` subcommands, and make `query` / `pattern` group help symmetric and self-describing about the read/write application split.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH3`
- [CLI delta](../specs/cli.delta.md) `CDP-DELTA-001.CC1`–`CC6`, `CDP-DELTA-001.CC8`

## CDP-TASK-003.P2 Implementation notes

- In `cli/internal/command/command.go`, clone the `pattern` group shape:
    - `query list` — `Args: cobra.NoArgs`, calls socket operation `query-list` with `map[string]any{}`.
    - `query explain <name>` — `Args: cobra.ExactArgs(1)`, rejects blank names before transport as a usage error (matching `pattern explain`, CDP-DELTA-001.CC3), calls `query-explain` with `map[string]any{"query": args[0]}`.
- Help text (CDP-DELTA-001.CC6): the `query` group short/long help states queries are discovered here and applied with `list --query` / `ready --query` plus repeated `--param key=value`; the `pattern` group help states patterns are applied with `weave --pattern`. Keep wording short and parallel.
- Update the root help expectation list in `cli/internal/command/command_test.go` (the `"Available Commands:"` assertion) to include `query`.
- No changes to `list` / `ready` / `weave` invocation behavior or flags.

## CDP-TASK-003.P3 Done when

- Go tests beside the existing `pattern list` / `pattern explain` tests cover: `query list` sending `query-list` with empty args and emitting the weaver JSON result; `query explain <name>` sending `query-explain` with the name; usage failures for `query explain` with zero or extra args, blank name, and `query list extra`.
- Root and group help output includes the `query` group and the application-split wording for both groups.
- `(cd cli && go test ./...)` passes.

## CDP-TASK-003.P4 Validation

```sh
(cd cli && go test ./...)
```
