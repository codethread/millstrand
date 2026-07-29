# Spool-shipped executables and the `mill bin` surface proposal

**Document ID:** `PROP-Sbn-001`
**Last Updated:** 2026-07-29
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)
**Related spool contract:** [Batteries](../../../spools/batteries.md), [writing shared spools](../../../docs/spools/writing-shared-spools.md)
**External prior art:** [`agent-harness.spool` harness MVP `PROP-Hmv-001`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/devflow/feat/azqfh-harness-mvp/proposal.md)

## PROP-Sbn-001.P1 Problem

Spools already ship executables. Nothing can find them or run them.

The kanban spool ships a Bun renderer at [`scripts/kanban-export/kanban-export.ts`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/scripts/kanban-export/kanban-export.ts) and a bash script at [`bin/compat-alarm`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/bin/compat-alarm), reachable only through [`Makefile`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/Makefile) targets run from a manual clone. `agent-harness.spool` ships [`bin/strand-harness`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/bin/strand-harness), whose install step is "put this on your `PATH` yourself". Every consumer who installed by `:git/sha` coordinate — the supported path — has all three files on disk and no way to name any of them.

Distribution is already solved. A git-coordinate spool is materialized whole, not filtered to its classpath, and the resolved path is public: `spool_sync/approved-spools` stamps `:root` on every entry (`src/skein/core/weaver/spool_sync.clj:817`) and `skein.api.runtime.alpha/approved` returns it (`src/skein/api/runtime/alpha.clj:53-67`). Nothing in the repository uses it that way; the only consumers are the loader's own path validation. What is missing is a declaration saying "this file is meant to be run", and a caller that can run it.

The caller is the harder half. Op handlers run inside a weaver started with no controlling terminal, so an op can never hand a curses application an fd. `bin/strand-harness` is the shape that works around this — the weaver returns a launcher path, a host-side script runs it — and `PROP-Hmv-001.G4` states the goal directly: run the agent "in the caller's real host TTY through a tiny shell wrapper". This proposal is downstream of that conclusion, not in tension with it. The wrapper is right; it needs somewhere to live and a way to be found.

The visible cost is that shipped TUIs do not exist. This repository's kanban dashboard lives at `scripts/agent-dash/`, whose own header says it is "not shipped, not part of the CLI surface". It is a good dashboard over a spool many workspaces load, and it cannot travel with that spool.

## PROP-Sbn-001.P2 Goals

- **PROP-Sbn-001.G1:** A spool declares an executable it ships; a consumer who installed by git coordinate lists and runs it with no checkout and no shell configuration.
- **PROP-Sbn-001.G2:** Interactive full-screen programs work with no stdio proxying, no pty allocation, and no hand-installed shell function.
- **PROP-Sbn-001.G3:** The `strand` binary gains no exec code path, preserving its pure-dispatcher contract (SPEC-002, TEN-006).
- **PROP-Sbn-001.G4:** A spool author expresses any build step, including none, without Skein knowing their toolchain.
- **PROP-Sbn-001.G5:** What a build will run is readable before it runs.
- **PROP-Sbn-001.G6:** For `:git` coordinates, build output cannot go stale against a pin bump.

## PROP-Sbn-001.P3 Non-goals

- **PROP-Sbn-001.N1:** Sandboxing spool-authored code, or defending against a hostile spool. A spool's Clojure already runs in the weaver on the user's host the moment its sha is approved; bins add a surface, not a trust decision. Per TEN-002 the blessed path is guidance, and enforcement against a hostile *worker* belongs to the harness seat's sandbox, not to this feature.
- **PROP-Sbn-001.N2:** Cross-machine execution. A plan resolves to a local path and runs locally.
- **PROP-Sbn-001.N3:** Becoming a package manager. Skein installs no toolchains, resolves no npm trees, vendors no compilers.
- **PROP-Sbn-001.N4:** Replacing Makefiles for spool developers working in their own checkout.
- **PROP-Sbn-001.N5:** Streaming stdio through the mill socket, now or later.
- **PROP-Sbn-001.N6:** Weaver-side liveness tracking of a running bin. See S6.

