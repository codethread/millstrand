# Brief: spool-suite-ci-gate — gate skein-src HEAD against the pinned spool suites

Kanban: card `yhqfh` (p2). Extracted by hand during the `unify-spool-classpath` kanban work (`devflow/feat/unify-spool-classpath/`), which moved `skein.spools.workflow` off Skein's main classpath into the per-spool root `spools/workflow/src`.

## Problem

Skein-src and the external spools consume each other in _opposite_ directions, and only one direction is tested:

- **skein-src → spools, at a pin.** `deps.edn:16-26` pins `io.github.codethread/devflow.spool` (`e9b28f5db61820b28a1ac1b590cc87e70c835cac`) and `io.github.codethread/kanban.spool` (`54eea43e3afc50c3f17335d297a74af8d6767704`) as `:test` git deps, kept synchronized with `.skein/spools.edn`. Skein's own suite exercises the spools at those frozen shas.
- **spools → skein-src, at HEAD.** Each spool's own `:test` alias resolves `io.skein/skein {:local/root "../skein-src"}` — the _live_ sibling checkout, not a pin. The spool suites therefore test whatever skein-src currently is.

Nothing in skein-src's CI runs the second direction, so a skein-src change can silently break a downstream spool suite. This already happened: moving `spools/workflow/src` off skein's main classpath (`unify-spool-classpath`) broke `devflow.spool`'s standalone suite, because `skein.spools.devflow` requires `skein.spools.workflow` and it was no longer on the classpath the spool's `:test` alias assembled. kanban.spool worked around it by adding `io.skein/workflow-spool {:local/root "../skein-src/spools/workflow"}` to its own `:test` alias — a fix discovered and applied by hand, with no skein-src gate that would have caught the breakage or would catch the next one.

## Deliverable

A **blocking CI job** that, on every skein-src PR and every push to main:

1. checks out `devflow.spool` and `kanban.spool` at the shas read from skein-src's own `deps.edn` `:test` `:extra-deps` (single source of truth — the shas are not duplicated into the workflow file);
2. arranges the sibling layout those spools' `:test` aliases already default to (a directory named `skein-src` beside each spool checkout, resolving to the candidate skein-src at HEAD);
3. runs each spool's `clojure -M:test`.

Both suites run in ~1–2 min, so a blocking gate is justified. The proposal also evaluates adding the same check to the land workflow's `merge-local-verify` step (`.skein/workflows.clj:357-373`), and specifies the failure UX (which spool, which pin, how to reproduce locally — TEN-003) and a single local-reproduction surface reused by CI, the land step, and manual runs (TEN-004).

## Scope

1. A blocking spool-suite job in `.github/workflows/quality.yml`, slotted beside the existing `clojure-test` / `cli-go-test` / `smoke-test` gates, with a cache strategy matching the sibling jobs.
2. Sha extraction from `deps.edn` at job time so the pins live in exactly one place.
3. Sibling-layout arrangement so each spool's default `../skein-src` local root resolves to the candidate checkout at HEAD (devflow additionally needs `../skein-src/spools/workflow` injected at run time; kanban already carries that root in its own `:test` alias at the pin).
4. A decision on whether `merge-local-verify` gains the same check, with the cost of omission stated.
5. Failure attribution and a local-reproduction recipe (a make target if warranted).

## Deliberately not built

- **No pin-bumping automation.** This gate tests HEAD against the _current_ pins; advancing the pins (Dependabot-style sha bumps) is a separate concern.
- **No changes to the spools' own repos.** `codethread/devflow.spool` and `codethread/kanban.spool` already declare the sibling-layout `:test` defaults this job relies on; this feature consumes them, it does not modify them.
- **No new coordinate grammar** and no persisted cross-repo paths; the job arranges the layout at run time.

## Acceptance

A blocking job runs both spool suites against skein-src HEAD on PR and main; the shas come solely from `deps.edn`; a red run names the failing spool, its pin, and the local reproduction command; the `merge-local-verify` decision is implemented as the proposal directs; and the existing quality gates (fmt/lint/reflect/docs, go, smoke, full suite) stay green.
