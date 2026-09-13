# Task 21: acceptance / atomic-landing gate

**Document ID:** `TASK-Alr-021`

**Phase:** `PLAN-Alr-001.PH7` **Harness:** build **Type:** AFK

**Depends on:** TASK-Alr-011, TASK-Alr-012, TASK-Alr-013, TASK-Alr-014, TASK-Alr-015, TASK-Alr-016, TASK-Alr-017, TASK-Alr-018, TASK-Alr-019, TASK-Alr-020

## TASK-Alr-021.P1 Scope

Prove the whole sweep green in one place before landing — the **only** task that runs the full locked suite and the full quality-gate battery, confirming atomicity (`PLAN-Alr-001.PH7`, `PROP-Alr-001.DW1/DW2`). No new rename work here; if a gate fails, the fix belongs to the owning family task, not this gate.

**Owned files (disjoint):** none authored — this is a validation-only gate task.

## TASK-Alr-021.P2 Must implement exactly

- **TASK-Alr-021.MI1:** Run the full landing battery, in order: `make build`; `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (the full locked suite — the lock serializes against sibling agents per repo rule); `(cd cli && go test ./...)`; `clojure -M:smoke`; `make fmt-check lint reflect-check docs-check` (zero findings); `make api-docs` (clean).
- **TASK-Alr-021.MI2:** Run the `PROP-Alr-001.DW1` whole-tree grep sweep — every pattern anchored so it cannot match legitimate prose: the `shuttle/`, `treadle/`, `workflow/notes` attribute/marker prefixes and the namespace-qualified `skein.spools.(shuttle|agents|treadle|reed)`. Each must return **only** intended survivors (free `:as` alias locals, `devflow/archive/*`).
- **TASK-Alr-021.MI3:** Confirm `git status --short` is clear of generated SQLite / runtime-metadata artifacts (`PLAN-Alr-001.PH7` gate).

## TASK-Alr-021.P3 Validation / Done when

- **TASK-Alr-021.DW1:** Every command in MI1 is green; the full locked suite passes under the `flock` lock.
- **TASK-Alr-021.DW2:** The MI2 anchored grep returns only intended survivors.
- **TASK-Alr-021.DW3:** `git status --short` clear of runtime artifacts; all quality gates at zero findings.

## TASK-Alr-021.P4 Out of scope

- **TASK-Alr-021.OS1:** Any rename edit (owned by PH1–PH6 family tasks); the canonical cutover (Task 22).
- **TASK-Alr-021.OS2:** CI or lock-policy changes.

## TASK-Alr-021.P5 Commit

- No source authored; if a trivial gate-only fixup is unavoidable, one atomic commit, devflow message, **no push**. Otherwise this task lands no commit and reports the green battery.

## TASK-Alr-021.P6 References

- **TASK-Alr-021.REF1:** `PLAN-Alr-001.PH7`, `PLAN-Alr-001.V1/V4`, `PROP-Alr-001.DW1/DW2`, `PROP-Alr-001.R3`.
- **TASK-Alr-021.REF2:** brief "Validation" line (the acceptance battery).
