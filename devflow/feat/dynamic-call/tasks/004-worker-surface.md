# Task 4: Worker CLI verb and discovery

**Document ID:** `TASK-Dyc-004`
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-Dwr-001` for v1 and `TASK-Dwr-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID, for example `TASK-Dwr-001.MI1`, so references are globally grepable and do not clash across documents.

## TASK-Dyc-004.P1 Scope

Type: AFK

Expose the construct through the generic workflow worker CLI and the discovery reads.

## TASK-Dyc-004.P2 Must implement exactly

- **TASK-Dyc-004.MI1:** `workflow dispatch <run-id> --workflow <name> [--params <json>] [--step <id>]
  [--by <actor>]` per CC8, sharing the one run-result shape. Declared args, JSON param handling, and
  stdin/payload-reference parsing follow the existing `continue` verb exactly.
- **TASK-Dyc-004.MI2:** Role-scoped inference: `dispatch` infers the sole ready dispatch and fails
  `:reason :workflow/ready-dispatch-ambiguous` with the complete compatible set when more than one is
  ready. `complete`, `choose`, and `continue` each keep inferring only their own role; `continue`
  refuses a dispatch with `:reason :workflow/step-not-dispatch` and guidance naming `dispatch`.
- **TASK-Dyc-004.MI3:** `workflow show` reports a declared dispatch with point name, allowlist, and
  required entrypoint, beside declared defers and calls (CC13, discovery half).
- **TASK-Dyc-004.MI4:** Glossary entries for every new reason, in the op's declared glossary.

## TASK-Dyc-004.P3 Done when

- **TASK-Dyc-004.DW1:** A run is driven end to end from the CLI: start, complete, dispatch, complete,
  done — against a disposable `--workspace` world from `mktemp -d`, never the repo's `.skein`.
- **TASK-Dyc-004.DW2:** Ambiguity, wrong-role `--step`, and each live-resolution failure return
  structured errors with their documented reasons.
- **TASK-Dyc-004.DW3:** `workflow show` output includes the dispatch declaration.
- **TASK-Dyc-004.DW4:** `clojure -M:test skein.spools.workflow-test` cold, `clojure -M:smoke`, and
  `(cd cli && go test ./...)` all pass; `make fmt-check lint reflect-check` clean; committed.

## TASK-Dyc-004.P4 Out of scope

- **TASK-Dyc-004.OS1:** Contract docs, cookbook, README — task 5.
- **TASK-Dyc-004.OS2:** Any change to `complete`/`choose`/`continue` semantics beyond refusing a
  dispatch.

## TASK-Dyc-004.P5 References

- **TASK-Dyc-004.REF1:** `DELTA-Dyc-001` CC8, CC13.
- **TASK-Dyc-004.REF2:** `PLAN-Dyc-001` PH4, AA6, AA7.
- **TASK-Dyc-004.REF3:** `spools/workflow/src/skein/spools/workflow/cli.clj` — the `continue` verb is
  the template.
- **TASK-Dyc-004.REF4:** Disposable-workspace rule: `CLAUDE.md` hard rules. Guard every expansion with
  `${ws:?}`.
