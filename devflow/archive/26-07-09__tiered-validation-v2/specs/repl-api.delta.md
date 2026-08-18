# REPL API delta for tiered-validation-v2

**Document ID:** `DELTA-Ttv-001`

**Root spec:** [repl-api.md](../../../specs/repl-api.md)

**Feature:** [../proposal.md](../proposal.md)

**Status:** Draft

**Last Updated:** 2026-07-09

## DELTA-Ttv-001.P1 Summary

Adds one author-side entry to the blessed `skein.test.alpha` vocabulary (SPEC-003.C28): `run-focused!`, a focused-test-run dispatcher an agent calls from the per-worktree warm test REPL. It reuses the cold runner's namespace validation so a warm focused run and a cold `clojure -M:test <ns...>` run accept and reject exactly the same namespace set. No other `skein.test.alpha` contract changes; the warm REPL's process machinery (socket server, port/PID files, idle self-termination) is dev/test tooling that stays off the blessed vocabulary, on the test classpath beside `skein.test-runner`.

## DELTA-Ttv-001.P2 Contract changes

- **DELTA-Ttv-001.CC1:** `skein.test.alpha` gains `(run-focused! namespaces)` in its durable vocabulary (SPEC-003.C28). It runs the named test namespaces in-process and returns the aggregate `clojure.test` summary without calling `System/exit`, so it is safe to call repeatedly inside a long-lived REPL. It validates the requested namespaces through the runner's single validation path (`skein.test-runner/validate-focused!`): an add-libs shard namespace or a namespace not declared in the runner's island sets fails loudly (TEN-003), identically to the cold focused entrypoint. It resolves the runner at call time (the runner lives on the test classpath, `skein.test.alpha` on the main classpath), so requiring `skein.test.alpha` outside a test JVM is unaffected.
- **DELTA-Ttv-001.CC2:** A warm focused run is never a validation gate. The Done-when gate for a slice is the cold focused run `clojure -M:test <ns...>`; `run-focused!` exists for sub-second iteration only. This is a workflow contract stated where agents read testing rules (AGENTS.md/CLAUDE.md and the agents spool policy prose), not a code invariant.

## DELTA-Ttv-001.P3 Design decisions

### DELTA-Ttv-001.D1 One validation path for warm and cold

- **Decision:** `run-focused!` delegates namespace validation and the in-process run to the same `skein.test-runner` core the cold `-main` focused mode uses; it never re-implements island membership or shard rejection.
- **Rationale:** PROP-Ttv-001.G6 requires warm and cold to reject identically so island placement stays the single source of truth (TEN-003, TEN-004). Sharing the code makes divergence impossible rather than merely discouraged.
- **Rejected:** A second, warm-only validator (would drift from the cold one and reintroduce two sources of truth for island membership).

### DELTA-Ttv-001.D2 The warm-loop plumbing is not blessed vocabulary

- **Decision:** Only `run-focused!` accretes into `skein.test.alpha`. The socket REPL server, the port/PID files, and the idle watchdog live on the test classpath (a `skein.test.warm` bootstrap, the `:test-repl` alias target), not in `skein.test.alpha`.
- **Rationale:** SPEC-003.C28 scopes `skein.test.alpha` to disposable weaver worlds and deterministic-clock controls; a process-lifecycle server is out of that scope and belongs beside `skein.test-runner`, which no root spec governs (TEN-004). The agent-facing entry an author actually calls is the only durable surface.
- **Rejected:** Promoting the warm server, its files, or the `:test-repl` alias into contract — dev tooling whose shape may change freely under TEN-000@1 (parallel to SPEC-005.C8 tooling internals).

## DELTA-Ttv-001.P4 Open questions

- **DELTA-Ttv-001.Q1 (resolved — keep the `skein.test.alpha` entry):** Considered moving `run-focused!` entirely test-side (with `skein.test.warm`) as ungoverned dev tooling. Kept in `skein.test.alpha` because the blessed name is the seam everything else points at: `scripts/test-warm` sends `skein.test.alpha/run-focused!` over the socket, and REPL users get one documented, stable entry while `skein.test-runner` (which it resolves at call time) stays test-internal and free to change shape. Moving it test-side would save one one-line public var at the cost of task contracts and tooling referencing an ungoverned internal directly — the wrong side of TEN-004's trade, since the surface already exists at its minimum (one function, no options map).
