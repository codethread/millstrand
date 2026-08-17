# Task 3: skein.api.peers.alpha discovery of sibling weavers

**Document ID:** `TASK-Guild-003`

## TASK-Guild-003.P1 Scope

Type: AFK

Create the blessed `skein.api.peers.alpha` namespace with the discovery half of the peering contract: `(peers)` enumerates sibling weaver metadata under the mill state root and `(peer name-or-workspace)` resolves exactly one running weaver, per DELTA-DaemonRuntime-002.CC1/CC2/CC3 and DELTA-ReplApi-002.

## TASK-Guild-003.P2 Must implement exactly

- **TASK-Guild-003.MI1:** New `src/skein/api/peers/alpha.clj` (ns `skein.api.peers.alpha`) with an `ns` docstring (repo rule). Resolve the mill state root the same way mill does (`$XDG_STATE_HOME/skein`, falling back to `~/.local/state/skein`) and scan `weavers/*/weaver.edn` metadata — the Clojure-client artifact (SPEC-004.C11) — reusing `skein.core.weaver.metadata` read and staleness helpers rather than reimplementing them.
- **TASK-Guild-003.MI2:** `(peers)` returns a vector of data-first rows — at least friendly name, selected workspace, weaver id, protocol version, socket path, state dir, and a `:running?`/staleness determination (pid-alive per `skein.core.weaver.metadata/pid-alive?`). Malformed present metadata fails loudly (SPEC-004.C14); an absent/empty state root yields `[]`.
- **TASK-Guild-003.MI3:** `(peer name-or-workspace)` resolves one running peer: matches by friendly name, or by selected-workspace path when the argument names an existing directory. Fails loudly (`ex-info` with a domain-style `:code` and data) when no running match exists, when the match is stale, and when a name matches more than one running weaver — the ambiguity error must list every candidate with its workspace path.
- **TASK-Guild-003.MI4:** Tests in `test/skein/peers_test.clj` using fixture metadata files under a temp `XDG_STATE_HOME` (no live weavers): write `weaver.edn` files with the current process pid for "running" rows and an impossible pid for "stale" rows. Cover: empty root; one running peer listed and resolvable by name and by workspace path; stale peer listed as stale but not resolvable; duplicate-name ambiguity failure listing candidates; malformed metadata failing loudly.
- **TASK-Guild-003.MI5:** Wire `skein.peers-test` into `test/skein/test_runner.clj`: add it to both the `:require` list and the `run-tests` call (preserve the `skein.shuttle-test` ordering constraint noted in that file's comment).

## TASK-Guild-003.P3 Done when

- **TASK-Guild-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes, including the new namespace.
- **TASK-Guild-003.DW2:** `git status --short` shows no runtime artifacts after the test run.

## TASK-Guild-003.P4 Out of scope

- **TASK-Guild-003.OS1:** Socket invocation (`call!`, task 4).
- **TASK-Guild-003.OS2:** Any mill/Go changes; auto-starting peers (PROP-Guild-001.NG3).

## TASK-Guild-003.P5 References

- **TASK-Guild-003.REF1:** [daemon-runtime delta](../specs/daemon-runtime.delta.md) CC1–CC3, [repl-api delta](../specs/repl-api.delta.md).
- **TASK-Guild-003.REF2:** `src/skein/core/weaver/metadata.clj` (`read-metadata`, `stale-or-missing?`, `pid-alive?`, file naming); SPEC-004.C9a/C9b for state-root layout, SPEC-004.C11 for the `weaver.edn`/`weaver.json` artifact split.
- **TASK-Guild-003.REF3:** Test isolation rules: PLAN-Guild-001.TC2 and the CLAUDE.md agent quick reference.
