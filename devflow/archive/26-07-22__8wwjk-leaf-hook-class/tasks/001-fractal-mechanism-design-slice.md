# Task 1: Accretive fractal mechanism + applied spool op (design slice)

**Document ID:** `TASK-Lhc-001`

## TASK-Lhc-001.P1 Scope

Type: AFK

Implement the arity-N fractal subcommand mechanism and per-leaf class support **accretively** (new shape accepted and preferred; existing op-level classes and defaults still tolerated — the flip is Task 5), and apply it end-to-end to one op: batteries `spool` folds `spool-status` in as its `status` read leaf. This is the design-authority slice: DELTA-Lhc-001/002/003 are the binding contract, and you may amend a delta only with concrete evidence from this seam (note any amendment in the plan's Developer Notes).

## TASK-Lhc-001.P2 Must implement exactly

- **TASK-Lhc-001.MI1:** Recursive arg-spec structural validation per DELTA-Lhc-001.CC1: leaf = node without `:subcommands` (doc-only leaves valid), interior nodes may not carry `:flags`/`:positionals`, empty `:subcommands {}` invalid, reserved names rejected at every level. Node-level `:hook-class`/ `:deadline-class` accepted on leaves per DELTA-Lhc-001.CC2 and **rejected on interior nodes** (that rejection is already assertive); absence is tolerated in this slice.
- **TASK-Lhc-001.MI2:** Recursive parse routing per DELTA-Lhc-001.CC3: `:subcommand` is always the full path **vector**; canonical error context (`:op`, `:path`, `:token`, `:available`) on missing/unknown tokens; payload refs and `:parse` work at every depth. `explain` renders nested subcommands recursively.
- **TASK-Lhc-001.MI3:** Returns mirror-recursion per DELTA-Lhc-001.CC4 in `skein.api.return-shape.alpha` and the op-entry alignment checks; `check!`/ `check-op-return!` take path contexts (DELTA-Lhc-001.CC7).
- **TASK-Lhc-001.MI4:** Leaf resolution at the socket per DELTA-Lhc-002.CC3/CC4, accretive: walk argv to the leaf; use leaf classes when declared, else fall back to the op-entry classes (current behavior). Missing/unknown verb of a subcommand op fails loudly pre-hook with the canonical context. Deadline default from the leaf when declared (envelope timeout still wins).
- **TASK-Lhc-001.MI5:** Help projection per DELTA-Lhc-003.CC1/CC2 and DELTA-Lhc-001.CC5/CC6: node keys `hook-class`/`deadline-class` (leaf values — from node metadata or, this slice only, the op-entry fallback; `null` on interior/subcommand-root nodes), envelope `operation` drops both keys, `schema-version` bumps, verb-path slicing to any depth and to interior nodes, catalog rule per DELTA-Lhc-003.CC1. Trailing `--help` rewrite composes with deep paths per DELTA-Lhc-002.CC6. Update the batteries reference help transform for the new envelope/node shape.
- **TASK-Lhc-001.MI6:** Dispatch label from the path vector joined with spaces (SPEC-004.C63b amendment in DELTA-Lhc-002.CC5); keep the `:action` amendment working until Task 5 removes it. Recursive annotation/glossary collection per DELTA-Lhc-001.CC5 for the direct registration route.
- **TASK-Lhc-001.MI7:** Applied op: batteries `spool` gains `status` (read leaf, offline contract verbatim per DELTA-Lhc-001.CC8); `spool-status` op removed; all `spool` leaves declare both classes in the arg-spec. Its returns tree, tests, and the smoke references to `spool-status` update with it.
- **TASK-Lhc-001.MI8:** Synthetic depth-3 grammar fixture in the test tier proving: composition from flat `def`ed node blocks, parse path vectors, per leaf classes, deep help slicing, deep returns routing, deep glossary refs.

## TASK-Lhc-001.P3 Done when

- **TASK-Lhc-001.DW1:** Cold `clojure -M:test` green on the owned/extended test namespaces: cli parser/validation, return-shape, op-entry/registry, weaver help, socket integration (pre-hook verb failures; read-leaf hook skipping; leaf deadline), batteries owner tests, test/alpha.
- **TASK-Lhc-001.DW2:** `clojure -M:smoke` and `(cd cli && go test ./...)` green; `make api-docs` re-run when docstrings changed (regenerated files committed); `make fmt-check lint reflect-check docs-check` green; clean `git status --short`.
- **TASK-Lhc-001.DW3:** Any delta amendments recorded in the delta file(s) and the plan's Developer Notes.

## TASK-Lhc-001.P4 Out of scope

- **TASK-Lhc-001.OS1:** Enforcement (mandatory classes, forbidding op-level classes beside an arg-spec, publication-seam strictness) — Task 5.
- **TASK-Lhc-001.OS2:** Sweeping any registrant other than the `spool` op — Tasks 2–4. Do not touch `.skein/`, guild, text-search, workflow/chime/cron, or batteries ops other than `spool`/`spool-status` (renderer excepted per MI5).
- **TASK-Lhc-001.OS3:** Root spec promotion and prose docs — Task 6.

## TASK-Lhc-001.P5 File ownership (exclusive only while Task 1 is open)

Ownership transfers when Task 1 closes: Task 2 then owns the batteries op definitions + owner tests, Task 3 the `register-built-in-ops!` declaration block, Task 4 the fixtures it names. The smoke suite is Task 1-owned (this slice changes the envelope and the `spool` surface) and stays green here.

`src/skein/api/cli/alpha.clj`, `src/skein/api/cli/internal/validation.clj`, `src/skein/api/cli/internal/help.clj`, `src/skein/api/return_shape/alpha.clj`, `src/skein/api/weaver/internal/op_entry.clj`, `src/skein/api/weaver/alpha.clj`, `src/skein/core/weaver/socket.clj`, `src/skein/core/weaver/help.clj`, `src/skein/test/alpha.clj`, `spools/batteries/src/skein/spools/batteries.clj` (spool op + renderer regions), and their co-located test namespaces.

## TASK-Lhc-001.P6 References

- Plan: [../8wwjk-leaf-hook-class.plan.md](../8wwjk-leaf-hook-class.plan.md) (PH1)
- Deltas: [repl-api](../specs/repl-api.delta.md), [daemon-runtime](../specs/daemon-runtime.delta.md), [cli](../specs/cli.delta.md)
