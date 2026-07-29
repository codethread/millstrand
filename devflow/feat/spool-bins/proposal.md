# Spool-shipped executables and the `mill bin` surface proposal

**Document ID:** `PROP-Sbn-001`
**Last Updated:** 2026-07-29
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md)
**Related spool contract:** [writing shared spools](../../../docs/spools/writing-shared-spools.md)
**External prior art:** [`agent-harness.spool` harness MVP `PROP-Hmv-001`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/devflow/feat/azqfh-harness-mvp/proposal.md)

## PROP-Sbn-001.P1 Problem

Spools already ship executables. Nothing can find them or run them.

The kanban spool ships a Bun renderer at [`scripts/kanban-export/kanban-export.ts`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/scripts/kanban-export/kanban-export.ts) and a shell script at [`bin/compat-alarm`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/bin/compat-alarm), reachable only through [`Makefile`](https://github.com/codethread/kanban.spool/blob/603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3/Makefile) targets run from a manual clone. `agent-harness.spool` ships [`bin/strand-harness`](https://github.com/codethread/agent-harness.spool/blob/27addcfc8725746b237ed84b7fd67a69add3046c/bin/strand-harness), whose install step is "put this on your `PATH` yourself". Every consumer who installed by `:git/sha` coordinate has these files on disk and no way to name them.

Distribution is already solved. A git-coordinate spool is materialized whole, not filtered to its classpath, and the resolved path is public: `spool_sync/approved-spools` stamps `:root` on every entry (`src/skein/core/weaver/spool_sync.clj:817`) and `skein.api.runtime.alpha/approved` returns it (`src/skein/api/runtime/alpha.clj:53-67`). What is missing is a declaration saying "this file is meant to be run", and a caller that can run it.

Op handlers run inside a weaver started with no controlling terminal, so an op cannot hand a curses application the caller's terminal. `bin/strand-harness` already has the right shape: the weaver returns data, then a host-side script runs in the caller's real TTY. The script needs a stable way to travel with a spool and be invoked.

## PROP-Sbn-001.P2 Goals

- **PROP-Sbn-001.G1:** A spool declares executable files it ships; a consumer who installed by git coordinate lists and runs them with no checkout and no shell configuration.
- **PROP-Sbn-001.G2:** Interactive full-screen programs inherit the caller's terminal directly, with no stdio proxy or pty allocation.
- **PROP-Sbn-001.G3:** The `strand` binary gains no exec path, preserving its pure-dispatcher contract (SPEC-002, TEN-006).
- **PROP-Sbn-001.G4:** The declaration stays small enough that a shell script is sufficient. Toolchains, builds, dependency checks, and setup remain inside the executable or in spool documentation.
- **PROP-Sbn-001.G5:** Running a bin behaves like running an executable installed on `PATH`: it preserves the caller's cwd and receives trailing arguments unchanged.

## PROP-Sbn-001.P3 Non-goals

- **PROP-Sbn-001.N1:** Build recipes, build stamps, build locks, artifact verification, or cache invalidation. Users build manually using the spool's normal instructions; Skein does not become a package manager.
- **PROP-Sbn-001.N2:** Sandboxing spool-authored code or defending against a hostile spool. A spool's Clojure already runs in the weaver on the user's host when its sha is approved. Per TEN-002, enforcement against a hostile worker belongs to the harness seat's sandbox.
- **PROP-Sbn-001.N3:** Cross-machine execution. A plan resolves to a local path and runs locally.
- **PROP-Sbn-001.N4:** Streaming stdio through the mill socket, now or later.
- **PROP-Sbn-001.N5:** Weaver-side liveness tracking of a running bin. See S6.
- **PROP-Sbn-001.N6:** Discovering every executable file under a spool root. Only declared `:bins` entries are public.

## PROP-Sbn-001.P4 Proposed scope

- **PROP-Sbn-001.S1 (the core `:bins` kind and `bins` protocol):** Core declares `:bins` as a sixth owner-partitioned registry kind. Any active spool may contribute entries to it. The core declaration owns validation because declaration shape, owner-root resolution, and path checks apply to every contributing spool.

  Core also registers the read-only `bins` op. `bins list` returns the effective winning declarations after overrides, and `bins plan <name>` returns the S7 exec plan. Both are `:read` hook-class. `mill bin list` relays `bins list`; `mill bin run` calls `bins plan` and execs the result. `strand bins list` remains an ordinary op call for clients that want JSON without execution.

  This is core surface because a bin declaration is a shared spool extension point, not part of batteries' everyday command collection. The protocol is present whenever a weaver is live, and `bins list` returns an empty collection when no active module declares a bin.

- **PROP-Sbn-001.S2 (declaration shape):** A bin entry is data in an owner-complete partition, matching the `:ops` kind's `{:entries … :overrides …}` shape:

  ```clojure
  {:bins {:entries
          {"kanban-dash"
           {:name       "kanban-dash"
            :doc        "Interactive kanban board TUI over the live workspace."
            :executable "bin/kanban-dash"
            :provenance 'ct.spools.kanban.dash}}
          :overrides #{}}}
  ```

  `:executable` is one non-empty relative path beneath the owning root. It is not argv and has no interpolation or path mini-language. The named file may be a committed compiled program or an executable script with a shebang. Build outputs that do not exist in a fresh materialized root cannot be declared directly; authors commit a small executable wrapper and keep build instructions or checks inside it or in spool documentation. A wrapper can invoke Bun, Go, another program, or a Makefile using ordinary shell conventions. Skein does not model those commands.

  Names are flat, collide loudly across owners, and resolve through `:overrides` exactly as ops do. Files do not become public because they live under `bin/` or carry an executable bit; only declared entries appear in `bins list`.

- **PROP-Sbn-001.S3 (path validation and arguments):** Core defines named public specs for the `:bins` partition and entry shapes, and the kind's registration path consults them before publication. Registration then requires `:executable` to be relative, remain beneath the owning root after canonicalization, name an existing regular file, and carry an executable bit; these filesystem checks stay in the candidate validator because a spec cannot express them. Invalid declarations fail the candidate refresh through SPEC-004.C46d's `:candidate-validator`, while every owner keeps its previous live partition.

  Planning resolves `:executable` to its canonical absolute path. `mill` appends every user argument after the bin name without parsing, resolving, or inspecting it. There is no `PATH` lookup and no shebang parsing in Skein. If the kernel cannot start the file or its declared interpreter, exec fails loudly with the host error.

- **PROP-Sbn-001.S4 (`mill bin` verb family):** `mill` gains two verbs:

  ```
  mill bin list                    # list declared bins, owners, docs, and executable paths
  mill bin run <bin> [args...]     # resolve a plan and execve into it
  ```

  `list` emits JSON machine output per SPEC-002.C4 and TEN-001. `run` emits nothing after a successful exec because mill is gone and the bin owns the stream. It emits a JSON failure envelope only when it never reaches exec.

  `run` stops parsing flags after `<bin>`, so `mill bin run kanban-dash --help` passes `--help` to the dashboard. `mill` flags, including `--workspace`, must precede the bin name. `mill bin --help` and `mill bin run --help` describe the Skein surface because neither invocation names a bin. Listing an empty registry returns an empty collection.

- **PROP-Sbn-001.S5 (`mill`, not `strand`, on interface grounds):** `bin run` requires client-side behavior: the flag-parsing cutover in S4, descriptor cleanup, environment composition, and an `execve`. SPEC-002 gives `strand` zero builtin subcommands, no argv interpretation, and byte-faithful relay. `bin run` therefore belongs to `mill`.

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
            "env": {"SKEIN_WORKSPACE": "/Users/ct/dev/projects/skein-src/.skein"}}}
  ```

  Mill already owns the caller's cwd and complete environment, so the plan does not echo them. Mill leaves cwd unchanged and constructs argv as the resolved path followed by the user-supplied arguments. The plan's `env` is only an overlay. `SKEIN_WORKSPACE` identifies the selected workspace and is sufficient for a bin to address its weaver through the ordinary client path.

  `mill bin` dials its mill daemon and relays through the existing invoke path (`cli/cmd/mill/forward.go:21`). Because the client may hold both mill-daemon and relayed-weaver connections, S6 closes every non-stdio descriptor it owns before exec.

- **PROP-Sbn-001.S8 (failure modes):** The surface fails loudly at registration, lookup, relay, and exec:

  | Outcome | Raised when |
  | ------- | ----------- |
  | `bin/unknown` | No active declaration carries the requested name. |
  | `bin/declaration-invalid` | A declaration has the wrong shape, escapes its owning root, or names a file that is absent, non-regular, or non-executable. Existing owner-registry collision and override errors apply unchanged during publication. |
  | `mill/no-selected-weaver` | No live weaver answers the list or plan request; this reuses mill's existing outcome. |
  | `bin/exec-failed` | Mill resolves a valid plan but the host refuses exec; the error preserves the path and host cause. |

  Skein does not preflight interpreters or dependencies. Those failures remain normal executable failures, or the script may check them and print its own remedy.

- **PROP-Sbn-001.S9 (spec and contract accounting):** SPEC-002.P1 currently draws the mill/strand line as "`mill` owns everything that must work without a running weaver; `strand` is a pure dispatcher for everything that needs one." `mill bin` requires a live weaver, so SPEC-002 changes that principle to distinguish privileged client-side behavior from pure relay. SPEC-002 also owns the two verbs, flag cutover, exec behavior, outcomes, typed Go decoding of the plan, and `SKEIN_WORKSPACE` child-environment contract. SPEC-004 owns the core `:bins` kind, candidate validation, `bins` read protocol, and named consulted specs for the partition, entry, list result, and plan result shapes. The alpha-surface index publishes those spec names with the declaration shape. The spool authoring guide explains how to ship an executable wrapper and consume `SKEIN_WORKSPACE`, and `devflow/UBIQUITOUS-LANGUAGE.md` gains the word "bin".

- **PROP-Sbn-001.S10 (demonstration):** The kanban dashboard moves from `scripts/agent-dash/` into `kanban.spool` behind an executable `bin/kanban-dash` wrapper. The wrapper owns Bun invocation and any setup guidance. This downstream move demonstrates the surface but is not an acceptance gate for this repository.

- **PROP-Sbn-001.S11 (validation):** Clojure tests prove registration consults the named partition and entry specs, and that the handlers validate list and plan results against their named specs. They also cover two independent non-batteries declarations, an empty registry, plan resolution, collision overrides, root containment, and candidate-validator refusal for absent, non-regular, and non-executable files while previous live partitions remain intact. Go tests cover typed rejection of malformed plans, list relay, flag cutover after `<bin>`, plan argument appending, cwd preservation, caller-environment inheritance plus `SKEIN_WORKSPACE` overlay, descriptor cleanup, and loud exec failure with the path and host cause. A manual terminal check covers raw mode, resize, alt-screen, and Ctrl-C.

### PROP-Sbn-001.EX1 A consumer runs a shipped dashboard

The consumer has kanban pinned by sha and has never cloned it:

```console
$ mill bin list
{"operation":"bins list","bins":[
  {"name":"kanban-dash",
   "spool":"codethread/kanban",
   "doc":"Interactive kanban board TUI over the live workspace.",
   "executable":"/Users/ct/.cache/skein/spools/603fa7b8…/bin/kanban-dash"}
]}

$ mill bin run kanban-dash
# full-screen board in this terminal; mill is gone
```

`bin/kanban-dash` is an ordinary executable script. It may invoke Bun directly, call a Makefile target, check dependencies, or explain a manual setup step. None of that becomes Skein protocol.

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

## PROP-Sbn-001.P6 Deferred work

- **PROP-Sbn-001.D1 (`PATH`-level shims):** A future `mill bin install <bin>` could write a wrapper into `$XDG_BIN_HOME`. It must call `mill bin run`, never symlink into a resolved spool root, so it survives pin changes.
- **PROP-Sbn-001.D2 (bin-aware discovery):** Whether bins belong in `strand help`, `mill bin about <name>`, or nowhere can wait for real consumers.
- **PROP-Sbn-001.D3 (hidden ops):** Names such as `_started` and `_finished` are parsed and listed like every other op; `_` is only a pseudo-private convention. A hidden or internal op contract affects the shared op and discovery surfaces and belongs in separate work.
