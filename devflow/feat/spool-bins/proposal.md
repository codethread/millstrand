# Spool-shipped executables and the `mill bin` surface proposal

**Document ID:** `PROP-Sbn-001`
**Last Updated:** 2026-07-30
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)
**Related spool contract:** [writing shared spools](../../../docs/spools/writing-shared-spools.md)
**External prior art:** [`agent-harness.spool` harness MVP `PROP-Hmv-001`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/devflow/feat/azqfh-harness-mvp/proposal.md)

## PROP-Sbn-001.P1 Problem

Spools already ship executables. Nothing can find them or run them.

The kanban spool ships a Bun renderer at [`scripts/kanban-export/kanban-export.ts`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/scripts/kanban-export/kanban-export.ts) and a shell script at [`bin/compat-alarm`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/bin/compat-alarm), reachable only through [`Makefile`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/Makefile) targets run from a manual clone. `agent-harness.spool` ships [`bin/strand-harness`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/bin/strand-harness), whose install step is "put this on your `PATH` yourself". Every consumer who installed by `:git/sha` coordinate has these files on disk and no way to name them.

Distribution is already solved. A git-coordinate spool is materialized whole, not filtered to its classpath, and the resolved path is public: `spool_sync/approved-spools` stamps `:root` on every entry (`src/skein/core/weaver/spool_sync.clj:817`) and `skein.api.runtime.alpha/approved` returns it (`src/skein/api/runtime/alpha.clj:53-67`). What is missing is a declaration saying "this file is meant to be run", and a caller that can run it.

Op handlers run inside a weaver started with no controlling terminal, so an op cannot hand a curses application the caller's terminal. `bin/strand-harness` already has the right shape: the weaver returns data, then a host-side script runs in the caller's real TTY. The script needs a stable way to travel with a spool and be invoked.

Some shipped executables also need a one-time build before their first run — the kanban renderer needs `bun install`. A surface that only names committed files pushes that step back into README prose and Makefile targets run from a manual clone, which is the gap this proposal exists to close. The whole consumer contract is: grab a spool, `mill bin build <name>` when the bin declares a build, then `mill bin run <name>`.

## PROP-Sbn-001.P2 Goals

- **PROP-Sbn-001.G1:** A spool declares executable files it ships; a consumer who installed by git coordinate lists and runs them with no checkout and no shell configuration.
- **PROP-Sbn-001.G2:** Interactive full-screen programs inherit the caller's terminal directly, with no stdio proxy or pty allocation.
- **PROP-Sbn-001.G3:** The `strand` binary gains no exec path, preserving its pure-dispatcher contract (SPEC-002, TEN-006).
- **PROP-Sbn-001.G4:** A spool author declares a minimal build recipe when their executable needs one; a consumer builds it with a single command and never reads the spool's build documentation. Skein still models no toolchain — the recipe is one argv the author owns.
- **PROP-Sbn-001.G5:** Running a bin behaves like running an executable installed on `PATH`: it preserves the caller's cwd and receives trailing arguments unchanged.
- **PROP-Sbn-001.G6:** What a build will run is readable before it runs: `mill bin list` shows the recipe.

## PROP-Sbn-001.P3 Non-goals

The correctness stance for this whole surface: failures are acceptable provided the user sees them and can act. A failed or half-finished build is recovered by re-running `mill bin build`; there is no cache system to keep consistent.

- **PROP-Sbn-001.N1:** Build bookkeeping: completion stamps, build locks, staleness detection, artifact verification, cache invalidation, or pin-bump guarantees. `mill bin build` runs the declared recipe when the user asks and never decides whether a build is needed, current, or concurrent; re-running the command is the recovery path for every build-state question. Skein does not become a package manager: it installs no toolchains and resolves no dependency trees.
- **PROP-Sbn-001.N2:** Sandboxing spool-authored code or defending against a hostile spool. A spool's Clojure already runs in the weaver on the user's host when its sha is approved. Per TEN-002, enforcement against a hostile worker belongs to the harness seat's sandbox.
- **PROP-Sbn-001.N3:** Cross-machine execution. A plan resolves to a local path and runs locally.
- **PROP-Sbn-001.N4:** Streaming stdio through the mill socket, now or later.
- **PROP-Sbn-001.N5:** Weaver-side liveness tracking of a running bin. See S6.
- **PROP-Sbn-001.N6:** Discovering every executable file under a spool root. Only declared `:bins` entries are public.

