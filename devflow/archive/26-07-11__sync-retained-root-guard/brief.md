# Brief: sync-retained-root-guard — fail loudly when a retained spool root is deleted

Kanban: card `pn7wh` (p2). Extracted from `unify-spool-classpath` (card `nbeu8`, [brief](../26-07-11__unify-spool-classpath/brief.md)) as the mitigation half of its "own the spool classpath" concern: this card ships the loud, actionable failure now; the classloader redesign that removes the hazard stays with the refinement card.

## Problem

`sync!` (`runtime/alpha.clj:20` → `spool-sync/sync-approved-spools`) loads each approved local root by calling `clojure.repl.deps/add-libs` (`spool_sync.clj:462-472`). `add-libs` does not resolve the one new root in isolation: it reads the process-global runtime basis (`clojure.java.basis/current-basis`) and hands the _entire retained `:libs` universe_ to `clojure.tools.deps/resolve-added-libs` as `:existing` (`clojure/repl/deps.clj:41-53`), then merges the result back with `clojure.java.basis.impl/update-basis!`. tools.deps therefore retains every runtime-added `:local/root` for the JVM lifetime, and each subsequent `add-libs` re-canonicalizes all of them.

When a session-retained local root is deleted from disk, the _next_ `sync!` — for any spool, even an unrelated one — fails while re-canonicalizing the deleted path, and the error names the stale retained lib, not the spool actually being synced. Observed on the live canonical weaver: deleting the vendored `skein.spools/kanban` root bricked `reload!`; the new `codethread/kanban` sync failed with an error blaming the old `skein.spools/kanban` path. The remedy — recreate a stub directory at the deleted path so canonicalization succeeds until the next weaver restart clears the retained basis — had to be reverse-engineered from the stack trace.

The reason skein cannot already surface this: `reload!` → `reload-config!` → `clear-reload-state!` (`runtime.clj:262-271`) resets skein's own `:approved-spool-sync-state` atom to `{}`, but it does **not** touch tools.deps' `the-basis` (`basis/impl.clj:45-51`). The retained universe outlives skein's sync state, so the only place the deleted-root fact lives is `(:libs (basis/current-basis))`, which sync! never inspects before calling `add-libs`.

## Decision

`sync!` gains a preflight that, before any `add-libs` runs, scans the session-retained basis for `:local/root` libs whose path is missing from disk and fails loudly (TEN-003) naming:

1. the retained lib coordinate,
2. the deleted path, and
3. the remedy options — recreate a stub directory at the path (works until the next restart), or restart the weaver JVM to clear the retained basis.

This is a mitigation, not the cure. The root cause — that tools.deps re-canonicalizes the whole retained universe on every add — is owned by the separate "own the spool classpath" refinement card.

## Scope

1. A retained-root preflight in `spool-sync/sync-approved-spools` (once per sync, before the per-entry loop) reading `(:libs (clojure.java.basis/current-basis))`.
2. A loud `ex-info` whose message and data name the retained lib, the deleted path, and both remedies.
3. Cold focused tests on the touched namespace, extending `test/skein/runtime_deps_test.clj` (which already documents this retention hazard in `keep-add-libs-root!`).

## Deliberately not built

- **No classloader redesign.** The retained-universe re-canonicalization is the refinement card's; this card only detects and reports.
- **No behavior change for healthy roots.** A sync where every retained root still exists on disk runs exactly as today — no new failure, no new sync-state field.
- **No auto-repair.** The preflight does not create the stub directory or restart anything; TEN-002/TEN-003 — it names the failing value and the allowed remedies and stops.
- **No change to the per-entry `:missing-root` path** (`spool_sync.clj:454`), which guards the entry being synced; the preflight guards the retained universe, a disjoint concern.

## Acceptance

Deleting a session-retained spool root and re-running `sync!`/`reload!` fails with an `ex-info` naming the retained lib, the deleted path, and the stub-or-restart remedy — before the misleading tools.deps error can fire. Healthy syncs are unchanged. Cold focused tests on the touched namespace green; fmt/lint/reflect gates clean.
