# Changelog

## Unreleased

- `mill weaver start --temp` starts a discoverable, disposable Weaver without remembered-start registration or restart history. Stop, process exit, failed startup, and graceful Mill shutdown remove its private runtime state and database while preserving workspace source and existing persistent data.

## 0.6.0 - 2026-10-02

- Breaking: removed `mill prime`. Batteries now supplies `strand primer`, with orientation and source/reference paths from the selected Weaver generation. Per-operation `strand prime` and `strand about` are unchanged.
- Trusted config can append to or replace the primer with `batteries/set-primer!`. Settings belong to one runtime; repeated config refresh does not duplicate appended text.
- Breaking: `mill init` no longer creates or edits agent instruction files. Stealth init no longer writes or excludes `CLAUDE.local.md`, supplies Codex suggestions, or returns `claude_guidance` and `codex_guidance` fields. Existing instruction files are untouched. See [workspace customisation](./docs/spools/customisation.md#customising-the-primer) for the new entry point and older exclude-block cleanup.

## 0.5.3 - 2026-09-14

- `mill` can manage opt-in named JVM pools, including host-scoped membership, status, restart admission, and continuity across Millstrand restarts.
- `mill` now starts a missing Weaver when forwarding a command and reports lifecycle and restart progress in a readable form with less polling overhead.
- Workspace modules can contribute prime advice that is composed through their selected owner configuration.
- Repository review workflows support path-scoped reviewer rosters and retain the provenance of scheduled reviewer selection.
- Socket requests now return structured error frames for every Clojure throwable instead of dropping the connection.
- The repository adds a runnable hello-world example, reusable release automation, external spool-consumer checks, and enforced Markdown and clj-kondo formatting gates.
- Agent delegation and review configuration now use the shared Harnesses and Millhouse workflow surfaces.

## 0.5.2 - 2026-09-01

- Workspace-owned declarations can be defined across focused namespaces and selected through one owner-complete configuration module.
- Relative workspace classpath roots now resolve from the selected workspace, independent of the Weaver process working directory.

## 0.5.1 - 2026-09-01

- Millstrand now has one product version across Weaver, `mill`, `strand`, and the Homebrew package. Build revisions and transport protocol versions remain separate identities.
- `mill changelog` prints the changelog retained with the resolved Millstrand source.
- Weaver workspaces now resolve libraries through ordinary `deps.edn` and optional `deps.local.edn` files. Coordinate changes require a replacement Weaver generation; source and activation changes remain refreshable. See [the dependency migration guide](https://github.com/codethread/millstrand/blob/251db0a7a9cdd1d00859140e8ae4c716af6771bc/docs/spools/deps-migration.md).
- Weaver restart now probes the candidate generation before cutover and preserves the previous generation when admission fails.

## 0.5.0 - 2026-08-27

- Last release before the workspace `deps.edn` cutover. Earlier changes remain available in the Git history.
