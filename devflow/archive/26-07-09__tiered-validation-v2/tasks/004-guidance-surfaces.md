# Task 4: Guidance surfaces (AGENTS/CLAUDE/agents spool)

**Document ID:** `TASK-Ttv-004`

Feature `tiered-validation-v2`, branch `tiered-test-validation`, worktree `/Users/ct/dev/projects/skein-src__tiered-test-validation`. Work only in this worktree.

Read first: `devflow/feat/tiered-validation-v2/tiered-validation-v2.plan.md` (PLAN-Ttv-001.PH4, `A1`, `AA8`, `AA9`, `V3`, `R3`, `TC5`) and the proposal (PROP-Ttv-001.G3/G4/S2/S3). **Depends on Task 3** — the guidance references the shipped `make test-warm` entry, so it lands after the tooling. Sibling with Task 5; file scopes are disjoint (guidance prose here vs the attr-scaling task queue there).

## TASK-Ttv-004.P1 Scope

Type: AFK

Rewrite the standing testing guidance to the tiered convention in the surfaces plan/task authors copy from: warm to iterate, cold focused `clojure -M:test <ns...>` as the slice gate, the full locked suite only at queue acceptance and land; warm is never a gate; bare `flock`.

## TASK-Ttv-004.P2 Must implement exactly

- **TASK-Ttv-004.MI1:** Rewrite the testing rule in `AGENTS.md` and `CLAUDE.md` (PLAN-Ttv-001.AA8) to the three tiers: (a) `make test-warm NS="ns..."` to iterate (never a Done-when gate — PLAN-Ttv-001.R3); (b) the cold focused run `clojure -M:test <ns...>` naming the touched namespaces as the per-slice Done-when gate; (c) the full locked suite `flock -w 3600 /tmp/skein-test.lock clojure -M:test` only at queue acceptance and land `merge-local-verify`. State the CI-independence invariant (warm never a gate, gitignored, no warm-only result path — `R3`).
- **TASK-Ttv-004.MI2:** Use **bare** `flock` on PATH (nix); never the removed `/opt/homebrew/opt/util-linux/bin/flock` path (PLAN-Ttv-001.TC5).
- **TASK-Ttv-004.MI3:** Add tiered-validation prose to the `about-doc` `:policy` map in `spools/agents/src/skein/spools/agents.clj` (beside `:task-sizing`, ~`:447-449`) prescribing the tiers for delegated task Done-when blocks (PLAN-Ttv-001.AA9, PROP-Ttv-001.S3).
- **TASK-Ttv-004.MI4:** Regenerate `spools/agents.api.md` with `make api-docs` if any docstring shifted, and commit it in sync (PLAN-Ttv-001.V3).

## TASK-Ttv-004.P3 Done when

- **TASK-Ttv-004.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make docs-check` is green and `make api-docs` leaves no diff (`spools/agents.api.md` in sync — PLAN-Ttv-001.V3).
- **TASK-Ttv-004.DW2:** `make fmt-check lint` pass for `spools/agents/src/skein/spools/agents.clj`.
- **TASK-Ttv-004.DW3:** `grep -rn "util-linux" AGENTS.md CLAUDE.md spools/agents/src/skein/spools/agents.clj` returns nothing; each surface names all three tiers with bare `flock` and marks warm as never a gate. (No Clojure test namespace is touched — this slice is prose; the full suite is the PH6 acceptance slice, not run here.)

## TASK-Ttv-004.P4 Out of scope

- **TASK-Ttv-004.OS1:** The `attr-scaling-ship-now` task queue (Task 5).
- **TASK-Ttv-004.OS2:** Any land-workflow or CI gate change (PROP-Ttv-001.NG3) beyond the prose statement of the tiers.

## TASK-Ttv-004.P5 References

- **TASK-Ttv-004.REF1:** PLAN-Ttv-001.PH4, `A1`, `AA8`, `AA9`, `V3`, `R3`, `TC5`; PROP-Ttv-001.G3/G4/S2/S3.
- **TASK-Ttv-004.REF2:** `AGENTS.md`, `CLAUDE.md`, `spools/agents/src/skein/spools/agents.clj` (`about-doc` `:policy`).
