# Brief: test suite structure and the consumer testing contract (`rrczv`)

Epic `rrczv`. Completed foundation `jdbsl`; planned features `ga2sh`, `ijka8`, `uoyeu`, and `whu7e`. Exploration evidence is recorded on tasks `96sp4`, `gs3c3`, `nyhmg`, and `mqbrh` beneath card `zumh0`.

## Ask

Finish organising Skein's tests around clear ownership, and add the missing binding contract for consumers who test code against Skein or write tests for their own spools.

The repository already has a practical guide at `docs/spools/testing.md` and shipped test support in `skein.test.alpha`. The binding behavior is scattered across `SPEC-003`, `SPEC-004`, `SPEC-005`, implemented RFCs, and source. There is no root testing spec that tells a consumer which testing surfaces Skein promises to preserve or what those surfaces prove.

## Contract boundary

Add `devflow/specs/testing.md` as the consumer-facing testing contract. Register it in the root-spec index in `devflow/README.md`. Existing component specs remain authoritative for the detailed runtime and API behavior they own; the testing spec names the supported composition and links to those clauses instead of copying their implementation detail.

The testing spec binds:

- how an external project selects a Skein checkout as a test dependency;
- the three supported tiers: ordinary pure tests, direct tests against blessed `skein.api.*.alpha` namespaces, and disposable weaver-world integration tests through `skein.test.alpha`; direct calls pass an explicit runtime when the API requires one;
- `skein.test.alpha` as the shipped author-side testing surface, including isolated unpublished runtimes, fixture-authored worlds, explicit storage modes, weaver-routed evaluation, deterministic clock control, quiescence, and checkout-root resolution;
- the boundary between the test JVM classpath and code acquired and loaded by the weaver;
- production-faithful spool activation through approved roots and module declarations, including the cases that tier-two tests cannot prove;
- isolation and cleanup: generated short roots, no ambient runtime publication, no use of a consumer's normal workspace, and runtime shutdown before fixture deletion;
- deliberate version selection in CI by tag or full commit SHA;
- exclusions: no promised runner, assertion DSL, strand/query wrappers, CLI subprocess harness, repo-internal helper namespace, or compatibility promise for Skein's own test file layout.

`skein.spools.test-support` remains repository-internal. Work on `ga2sh` may consolidate Skein's own fixtures there, but it must not become part of the consumer contract or a dependency of the shipped default behavior in `skein.test.alpha`.

## Implementation features

- `jdbsl` keeps workspace-owned `.skein` tests under `test/skein/ct` and enforces that boundary. It is complete and landing separately.
- `ga2sh` consolidates repository-only temp, cleanup, Git, polling, and runtime fixtures while restoring the shipped test API's classpath isolation.
- `ijka8` splits all 137 tests in `weaver_test.clj` into nine behavior-owned suites. Shared fixtures reuse the existing support surfaces; no third shared helper namespace is added.
- `uoyeu` gives scheduler and Cron tests one layer-plus-role directory scheme while preserving their six distinct coverage boundaries.
- `whu7e` keeps `test/skein/api` as public-contract pins and moves deeper behavior to core or named integration suites. Its quality check governs Skein's repository, not downstream test code.

These file moves, namespace names, runner groups, and repository quality checks are implementation policy. They do not belong in the consumer testing spec.

## Done when

- `devflow/specs/testing.md` states the supported downstream testing model and its exclusions without turning the existing guide into a second spec.
- `devflow/README.md` lists the testing spec with the other canonical root specs.
- The spec points to the owning clauses for runtime publication, test helpers, deterministic time, and blessed alpha surfaces; it does not duplicate or silently broaden them.
- The four implementation features retain their detailed card contracts and can land independently under epic `rrczv`.
- Any public behavior changed while implementing those cards updates the testing spec and guide together; internal-only reorganisations do not create spec churn.
- `make docs-check` passes, and the docs-style review finds no generated-prose tells or hard-wrapped new Markdown paragraphs.
