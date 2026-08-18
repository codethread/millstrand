# Task 1: make spool-suite-gate target (sha extract, layout, devflow injection, attribution)

**Document ID:** `TASK-ssc-001`

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-ssc-001` for v1 and `TASK-ssc-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-ssc-001.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-ssc-001.P1 Scope

Type: AFK

Build the single local-reproduction surface for the spool-suite gate: a new `spool-suite-gate` target in the repo-root `Makefile` that owns the whole recipe (PLAN-ssc-001.A1) — sha extraction from `deps.edn`, scratch sibling-layout arrangement, the devflow-only workflow-spool injection, running each spool's own suite against skein-src HEAD, and failure attribution. No CI or land wiring in this slice.

## TASK-ssc-001.P2 Must implement exactly

- **TASK-ssc-001.MI1 — Sha extraction from `deps.edn` as EDN (PLAN-ssc-001.A2).** Read the two spool coordinates from `deps.edn` `:aliases :test :extra-deps` — `io.github.codethread/devflow.spool` and `io.github.codethread/kanban.spool` — and pull each `:git/sha`. Parse `deps.edn` as EDN (e.g. a `clojure -X`/`clojure -M` one-liner or babashka), never line-grep, so a formatting change cannot silently desync the gate. Never restate a sha in the Makefile.
- **TASK-ssc-001.MI2 — Sha extraction fails loudly (PLAN-ssc-001.V1, second direction).** If either coordinate or its `:git/sha` is absent or `deps.edn` is unparseable as EDN, the target aborts with a non-zero exit and a legible message; it MUST NOT silently fall back to running against `HEAD`/an empty sha. This is a required guard, not a nicety.
- **TASK-ssc-001.MI3 — Scratch sibling layout (PLAN-ssc-001.A3).** For each spool, materialize the spool source at its pinned sha into a scratch working dir under a `mktemp -d` root (never the developer's real sibling tree), and arrange a `skein-src` entry beside it resolving to the **invoking repo checkout** at HEAD (PR head on CI, merged local main at land — resolve from the make target's own working directory, never a hard-coded PR-head assumption), matching the spools' committed `:local/root "../skein-src"` default.
- **TASK-ssc-001.MI4 — Run kanban.spool plain (PLAN-ssc-001.A4).** From the `kanban.spool` checkout, run `clojure -M:test` with no dep injection; it already carries `io.skein/workflow-spool` in its own `:test` alias.
- **TASK-ssc-001.MI5 — Run devflow.spool with the NG2-safe `-Sdeps` workflow-spool injection (PLAN-ssc-001.A4).** From the `devflow.spool` checkout, run `clojure -Sdeps '{:deps {io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}}}' -M:test`. The moved `spools/workflow/src` root is injected at job time so the devflow suite resolves `skein.spools.workflow`. Use ONLY an NG2-safe form (the `-Sdeps` dep map, or a skein-src-side/user `deps.edn`); NEVER add a test alias into `devflow.spool`'s own `deps.edn` (that edits the spool, violating PROP-ssc-001.NG2).
- **TASK-ssc-001.MI6 — Failure attribution (PLAN-ssc-001.A7).** On any red suite, fail loudly naming the spool (`devflow.spool` / `kanban.spool`), its resolved sha, and the one command that reproduces it locally (`make spool-suite-gate`, or the exact per-spool `clojure` invocation including the `-Sdeps` injection for devflow). Emit the command from the resolved values, never a hand-copied literal.
- **TASK-ssc-001.MI7 — No pin bump, no spool edit, no persisted cross-repo paths.** `deps.edn` and `.skein/spools.edn` are read-only (PROP-ssc-001.NG1); no changes to the spool repos (NG2); the layout and workflow-spool root are arranged at run time, not written into any committed config (NG3).

## TASK-ssc-001.P3 Done when

- **TASK-ssc-001.DW1 — Green on clean HEAD.** `make spool-suite-gate` exits 0 against the current branch HEAD, running both spool suites; matches the p4nbj counts (devflow ~18 tests/130 assertions, kanban ~25 tests/173 assertions). Requires Homebrew OpenJDK on PATH: `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make spool-suite-gate`.
- **TASK-ssc-001.DW2 — Red with attribution on a broken HEAD.** With a scratch/throwaway edit to skein-src HEAD that breaks a spool suite (e.g. remove or rename a namespace the devflow suite requires), `make spool-suite-gate` exits non-zero and its message names the failing spool, its resolved sha, and the local repro command. Revert the throwaway edit afterward.
- **TASK-ssc-001.DW3 — Sha extraction fails loudly (both directions of the guard).** With a temporary malformed `deps.edn` (a spool coordinate or `:git/sha` removed, or the file made unparseable), the target aborts non-zero with a legible message and does NOT run against `HEAD`/an empty sha. Revert the malformation afterward.
- **TASK-ssc-001.DW4 — Quality gates green for the changed surface.** `make fmt-check lint reflect-check` pass (the Makefile is the only changed file; no Clojure src/test namespace changes, so per PLAN-ssc-001.V2 there is no `clojure -M:test <ns...>` cold gate for this slice).
- **TASK-ssc-001.DW5 — Working tree clean.** `git status --short` shows only the intended `Makefile` change; no scratch dirs, generated SQLite, or runtime metadata artifacts left behind.

## TASK-ssc-001.P4 Out of scope

- **TASK-ssc-001.OS1:** The CI job in `.github/workflows/quality.yml` (TASK-ssc-002).
- **TASK-ssc-001.OS2:** The land `:merge-local-verify` extension in `.skein/workflows.clj` (TASK-ssc-003).
- **TASK-ssc-001.OS3:** Any pin bump in `deps.edn`/`.skein/spools.edn` (PROP-ssc-001.NG1) or any edit to the spool repos (NG2).

## TASK-ssc-001.P5 References

- **TASK-ssc-001.REF1:** Plan PLAN-ssc-001.A1–A4, A7, V1, V2, P8 (esp. TC2, TC3, TC5) — [../spool-suite-ci-gate.plan.md](../spool-suite-ci-gate.plan.md).
- **TASK-ssc-001.REF2:** Proposal PROP-ssc-001.G2–G4, S2–S5, NG1–NG3 — [../proposal.md](../proposal.md).
- **TASK-ssc-001.REF3:** Empirical red→green reproduction — task note `p4nbj` (by u0avh) on card `yhqfh`: exact red command/message, the working `-Sdeps` green command, and the kanban control counts. (The launch brief's `in1kg` id is a provenance mislabel — the note is `p4nbj`; see PLAN-ssc-001.DN2.)
- **TASK-ssc-001.REF4:** `deps.edn` `:aliases :test :extra-deps` — the two spool `:git/sha` pins and the `sync-with-.skein/spools.edn` comments (read-only source of truth).
- **TASK-ssc-001.REF5:** `Makefile` — existing target conventions (`.PHONY`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` guard used by `api-docs`).
