# Project Tenets delta for Go CLI Migration

**Document ID:** `TEN-D001` **Root document:** [TENETS.md](../../../TENETS.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-25

## TEN-D001.P1 Summary

Add a core boundary tenet for the daemon-core architecture: rich, Clojure-native extension and inspection belongs in the daemon/REPL/config layer; the scripted CLI stays simple, low-privilege, and JSON-only. This captures the intended split behind the Go CLI migration and daemon query registry work.

## TEN-D001.P2 Tenet changes

- **TEN-D001.CC1:** Append a new tenet after the current `TEN-005` as `TEN-006`:

    ```markdown
    - **TEN-006**: The CLI is a thin JSON control surface; the daemon/REPL is the rich semantic surface.
        - The scripted CLI should expose simple commands, string flags, JSON machine output, and named handles to daemon-owned behavior. It should not parse, author, or debug rich Clojure/EDN userland structures.
        - Complex query definitions, runtime customization, inspection, and debugging belong in the trusted daemon config and REPL workflows. The CLI can invoke those capabilities by stable names and simple JSON-shaped params.
        - The engine may translate between JSON wire data and Clojure-native/EDN data internally, but that translation is hidden behind daemon APIs.
    ```

- **TEN-D001.CC2:** Preserve existing tenet IDs during promotion. Do not renumber `TEN-005`; if another tenet claims `TEN-006` first, choose the next unused ID and update this delta before promotion.

## TEN-D001.P3 Design decisions

### TEN-D001.D1 Named access over rich CLI syntax

- **Decision:** The CLI invokes complex daemon behavior by stable names and simple params instead of accepting rich EDN definitions.
- **Rationale:** Agents and scripts need a reliable low-friction control surface. Rich query authoring and inspection are better served by the REPL, where Clojure-native data and debugging tools are available.
- **Rejected:** Making the Go CLI understand EDN query forms or duplicate REPL/debugging workflows.

## TEN-D001.P4 Open questions

- **TEN-D001.Q1:** None.
