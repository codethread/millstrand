# Agent Contributor Guide

Read `./devflow/TENETS.md`, `./devflow/PHILOSOPHY.md`, and `./devflow/UBIQUITOUS-LANGUAGE.md` before all work. The first two govern what you may build; the third defines the words this repo uses, and other docs assume you know them. This file holds only what the live surface cannot tell you; everything else is routed by scenario below.

## What to read, when

**Arriving in this repo** — run `mill skein prime` once: where the source, docs, and config live. The deep reference is `docs/reference.md`; its "Discovery tiers" section explains the convention behind everything below (`prime` orients, `about` explains an op, `strand help [<op>]` answers exact invocation).

**Working with the user** — claim a kanban card first; `strand kanban prime` is the board discipline.

**Driving any registered workflow** — this repo activates the shipped worker surface in `.skein/init.clj`: `strand prime workflow` before you start, `strand about workflow` for the manual, `strand help workflow` for exact invocation.

**Starting multi-step work** — `mill strand prime`: the planning/tracking workflow. Feature work starts with the registered `intake` workflow; read `strand workflow show intake` for its parameter contract before starting the run. At proposal sign-off, `approved-to-cards` is the default route: the proposal lands on mainline, a decompose stage authors implementation cards, and the devflow run ends there — implementation is then worked card by card from the board. The older `approved` → spec-plan/AFK path stays registered but sidelined. Large module changes pour the registered story workflow; invocation and discipline come from `strand prime workflow` and `strand about workflow`.

**Delegating** — `strand prime agent` first; `strand about agent` is the full verb manual. Delegate real work as tracked agent runs, never harness-native subagents (recon-only). Seats and review rosters: `strand agent harnesses` / `strand agent rosters`, with routing policy beside the definitions in `.skein/harnesses.clj` / `.skein/reviewers.clj`.

**Waiting on or recovering runs** — `strand workflow await <run-id>` blocks until a run needs you (`strand help workflow` for the await cap); `strand ready --query work` is the default ready view; failures surface via `strand list --query agent-failures` and `strand agent logs <run-id> --tail 80`.

**Landing a finished branch** — coordinator-only: `strand workflow show land`, then use generic workflow verbs and `strand help land` for the policy boundaries. Required checks are strict here, so a merge gate failing with "N of N required status checks are expected" usually means the PR is `BEHIND` main (`gh pr view <n> --json mergeStateStatus`) rather than that a check went red: rebase onto `origin/main`, push, re-establish green CI at the new HEAD, then clear the gate stamp.

**Changing shipped behavior** — update the relevant root spec in `devflow/specs/`; namespace tiers are contractual (SPEC-003.C19). **Authoring or changing spools** — `docs/spools/writing-shared-spools.md`; the spool index is `spools/README.md`.

## Commands

```sh
make build                              # repo-local ./bin/strand, ./bin/mill — the agent path for CLI changes
mill start                              # supervisor, in a durable terminal
mill init [--workspace <dir>]           # create/complete a workspace
mill weaver start|status|stop|repl [--workspace <dir>]
make dash                               # code-owner TUI over the live coordination world

make test-warm NS="ns..."               # warm REPL to iterate a slice — never a Done-when gate
clojure -M:test <ns...>                 # cold focused run — the per-slice Done-when gate
flock -w 3600 /tmp/skein-test.lock clojure -M:test  # full locked suite — queue acceptance only; CI-blocking
(cd cli && go test ./...)               # primary validation, CI-blocking
clojure -M:smoke                        # primary validation, CI-blocking
make spool-suite-gate                   # pinned external spool suites vs this checkout — CI-blocking (GITLIBS=<dir> overrides the gitlibs cache)
make dash-check fmt-check lint reflect-check docs-check   # blocking CI quality gates, held at zero findings
make api-docs                           # regenerate *.api.md after touching any spool or skein.api.*.alpha docstring
```

`make lint` includes `lint-conventions` (ns docstrings everywhere; no locals named after clojure.core macros; requires embedded in quoted forms must resolve to real namespaces; api-module form per `quality.api-form` — contract in SPEC-003.C19a; shipped spool sources use `skein.core.*` only from unsafe-named namespaces per `quality.spool-tiers` — the unsafe-namespace convention in `docs/spools/writing-shared-spools.md`; no hand-escaped JSON string literal that `json/write-str` would reproduce, per `quality.json-literals`). New splint suppressions need written justification in `.splint.edn`. After validation, `git status --short` must not show generated SQLite or runtime metadata artifacts. Strand data is plain SQLite under a workspace's `data/skein.sqlite`.

## Hard rules

- **Agents never run `make install`** — it clobbers the user's global on-PATH binaries, which is the user's call. Use `make build` and the repo-local binaries.
- **Never restart a running weaver to pick up changes**; restarting the canonical weaver requires explicit user sign-off (it tears down live agent runs and registries other agents depend on). Pickup ladder: `make build` for Go CLI changes; `runtime/refresh!` for config, startup, and module source changes; a targeted `(require 'the.ns :reload)` only for already-loaded base-classpath namespaces; `runtime/reload-code!` for deliberate code-only work on synced roots (a bare `:reload` is blind to spool classloaders); only JVM-level changes or a refresh-recorded pending generation justify a restart. Semantics and recipes: `docs/spools/customisation.md`.
- **Kill by PID only**, never `pkill -f <pattern>` — delegated agents' prompts can quote the very command you match, so a pattern kill strafes healthy sibling runs.
- **Disposable workspaces for everything except coordination.** Tests, smoke runs, and config experiments use `--workspace` worlds from `mktemp -d`, never the user's default workspaces. Hold the path in your own shell variable and guard every expansion with `${ws:?}` — an empty variable must fail the command, not silently resolve to the canonical world.
- **Warm test output never satisfies a Done-when gate**, and the full suite is serialized across agents: hold the flock (bare `flock`, on PATH via nix), and run it only at queue acceptance. `SKEIN_TEST_AWAIT_SCALE` multiplies await budgets on slow hosts (CI sets 3).

## Repo coordination workspace (.skein)

The repo's `.skein` workspace is the shared coordination world — kanban board, devflow runs, delegation, and cross-agent tracking live there, worked as the scenarios above describe; everything else follows the disposable-workspace rule. Config layout and change discipline live in the `.skein/init.clj` header; smoke-test config changes in a disposable world first (note: `spools.edn` local roots resolve relative to the config dir).

## Implementation boundaries

- Keep SQL and shared persistence behavior in `skein.core.db`; strand attribute values stay JSON `TEXT` in the `attributes` table — no JSONB assumptions.
- Runtime publication: one ambient runtime per real weaver process (SPEC-004.C8a); tests and embedded runtimes start `:publish? false` and pass the runtime explicitly.
- Spool state is runtime-owned via `skein.api.runtime.alpha/spool-state`; no module-level atoms in spools.

<!-- mill:skein-prime -->

## Skein / strand

This repo uses Skein strands to track work. Orientation ships in the `mill` CLI:

- `mill strand prime` — the day-to-day strand workflow; run it before multi-step work.
- `mill skein prime` — read on demand, only when building on this repo's `.skein/` config or spools.
<!-- /mill:skein-prime -->
