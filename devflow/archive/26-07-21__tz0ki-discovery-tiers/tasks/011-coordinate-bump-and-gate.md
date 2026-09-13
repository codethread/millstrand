# Task 11: Coordinate bump + spool-suite-gate + agent discovery E2E

**Document ID:** `TASK-Dtf-011`

## TASK-Dtf-011.P1 Scope

Type: AFK

Bump this repo's pinned `agent-harness.spool` coordinate to the v8 tag + peeled SHA, validate the whole `spools.edn`, and prove the factored `agent` discovery end-to-end. Touches `.skein/spools.edn`.

## TASK-Dtf-011.P2 Must implement exactly

- **TASK-Dtf-011.MI1:** Update `.skein/spools.edn` `ct.spools/agent-run` `:git/tag` to `v8` and `:git/sha` to the Task 10 peeled SHA (validated comment-preserving edit; SPEC-003.C63a convention).
- **TASK-Dtf-011.MI2:** Validate the whole `spools.edn` (every use entry) and confirm the coordinate syncs against a disposable world.
- **TASK-Dtf-011.MI3:** A disposable-world discovery E2E for `agent`: `strand help agent`, `help agent <verb>`, `about agent`, `prime agent` — the whole-tree `about` fetch is gone; per-verb help + glossary work. Per PLAN-Dtf-001.PH8/V3.

## TASK-Dtf-011.P3 Done when

- **TASK-Dtf-011.DW1:** `make spool-suite-gate` green against this checkout; the disposable-world `agent` discovery E2E passes.
- **TASK-Dtf-011.DW2:** `clojure -M:smoke`, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check` green; `git status --short` shows only the intended `spools.edn` change.

## TASK-Dtf-011.P4 Out of scope

- **TASK-Dtf-011.OS1:** Producer adoption (Task 9) and release (Task 10); spec promotion (Task 12).

## TASK-Dtf-011.P5 References

- **TASK-Dtf-011.REF1:** PLAN-Dtf-001.PH8/V3; SPEC-003.C63a; `.skein/spools.edn`.