## PROP-Sbn-001.P4 Proposed scope

- **PROP-Sbn-001.S1 (the `:bins` kind and the `bins` op, both owned by batteries):** Batteries declares a `:bins` registry kind through `skein.api.registry.alpha/declare-kind!`, and — because a registry kind is only data — also registers the read-only op that consults it. That op is `bins`, with two leaves: `bins list` returns every declared entry projected as in EX1, and `bins plan <name>` returns the S7 exec plan. Both are `:read` hook-class. `mill bin` is a thin client over them: `list` relays `bins list`, `build` reads the recipe and root from `bins list`, and `run` calls `bins plan` and execs the result. Neither leaf is a core op, and `strand bins list` remains a perfectly ordinary op call for anyone who wants the JSON without the exec.

  Placing both in batteries rather than core is the TEN-004 answer, and there is precedent the surface already relies on: SPEC-002.P1 puts the entire everyday command surface — `add`, `list`, `ready`, `weave` — in batteries too. A workspace that removed batteries already has almost no CLI, so `mill bin` failing there is consistent with the system's existing shape rather than an anomaly a core kind would cure. `mill init` seeds batteries into every workspace (`cli/internal/config/bootstrap.go:13,43` writes both the `spools.edn` entry and the `init.clj` module declaration), so present-by-default is a verified default rather than a hope.

  The cost is a named coupling, and this proposal states it rather than leaving it implicit: **the `mill` binary carries the op name `bins` compiled in.** A core Go binary therefore depends on an op name registered by a shipped spool, which is new and belongs in SPEC-002. It also fixes the failure story honestly. Without batteries the machinery produces SPEC-002.C38's unknown-op domain error listing available ops; the *kind* never enters it, because the op that would have consulted the kind does not exist. `mill bin` recognises that error for its own op name and re-reports it as a `bin/surface-absent` outcome naming batteries, so the operator is told what to activate. That recognition is the mill↔batteries contract, and without it the failure is real but unhelpful.

  Promotion to a core kind is deferred work (D1), taken only if this coupling proves troublesome.

- **PROP-Sbn-001.S2 (declaration shape):** A bin entry is data in an owner-complete partition, matching the `:ops` kind's `{:entries … :overrides …}` shape exactly rather than by analogy:

  ```clojure
  {:bins {:entries
          {"kanban-dash"
           {:name  "kanban-dash"
            :doc   "Interactive kanban board TUI over the live workspace."
            :exec  ["bun" [:root "dash/index.tsx"]]
            :build ["bun" "install" "--frozen-lockfile"]
            :provenance 'ct.spools.kanban.dash}}
          :overrides #{}}}
  ```

  `:exec` and `:build` are argv vectors, never shell strings. `:build` is optional and its absence means there is nothing to build, which is the normal case for a shell script. Names are flat, collide loudly across owners, and resolve through `:overrides` exactly as ops do.

- **PROP-Sbn-001.S3 (argv resolution):** `argv[0]` follows execvp's rule. Containing a `/`, it resolves against the owning root and must exist there; bare, it is a `PATH` lookup performed by the OS at exec time. So `["bun" "dash/index.tsx"]` means "find `bun` on the user's `PATH`", and `["bin/strand-harness"]` means "run exactly this shipped file". Skein does not control which `bun` it invokes. The stricter alternative — interpreters vendored inside each spool root — is refused as N3, and the trust position is already conceded by S5: running a spool's `bun install` at user privilege and running its `bun` are the same grant.

  Remaining argv elements are **not** path-resolved by scanning for a `/`. That heuristic is too greedy: `--allow-read=/tmp`, a URL, and any `key=a/b` flag value all contain a slash and are not paths. Instead a root-relative path is written explicitly as `[:root "dash/index.tsx"]`, and every bare string is passed through untouched. `:exec ["bun" [:root "dash/index.tsx"]]` is the flagship declaration in full. Three scopes, stated because an implementer would otherwise have to guess: `[:root …]` elements are existence-checked at registration alongside `argv[0]`; `:build` argv may use them but rarely needs to, since build already runs with the root as cwd; and user-supplied trailing args at `run` time are never resolved or inspected, because they belong to the bin.

  Registration-time checking has a seam already: SPEC-004.C46d's `:candidate-validator` runs between staging and publication, receives the effective candidate entries, can resolve each owner's root, and a throw refuses the refresh while every owner keeps its previous live partition. That is exactly the fail-at-registration behaviour S9 wants, so `:bins` declares one.

