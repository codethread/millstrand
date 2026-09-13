# Document framing and extend smoke

**Document ID:** `CDP-TASK-005`

## CDP-TASK-005.P1 Scope

Type: AFK

Document query discovery alongside pattern discovery, add the weave↔batch "one transactional engine, two doors" framing to user docs, and extend the smoke demo to exercise `query list` / `query explain` through the CLI subprocess path.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH5`, `CDP-PLAN-001.A5`
- [CLI delta](../specs/cli.delta.md) `CDP-DELTA-001.CC6`–`CC7`
- [Weaver runtime delta](../specs/daemon-runtime.delta.md) `CDP-DELTA-003.CC7`

## CDP-TASK-005.P2 Implementation notes

- Update `docs/skein.md` and `docs/getting-started.md` where named queries, patterns, `weave`, or batch mutation are described:
    - Show `query list` / `query explain <name>` beside `pattern list` / `pattern explain <name>` as the named-definition discovery pair, with application staying `list --query` / `ready --query` and `weave --pattern`.
    - Add the framing: `weave --pattern` is the CLI-safe, named, spec-checked, create-only front door over the same transactional batch engine as REPL-only `skein.batch.alpha/apply!`, which can also update, burn, and upsert edges at the trusted tier.
- Extend `dev/skein/smoke.clj`: in the existing disposable-world CLI section, register a parameterized named query (via the existing trusted config or `weaver repl --stdin` path the smoke already uses), then run `strand query list` and `strand query explain <name>` as CLI subprocess commands and assert on the returned JSON fields (`name`, `params`, `referenced-params`, `summary`).
- Check `CLAUDE.md` and root `README.md` after the docs change for any stale command-surface statements and sync them if needed.

## CDP-TASK-005.P3 Done when

- Docs present query and pattern discovery symmetrically and contain the weave↔batch framing.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes, including the new query introspection steps, and cleans up its generated state.
- `git status --short` shows no generated SQLite or runtime metadata artifacts after the smoke run.

## CDP-TASK-005.P4 Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
git status --short
```
