# Document library author testing

## TASK-005.P1 Scope

Type: AFK

Add `docs/library-authoring.md` documenting the first-class library author experience: writing normal Clojure libraries, testing pure code, requiring Skein namespaces through a selected checkout, and running weaver-world integration tests with `skein.test.alpha`.

References:

- [Plan](../library-author-testing-support.plan.md) `LAT-PLAN-001.PH5`
- [Proposal](../proposal.md)
- [RFC-005](../rfcs/2026-06-26-library-author-testing.md)
- [Classpath spike](../spikes/2026-06-26-library-author-classpath.md)
- [API shape spike](../spikes/2026-06-26-atom-test-alpha-api.md)

## TASK-005.P2 Implementation notes

- Create `docs/library-authoring.md`.
- Cover:
    - recommended library repo shape
    - `deps.edn` examples with Skein as a local-root test dependency
    - pure Clojure tests
    - author test-JVM tests requiring Skein namespaces
    - weaver-world tests using `skein.test.alpha`
    - classpath boundary between author test JVM and weaver JVM/library classloader
    - `spools.edn`, `skein.api.runtime.alpha/sync!`, and `skein.api.runtime.alpha/use!` test workflow
    - `:storage :sqlite-file` versus `:storage :sqlite-memory`
    - short temp path guidance for Unix socket limits
    - CI checkout/pinning against a selected Skein commit/tag
    - publishing/versioning expectations via Git commits/tags without implying a package registry
- Include copyable examples but avoid duplicating full root specs.
- Update `README.md` or `docs/getting-started.md` only if there is an existing docs index/entry point that should link to library authoring.

## TASK-005.P3 Done when

- `docs/library-authoring.md` gives an external author enough information to set up tests against a selected Skein checkout.
- The docs distinguish test-JVM `require` from weaver-side `sync!`/`use!`.
- Examples use `skein.test.alpha` and do not rely on the user's default `~/.config/skein` world.
- The docs do not imply package registry, installer, lockfile, or CLI package commands.

## TASK-005.P4 Validation

Run a docs sanity check by executing at least one documented command or fixture if practical. At minimum run relevant tests affected by docs examples:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

If examples are not executable as written in this task, record why in the plan Developer Notes.