- **PROP-Sbn-001.S4 (`mill bin` verb family):** `mill` gains one command group with three verbs. `list` and `build` emit JSON machine output per SPEC-002.C4 and TEN-001, and none render tables. `run` emits nothing on success: after the exec, mill is gone and the bin owns the stream. It emits a JSON failure envelope only when it never reaches the exec.

  ```
  mill bin list                       # every declared bin, its owner, resolved cwd, build recipe, reachability
  mill bin build <bin>  [--dry-run]   # run the declared :build argv in the owning root
  mill bin run   <bin>  [args...]     # resolve a plan and execve into it
  ```

  `run` disables its own flag parsing: everything after `<bin>` is the child's argv, so `mill bin run kanban-dash --help` reaches the dashboard. `mill`'s own flags must precede the bin name. `mill bin --help` and `mill bin run --help` still describe the Skein surface, because neither has named a bin. The only mill-owned flag on the group is `--workspace`, matching every other mill command; it must precede the bin name. `list` on a weaver with zero declared bins returns an empty collection, not an error.

- **PROP-Sbn-001.S5 (`mill`, not `strand`, on interface grounds):** `bin run` is necessarily a builtin with rich client-side behavior — the flag-parsing cutover in S4, a `chdir`, environment composition, and an `execve`. SPEC-002 gives `strand` zero builtin subcommands, no argv interpretation, and byte-faithful relay; `bin run` cannot live there without demolishing that contract, independently of any security story. This is the whole argument. In particular this proposal does **not** claim that placing the verb on `mill` denies host execution to a `strand`-only caller: a spool op is already spool-authored Clojure running on the same host as the same user, so what such a caller lacks is the operator's terminal, session, and cwd, not code execution. G3 is an interface-discipline goal, not a security boundary.

- **PROP-Sbn-001.S6 (`execve`, and the door it closes):** `run` resolves a plan, drains and closes every Skein-owned descriptor, `chdir`s to the plan's cwd, and calls `syscall.Exec`. There is no fork-and-wait mode. The child inherits fds 0/1/2 as the real tty in the same session and foreground process group, so raw mode, alt-screen, mouse reporting, resize, and Ctrl-C work because nothing sits between the terminal and the program.

  Supervision is not denied to bins that need it; it moves inside the bin. `bin/strand-harness` is itself a supervisor — it forks the launcher, traps `HUP`/`INT`/`TERM`, and reports `harness _finished` — so `mill` execs into a supervisor rather than becoming one, and closing the terminal still closes the run record.

  This is a one-way door and the proposal owns it: after the `execve` there is no mill process and the weaver never learns the child's pid, so **no weaver-side liveness check, reaper, or timeout for a running bin is possible under this design**. A `SIGKILL`, a crashed terminal emulator, or a reboot leaves any run record the bin created open forever. That capability is exactly what a stdio-proxying design would retain, and it is traded away deliberately. It is affordable because PHILOSOPHY holds that the work record is not the source of truth: stale run records are working memory, and reconciling them is the owning spool's problem, not Skein's.

