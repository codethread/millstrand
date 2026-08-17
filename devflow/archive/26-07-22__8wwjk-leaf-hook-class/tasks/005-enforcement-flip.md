# Task 5: Enforcement flip — delete defaults, strict validation everywhere

**Document ID:** `TASK-Lhc-005`

## TASK-Lhc-005.P1 Scope

Type: AFK

Remove the Task 1 scaffolding and make the DELTA contracts assertive on every registration route. After this task, nothing in-repo registers without explicit leaf classes, and `spool-suite-gate` goes red by design until the sibling spools adopt (Tasks 7–8) and pins bump (Task 9).

## TASK-Lhc-005.P2 Must implement exactly

- **TASK-Lhc-005.MI1:** Delete the class defaults and the op-level tolerance: arg-spec ops **must** declare both classes on every leaf and **must not** carry class keys in registration opts (DELTA-Lhc-002.CC1); raw-envelope ops **must** declare both in registration opts; interior-node classes stay forbidden; a leaf of a stream-class op that declares anything other than `:deadline-class :unbounded` fails loudly (DELTA-Lhc-001.CC2). Loud failures use the canonical error context.
- **TASK-Lhc-005.MI2:** Socket gate and deadline resolution read the resolved leaf for arg-spec ops (DELTA-Lhc-002.CC3/CC4) — the arg-spec-op fallback to op-entry classes is deleted; raw-envelope ops keep reading their (now mandatory) registration classes, which is their canonical source, not a fallback. Retire the dispatch label's `:action` special-case (DELTA-Lhc-002.CC5).
- **TASK-Lhc-005.MI3:** Publication goes strict per DELTA-Lhc-002.CC2 + DELTA-Lhc-001.CC5: the canonical op-entry validator (structure, classes, recursion, returns alignment) runs for every published entry at generation reconcile, and glossary-ref existence is checked after that generation's glossary contributions merge — reconcile fails loudly before the generation becomes effective. Core-registry's storage spec stays thin.
- **TASK-Lhc-005.MI4:** Help renders node classes from node metadata only (fallback removed); envelope shape already final from Task 1.
- **TASK-Lhc-005.MI5:** Assertive registration-failure coverage: missing leaf class, class beside an arg-spec, interior class, empty `:subcommands {}`, deep glossary-ref miss via publication, raw-envelope missing classes, stream leaf declaring `:standard`.
- **TASK-Lhc-005.MI6:** Sweep every remaining test-only registration the Task 4 enumeration did not own (stream-op-init, peers, glossary, test-alpha, weaver test namespaces — re-grep to prove zero tolerated-default registrations remain) and rewrite the config help golden(s) intentionally for the final envelope shape.

## TASK-Lhc-005.P3 Done when

- **TASK-Lhc-005.DW1:** Cold `clojure -M:test` green across the cli, op-entry, return-shape, weaver-help, socket, batteries, and registry-fixture test namespaces; `clojure -M:smoke` and `(cd cli && go test ./...)` green.
- **TASK-Lhc-005.DW2:** `make api-docs` when docstrings changed; `make fmt-check lint reflect-check docs-check` green; clean `git status --short`. `make spool-suite-gate` may be red (expected; note its failure list in the worklog).

## TASK-Lhc-005.P4 Out of scope

- **TASK-Lhc-005.OS1:** Root spec promotion/docs (Task 6); sibling spool edits (Tasks 7–8); pin bumps (Task 9).

## References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH3)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