## PROP-Sbn-001.P4 Proposed scope

- **PROP-Sbn-001.S1 (the core `:bins` kind and `bins` protocol):** Core declares `:bins` as a sixth owner-partitioned registry kind. Any active spool may contribute entries to it. The core declaration owns validation because declaration shape, owner-root resolution, and path checks apply to every contributing spool.

  Core also registers the read-only `bins` op. `bins list` returns the effective winning declarations after overrides, and `bins plan <name>` returns the S7 exec plan. Both are `:read` hook-class. `mill bin list` relays `bins list`; `mill bin build` reads the same plan and runs the recipe; `mill bin run` calls `bins plan` and execs the result. `strand bins list` remains an ordinary op call for clients that want JSON without execution.

  This is core surface because a bin declaration is a shared spool extension point, not part of batteries' everyday command collection. The protocol is present whenever a weaver is live, and `bins list` returns an empty collection when no active module declares a bin.

- **PROP-Sbn-001.S2 (declaration shape):** A bin entry is data in an owner-complete partition, matching the `:ops` kind's `{:entries … :overrides …}` shape:

  ```clojure
  {:bins {:entries
          {"kanban-dash"
           {:name       "kanban-dash"
            :doc        "Interactive kanban board TUI over the live workspace."
            :executable "bin/kanban-dash"
            :build      ["bun" "install" "--frozen-lockfile"]
            :provenance 'ct.spools.kanban.dash}}
          :overrides #{}}}
  ```

  `:executable` is one non-empty relative path beneath the owning root. It is not argv and has no interpolation or path mini-language. The named file may be a committed compiled program, an executable script with a shebang, or the output of the entry's own `:build` recipe.

  `:build` is an optional argv vector of plain strings — never a shell string, with no interpolation and no path mini-language. It runs with the owning root as cwd, so relative paths in a recipe are ordinary relative paths, and its first element resolves like any spawned command: a path runs directly, a bare name is the OS's `PATH` lookup at spawn time. Its absence means there is nothing to build, the normal case for a shell script. Authors who prefer a committed wrapper that performs its own setup may still ship one; `:build` is the declared alternative that makes the step discoverable instead of documented.

  Names are flat, collide loudly across owners, and resolve through `:overrides` exactly as ops do. Files do not become public because they live under `bin/` or carry an executable bit; only declared entries appear in `bins list`.

- **PROP-Sbn-001.S3 (path validation and arguments):** Core defines named public specs for the `:bins` partition and entry shapes, and the kind's registration path consults them before publication. Registration then requires `:executable` to be relative and remain beneath the owning root after canonicalization, and `:build`, when present, to be a vector of non-empty strings. For a bin without `:build`, registration also requires `:executable` to name an existing regular file carrying an executable bit; these filesystem checks stay in the candidate validator because a spec cannot express them. Invalid declarations fail the candidate refresh through SPEC-004.C46d's `:candidate-validator`, while every owner keeps its previous live partition.

  A bin with `:build` may name an executable that is absent from a fresh materialized root by construction, so its existence and executable-bit checks move to plan time: `bins plan` fails `bin/not-built`, naming the build command as the remedy (S8).

  Planning resolves `:executable` to its canonical absolute path. `mill` appends every user argument after the bin name without parsing, resolving, or inspecting it. Beyond the OS resolving a bare recipe command at spawn time, there is no `PATH` lookup and no shebang parsing in Skein. If the kernel cannot start the file or its declared interpreter, exec fails loudly with the host error.