- **PROP-Sbn-001.S7 (the plan, and environment as an overlay):** `bins plan <name>` (S1) is the read that produces this. The weaver's whole contribution is resolution: it opens no process and holds no descriptor, for `plan` and for `build` alike — every process this feature starts is started by the mill client.

  ```json
  {"operation": "bin plan",
   "bin": "kanban-dash",
   "exec": {"argv": ["bun", "/Users/ct/.cache/skein/spools/603fa7b8…/dash/index.tsx"],
            "cwd":  "/Users/ct/.cache/skein/spools/603fa7b8…",
            "env":  {"SKEIN_WORKSPACE": "/Users/ct/dev/projects/skein-src/.skein"}}}
  ```

  `env` is an **overlay merged onto the caller's environment**, never the child's whole environment. Passing it as a complete environment would strip `PATH`, `HOME`, and `TERM`, and the TUI this feature exists for would not start. `SKEIN_WORKSPACE` carries the selected workspace so a bin never re-derives it — the bug in `scripts/agent-dash/data.ts:42-43`, which walks up from its own file location and breaks as soon as the directory moves.

  `mill bin` dials its own mill daemon and relays through the existing invoke path (`cli/cmd/mill/forward.go:21` is that relay's server side), so no new transport is introduced. Because the client may hold both a mill-daemon leg and a relayed weaver leg, S6's drain-and-close covers descriptors plural.

- **PROP-Sbn-001.S8 (build: explicit, readable, atomic, git-scoped):** `build` runs the declared argv in the owning root at the user's privilege. `list` shows the recipe and `--dry-run` prints the argv without executing it, so the grant is readable before it is given. `run` never builds implicitly: an unbuilt bin fails loudly and prints the build command, per TEN-003.

  Build output for a `:git` coordinate lands inside the sha-addressed cache directory beside its source, and this is verified rather than assumed. `materialize-git-spool!` short-circuits to `:cached` whenever the sha directory is a non-empty directory (`spool_sync.clj:1015-1017`); the concurrent-materializer race path treats an existing non-empty target as a cache hit and never overwrites (`move-git-spool-to-cache!`, `spool_sync.clj:999-1013`); and no production path in `src/` or `cli/` deletes or re-materializes a populated cache root. A pin bump therefore produces a fresh unbuilt directory and invalidates by construction, which is G6.

  Three consequences follow that the naive reading misses:

  - **The cache is machine-global.** Every workspace and weaver pinning the same sha shares one directory, so build artifacts are cross-workspace shared state, and two concurrent `mill bin build` invocations race in the same tree. Because those invocations are separate short-lived mill client processes in different workspaces, an in-process lock is useless: `build` takes a cross-process advisory file lock on the root for the duration of the recipe, and a second builder waits rather than failing.
  - **`built?` reads a stamp, not the artifacts.** An interrupted `bun install` leaves a partial tree that a naive existence check accepts, which violates TEN-003. So the build writes a completion stamp as its last action and `built` reports whether that stamp exists. Building into a temporary directory and moving it atomically is *not* offered as an alternative: `bun install` mutates its source tree in place, so that path would mean copying the whole sha root first, which nothing here proposes. The stamp is keyed per bin, not per root, because two bins in one root can declare different recipes; it records the recipe argv it completed, so a changed recipe reads as unbuilt. Re-running `build` on an already-built bin re-runs the recipe and re-stamps, since the recipes in scope are idempotent installers. After an interruption the recipe simply re-runs over the dirty tree; nothing cleans partial output, and a recipe that cannot tolerate that must handle it itself.
  - **G6 covers `:git` coordinates only.** `:local/root` and `:skein/source-root` spools — a spool developer's own checkout, and the batteries spool every `mill init` workspace loads — have no sha directory. There `build` runs the same argv in the live checkout, staleness is the developer's problem exactly as it is with their Makefile today, and the proposal claims nothing more.

- **PROP-Sbn-001.S9 (failure modes):** Registration refuses an absolute path in a declaration, and refuses a rooted `argv[0]` or `[:root …]` element naming a file absent from the owning root. `list` reports reachability per bin so a missing interpreter is visible before a run rather than as an exec failure. A name collision across owners fails loudly and names both owners. `mill bin` requires a live weaver and fails loudly telling the operator to start one, rather than caching resolution into mill. Each failure is a named glossary outcome, and the set is closed here so SPEC-002 can be written from it:

  | Outcome | Raised when |
  | ------- | ----------- |
  | `bin/unknown` | No declared bin carries that name. |
  | `bin/not-built` | The bin declares `:build` and carries no completion stamp. |
  | `bin/unreachable` | A declared path is absent from the owning root, or a bare `argv[0]` resolves nowhere on `PATH`. |
  | `bin/name-collision` | Two owners declare the same name with no `:overrides` resolution. |
  | `bin/declaration-invalid` | An absolute path, a malformed `[:root …]` element, or a non-vector argv reaches registration. |
  | `bin/surface-absent` | The `bins` op is not registered, meaning batteries is not active (S1). |
  | `bin/weaver-unavailable` | No live weaver answers the plan request. |

- **PROP-Sbn-001.S10 (spec and contract accounting):** SPEC-002.P1 currently draws the mill/strand line as "`mill` owns everything that must work without a running weaver; `strand` is a pure dispatcher for everything that needs one." `mill bin` requires a live weaver and therefore rewrites that dividing principle to distinguish *privileged client-side behavior* from *pure relay*. This proposal owns that rewrite explicitly rather than letting it drift. Also updated: SPEC-002 for the three verbs, their flags, results, and the `run` flag-parsing cutover; SPEC-004 for the `:bins` kind's publication, its `:candidate-validator`, and the batteries-owned `bins` op contract that mill's compiled-in op name depends on; the alpha-surface index for whether `:bins` is in-contract while it lives in batteries; `spools/batteries.md` for the kind; the spool authoring guide for declaring a bin; and `devflow/UBIQUITOUS-LANGUAGE.md` for the word "bin".

- **PROP-Sbn-001.S11 (demonstration):** The kanban dashboard is extracted from `scripts/agent-dash/` into `kanban.spool` and shipped through this surface, with the repo-local merge-lock banner dropped. This is the demonstration that the surface works end to end, not an acceptance gate on this repository: it lands in another repository on its own schedule, and `mill bin` is complete without it.

- **PROP-Sbn-001.S12 (validation):** Clojure tests cover the `:bins` kind end to end in a disposable world: declaration acceptance, every S9 outcome, `:overrides` collision resolution, and the `:candidate-validator` refusing a declaration whose `[:root …]` path is absent while every owner keeps its previous live partition. Go tests cover the mill client: flag-parsing cutover after `<bin>`, the `bin/surface-absent` recognition of an unknown-op error, and the cross-process build lock under two concurrent builders. The `execve` path is covered by a Go integration test against a stub bin that writes its argv, cwd, inherited environment, and open descriptors to a file, which is what makes S6's drain-and-close and S7's env-overlay claims mechanically checkable rather than asserted. Terminal behaviour itself (raw mode, resize, alt-screen) is not automatically tested; it is a manual check against a real terminal, recorded in the feature's Done-when.

### PROP-Sbn-001.EX1 A consumer finds and runs a shipped dashboard

These snippets illustrate the proposed contract; none of this surface exists today. The consumer has kanban pinned by sha and has never cloned it.

```console
$ mill bin list
{
  "operation": "bin list",
  "bins": [
    {
      "name": "kanban-dash",
      "spool": "codethread/kanban",
      "doc": "Interactive kanban board TUI over the live workspace.",
      "root": "/Users/ct/.cache/skein/spools/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3",
      "build": ["bun", "install", "--frozen-lockfile"],
      "built": false,
      "reachable": true
    },
    {
      "name": "strand-harness",
      "spool": "ct.spools/agent-run",
      "doc": "Open a coding agent in this terminal as a tracked interactive run.",
      "root": "/Users/ct/.cache/skein/spools/27addcfc8725746b237ed84b7fd67a69add3046c",
      "built": true,
      "reachable": true
    }
  ]
}
```

`kanban-dash` declares a build and has not been built, so running it refuses and says what to run:

```console
$ mill bin run kanban-dash
{"operation": "bin run", "error": "bin/not-built",
 "bin": "kanban-dash", "remedy": "mill bin build kanban-dash"}
$ echo $?
1
```

The recipe was visible in `list` before consenting to it, and `--dry-run` prints it without running:

```console
$ mill bin build kanban-dash --dry-run
{"operation": "bin build", "bin": "kanban-dash", "dry-run": true,
 "argv": ["bun", "install", "--frozen-lockfile"],
 "cwd": "/Users/ct/.cache/skein/spools/603fa7b8…"}

$ mill bin build kanban-dash
{"operation": "bin build", "bin": "kanban-dash", "status": "built", "elapsed-ms": 4130}

$ mill bin run kanban-dash
# full-screen board, in this terminal; mill is gone
```

Bumping the kanban pin to a new sha changes the root, so `built` reverts to `false` with no bookkeeping.

### PROP-Sbn-001.EX2 An existing wrapper becomes reachable unchanged

This is the cheapest case, because the executable already exists and needs no edit. `bin/strand-harness` shipped at `27addcfc` as a POSIX `sh` script that already reads `SKEIN_WORKSPACE` from its environment. The entire delta is a declaration:

```clojure
{:bins {:entries
        {"strand-harness"
         {:name "strand-harness"
          :doc  "Open a coding agent in this terminal as a tracked interactive run."
          :exec ["bin/strand-harness"]
          :provenance 'ct.spools.agent-cli}}
        :overrides #{}}}
```

```console
$ mill bin run strand-harness claude --cwd ~/dev/projects/skein-src
```

No build step, no wrapper, no rc file. `mill` execs into `strand-harness`, which forks the launcher and reports `_started`/`_finished` exactly as it does today; the run lifecycle is untouched because nothing about it moved. The spool's README loses its manual `PATH` step.

That a landed design converged independently on a host-side wrapper script under `bin/` is the strongest available evidence that the shape is right. What it lacked was a way to reach it.

## PROP-Sbn-001.P5 Open questions

- **PROP-Sbn-001.Q1:** Should every shipped script be declarable, or only user-facing ones? `bin/compat-alarm` is a maintainer tool that a consumer has no reason to see in `mill bin list`. This may want a visibility marker on the entry rather than a convention that everything under `bin/` is a bin.
- **PROP-Sbn-001.Q2:** What does a bin learn about its caller beyond `SKEIN_WORKSPACE`? A run id, weaver id, or peer name may belong in the overlay; none is currently justified by a consumer.
- **PROP-Sbn-001.Q3:** Should `mill bin` support a bin declared by a spool that is approved but not activated? Resolution needs only the root, but the declaration lives in the contribution, so today the answer is no by construction. That has a consequence worth confirming as intended: a spool whose module fails to load takes its bins down with its ops, so a broken `contribute` costs the operator the dashboard as well as the CLI surface.

## PROP-Sbn-001.P6 Deferred work

- **PROP-Sbn-001.D1 (core kind promotion):** If the "batteries not loaded" failure in S1 proves annoying in practice, promote `:bins` to a sixth always-declared core kind. TEN-000 makes this a cheap later move and an expensive early one, so it is deliberately not taken now.
- **PROP-Sbn-001.D2 (`PATH`-level shims):** A `mill bin install <bin>` writing a two-line wrapper into `$XDG_BIN_HOME` would remove the `mill bin run` prefix from daily use. The wrapper must call `mill bin run`, never symlink into a sha-addressed path, so that it survives pin bumps. Deferred until the core surface has proved itself.
- **PROP-Sbn-001.D3 (bin-aware `strand help`):** Bins are invisible to the discovery tiers. Whether they belong in `help`, in a `bin about <name>`, or nowhere is a question for after the first real consumers exist.
