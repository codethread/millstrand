# Spool-shipped executables and the `mill bin` surface proposal

**Document ID:** `PROP-Sbn-001`
**Last Updated:** 2026-07-30
**Related RFCs:** None
**Related root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md), [Alpha Surface](../../specs/alpha-surface.md)
**Related proposals:** [`PROP-Auf-001`](../authoring-forms/proposal.md) (authoring forms) — the shipped contribution grammar this proposal declares bins in
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

- **PROP-Sbn-001.S1 (the core `:bins` kind, the `defbin` form, and the `bins` protocol):** Core declares `:bins` as a sixth owner-partitioned registry kind and ships `defbin` as the sixth core authoring form, beside `defop`, `defquery`, `defpattern`, `defhook`, and `defhandler` in `skein.api.skein.alpha` (PROP-Auf-001.REC4; AC1 requires every core kind to have a named form). `:bins` is Skein's kind rather than a domain vocabulary a spool owns, which is what puts both the kind and its form in core (S9). Any active module may declare entries, and core owns the one shape check they get, because it applies identically to every declaring module.

  Core also registers the read-only `bins` op. `bins list` returns the effective winning declarations after overrides, and `bins plan <name>` returns the S7 exec plan. Both are `:read` hook-class. `mill bin list` relays `bins list`; `mill bin build` reads the same plan and runs the recipe; `mill bin run` calls `bins plan` and execs the result. `strand bins list` remains an ordinary op call for clients that want JSON without execution.

  This is core surface because a bin declaration is a shared spool extension point, not part of batteries' everyday command collection. The protocol is present whenever a weaver is live, and `bins list` returns an empty collection when no active module declares a bin.

- **PROP-Sbn-001.S2 (declaration shape):** A bin is declared where it is defined, in one top-level form:

  ```clojure
  (ns ct.spools.kanban.dash
    (:require [skein.api.skein.alpha :as skein]))

  (skein/defbin kanban-dash
    "Interactive kanban board TUI over the live workspace."
    {:executable "../../../../bin/kanban-dash"
     :build      ["bun" "install" "--cwd" "../../../../scripts/kanban-export"
                  "--frozen-lockfile"]})
  ```

  The leading `..` segments are the honest cost of resolving against the declaring file: `ct.spools.kanban.dash` lives at `src/ct/spools/kanban/dash.clj`, four levels below the checkout that holds `bin/`. A shallower namespace shortens the path, and moving the namespace deeper breaks it, so an author who dislikes that coupling keeps the declaration in a shallow namespace. `mill bin list` prints the resolved absolute path, which is what makes a stale one visible.

  The form is `[form-name doc opts]`. A bin names a file rather than a handler, so like `defquery` it defines the declaration Var and no function. The entry key is the form name as a string, which is also the name the operator passes to `mill bin run`. The docstring sits where it does in every other core form. Authoring at a form narrows bin names to what `def` accepts, so a bin is named like a Clojure symbol and interns a Var of that name in the declaring namespace. Nothing in scope wants a name outside that set, and `kanban-dash` is the shape an operator would type anyway. `:name` and `:provenance` are derived, the first from the form name and the second from the defining namespace, and the owner is the collecting module, so no author writes any of the three. `:override? true` in the options map records explicit override intent exactly as it does for the other five core forms. Collection normalizes to the owner-complete partition the publication kernel already understands:

  ```clojure
  {:bins {:entries {"kanban-dash" {:name       "kanban-dash"
                                   :doc        "Interactive kanban board TUI over the live workspace."
                                   :executable "../../../../bin/kanban-dash"
                                   :build      ["bun" "install" "--cwd"
                                                "../../../../scripts/kanban-export"
                                                "--frozen-lockfile"]
                                   :provenance 'ct.spools.kanban.dash}}
          :overrides #{}}}
  ```

  That map is internal to publication; it is shown here because S3's registration checks and S7's plan read it, not because a bin author writes it.

  `:executable` is one non-empty string naming a program, resolved the way a shell resolves a command. A name with no separator is looked up on `PATH` at spawn time. A name containing a separator is a path: `./x` and `../x` resolve against the directory of the file that declared the form, `~/x` expands to the caller's home directory, and an absolute path is taken as given. There is no environment interpolation and no path mini-language beyond those four cases. The named file may be a committed compiled program, an executable script with a shebang, the output of the entry's own `:build` recipe, or a program the consumer already has installed.

  Resolving against the declaring file rather than a registry root is what makes the surface work for real spools. Roots are per-library subdirectories of a multi-root family checkout, but a `bin/` directory serves the whole repository — `agent-harness.spool` puts `bin/strand-harness` beside its nine library roots, not inside one — so any rule anchored on the owning root cannot name the executables this proposal exists to reach. The declaring file needs no owner-to-root mapping, which also disposes of the question of what a module with several `:spools` prerequisites is relative to.

  `:build` is an optional argv vector of plain strings — never a shell string, with no interpolation and no path mini-language. It runs with the declaring file's directory as cwd, so relative paths in a recipe are ordinary relative paths, and its first element resolves like any spawned command: a path runs directly, a bare name is the OS's `PATH` lookup at spawn time. Its absence means there is nothing to build, the normal case for a shell script. Authors who prefer a committed wrapper that performs its own setup may still ship one; `:build` is the declared alternative that makes the step discoverable instead of documented.

  Names are flat, collide loudly across owners, and resolve through the declaring form's `:override? true` exactly as ops do. Files do not become public because they live under `bin/` or carry an executable bit; only declared entries appear in `bins list`. Removal is by omission: an owner that stops evaluating a `defbin` form drops the bin at the next refresh, and image activation replays the retained declaration record, so a bin behaves like every other collected entry.

