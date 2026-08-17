# Task 7: Shipped reference-spool declarations

**Document ID:** `TASK-Dcr-007`

## TASK-Dcr-007.P1 Scope

Type: AFK

Execution seat: `sol-low`

Implement `PLAN-Dcr-001.PH7`: declare and cover text-search, roster, and guild op returns.

Dispatch precondition: do not start until the coordinator confirms `uson2-cli-style-guide` has landed on main and this branch has been rebased onto that main. Capture post-dispatch `:operation` where the landed rule applies.

Owned files:

- `spools/text-search/src/skein/spools/text_search.clj`
- `spools/roster/src/skein/spools/roster.clj`
- `spools/guild/src/skein/spools/guild.clj`
- `test/skein/spools/text_search_test.clj`
- `test/skein/roster_test.clj`
- `test/skein/guild_test.clj`
- generated spool API references if public docstrings change

## TASK-Dcr-007.P2 Must implement exactly

- **TASK-Dcr-007.MI1:** Declare text-search `search`, roster and every roster subcommand, `guild.describe`, and guild-declared test ops with actual post-dispatch return cases.
- **TASK-Dcr-007.MI2:** Extend Guild's authoring API to accept and pass return declarations for dynamic ops through the shared registry metadata route. Do not add a predicate or another schema language.
- **TASK-Dcr-007.MI3:** In each owner suite, enumerate production ops by provenance, fail on missing declarations, derive required leaves from declarations, and check one successful captured value per leaf.
- **TASK-Dcr-007.MI4:** Use closed or deliberately open shapes per subcommand. Keep `:json` only at genuinely dynamic leaves.

## TASK-Dcr-007.P3 Done when

- **TASK-Dcr-007.DW1:** Every owned production return leaf is declared and checked, including every roster case and Guild's dynamic declaration path.
- **TASK-Dcr-007.DW2:** Cold focused gate passes: `clojure -M:test skein.spools.text-search-test skein.roster-test skein.guild-test`.
- **TASK-Dcr-007.DW3:** `make fmt-check lint reflect-check` passes. If public docstrings change, `make api-docs` and `make docs-check` also pass and generated changes are committed.

## TASK-Dcr-007.P4 Out of scope

- **TASK-Dcr-007.OS1:** Dispatch-boundary edits, declarations for batteries or repo-local spools, predicate escape hatches, unions beyond scalar nullability, or runtime output checking.

## TASK-Dcr-007.P5 Commit policy

- One atomic conventional commit, authored with a HEREDOC message. Commit only owned files. Do not amend, push, or land.

## TASK-Dcr-007.P6 References

- **TASK-Dcr-007.REF1:** `PLAN-Dcr-001.A4/A7`, `PH7`, `V2`, `R1/R3/R4`.
- **TASK-Dcr-007.REF2:** `DELTA-Dcr-repl-001.CC4/CC7`.
- **TASK-Dcr-007.REF3:** The three owned spool source files and owner suites listed above.
