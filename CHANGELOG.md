# Changelog

## 0.5.2 - 2026-09-01

- Workspace-owned declarations can be defined across focused namespaces and selected through one owner-complete configuration module.
- Relative workspace classpath roots now resolve from the selected workspace, independent of the Weaver process working directory.

## 0.5.1 - 2026-09-01

- Millstrand now has one product version across Weaver, `mill`, `strand`, and the Homebrew package. Build revisions and transport protocol versions remain separate identities.
- `mill changelog` prints the changelog retained with the resolved Millstrand source.
- Weaver workspaces now resolve libraries through ordinary `deps.edn` and optional `deps.local.edn` files. Coordinate changes require a replacement Weaver generation; source and activation changes remain refreshable. See [the dependency migration guide](docs/spools/deps-migration.md).
- Weaver restart now probes the candidate generation before cutover and preserves the previous generation when admission fails.

## 0.5.0 - 2026-08-27

- Last release before the workspace `deps.edn` cutover. Earlier changes remain available in the Git history.
