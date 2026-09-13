# Weaver Runtime delta for fanout-cap

**Document ID:** `DELTA-Foc-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (SPEC-004) **Feature:** [../proposal.md](../proposal.md) (PROP-Foc-001) **Status:** Reviewed **Last Updated:** 2026-07-14

## DELTA-Foc-001.P1 Summary

This delta names one new piece of trusted-config runtime spool state — agent-run's fan-out concurrency ceiling, set through `set-fanout-ceiling!` — as a second instance of the existing SPEC-004.C95 pattern. It is the feature's only root-spec touch: it edits the SPEC-004.C95 enumeration of runtime-owned spool state and changes nothing else in the weaver runtime contract. The concurrency window, the delegation flag, and the `agent-run/fanout-group`/`fanout-cap` attributes are userland spool behavior contracted in the `spools/agent-run` and `spools/delegation` READMEs (SPEC-005.C4), not root-spec surface.

## DELTA-Foc-001.P2 Contract changes — SPEC-004.P4 API boundary (C95)

- **DELTA-Foc-001.CC1:** SPEC-004.C95's list of runtime-owned spool state set by trusted config gains the agent-run fan-out concurrency ceiling: it is runtime state set through `skein.spools.agent-run/set-fanout-ceiling!`, defaulting to 4 when trusted config sets nothing, and invalid ceiling values (zero, negative, non-integer) fail loudly (TEN-003) — the same shape and fail-loud discipline the clause already records for `skein.spools.batteries/set-read-limit!`. This is an amendment to C95's existing prose; no new clause number is allocated.
- **DELTA-Foc-001.CC2:** The ceiling lives in agent-run's engine spool-state map, so adding it is a spool-state shape change governed by the versioned-reuse semantics C95 already defines: agent-run's engine `state-version` bumps, so a preserved pre-feature state map missing the ceiling key is reinitialized through `migrate-state` rather than silently reused (the exact hazard C95 exists to prevent). No new runtime contract is introduced — the feature is an additional consumer of C95/C96 reload semantics, reaffirming them.

## DELTA-Foc-001.P3 Design decisions

### DELTA-Foc-001.D1 The ceiling is trusted-config spool state, not a `config.json` field

- **Decision:** The workspace concurrency ceiling is runtime-owned spool state set by trusted `init.clj` config through a spool setter (`set-fanout-ceiling!`), exactly like the batteries read cap — not a `config.json` alpha field.
- **Rationale:** `config.json` is the low-privilege shareable workspace-identity marker (SPEC-002.C2); a concurrency ceiling is trusted operational tuning, not portable identity, so it belongs on the trusted-config setter surface the customisation guide already documents for `set-read-limit!`/`set-preamble-extension!`/`set-default-review-contract!`.
- **Rejected:** A `config.json` field, an environment variable, or a durable per-workspace registry row — none carry the trusted-config privilege boundary or match the established setter precedent.

### DELTA-Foc-001.D2 No SPEC-002 (CLI Surface) delta

- **Decision:** The `--max-concurrent` runtime override on `agent review`, `agent council`, and `delegate --ready` adds no root-spec CLI contract; SPEC-002 is not amended.
- **Rationale:** `--max-concurrent` is a userland spool op flag. The `strand` dispatcher ships argv verbatim to the op (SPEC-002.C30) and per-command contracts for userland ops are explicitly not root-spec (SPEC-002.C43, SPEC-005.C4); the flag's contract lives in `spools/delegation/README.md`. This confirms PROP-Foc-001.C6's claim that no CLI-surface delta is required.
- **Rejected:** Documenting the flag in cli.md — it would duplicate a userland spool contract into the root CLI spec against SPEC-005.C4.

### DELTA-Foc-001.D3 No SPEC-004 scheduling-clause delta

- **Decision:** The sliding concurrency window at agent-run's `claim!` seam changes no SPEC-004 scheduling clause; only C95's state enumeration is touched.
- **Rationale:** Agent-run's run-admission scheduling is userland engine behavior, not a root-spec contract (SPEC-005.C4); the window is back-pressure layered _after_ `weaver/ready` and preserves the readiness-is-the-only-scheduler contract the agent-run README owns (PROP-Foc-001.C4/NG3). The durable scheduler (SPEC-004.C97–C102a) is untouched — the window arms no timer, stores no queue, and is derived live from the in-flight count each scan.
- **Rejected:** Adding a scheduling clause to SPEC-004 — it would pull userland engine mechanics into the root spec against the tier boundary.

## DELTA-Foc-001.P4 Open questions

- **DELTA-Foc-001.Q1:** None for contract scope. The setter's exact name, the in-flight entry shape that lets `claim!` count headless slots atomically, and the delegation flag wiring are implementation choices owned by the plan (PLAN-Foc-001), preserving the C95 pattern and the fail-loud validation above.
