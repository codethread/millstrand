# Task 4: Entity projection and exact-copy adoption

**Document ID:** `TASK-Dcr-004`

## TASK-Dcr-004.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH4`: the canonical exact entity constructor, local adoption, kanban upstream adoption, and synchronized pin advance.

Dispatch precondition: the coordinator supplies the kanban upstream checkout/branch and target commit location before dispatch. Never patch a tools.deps/gitlibs cache.

Owned files:

- `src/skein/api/spool/alpha.clj`
- `spools/loom/src/skein/spools/loom.clj`
- `.skein/config.clj`
- `test/skein/api/spool_test.clj`
- `test/skein/spools/loom_test.clj`
- `test/skein/config_ops_test.clj` and runner inventory needed to make it focus-eligible
- `deps.edn` and `.skein/spools.edn`
- the coordinator-provided kanban upstream source and focused tests for the ten exact copies in `PROP-Dcr-001.P1`
- generated API references changed by public docstrings

## TASK-Dcr-004.P2 Must implement exactly

- **TASK-Dcr-004.MI1:** Add `skein.api.spool.alpha/entity-projection`. Fail loudly if any canonical field is missing and return exactly `:id`, `:title`, `:state`, and `:attributes`, discarding every other key.
- **TASK-Dcr-004.MI2:** Replace Loom's exact projection and use the constructor as the base for the repo kanban-tree row before its domain fields are added. Create `test/skein/config_ops_test.clj`, register `skein.config-ops-test` in the focus-eligible runner inventory, and cover the kanban-tree base projection plus the unchanged richer output. Do not narrow the final richer row.
- **TASK-Dcr-004.MI3:** In the supplied kanban upstream checkout, replace the ten exact copies identified by the proposal, including `summarize-strand`, and cover exact output plus loud missing-field failure.
- **TASK-Dcr-004.MI4:** Run the upstream kanban focused suite before advancing both synchronized kanban pins to the same tested commit. Do not change batteries or domain-specific summaries.

## TASK-Dcr-004.P3 Done when

- **TASK-Dcr-004.DW1:** Exact-key, missing-field, Loom, kanban-tree base-row, and upstream kanban adoption tests pass without narrowing richer output.
- **TASK-Dcr-004.DW2:** Cold focused gates pass: `clojure -M:test skein.api.spool-test skein.spools.loom-test skein.config-ops-test` and, in the supplied kanban checkout, `clojure -M:test ct.spools.kanban-test`.
- **TASK-Dcr-004.DW3:** After both pins advance identically, `make spool-suite-gate` passes against this checkout.
- **TASK-Dcr-004.DW4:** `make api-docs`, `make fmt-check lint reflect-check`, and `make docs-check` pass; generated API changes are committed.

## TASK-Dcr-004.P4 Out of scope

- **TASK-Dcr-004.OS1:** Batteries narrowing, agent `ps`, richer kanban summaries, return declarations, or local edits inside the gitlibs cache.

## TASK-Dcr-004.P5 Commit policy

- Commit the kanban upstream change first in its supplied checkout. Then make one atomic conventional commit in this branch, authored with a HEREDOC message, containing local adoption and both identical pins. Do not amend, push, or land.

## TASK-Dcr-004.P6 References

- **TASK-Dcr-004.REF1:** `PLAN-Dcr-001.A5`, `PH4`, `V3`, `TC5/TC6`, `R2`.
- **TASK-Dcr-004.REF2:** `PROP-Dcr-001.P1`, `P4.5`; `DELTA-Dcr-as-001.CC2`.
- **TASK-Dcr-004.REF3:** `src/skein/api/spool/alpha.clj`, `spools/loom/src/skein/spools/loom.clj`, `.skein/config.clj`, `deps.edn`, `.skein/spools.edn`.