- **PROP-Sbn-001.S4 (`mill bin` verb family):** `mill` gains three verbs:

  ```
  mill bin list                    # list declared bins, owners, docs, executable paths, and build recipes
  mill bin build <bin>             # run the declared :build argv in the owning root
  mill bin run <bin> [args...]     # resolve a plan and execve into it
  ```

  `list` emits JSON machine output per SPEC-002.C4 and TEN-001. `build` spawns the recipe as a child process with the owning root as cwd and the caller's environment plus the S7 overlay, leaves its stdio on the caller's terminal, waits, and reports the exit in a JSON envelope. It runs whenever asked — no stamp, no lock, no staleness check; re-running is both the upgrade path and the recovery path. `run` emits nothing after a successful exec because mill is gone and the bin owns the stream. It emits a JSON failure envelope only when it never reaches exec.

  `run` stops parsing flags after `<bin>`, so `mill bin run kanban-dash --help` passes `--help` to the dashboard. `mill` flags, including `--workspace`, must precede the bin name. `mill bin --help` and `mill bin run --help` describe the Skein surface because neither invocation names a bin. Listing an empty registry returns an empty collection.

- **PROP-Sbn-001.S5 (`mill`, not `strand`, on interface grounds):** `bin run` and `bin build` require client-side behavior: the flag-parsing cutover in S4, descriptor cleanup, environment composition, child-process supervision for `build`, and an `execve` for `run`. SPEC-002 gives `strand` zero builtin subcommands, no argv interpretation, and byte-faithful relay. Both verbs therefore belong to `mill`. Every process this feature starts is started by the mill client; the weaver only resolves.

  This is interface discipline, not a security boundary. A spool op is already spool-authored Clojure running on the same host as the same user. What a `strand`-only caller lacks is the operator's terminal, session, and cwd, not code execution.

- **PROP-Sbn-001.S6 (`execve`, and the door it closes):** `run` resolves a plan, drains and closes every Skein-owned descriptor, preserves the mill caller's cwd, overlays the plan environment onto the caller's environment, and calls `syscall.Exec`. The child inherits file descriptors 0/1/2 as the real terminal in the same session and foreground process group. Raw mode, alt-screen, mouse reporting, resize, and Ctrl-C work because nothing sits between the terminal and the program.

  Supervision moves inside bins that need it. `bin/strand-harness` forks its launcher, traps `HUP`/`INT`/`TERM`, and reports lifecycle events through spool-owned ops, so mill execs into the supervisor rather than becoming one.[^s6-liveness]

  After exec there is no mill process and the weaver never learns the child's pid, so no weaver-side liveness check, reaper, or timeout is possible under this design. A `SIGKILL`, crashed terminal emulator, or reboot may leave a run record open. PHILOSOPHY holds that the work record is not the source of truth; reconciliation belongs to the spool that owns the record.

  [^s6-liveness]: `bin/strand-harness` already reports `_started` and `_finished` through ordinary ops. The leading `_` is a pseudo-private naming convention with no parser or discovery behavior. Hidden ops are deferred in D3. Message passing follows Go's "Do not communicate by sharing memory; instead, share memory by communicating" maxim at the process boundary.

- **PROP-Sbn-001.S7 (the plan and environment overlay):** `bins plan <name>` resolves a declaration without opening a process or holding a descriptor:

  ```json
  {"operation": "bins plan",
   "bin": "kanban-dash",
   "exec": {"path": "/Users/ct/.cache/skein/spools/603fa7b8…/bin/kanban-dash",
            "env": {"SKEIN_WORKSPACE": "/Users/ct/dev/projects/skein-src/.skein"}},
   "build": {"argv": ["bun", "install", "--frozen-lockfile"],
             "cwd":  "/Users/ct/.cache/skein/spools/603fa7b8…"}}
  ```

  Mill already owns the caller's cwd and complete environment, so the plan does not echo them. For `run`, mill leaves cwd unchanged and constructs argv as the resolved path followed by the user-supplied arguments. `build` appears only when the declaration carries a recipe; `mill bin build` runs its `argv` with its `cwd` as working directory. In both cases the plan's `env` is only an overlay. `SKEIN_WORKSPACE` identifies the selected workspace and is sufficient for a bin to address its weaver through the ordinary client path.

  `mill bin` dials its mill daemon and relays through the existing invoke path (`cli/cmd/mill/forward.go:21`). Because the client may hold both mill-daemon and relayed-weaver connections, S6 closes every non-stdio descriptor it owns before exec.

