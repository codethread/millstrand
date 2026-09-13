# Task 3: Blessed runtime.alpha/reload-spool! verb

**Document ID:** `TASK-shr-003`

## TASK-shr-003.P1 Scope

Type: AFK

Add the thin blessed verb `skein.api.runtime.alpha/reload-spool!` over the Task 1/2 core seam: validate `coord` is a symbol, delegate the mechanics, return the data-first result map. Its docstring names the gap it fills so `make api-docs` publishes the distinction, and the api-docs are regenerated.

## TASK-shr-003.P2 Must implement exactly

- **TASK-shr-003.MI1:** New fn `reload-spool!` in `src/skein/api/runtime/alpha.clj` with signature `[runtime coord]` — explicit runtime first (SPEC-003.C18), `coord` a `spools.edn` coordinate symbol. It validates that `coord` is a symbol (failing loudly on invalid input) and delegates the mechanics to `skein.core.weaver.spool-sync/reload-synced-spool!`, exactly as `sync!`→`sync-approved-spools` (`alpha.clj:20`) and `reload!`→`reload-config!` (`alpha.clj:30`) delegate. The fn stays thin: no load-file/classloader mechanics in the alpha tier.
- **TASK-shr-003.MI2:** It returns the core seam's data-first result map (coordinate, resolved canonical root, namespaces in reload order with source files). The mechanism (load-file under the spool classloader) is NEVER named in the signature, docstring behavior, or the return map's meaning — only the coordinate identity and "make its latest synced source live" promise (`DELTA-shr-001.D1`, `PROP-shr-001.C5` forward-compat).
- **TASK-shr-003.MI3:** The docstring names the gap it fills: neither `runtime/reload!` (re-runs startup files but does not unload already-loaded namespaces/vars) nor a bare `(require ns :reload)` (classloader-blind to per-spool synced roots) makes updated synced spool code live; `reload-spool!` does. Note that it reloads code only and leaves re-registration to the caller (`DELTA-shr-001.CC3`).
- **TASK-shr-003.MI4:** Run `make api-docs` and commit the regenerated `spools/*.api.md` / `docs/api/*.api.md`; the only diff must be the new `runtime.alpha/reload-spool!` entry.
- **TASK-shr-003.MI5:** Add cold-focused tests in `test/skein/spools_test.clj`: symbol-guard coverage (a non-symbol `coord` fails loudly) and the keystone reload exercised _through the blessed `runtime/reload-spool!`_ over the `:publish? false` `with-runtime` harness (the runtime is passed explicitly; no ambient singleton is read).

## TASK-shr-003.P3 Done when

- **TASK-shr-003.DW1:** `clojure -M:test skein.spools-test` passes cold, including the symbol-guard and through-the-blessed-fn keystone tests.
- **TASK-shr-003.DW2:** `make fmt-check lint reflect-check` reports zero findings.
- **TASK-shr-003.DW3:** `make api-docs` regenerates clean, and `git status --short` shows only the intended `alpha.clj`, `spools_test.clj`, and `*.api.md` changes (no generated SQLite/runtime artifacts).

## TASK-shr-003.P4 Out of scope

- **TASK-shr-003.OS1:** Any CLI op — hot-reload is a trusted runtime/REPL workflow (PROP-shr-001.NG4).
- **TASK-shr-003.OS2:** Composing `reload!` inside `reload-spool!` — re-registration is left to the caller (PROP-shr-001.C4/DL2); `reload-spool!` does not call `reload!` and `reload!` does not call it.
- **TASK-shr-003.OS3:** Any `alpha-surface.md` / `daemon-runtime.md` spec change (CM2/CM3) or the DELTA-shr-001 → repl-api.md merge (a land-time promotion step).
- **TASK-shr-003.OS4:** The pickup-ladder guidance edits (Task 4).

## TASK-shr-003.P5 References

- **TASK-shr-003.REF1:** `PLAN-shr-001.PH3` (blessed alpha verb), `PLAN-shr-001.A1`/`A5`, `PLAN-shr-001.AA2`/`AA8`, `PLAN-shr-001.TC2` — [../spool-hot-reload.plan.md](../spool-hot-reload.plan.md).
- **TASK-shr-003.REF2:** `PROP-shr-001.C1` (signature/placement), `PROP-shr-001.C4` (compose boundary), `PROP-shr-001.C5` (forward-compat) — [../proposal.md](../proposal.md).
- **TASK-shr-003.REF3:** `DELTA-shr-001.CC1`/`CC3`/`D1` (the durable contract this verb adds) — [../specs/repl-api.delta.md](../specs/repl-api.delta.md).
- **TASK-shr-003.REF4:** Source anchors — `src/skein/api/runtime/alpha.clj` (`sync!:20`, `reload!:30` as the delegation shape to copy).
- **TASK-shr-003.REF5:** Blocked by Task 2 (`TASK-shr-002`), which delivers the dependency-ordered core seam this verb delegates to.
