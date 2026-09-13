# Proposal: cut the views namespace (b8vld)

## Problem

`skein.api.views.alpha` ships a whole mechanism — a weaver-memory registry of named read-only functions — that nothing uses. The 2026-07-18 api audit found zero first-party consumers; its only live wiring is three client RPC dispatch slots, one smoke section, and tests. Under the accretion-only v1 regime (epic mrgob), stamping v1 freezes it forever.

## Decision

Cut the mechanism whole (user-approved 2026-07-18). No replacement surface: declarative graph reads are named queries; arbitrary registered read functions are ops with `:hook-class :read`, which strictly supersede views (doc, arg-spec, return shape, CLI weave, provenance, loud collision failure, deadline class, streaming). A future view mechanism re-enters as a new root namespace at zero cost.

## Scope of removal

Code:

- `src/skein/api/views/alpha.clj` — delete.
- `src/skein/core/client.clj` — drop the `:register-view!` / `:view!` / `:views` RPC slots.
- `src/skein/core/weaver/access.clj` — drop the `view-registry` accessor.
- `src/skein/core/weaver/runtime.clj` — drop the `:view-registry` atom from runtime state and its `reload!` reset.
- `dev/skein/smoke.clj` — drop the view registration/invocation/introspection assertions.
- `src/skein/api/weaver/alpha.clj` ns docstring — drop views from the domain-surface list.
- `src/skein/api/scheduler/alpha.clj` ns docstring — reword "wake-at strand attributes plus views" to name queries.
- `scripts/generate_api_docs.clj` — remove `"views"` from the api-docs namespace list (without this, `make api-docs` breaks on the deleted source).
- Tests: view blocks in `alpha_test.clj`, `client_test.clj`, `weaver_test.clj`, `spools_test.clj`. Coverage the view tests carry for non-view contracts:
    - reload-clears-registries keeps coverage through the surviving queries/events assertions in `spools_test.clj` — the view line is dropped without loss;
    - the addURL+register+invoke spool-classloader dance (`weaver_test.clj:1662-1673`) is view-specific today (the hooks test at `:1345` covers only hook resolution), so the `weaver-view-registry-operations` classloader test is rewritten as an op-based equivalent (`op!` invokes under `with-spool-classloader`, `weaver/alpha.clj:513`) rather than deleted.

Specs (root spec edits, landing before the v1 stamp):

- `alpha-surface.md` SPEC-005.C2: remove `views` from the blessed namespace list.
- `daemon-runtime.md`: remove the view registry from SPEC-004.C1, the `skein.api.views.alpha` ownership sentence from C16, view registry operations from C27 and the reload-state list, views from C51, and delete C56–C59 outright. Reword the scheduler framing ("wake-at strand attributes plus views" → named queries).
- `repl-api.md` SPEC-003: remove the views helper bullet (:72), the registration-guidance paragraph (:81), the config-require mention (:83), the view references in C59c/C59d and the reload-clears list (:133), and views from the userland unwrapped-surface list (:204).
- `cli.md`: drop "view" from the C21 activation-command exclusion and the non-public surface list — the concept no longer exists to exclude.

Docs:

- `docs/api/views.api.md` — delete; regenerate api docs (weaver/scheduler api docs pick up their source-docstring edits).
- `docs/api/README.md` — hand-authored: drop the prose mention (:8) and the views table row (:41).
- `mkdocs.yml` — drop the views.api.md nav entry.
- `docs/reference.md` — remove the views section and list mentions.
- `docs/clojure-crash-course.md` — drop the views require/alias from the example.
- `docs/spools/customisation.md` — drop the views mentions (:4, :78, :125).
- `docs/spools/writing-shared-spools.md` — drop views from the symbol-registration list (:74), reword "surfaced by a view or query" in the pull-based-timing rule (:88), and fix the migration-table row (:392).
- `src/skein/userland/alpha.clj` docstring — drop views from the unwrapped-surface list.
- `devflow/prd/runtime-transformations.md` (PRD-001, live) — drop the views goal and namespace bullets, recast the "feature view" example as a read-class registered op, and trim the view non-goal bullets that lose their referent.

## Non-goals

- No new read mechanism, no migration shim, no deprecation period (nothing to migrate).
- Historical RFCs and archived feature folders stay untouched.

## Risks

- An out-of-tree nREPL client calling the removed RPC slots: swept and closed (Go CLI, userland, sibling spools all clean); residual risk accepted by the user.
- Coverage loss for spool-classloader resolution currently exercised via the view registry test: mitigated by rewriting it as an op-based test, not deleting it.

## Validation

`clojure -M:test` (full locked suite at acceptance), `(cd cli && go test ./...)`, `clojure -M:smoke`, `make spool-suite-gate`, `make fmt-check lint reflect-check docs-check`, `make api-docs` with a clean `git status --short` after.