- **PROP-Sbn-001.S3 (path validation and arguments):** Skein does not police where a bin's path leads. A declaration naming `/usr/local/bin/x` or a path walking out of its spool is accepted, because the spool's own Clojure could open that file already and the seat's sandbox is where enforcement belongs (N2). There is no containment rule to state, no owner-to-root resolution to define, and no case in which registration refuses a path. What remains is a convenience for shipping non-Clojure code, and the correctness stance is P3's: the user sees the failure and acts on it.

  `bin-declaration` and the `::bin-options` grammar live in `skein.core.contribution` beside the other five core constructors, closed to `:executable`, `:build`, and `:override?`. They require `:executable` to be a non-empty string and `:build`, when present, to be a vector of non-empty strings. That is the whole of declaration validation. A malformed declaration throws while the named top-level form is being evaluated, so the failure points at that form rather than at a module-wide callback. It surfaces on the ordinary module evaluation-failure channel (SPEC-004.C46) rather than as one of S8's op outcomes, and the shipped constructors name the failing form rather than the entry, so this proposal claims no richer error data than `defop` and `defquery` already produce.

  With no path rule to enforce, the kind needs no `:candidate-validator` and registration performs no filesystem check on any bin. Every filesystem question moves to `bins plan`, which evaluates one runnable predicate — the resolved path names an existing regular file carrying an executable bit — and reports the result. This is the one place a bin's readiness is ever consulted, so a bin whose recipe has not run, whose file was deleted after registration, and whose spool was re-materialized at a new pin all reach the same predicate by the same route. A bare `PATH` name is not stattable without a lookup Skein does not perform, so the plan reports its runnability as unknown and lets exec fail loudly.

  The plan always resolves and always carries the recipe, because `mill bin build` needs it exactly when the predicate fails. `mill bin run` refuses when the predicate is false: `bin/not-built` for a bin declaring `:build`, naming the build command as the remedy, and `bin/not-runnable` otherwise, naming the resolved path (S8).

  Planning resolves `:executable` to its canonical absolute path. `mill` appends every user argument after the bin name without parsing, resolving, or inspecting it. Beyond the OS resolving a bare recipe command at spawn time, there is no `PATH` lookup and no shebang parsing in Skein. If the kernel cannot start the file or its declared interpreter, exec fails loudly with the host error.

