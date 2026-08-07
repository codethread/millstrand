# Brief: in-tree installer removal and test conversion (rrvnn)

Feature 4 of epic waq0l (retire spool `install!`). The user's standing brief is the epic body plus its AUTHORITY grant (recorded 2026-07-23, note lpy54): drive to completion and merge to main without human waits; automated review gates still run.

Delete all 7 in-tree spool installers (batteries, workflow, executors.shell, guild, chime, cron, unsafe-text-search) and convert every test to module activation, per ADR-003.P5 (decision C: this completes PROP-Olr-001.G9/S7) and the P7 conversion conventions.

Per-spool work:

1. **Exported base declarations.** Each spool exports its base module declaration as data (e.g. `batteries/module`); production `.millstrand/init.clj` assocs `:spools` guards onto it; tests pass the base datum with `:load :image` (the fbr4m grammar: `{:ns <sym> :load :image :contribute <qualified-sym> [:reconcile <qualified-sym>] [:after [...]] [:spools [...]]}` — `:image` requires `:ns` + explicit `:contribute`, refuses `:file`, fails loudly on an unloaded ns). One source of truth, two honest variants (ADR-003.P7).
2. **batteries:** fix reconcile to branch on contribution status per ADR-003.P6 — `:removed` must not re-register glossary outcomes; keep glossary seeding on `:applied`.
3. **guild:** export an explicit runtime-state setter for the optional fallback guild-name (install!'s optional arg has no module equivalent); keep guild_test fallback coverage.
4. **cron:** pure deletion (install! is byte-equivalent to contribute); fix its stale ":call at startup" docstring language.
5. **chime:** deletion only (engine registration moved to reconcile in 2af05d7). **unsafe-text-search:** has contribute, no reconcile — delete install!.
6. Tests-of-installer-semantics re-target the module path: batteries_test ~1034 (glossary seeding via reconcile), workflow_test ~17 (vocab ownership via reconcile).

Test conversion (28 call sites, fixtures in `test/skein/spools/test_support.clj` and per-ns fixtures like `with-batteries`): one shared test-support helper over 28 inline copies (kept in test-support unless already blessed for `skein.test.alpha` — ADR-003 did not bless promotion; keep it in test-support). Shell tests activate `:workflow` first with `:after [:workflow]` (note 4q8cg). Rider R6: classpath activation and root approval do not mix. Rider R5: audit for tests that run full `refresh!` before converting. Image activation shipped — use it and confirm the suite delta is ~neutral.

Docs/spec sweep: `make api-docs` (7 generated `spools/*.api.md` lose install! entries); prose in `spools/guild.md` ~89, `spools/unsafe-text-search.md` ~57; per ADR-003.P8 and card note tehh8, move P7's operational guidance (fixture activation rules, exported-base-declaration pattern) into `docs/spools/testing.md` and `docs/spools/writing-shared-spools.md` where it touches spool authors, linking back to the ADR. Comments/docstrings describe latest code state only (no "legacy direct callers" language). Grep gate: `rg 'install!' src spools test docs devflow .millstrand --glob '!devflow/archive/**'` returns only kanban_tracker.clj (workspace config, out of scope) and historical archive text; record the final allowlist in a card note.

Out of scope: sibling repos (9snqu, kst0n), consumer cutover/pin bumps (rtnfv), kanban_tracker.clj's config-level install!.

Done when: full locked suite green at queue acceptance (`flock -w 3600 /tmp/skein-test.lock clojure -M:test`); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check`; `git status --short` clean of generated artifacts; the rg gate matches only the recorded allowlist; canonical world picks up the `.millstrand/init.clj` change via `runtime/refresh!` (no weaver restart).
