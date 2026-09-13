# Task 6: Default-transform slot + help-render integration

**Document ID:** `TASK-Dtf-006`

## TASK-Dtf-006.P1 Scope

Type: AFK

Add the net-new at-most-one default-help-transform slot and wire the help op to render through it with a `--json` bypass. Touches a new `skein.api.runtime`-tier namespace (distinct from the Task 2 glossary ns) and the help-render path (`help.clj`, serialized after Task 4).

## TASK-Dtf-006.P2 Must implement exactly

- **TASK-Dtf-006.MI1:** A net-new, runtime-owned, **reload-cleared** at-most-one slot holding a transform fn (full envelope → rendered string). Contract per DELTA-Dtf-002.CC1: input is the full envelope; output is the verbatim relay string (JSON or text); a throwing transform **fails loudly** naming the transform; explicit set is at-most-one (loud on occupied unless explicit replace); introspectable (read verb reports registration + provenance). Follows op-registry reload lifecycle (SPEC-004.C46), never `spool-state`. Not auto-registered by any `install!`.
- **TASK-Dtf-006.MI2:** The `help` op renders through the registered transform when present, else emits the raw canonical envelope JSON. `--json` is the sole opt-out and **always** bypasses the slot, so a broken transform never bricks help. `--json` leading-only in the help surface. Per DELTA-Dtf-001.CC4.
- **TASK-Dtf-006.MI3:** `about`/`prime` output is **not** transformed.

## TASK-Dtf-006.P3 Done when

- **TASK-Dtf-006.DW1:** Tests cover: raw default; elected-transform render; `--json` bypass; throwing-transform loud recovery (help not bricked); reload clear/re-establish; explicit replace; introspection — passing under `clojure -M:test` on the co-located test namespace(s) for the slot and help-render code.
- **TASK-Dtf-006.DW2:** `clojure -M:smoke`, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check`, `make api-docs` green.

## TASK-Dtf-006.P4 Out of scope

- **TASK-Dtf-006.OS1:** The batteries reference transformer implementation (Task 7) — this task ships the slot + render plumbing only, and registers no transformer.

## TASK-Dtf-006.P5 References

- **TASK-Dtf-006.REF1:** DELTA-Dtf-002.CC1/D1; DELTA-Dtf-001.CC4; PLAN-Dtf-001.PH5a.
- **TASK-Dtf-006.REF2:** `src/skein/core/weaver/help.clj`; `skein.api.runtime.alpha` reload semantics (SPEC-004.C46/C63c).
