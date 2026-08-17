# Live Weaver REPL And Runtime Loader Plan

**Document ID:** `LWRL-PLAN-001` **Feature:** `live-weaver-repl-and-runtime-loader` **Proposal:** [proposal.md](./proposal.md) **RFC:** None **Root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md) **Feature specs:** [repl-api.delta.md](./specs/repl-api.delta.md), [daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [cli.delta.md](./specs/cli.delta.md) **Status:** Shipped **Last Updated:** 2026-07-01

## LWRL-PLAN-001.P1 Goal and scope

Make the REPL match the product philosophy by attaching users directly to the live weaver process, while preserving the fixed-form Clojure client bridge for explicit client/test automation. At the same time, make the runtime loader/config helper naming honest by moving privileged `skein.libs.alpha` behavior to a core runtime helper namespace and reserving the library story for actual user/community code loaded into the weaver.

## LWRL-PLAN-001.P2 Approach

- **LWRL-PLAN-001.A1:** Treat direct live weaver REPL as the primary product path. If the CLI launches a process to speak nREPL, that process is a thin attach client only; it must not evaluate user forms locally or rebuild helper-JVM semantics.
- **LWRL-PLAN-001.A2:** Keep `skein.client/call-world` and fixed nREPL forms as an explicit lower-level client bridge for tests, tools, and compatibility. The goal is demotion, not deletion.
- **LWRL-PLAN-001.A3:** Make `skein.repl` in-process first. Helper functions should prefer `@skein.weaver.runtime/current-runtime` when present, so the same compact calls work naturally from direct weaver REPL sessions.
- **LWRL-PLAN-001.A4:** Add `skein.runtime.alpha` as the honest privileged loader/config namespace, then leave `skein.libs.alpha` as a thin compatibility alias while docs/templates move to the new name.
- **LWRL-PLAN-001.A5:** Update docs around the distinction between built-in privileged helper namespaces and genuine userland libraries. Userland libraries remain normal Clojure loaded via config, approved roots, or direct REPL evaluation.
- **LWRL-PLAN-001.A6:** Validate with real mill/weaver flows, not only mocked helper calls, because this change is about process topology.
- **LWRL-PLAN-001.A7:** Audit `src/skein/libs` and remove the mixed message: privileged loader/config behavior moves to core runtime namespaces; anything left as a library must be authorable by a user with the documented helper/API surface.

## LWRL-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| LWRL-PLAN-001.AA1 | `cli/internal/command` | Replace helper JVM `replArgs` launch path with thin nREPL attach/eval behavior for `weaver repl` and `--stdin`; keep any API-client mode explicit if retained. |
| LWRL-PLAN-001.AA2 | `cli/cmd/mill` | Ensure `weaver-repl-context` returns selected-world running status, nREPL metadata, and source only when needed to launch the thin attach client. |
| LWRL-PLAN-001.AA3 | `src/skein/repl.clj` | Make helpers work in-process against `current-runtime`; keep connected-client support for explicit client/test use. |
| LWRL-PLAN-001.AA4 | `src/skein/client.clj` | Preserve fixed-form API client bridge, but remove assumptions that it is the interactive REPL path. |
| LWRL-PLAN-001.AA5 | `src/skein/runtime/alpha.clj` or equivalent | Add new privileged loader/config helper namespace over approved-root sync, reload, use, and introspection operations. |
| LWRL-PLAN-001.AA6 | `src/skein/libs/alpha.clj` | Convert to compatibility shim or deprecating wrapper over the new runtime namespace. |
| LWRL-PLAN-001.AA7 | `src/skein/libs/*` | Audit every shipped namespace under library naming. Keep only authorable example/userland-style code there, move privileged behavior to core runtime/helper namespaces, or mark temporary compatibility shims explicitly. |
| LWRL-PLAN-001.AA8 | generated config and docs | Change new templates/examples from `skein.libs.alpha` to `skein.runtime.alpha`; clarify built-in helper vs userland library boundaries. |
| LWRL-PLAN-001.AA9 | tests/smoke | Cover direct live REPL evaluation, stdin, current-runtime introspection, helper availability, loader namespace migration, and authorable library/example behavior. |

## LWRL-PLAN-001.P4 Contract and migration impact

- **LWRL-PLAN-001.CM1:** `strand weaver repl` behavior changes materially: arbitrary forms run in the weaver JVM instead of a helper JVM. This is intended and staged in `repl-api.delta.md` and `cli.delta.md`.
- **LWRL-PLAN-001.CM2:** Existing user config requiring `skein.libs.alpha` should continue for alpha compatibility, but fresh generated config and docs use `skein.runtime.alpha`.
- **LWRL-PLAN-001.CM3:** Clojure client/test code using `skein.client/call-world` remains supported.
- **LWRL-PLAN-001.CM4:** Direct REPL users gain more authority. The docs must explicitly say blessed API helpers preserve hooks/events/validation, while arbitrary lower-level mutation is trusted and footgun-capable.
- **LWRL-PLAN-001.CM5:** No persisted data migration is needed.
- **LWRL-PLAN-001.CM6:** `skein.libs.alpha` compatibility is transitional. New code should not add privileged behavior under `skein.libs.*`; that namespace family is reserved for userland-style libraries/examples once the shim can be removed.

## LWRL-PLAN-001.P5 Implementation phases

### LWRL-PLAN-001.PH1 Direct nREPL attach spike-to-implementation

Outcome: Replace the default helper JVM launch with a thin direct attachment to the selected weaver nREPL. Interactive sessions can evaluate a simple form in the weaver JVM, and `--stdin` prints direct results from weaver evaluation. The attach client may be implemented in Clojure to reuse nREPL libraries, but it is transport/UI only.

### LWRL-PLAN-001.PH2 In-process `skein.repl` helpers

Outcome: `skein.repl` helpers work from inside the weaver process by using `@current-runtime`, while existing connected-client behavior remains available for explicit client/test contexts.

### LWRL-PLAN-001.PH3 Runtime loader namespace split

Outcome: Add `skein.runtime.alpha` with the current privileged loader/config API, convert `skein.libs.alpha` to a compatibility shim, and update internal generated config templates to use the new namespace.

### LWRL-PLAN-001.PH4 Library namespace cleanup

Outcome: Audit `src/skein/libs`. Any code that owns special loader/runtime behavior is moved under core runtime/helper namespaces or left only as an explicit compatibility shim. Remaining library/example code is authorable by users using documented APIs and helpers.

### LWRL-PLAN-001.PH5 Docs and product language

Outcome: Update README/getting-started/Skein docs/spec examples so direct REPL, built-in privileged helpers, and user/community libraries are described honestly and consistently.

### LWRL-PLAN-001.PH6 Integration validation and cleanup

Outcome: Add or update integration tests for mill-routed direct REPL attach, stdin, live process introspection, helper calls, loader namespace compatibility, and smoke coverage. Remove obsolete assumptions that default REPL uses a helper JVM.

## LWRL-PLAN-001.P6 Validation strategy

- **LWRL-PLAN-001.V1:** Clojure tests for `skein.repl` in-process helper dispatch and compatibility connected-client dispatch.
- **LWRL-PLAN-001.V2:** Go CLI tests for `weaver repl` context handling and no unnecessary source requirement for direct attach.
- **LWRL-PLAN-001.V3:** Integration test starts disposable mill/world/weaver, runs `strand weaver repl --stdin` with forms that prove evaluation is in the weaver JVM, such as inspecting `@skein.weaver.runtime/current-runtime`.
- **LWRL-PLAN-001.V4:** Tests for `skein.runtime.alpha` API parity and `skein.libs.alpha` compatibility alias.
- **LWRL-PLAN-001.V5:** Tests or docs examples showing at least one shipped library/example uses only the authorable public/trusted helper surface and does not proxy loader/config special behavior.
- **LWRL-PLAN-001.V6:** Run primary validation: `clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- **LWRL-PLAN-001.V7:** Verify `git status --short` after validation shows no generated SQLite/runtime artifacts.

## LWRL-PLAN-001.P7 Risks and open questions

- **LWRL-PLAN-001.R1:** The thin attach client may be mistaken for the old helper JVM. Mitigation: keep its namespace/API minimal, prove with tests that user forms evaluate in the weaver JVM, and avoid fixed-form API routing in the default REPL path.
- **LWRL-PLAN-001.R2:** `skein.repl` may currently assume connected-client state. Mitigation: make in-process runtime dispatch the first branch and keep client connection only as fallback.
- **LWRL-PLAN-001.R3:** Namespace rename can churn docs/tests. Mitigation: compatibility shim first; update fresh templates and docs without breaking existing config.
- **LWRL-PLAN-001.R4:** More powerful REPL increases chance of users bypassing hooks/events. Mitigation: document blessed API paths clearly; do not pretend direct trusted eval is constrained.
- **LWRL-PLAN-001.R5:** Leaving `skein.libs.alpha` as a shim can preserve the misleading story. Mitigation: mark it compatibility-only in docs and tests, forbid new privileged behavior under `skein.libs.*`, and make removal a visible follow-up if not completed in this feature.
- **LWRL-PLAN-001.Q1:** Decide during implementation whether old helper-JVM behavior deserves `--client` CLI support or should remain only as library/test APIs.

## LWRL-PLAN-001.P8 Task context

- **LWRL-PLAN-001.TC1:** Current `strand weaver repl` path is in `cli/internal/command/command.go`: it asks mill for `weaver-repl-context`, then launches `clojure -M -m skein.repl`.
- **LWRL-PLAN-001.TC2:** Current helper bridge is mostly `src/skein/client.clj`, `src/skein/repl.clj`, and repeated `call-daemon` functions in `skein.*.alpha` helper namespaces.
- **LWRL-PLAN-001.TC3:** Current mill lifecycle code returns source in `weaverReplContext`; direct attach may still need source to launch a thin Clojure nREPL attach client, but source must not become selected-world identity or local evaluation context.
- **LWRL-PLAN-001.TC4:** Current privileged loader/config behavior lives in `skein.libs.alpha` plus `skein.weaver.api` operations `approved-libs`, `sync-approved-libs`, `reload-config!`, `use!`, `uses`, and `use`.
- **LWRL-PLAN-001.TC5:** The root specs currently describe the helper JVM as the default REPL path and `skein.libs.alpha` as the runtime library workspace helper; the feature deltas intentionally replace that language.
- **LWRL-PLAN-001.TC6:** `src/skein/libs/ephemeral.clj` is closer to the desired userland-style example: it composes documented helpers and attributes/edges. Use that style as the bar for code that remains under library/example naming.

## LWRL-PLAN-001.P9 Developer Notes

### LWRL-PLAN-001.DN1 Plan creation — 2026-06-30

- Created from design review of the current mill/weaver/REPL path and user feedback that the helper JVM bridge weakens live process introspection and makes `skein.libs.alpha` look disingenuously userland. Plan marked Reviewed because the direction is settled enough to slice into implementation tasks.

### LWRL-PLAN-001.DN2 Review fixes — 2026-06-30

- Review found the REPL delta accidentally replaced the `connect!` contract, left the loader namespace ambiguous, and underspecified direct attach topology. Updated deltas and plan to preserve `connect!`, decide `skein.runtime.alpha`, and specify a thin nREPL attach client whose only job is transport/UI while all user forms evaluate in the weaver JVM.

### LWRL-PLAN-001.DN3 Library cleanup clarification — 2026-06-30

- Tightened scope after user clarification: this feature must not merely rename `skein.libs.alpha`. It must separate core-owned loader/config machinery from authorable libraries, audit `src/skein/libs`, and leave library-named code as userland-style examples or explicit temporary shims only.

### LWRL-PLAN-001.DN4 Task queue creation — 2026-06-30

- Added an AFK-ready task queue in `tasks/` with seven slices: direct stdin attach, in-process `skein.repl` dispatch, interactive attach, `skein.runtime.alpha` namespace split, library namespace cleanup proof, docs/help refresh, and end-to-end validation. Slices are ordered to produce an early live nREPL proof while preserving explicit client bridge compatibility.

### LWRL-PLAN-001.DN5 Task 1 implementation — 2026-06-30

- `strand weaver repl --stdin` now launches a thin `skein.repl --attach-stdin host port` client using mill-returned nREPL metadata and evaluates user forms in the selected weaver JVM. Existing connected-helper `--stdin` and `connect!` code remains for explicit client/test workflows until later slices reshape helper dispatch.

### LWRL-PLAN-001.DN6 Task 3 implementation — 2026-06-30

- `skein.repl` helpers now use `@skein.weaver.runtime/current-runtime` and dispatch core strand/query/relation/burn operations through `skein.weaver.api` when evaluated inside the weaver JVM without an explicit connected world. The connected client bridge remains available when `connect!` selects a world and is still exercised by tests using the explicit client path.
- `load-queries!` now checks for an active in-process runtime or connected world before reading the requested file, preserving the fail-loud no-world error instead of leaking local file errors first.

### LWRL-PLAN-001.DN7 Task 2 implementation — 2026-06-30

- Default `strand weaver repl` now launches the same thin `skein.repl` attach client topology as `--stdin`, using mill-returned nREPL host/port and evaluating user forms in the selected weaver JVM. The interactive attach client prepares the weaver-side `skein.repl` namespace with `(require 'skein.repl)` before prompting.
- Added a deterministic non-stdin attach proof that drives scripted interactive input against a disposable running weaver nREPL and verifies live runtime introspection output.

### LWRL-PLAN-001.DN8 Task 4 implementation — 2026-06-30

- Added `skein.runtime.alpha` as the privileged approved-root/config loader helper and converted `skein.libs.alpha` to a compatibility shim over it. Runtime helper tests now exercise the new namespace while retaining explicit shim coverage.
- Fresh generated `init.clj` now uses `skein.runtime.alpha`; `dev/user.clj` no longer aliases `skein.weaver.runtime` as `runtime` so the generated template can load in the default user namespace.
- Deep-review follow-up fixed generated-template reload from live REPL contexts by avoiding the `runtime` alias for `skein.weaver.runtime` in REPL/dev helper namespaces, and updated root specs to name `skein.runtime.alpha` while preserving `skein.libs.alpha` compatibility.

### LWRL-PLAN-001.DN9 Task 5 implementation — 2026-06-30

- Audited `src/skein/libs`: only the explicit `skein.libs.alpha` compatibility shim and userland-style `skein.libs.ephemeral` remain.
- Rewrote `skein.libs.ephemeral` to compose documented `skein.repl` and `skein.graph.alpha` helpers instead of carrying its own daemon/client/runtime dispatch helper.
- Added focused library tests proving the ephemeral example avoids privileged loader/config/runtime references and works through the public helper surface against an active runtime.

### LWRL-PLAN-001.DN10 Task 6 docs/help refresh — 2026-06-30

- Refreshed README, getting-started, Skein reference, crash-course examples, smoke copy, and helper namespace docstrings so default `strand weaver repl` is described as direct live nREPL attachment into the weaver JVM.
- Moved public docs examples to `skein.runtime.alpha`; remaining `skein.libs.alpha` references are compatibility/test-shim references.
- Clarified that shipped `skein.*.alpha` namespaces are privileged built-in helpers, while user/community libraries are trusted Clojure loaded via config, approved roots, or live REPL experimentation.

### LWRL-PLAN-001.DN11 Task 7 validation cleanup — 2026-06-30

- Primary validation passed: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, and `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.
- Smoke coverage now additionally proves `skein.libs.alpha` compatibility from live `strand weaver repl --stdin` while retaining direct live runtime introspection, in-process helpers, and fresh `skein.runtime.alpha` config bootstrap coverage.
- No generated SQLite, runtime metadata, sockets, temporary worlds, or built CLI artifacts remained after validation.

### LWRL-PLAN-001.DN12 Finish/archive — 2026-07-01

- Shipped live weaver REPL attachment, direct `--stdin` evaluation in the weaver JVM, in-process `skein.repl` helper dispatch, `skein.runtime.alpha` loader/config namespace with `skein.libs.alpha` compatibility, library namespace cleanup, friendly weaver names, `mill weaver list`, and the initial Neovim/Conjure integration under `integrations/neovim`.
- Promoted the staged CLI, REPL API, and Weaver Runtime deltas into the root specs through the implementation commits and marked feature-local deltas merged. No RFCs were associated with this feature.
- Validation during implementation passed for Go CLI tests and Clojure tests; smoke validation passed before the final weaver-list/Neovim integration follow-up, which added focused Go/Clojure validation and Neovim headless plugin loading checks.
