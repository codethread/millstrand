# Promote specs and validate

## TASK-008.P1 Scope

Type: AFK

Finalize the feature by merging shipped contract changes from feature-local spec deltas into the canonical root specs, marking deltas merged, running full validation, and confirming no generated runtime artifacts remain.

References:

- [Plan](../library-author-testing-support.plan.md) validation and contract sections
- [Weaver runtime delta](../specs/daemon-runtime.delta.md)
- [REPL API delta](../specs/repl-api.delta.md)
- [CLI delta](../specs/cli.delta.md)
- Root specs: [Weaver Runtime](../../../specs/daemon-runtime.md), [REPL API](../../../specs/repl-api.md), [CLI Surface](../../../specs/cli.md)

## TASK-008.P2 Implementation notes

- Read the implemented code and all feature-local spec deltas before editing root specs.
- Merge shipped durable outcomes into:
    - `devflow/specs/daemon-runtime.md`
    - `devflow/specs/repl-api.md`
    - `devflow/specs/cli.md`
- Mark feature-local deltas as `Merged` after their durable content is promoted.
- Do not promote behavior that was cut or not implemented; instead record cut/deferred scope in the plan Developer Notes.
- Ensure `docs/library-authoring.md` and smoke/docs claims match the implemented behavior.
- Keep canonical smoke file-backed.
- Run full validation and clean generated artifacts.

## TASK-008.P3 Done when

- Root specs accurately describe implemented storage metadata, `skein.test.alpha`, and CLI status behavior.
- Feature-local deltas are marked `Merged` or explicitly explain unmerged/cut scope.
- Full Clojure tests, Go tests, and smoke pass, or any inability to run them is clearly recorded with reason.
- `git status --short` shows no generated SQLite databases, runtime metadata, sockets, or built CLI artifacts that should have been cleaned.

## TASK-008.P4 Validation

Run:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
git status --short
```

If validation fails, fix the failure within this task if it is caused by prior feature work. If a failure is unrelated or cannot be resolved, document it in the plan Developer Notes before stopping.
