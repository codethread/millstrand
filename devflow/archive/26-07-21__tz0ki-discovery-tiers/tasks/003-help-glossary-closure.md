# Task 3: Help glossary-closure integration

**Document ID:** `TASK-Dtf-003`

## TASK-Dtf-003.P1 Scope

Type: AFK

Wire the glossary into the help projection: nodes carry `failure-modes` name refs (already projected in Task 1's node), and the envelope's `glossary` field becomes the referenced-term closure for the returned subtree. Touches `help.clj` (serialized after Task 1).

## TASK-Dtf-003.P2 Must implement exactly

- **TASK-Dtf-003.MI1:** The help projection resolves the **closure** of every `failure-modes` outcome name referenced by any node under `node` (or under a sliced subtree) to `{name → definition}` and places it once in the envelope `glossary` field. Definitions never appear inline on nodes — nodes carry names only. Per DELTA-Dtf-001.CC1 and DELTA-Dtf-002.CC5.
- **TASK-Dtf-003.MI2:** Slicing (`help <op> <verb>`) narrows the closure to the returned subtree's referenced outcomes.
- **TASK-Dtf-003.MI3:** The no-arg catalog (Task 1) entries need no glossary closure (summary nodes carry no `failure-modes`); leave the catalog `glossary` unset/omitted per DELTA-Dtf-001.CC3.

## TASK-Dtf-003.P3 Done when

- **TASK-Dtf-003.DW1:** Tests cover full-tree vs sliced glossary closure and that node `failure-modes` stay name-only, and pass under `clojure -M:test` on the co-located weaver-help test namespace(s).
- **TASK-Dtf-003.DW2:** `clojure -M:smoke` and `make fmt-check lint reflect-check docs-check` green.

## TASK-Dtf-003.P4 Out of scope

- **TASK-Dtf-003.OS1:** The glossary registry/validation (Task 2); meta-verbs/source (Task 4).

## TASK-Dtf-003.P5 References

- **TASK-Dtf-003.REF1:** DELTA-Dtf-001.CC1; DELTA-Dtf-002.CC5; PLAN-Dtf-001.PH2.
- **TASK-Dtf-003.REF2:** `src/skein/core/weaver/help.clj` (envelope projection from Task 1).
