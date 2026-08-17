# Weaver Runtime delta for agent-layer-rename

**Document ID:** `SPEC-Alr-003` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Alr-001`) **Rename table:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-09

## SPEC-Alr-003.P1 Summary

This is the F1 mechanical-rename delta (no behavior change). The weaver-runtime spec names the renamed surface in exactly one place: the `await-quiescent!` off-lane-completion example in `SPEC-004.C74b`, which lists `reed`'s subprocess and a `treadle` gate's `shuttle` run as completions the lane primitive does not observe. Both are noun renames; the described off-lane behavior is unchanged. All other `skein.spools.*` mentions in this spec (`batteries`, `roster`, `guild`) are unrenamed spools and stay as-is.

## SPEC-Alr-003.P2 Contract changes

- **SPEC-Alr-003.CC1** (edit, `SPEC-004.C74b`, line 163): rename the off-lane-completion examples inside the parenthetical. `reed` → `the shell executor`; `a treadle gate's shuttle run` → `a subagent-executor gate's agent run`. The `:shell` backend value and `chime` are unrenamed and stay. The "gate" concept persists; only the executor noun (`treadle` → subagent executor) and the run noun (`shuttle` run → `agent-run`'s agent run) move.

    Old fragment:

    ```text
    …observes no off-lane completion a handler may have started (reed's `:shell` subprocess on a private worker pool, chime's per-dispatch notifier daemon threads, a treadle gate's shuttle run reaching a terminal state), which keep their own completion signal layered on top…
    ```

    New fragment:

    ```text
    …observes no off-lane completion a handler may have started (the shell executor's `:shell` subprocess on a private worker pool, chime's per-dispatch notifier daemon threads, a subagent-executor gate's agent run reaching a terminal state), which keep their own completion signal layered on top…
    ```

## SPEC-Alr-003.P3 Flagged (out of scope for F1)

- **SPEC-Alr-003.F1:** None. `SPEC-004.C74b` is prose describing off-lane completion signals; renaming the executor and run nouns leaves the lane-only-settle contract intact. No other `SPEC-004` contract names the renamed surface (`C95`, `C39`, `C74a`, `C90` reference the unrenamed `batteries`/`roster`/`guild` spools).
