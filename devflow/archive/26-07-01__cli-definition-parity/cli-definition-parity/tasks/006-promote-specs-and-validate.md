# Promote specs and validate

**Document ID:** `CDP-TASK-006`

## CDP-TASK-006.P1 Scope

Type: AFK

Merge the three feature spec deltas into the root specs, update the devflow index, and run full validation so the feature is ready for finish/archive.

References:

- [Plan](../cli-definition-parity.plan.md) `CDP-PLAN-001.PH6`
- [CLI delta](../specs/cli.delta.md), [REPL API delta](../specs/repl-api.delta.md), [Weaver runtime delta](../specs/daemon-runtime.delta.md)

## CDP-TASK-006.P2 Implementation notes

- Merge durable contract changes into the root specs, preserving existing clause IDs and appending rather than renumbering:
    - `devflow/specs/cli.md`: command tree, replacement SPEC-002.C13 text, new query introspection contracts, SPEC-002.C24 read-only list, help-symmetry language, SPEC-002.C22 weave↔batch framing, Deferred wording (CDP-DELTA-001).
    - `devflow/specs/repl-api.md`: `query-explain` in the helper list plus its contract beside SPEC-003.C11 (CDP-DELTA-002).
    - `devflow/specs/daemon-runtime.md`: SPEC-004.C16 API operations, SPEC-004.C26 allowlist, narrowed SPEC-004.C27, query introspection contracts under SPEC-004.P8, hook non-gating note, batch-engine framing (CDP-DELTA-003).
- Set each feature delta's Status to Merged and bump Last Updated on the touched root specs.
- Update `devflow/README.md` if spec index descriptions or the active-features line for `cli-definition-parity` need it.
- Confirm implementation matches the merged spec text before merging; fix text, not behavior, if wording drifted during implementation.

## CDP-TASK-006.P3 Done when

- Root specs contain the merged contracts; deltas are marked Merged.
- All validation commands pass and the worktree shows no generated artifacts.

## CDP-TASK-006.P4 Validation

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
(cd cli && go test ./...)
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
git status --short
```