- **PROP-Sbn-001.S4 (`mill bin` verb family):** `mill` gains three verbs:

  ```
  mill bin list                    # list declared bins, owners, docs, executable paths, and build recipes
  mill bin build <bin>             # run the declared :build argv where the form was declared
  mill bin run <bin> [args...]     # resolve a plan and execve into it
  ```

  `list` emits JSON machine output per SPEC-002.C4 and TEN-001. `build` spawns the recipe as a child process with the declaring file's directory as cwd and the caller's environment plus the S7 overlay, leaves its stdio on the caller's terminal, waits, and reports the exit in a JSON envelope. It runs whenever asked — no stamp, no lock, no staleness check; re-running is both the upgrade path and the recovery path. A recipe process that cannot be started at all fails `bin/build-start-failed` with the host cause (S8). `run` emits nothing after a successful exec because mill is gone and the bin owns the stream. It emits a JSON failure envelope only when it never reaches exec.

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
   "runnable": false,
   "exec": {"path": "/Users/ct/.cache/skein/spools/603fa7b8…/bin/kanban-dash",
            "env": {"SKEIN_WORKSPACE": "/Users/ct/dev/projects/skein-src/.skein"}},
   "build": {"argv": ["bun", "install", "--cwd", "../../../../scripts/kanban-export",
                      "--frozen-lockfile"],
             "cwd":  "/Users/ct/.cache/skein/spools/603fa7b8…/src/ct/spools/kanban"}}
  ```

  `runnable` is the S3 predicate's result at resolution time; the plan resolves whether or not it holds, so `mill bin build` always has the recipe. It is `mill bin run` that acts on a false value.

  Mill already owns the caller's cwd and complete environment, so the plan does not echo them. For `run`, mill leaves cwd unchanged and constructs argv as the resolved path followed by the user-supplied arguments. `build` appears only when the declaration carries a recipe; `mill bin build` runs its `argv` with its `cwd` as working directory. In both cases the plan's `env` is only an overlay. `SKEIN_WORKSPACE` identifies the selected workspace and is sufficient for a bin to address its weaver through the ordinary client path.

  `mill bin` dials its mill daemon and relays through the existing invoke path (`cli/cmd/mill/forward.go:21`). Because the client may hold both mill-daemon and relayed-weaver connections, S6 closes every non-stdio descriptor it owns before exec.

- **PROP-Sbn-001.S8 (failure modes):** The surface fails loudly at lookup, planning, relay, build, and exec. Registration is not among them: a declaration that parses is published, and a malformed `defbin` form throws during module evaluation and is reported on that channel (S3). Existing owner-registry collision and override errors apply unchanged during publication.

  | Outcome | Raised when |
  | ------- | ----------- |
  | `bin/unknown` | No active declaration carries the requested name. |
  | `bin/not-built` | `mill bin run` on a bin declaring `:build` whose plan reports the S3 runnable predicate false; the error names `mill bin build <bin>` as the remedy. |
  | `bin/not-runnable` | `mill bin run` on a bin declaring no `:build` whose plan reports the same predicate false; the error names the resolved path. |
  | `bin/no-build-recipe` | `mill bin build` named a bin that declares no `:build`. |
  | `bin/build-start-failed` | The recipe process could not be started — a bare command absent from the caller's `PATH`, or an unstartable path; the error preserves the recipe argv and host cause. |
  | `bin/build-failed` | The recipe started and exited non-zero; the error preserves the exit code. The recipe's own output is the diagnosis, and re-running the build after acting on it is the recovery. |
  | `mill/no-selected-weaver` | No live weaver answers the list or plan request; this reuses mill's existing outcome. |
  | `bin/exec-failed` | Mill resolves a valid plan but the host refuses exec; the error preserves the path and host cause. |

  Skein does not preflight interpreters or dependencies. Those failures remain normal executable failures, or the script may check them and print its own remedy.

- **PROP-Sbn-001.S9 (spec and contract accounting):** SPEC-002.P1 currently draws the mill/strand line as "`mill` owns everything that must work without a running weaver; `strand` is a pure dispatcher for everything that needs one." `mill bin` requires a live weaver, so SPEC-002 changes that principle to distinguish privileged client-side behavior from pure relay. SPEC-002 also owns the three verbs, flag cutover, exec behavior, `build`'s child-process contract and result envelope, outcomes, typed Go decoding of the plan, and `SKEIN_WORKSPACE` child-environment contract. SPEC-004 owns the core `:bins` kind, the `bins` read protocol, path resolution against the declaring file, and the named consulted specs for the list and plan result shapes. SPEC-003 owns `defbin` as the sixth public form in `skein.api.skein.alpha`.

  Adding a public Var to that namespace needs its own TEN-004 justification, and PROP-Auf-001.AC1 does not supply it: AC1 binds core and shipped domain kinds alike, and accepts a documented factory-backed batch form in place of a named form, so it settles neither where `:bins` lives nor that it needs a macro. The justification is the one S1 states — `:bins` is Skein's own kind, sitting beside `:ops`, `:queries`, `:patterns`, `:hooks`, and `:events`, rather than a domain vocabulary some spool owns. Its consequence is what makes the choice load-bearing: the domain route would put the kind in a spool, and every executable-shipping spool would then take a hard dependency and a module-ordering edge on that owner merely to declare a bin, exactly as `.skein/nvd_scan.clj:180` does for cron's `defjob`. That would leave a core `mill` verb family depending on a deletable spool (SPEC-004.C50a). Once the kind is core, the form is core with it, and AC1 then requires that the form be shipped API surface rather than repository-local `.skein` code.

  The form's option grammar and declaration constructor stay internal in `skein.core.contribution`, and the kind's kernel `:entry-spec` stays permissive, matching the five existing core kinds. `test/skein/api/skein_test.clj` pins that namespace's publics to exactly the five shipped forms, so the feature amends that assertion deliberately rather than discovering it. The alpha-surface index publishes the form and the result spec names; `make api-docs` regenerates `docs/api/skein.api.md` from the `defbin` docstring. The spool authoring guide gains bins in its "Author contributions with kind-specific forms" section, explains how to ship an executable wrapper and consume `SKEIN_WORKSPACE`, and `devflow/UBIQUITOUS-LANGUAGE.md` gains the word "bin".

- **PROP-Sbn-001.S10 (demonstration):** The kanban dashboard moves from `scripts/agent-dash/` into `kanban.spool` as an executable `bin/kanban-dash`, joining the renderer whose `package.json` and `bun.lock` already live under `scripts/kanban-export/`. Its recipe carries that directory itself, through Bun's own `--cwd`, because `:build` has no cwd field and runs where the declaring file sits. This downstream move demonstrates the surface but is not an acceptance gate for this repository.

- **PROP-Sbn-001.S11 (validation):** Clojure tests prove `defbin` collects the normalized partition through the production module path and that the handlers validate list and plan results against their named specs. The form's own coverage matches the other five core forms and the contribution acceptance conditions it inherits: expansion and collection, malformed options failing during evaluation of the form, `:override? true` producing the override intent a bare redeclaration correctly refuses (AC2), removal by omission after a form is deleted from source (AC4), and image replay producing a partition equal to source collection (AC3) — including AC9's two failure cases, an image namespace with no retained record and an explicitly recorded empty one. Beyond the form, tests cover two independent non-batteries declarations, an empty registry, collision overrides, and each of S2's four resolutions: a bare name left to `PATH` with unknown runnability, `./` and `../` resolved against the declaring file's directory including a path leaving the spool root, `~` expansion, and an absolute path taken as given. Plan tests prove a declaration is published whatever its path leads to, and that the runnable predicate reports false for absent, non-regular, and non-executable files while the plan still resolves and still carries the recipe. Go tests cover typed rejection of malformed plans, list relay, flag cutover after `<bin>`, plan argument appending, cwd preservation, caller-environment inheritance plus `SKEIN_WORKSPACE` overlay, descriptor cleanup, and loud exec failure with the path and host cause; for `build`, running the recipe with the declaring file's directory as cwd and the caller's environment, the JSON exit envelope, `bin/build-start-failed` preserving the recipe argv and host cause, `bin/build-failed` preserving a non-zero exit, and `bin/no-build-recipe`; and end to end, that a freshly materialized spool's unrunnable build-bin yields `bin/not-built` from `run`, a successful `mill bin build` from that same plan, and a subsequent runnable plan. A manual terminal check covers raw mode, resize, alt-screen, and Ctrl-C.

### PROP-Sbn-001.EX1 A consumer runs a shipped dashboard

The consumer has kanban pinned by sha and has never cloned it:

```console
$ mill bin list
{"operation":"bins list","bins":[
  {"name":"kanban-dash",
   "spool":"codethread/kanban",
   "doc":"Interactive kanban board TUI over the live workspace.",
   "executable":"/Users/ct/.cache/skein/spools/603fa7b8…/bin/kanban-dash",
   "build":["bun","install","--cwd","../../../../scripts/kanban-export","--frozen-lockfile"]}
]}

