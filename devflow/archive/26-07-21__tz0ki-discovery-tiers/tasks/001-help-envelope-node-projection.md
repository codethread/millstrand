# Task 1: Help envelope + fractal node projection

**Document ID:** `TASK-Dtf-001`

## TASK-Dtf-001.P1 Scope

Type: AFK

Build the canonical help **response envelope** and the uniform **fractal node** projection in the weaver help op, replacing the current ad-hoc op-detail shape. Base slice; the glossary closure, meta-verbs, source, transform, and rendering land in later tasks.

## TASK-Dtf-001.P2 Must implement exactly

- **TASK-Dtf-001.MI1:** `strand help <op>` returns the envelope `{schema-version, operation{name, provenance, stream?, deadline-class, hook-class, raw-envelope}, source, glossary, node}` per DELTA-Dtf-001.CC1. `schema-version` is integer `1`. `source` present (may be `null`; full resolution is Task 4 — return `null` here is acceptable). `glossary` is an empty map here (Task 3 fills it).
- **TASK-Dtf-001.MI2:** `node` is the uniform fractal shape per DELTA-Dtf-001.CC2 / DELTA-Dtf-003.CC1: `{name, doc, invocation{mode, flags[], positionals[]}, returns|null, use-when[], notes[], failure-modes[], children[]}`, every key always present with the defined empty/null semantics. `invocation.mode` is `"declared"` or `"raw-envelope"`. Op-wide facts live only in `operation`, never on the node.
- **TASK-Dtf-001.MI3:** Project from existing registry data — op envelope, arg-spec `explain` (SPEC-003.C64/C65), per-case return-shape `explain` (SPEC-003.C60b). Flat op → root node with empty `children`; subcommand op → root node with one child per subcommand (same shape, its flags/positionals + routed return case).
- **TASK-Dtf-001.MI4:** `strand help <op> <verb>` slices to the verb's node (same shape under `node`).
- **TASK-Dtf-001.MI5:** `strand help` (no op) returns the versioned catalog `{schema-version, ops[]}` where each entry is a shallow per-op envelope `{operation, source, node(summary)}` per DELTA-Dtf-001.CC3.
- **TASK-Dtf-001.MI6:** Reword the SPEC-002.C39 characterization in code comments/docstrings toward "one declared, versioned schema, uniformly projected" (DELTA-Dtf-001.D1) — no behavioral hand-authoring.

## TASK-Dtf-001.P3 Done when

- **TASK-Dtf-001.DW1:** Focused tests cover envelope shape, node field presence/types, `invocation.mode` (declared vs raw-envelope), full-tree vs sliced node, and the no-arg catalog, and pass under `clojure -M:test` on the co-located weaver-help test namespace(s) you add/extend.
- **TASK-Dtf-001.DW2:** `check-op-return!` coverage updated for the `help` op leaves.
- **TASK-Dtf-001.DW3:** `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` green; `git status --short` shows no generated artifacts.

## TASK-Dtf-001.P4 Out of scope

- **TASK-Dtf-001.OS1:** Glossary closure (Task 3), `about`/`prime` + source resolution (Task 4), `--help` grammar (Task 5), the transform slot/rendering (Task 6). Do not add a default renderer; raw JSON envelope is the output here.
- **TASK-Dtf-001.OS2:** Renderer recursion proof (Task 7) — do not claim arbitrary-depth here.

## TASK-Dtf-001.P5 References

- **TASK-Dtf-001.REF1:** DELTA-Dtf-001.CC1/CC2/CC3/D1; DELTA-Dtf-003.CC1; PLAN-Dtf-001.PH1.
- **TASK-Dtf-001.REF2:** `src/skein/core/weaver/help.clj`; op-entry `src/skein/api/weaver/internal/ op_entry.clj`; arg-spec/return-shape explain (SPEC-003.C64/C65/C60b).
