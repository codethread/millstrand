# Weaver Runtime delta for agent-engine-primitives

**Document ID:** `SPEC-Aep-003` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Aep-001`) **Status:** Merged **Last Updated:** 2026-07-09

## SPEC-Aep-003.P1 Summary

A staleness correction, not an F2 behavioral change. F2's cutover docs (`PROP-Aep-001.C12.2`, `PLAN-Aep-001.S11/TC4`, `TASK-Aep-011`) depend on the rule that a normal world's live SQLite is the `database_path` under the weaver state directory, but `SPEC-004.C92` still says normal `:sqlite-file` worlds use the selected workspace's `data/skein.sqlite`. Observed behavior (docs-review pass aa4b5716, verified against a fresh `mill init` world): `mill weaver status --workspace <w>` reports `data_dir`/`database_path` under `~/.local/state/skein/weavers/<hash>/data/` — resolvable before any weaver starts — and the workspace directory holds config (`config.json`, `init.clj`, `spools.edn`), not the database (`cli/internal/config/config.go:62`). The gate-link conditional in `PROP-Aep-001.C11` remains not fired (`PLAN-Aep-001.CM4`); this delta touches only `C92`.

## SPEC-Aep-003.P2 Contract changes

- **SPEC-Aep-003.CC1** (edit, `SPEC-004.C92`): correct the storage-location sentence.

    Old:

    ```text
    - **SPEC-004.C92:** `:sqlite-file` is the default storage kind for normal weaver workspaces. It uses the selected workspace's `data/skein.sqlite` unless trusted runtime construction supplies another database file.
    ```

    New:

    ```text
    - **SPEC-004.C92:** `:sqlite-file` is the default storage kind for normal weaver workspaces. It uses `data/skein.sqlite` under the world's weaver state directory — the `data_dir`/`database_path` reported by `mill weaver status --workspace <w>`, resolvable before any weaver starts — unless trusted runtime construction supplies another database file. The selected workspace directory holds the world's config, not its database.
    ```
