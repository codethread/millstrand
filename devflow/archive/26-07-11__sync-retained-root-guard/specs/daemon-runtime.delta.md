# Weaver Runtime delta for sync-retained-root-guard

**Document ID:** `DELTA-srr-dr-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`, amends the `.C43`/`.C44` sync-outcome area) **Feature:** [../proposal.md](../proposal.md) (`PROP-srr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Draft **Last Updated:** 2026-07-11

## DELTA-srr-dr-001.P1 Summary

`SPEC-004.C43`/`.C44` frame every per-spool root problem — missing, unreadable, or runtime-add failure — as a soft per-spool sync _outcome_ recorded in weaver memory, never an exception out of `sync!`. That framing has one blind spot: `add-libs` retains every runtime-added `:local/root` in the process-global tools.deps basis for the JVM lifetime and re-canonicalizes the whole retained universe on the next re-resolving add, so a session-retained root that is deleted from disk _and_ has left the approved allowlist poisons the next `add-libs` for an unrelated spool — the failure names the stale retained lib, not the spool being synced.

This delta adds one new durable failure mode: a once-per-sync retained-root preflight that fails the whole sync loudly (TEN-003) before any `add-libs` runs. It leaves the per-entry `:missing-root` outcome (`.C43`/`.C44`) unchanged for still-approved roots; the two paths are disjoint. No loader, classloader, config key, sync-state field, or healthy-path behavior changes.

## DELTA-srr-dr-001.P2 Contract changes

- **DELTA-srr-dr-001.CC1 (new — retained-root preflight):** `sync!` runs a once-per-sync preflight before any per-spool `add-libs`. It scans the session-retained runtime basis (`clojure.java.basis/current-basis` `:libs` — the process-global universe `add-libs` itself re-canonicalizes) and flags every entry that (a) carries a `:local/root`, (b) whose path no longer exists on disk, **and** (c) is not in the current approved allowlist (`spools.edn`/`spools.local.edn`, `SPEC-004.C42`/`.C43`). Maven-resolved deps carry no `:local/root` and are out of scope. A nil/empty basis (process not launched by the CLI) has no retained local roots and the preflight is a no-op.
- **DELTA-srr-dr-001.CC2 (new — loud whole-sync failure):** On finding one or more retained-but-deleted allowlist-orphan roots, the preflight throws an `ex-info` that aborts the whole sync before any per-spool result is produced — a genuinely new failure mode alongside `.C43`/`.C44`'s soft outcomes, because a poisoned retained root breaks the entire `add-libs` universe, not one entry. The message names, in stack-trace-free prose, that a session-retained spool root was deleted and why `sync!` cannot proceed. `ex-data` carries `:missing-roots` (a vector of `{:lib <retained lib symbol> :local/root <deleted path string>}`, one per orphan, so the operator sees all of them at once), `:remedy` (the allowed alternatives named not applied — `:stub-dir`, recreate a bare directory at each deleted path, effective until the next weaver restart clears the retained basis; and `:restart`, restart the weaver JVM to discard the retained basis), and `:retained-universe-source` (`clojure.java.basis/current-basis` `:libs`, so the diagnostic is self-describing). The exception propagates through `sync!` and, on the reload path, through `reload-config!` (`SPEC-004.C46`) exactly like any other startup-file failure — no reload-path change is needed.
- **DELTA-srr-dr-001.CC3 (reaffirmed unchanged):** The per-entry `:missing-root` outcome (`SPEC-004.C43`/`.C44`) is unchanged. A deleted root that is _still_ in the approved allowlist stays a soft per-spool sync outcome; the preflight fires only for retained roots that have _left_ the allowlist. Neither path absorbs the other. Healthy syncs — every retained root still present on disk — behave exactly as today.

## DELTA-srr-dr-001.P3 Design decisions

### DELTA-srr-dr-001.D1 Preflight over wrapping the tools.deps error

- **Decision:** Read the same basis `add-libs` reads and fail _before_ it, naming the real culprit, rather than catching and rewriting the tools.deps canonicalization error inside the per-entry sync.
- **Rationale:** A wrap would fire after `add-libs`, depend on parsing tools.deps' error shape (brittle across versions), and attribute the failure to whichever entry was mid-sync.
- **Rejected:** Catching `:runtime-add-failed` and rewriting it (`PROP-srr-001.Q1`).

### DELTA-srr-dr-001.D2 Scope to allowlist-orphans only

- **Decision:** The preflight aborts only for retained roots that are deleted **and** absent from the current allowlist; a still-approved deleted root is left entirely to the soft `:missing-root` path.
- **Rationale:** Widening to all missing retained roots would abort the whole sync in the common case where the deleted root is still approved and today completes softly, regressing healthy-path behavior. A residual window (a still-approved deleted root hit while an unrelated spool adds a new coordinate) remains; it closes fully only with the classloader redesign, which this feature defers.
- **Rejected:** Widening the predicate to all missing retained roots (`PROP-srr-001.Q5`).

### DELTA-srr-dr-001.D3 Report remedies, never apply them

- **Decision:** The preflight names `:stub-dir` and `:restart` and stops; it does not `mkdir` the deleted path, prune the retained basis, or restart the weaver.
- **Rationale:** Both auto-repairs mutate global/on-disk state on the operator's behalf without sign-off, and pruning the process-global basis is exactly the redesign this card defers (`PROP-srr-001.NG1`/`.NG4`). This delta reads `the-basis`; it never mutates it.
- **Rejected:** Auto-stub or `update-basis!` eviction (`PROP-srr-001.Q4`).

## DELTA-srr-dr-001.P4 Open questions

- **DELTA-srr-dr-001.Q1:** None. The alternatives were weighed and resolved at proposal authoring (`PROP-srr-001.Q1`–`.Q5`); the residual still-approved-deleted-root window (D2) is an accepted limitation closed only by the deferred classloader redesign.