$ mill bin run kanban-dash
{"operation":"bin run","error":"bin/not-built",
 "bin":"kanban-dash","remedy":"mill bin build kanban-dash"}

$ mill bin build kanban-dash
{"operation":"bins build","bin":"kanban-dash","exit":0,"elapsed-ms":4130}

$ mill bin run kanban-dash
# full-screen board in this terminal; mill is gone
```

Nothing recorded that the build happened; the S3 runnable predicate is the only build state Skein reads. Bumping the pin materializes a fresh root whose executable is absent again, so `run` refuses with the same remedy. Re-running `mill bin build` after any doubt is always safe for the recipes in scope — ordinary idempotent installers — and where one is not, that is the author's contract with their users, not Skein's.

### PROP-Sbn-001.EX2 An existing wrapper becomes reachable unchanged

`bin/strand-harness` already reads `SKEIN_WORKSPACE`, supervises its launcher, and reports `_started` and `_finished`. Its whole declaration is one form in the namespace that owns it:

```clojure
(skein/defbin strand-harness
  "Open a coding agent in this terminal as a tracked interactive run."
  {:executable "../../../../bin/strand-harness"})
```

```console
$ mill bin run strand-harness claude --cwd ~/dev/projects/skein-src
```

Mill preserves the directory from which the operator invoked it and passes the trailing arguments unchanged. The spool's README loses its manual `PATH` installation step.

## PROP-Sbn-001.P5 Resolved questions

- **PROP-Sbn-001.Q1:** A spool author declares whichever executables they want consumers to see. Only entries declared with `defbin` appear in `mill bin list`; no visibility marker is needed.
- **PROP-Sbn-001.Q2:** `SKEIN_WORKSPACE` is sufficient caller context. It identifies the selected world and therefore the weaver a bin should address through the ordinary client path. The overlay carries no run id, weaver id, or peer name.
- **PROP-Sbn-001.Q3:** Bins are available only from activated modules, because a `defbin` form collects only while its module source is evaluated. If a module is inactive or fails to activate, its bins are absent along with its ops. This follows the current activation model and needs no workaround.
- **PROP-Sbn-001.Q4:** Why no completion stamp or build lock: the S3 runnable predicate, evaluated at plan time, is the only build state Skein consults. A half-finished build either leaves the predicate failing (`run` keeps refusing with the remedy) or produces a runnable but broken program (the bin fails loudly in the user's terminal). Both are visible, and both are fixed by re-running the build command. Concurrent builds in one root race benignly for idempotent installers; the loser re-runs. A stamp-and-lock design was written in full (`46b19a1e`) and cut deliberately — it pulled in stamp identity, external state keyed by root and recipe, a cross-process protocol needing a spec owner, and cache GC, all to answer questions "re-run the build" already answers.

## PROP-Sbn-001.P6 Deferred work

- **PROP-Sbn-001.D1 (`PATH`-level shims):** A future `mill bin install <bin>` could write a wrapper into `$XDG_BIN_HOME`. It must call `mill bin run`, never symlink into a resolved spool root, so it survives pin changes.
- **PROP-Sbn-001.D2 (bin-aware discovery):** Whether bins belong in `strand help`, `mill bin about <name>`, or nowhere can wait for real consumers.
- **PROP-Sbn-001.D3 (hidden ops):** Names such as `_started` and `_finished` are parsed and listed like every other op; `_` is only a pseudo-private convention. A hidden or internal op contract affects the shared op and discovery surfaces and belongs in separate work.
- **PROP-Sbn-001.D4 (build verification and staleness):** A declared `:test` recipe, completion stamps, and pin-bump staleness guarantees were designed (`46b19a1e`) and deliberately cut per the P3 stance. They return only if bookkeeping-free re-running proves insufficient for real consumers.