- **PROP-Sbn-001.S8 (failure modes):** The surface fails loudly at registration, lookup, relay, and exec:

  | Outcome | Raised when |
  | ------- | ----------- |
  | `bin/unknown` | No active declaration carries the requested name. |
  | `bin/declaration-invalid` | A declaration has the wrong shape, escapes its owning root, or — for a buildless bin — names a file that is absent, non-regular, or non-executable. Existing owner-registry collision and override errors apply unchanged during publication. |
  | `bin/not-built` | A bin declaring `:build` names an executable that does not exist at plan time; the error names `mill bin build <bin>` as the remedy. |
  | `bin/no-build-recipe` | `mill bin build` named a bin that declares no `:build`. |
  | `bin/build-failed` | The recipe exited non-zero; the error preserves the exit code. The recipe's own output is the diagnosis, and re-running the build after acting on it is the recovery. |
  | `mill/no-selected-weaver` | No live weaver answers the list or plan request; this reuses mill's existing outcome. |
  | `bin/exec-failed` | Mill resolves a valid plan but the host refuses exec; the error preserves the path and host cause. |

  Skein does not preflight interpreters or dependencies. Those failures remain normal executable failures, or the script may check them and print its own remedy.

- **PROP-Sbn-001.S9 (spec and contract accounting):** SPEC-002.P1 currently draws the mill/strand line as "`mill` owns everything that must work without a running weaver; `strand` is a pure dispatcher for everything that needs one." `mill bin` requires a live weaver, so SPEC-002 changes that principle to distinguish privileged client-side behavior from pure relay. SPEC-002 also owns the three verbs, flag cutover, exec behavior, `build`'s child-process contract and result envelope, outcomes, typed Go decoding of the plan, and `SKEIN_WORKSPACE` child-environment contract. SPEC-004 owns the core `:bins` kind, candidate validation, `bins` read protocol, and named consulted specs for the partition, entry, list result, and plan result shapes. The alpha-surface index publishes those spec names with the declaration shape. The spool authoring guide explains how to ship an executable wrapper and consume `SKEIN_WORKSPACE`, and `devflow/UBIQUITOUS-LANGUAGE.md` gains the word "bin".

- **PROP-Sbn-001.S10 (demonstration):** The kanban dashboard moves from `scripts/agent-dash/` into `kanban.spool` as an executable `bin/kanban-dash` declaring `:build ["bun" "install" "--frozen-lockfile"]`. This downstream move demonstrates the surface but is not an acceptance gate for this repository.

- **PROP-Sbn-001.S11 (validation):** Clojure tests prove registration consults the named partition and entry specs, and that the handlers validate list and plan results against their named specs. They also cover two independent non-batteries declarations, an empty registry, plan resolution, collision overrides, root containment, and candidate-validator refusal for absent, non-regular, and non-executable files while previous live partitions remain intact — plus acceptance of a `:build` bin whose executable is absent, and `bin/not-built` with its remedy at plan time. Go tests cover typed rejection of malformed plans, list relay, flag cutover after `<bin>`, plan argument appending, cwd preservation, caller-environment inheritance plus `SKEIN_WORKSPACE` overlay, descriptor cleanup, and loud exec failure with the path and host cause; for `build`, running the recipe with the owning root as cwd and the caller's environment, the JSON exit envelope, `bin/build-failed` preserving a non-zero exit, and `bin/no-build-recipe`. A manual terminal check covers raw mode, resize, alt-screen, and Ctrl-C.

### PROP-Sbn-001.EX1 A consumer runs a shipped dashboard

The consumer has kanban pinned by sha and has never cloned it:

```console
$ mill bin list
{"operation":"bins list","bins":[
  {"name":"kanban-dash",
   "spool":"codethread/kanban",
   "doc":"Interactive kanban board TUI over the live workspace.",
   "executable":"/Users/ct/.cache/skein/spools/603fa7b8…/bin/kanban-dash",
   "build":["bun","install","--frozen-lockfile"]}
]}

$ mill bin run kanban-dash
{"operation":"bins plan","error":"bin/not-built",
 "bin":"kanban-dash","remedy":"mill bin build kanban-dash"}

$ mill bin build kanban-dash
{"operation":"bins build","bin":"kanban-dash","exit":0,"elapsed-ms":4130}

$ mill bin run kanban-dash
# full-screen board in this terminal; mill is gone
```

Nothing recorded that the build happened; the executable's existence is the only build state Skein reads. Bumping the pin materializes a fresh root whose executable is absent again, so `run` refuses with the same remedy. Re-running `mill bin build` after any doubt is always safe for the recipes in scope — ordinary idempotent installers — and where one is not, that is the author's contract with their users, not Skein's.

### PROP-Sbn-001.EX2 An existing wrapper becomes reachable unchanged

`bin/strand-harness` already reads `SKEIN_WORKSPACE`, supervises its launcher, and reports `_started` and `_finished`. Its whole declaration is:

```clojure
{:bins {:entries
        {"strand-harness"
         {:name       "strand-harness"
          :doc        "Open a coding agent in this terminal as a tracked interactive run."
          :executable "bin/strand-harness"
          :provenance 'ct.spools.agent-cli}}
        :overrides #{}}}
```

```console
$ mill bin run strand-harness claude --cwd ~/dev/projects/skein-src
```

Mill preserves the directory from which the operator invoked it and passes the trailing arguments unchanged. The spool's README loses its manual `PATH` installation step.

## PROP-Sbn-001.P5 Resolved questions

- **PROP-Sbn-001.Q1:** A spool author declares whichever executables they want consumers to see. Only entries contributed through `:bins` appear in `mill bin list`; no visibility marker is needed.
- **PROP-Sbn-001.Q2:** `SKEIN_WORKSPACE` is sufficient caller context. It identifies the selected world and therefore the weaver a bin should address through the ordinary client path. The overlay carries no run id, weaver id, or peer name.
- **PROP-Sbn-001.Q3:** Bins are available only from activated modules because declarations live in contributions. If a module is inactive or fails to contribute, its bins are absent along with its ops. This follows the current activation model and needs no workaround.
- **PROP-Sbn-001.Q4:** Why no completion stamp or build lock: the executable's existence, read at plan time, is the only build state Skein consults. A half-finished build either leaves the executable absent (`run` keeps refusing with the remedy) or present but broken (the bin fails loudly in the user's terminal). Both are visible, and both are fixed by re-running the build command. Concurrent builds in one root race benignly for idempotent installers; the loser re-runs. A stamp-and-lock design was written in full (`46b19a1e`) and cut deliberately — it pulled in stamp identity, external state keyed by root and recipe, a cross-process protocol needing a spec owner, and cache GC, all to answer questions "re-run the build" already answers.

## PROP-Sbn-001.P6 Deferred work

- **PROP-Sbn-001.D1 (`PATH`-level shims):** A future `mill bin install <bin>` could write a wrapper into `$XDG_BIN_HOME`. It must call `mill bin run`, never symlink into a resolved spool root, so it survives pin changes.
- **PROP-Sbn-001.D2 (bin-aware discovery):** Whether bins belong in `strand help`, `mill bin about <name>`, or nowhere can wait for real consumers.
- **PROP-Sbn-001.D3 (hidden ops):** Names such as `_started` and `_finished` are parsed and listed like every other op; `_` is only a pseudo-private convention. A hidden or internal op contract affects the shared op and discovery surfaces and belongs in separate work.
- **PROP-Sbn-001.D4 (build verification and staleness):** A declared `:test` recipe, completion stamps, and pin-bump staleness guarantees were designed (`46b19a1e`) and deliberately cut per the P3 stance. They return only if bookkeeping-free re-running proves insufficient for real consumers.
