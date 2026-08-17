# Task 4: Meta-verbs + about/prime metadata + source pointer

**Document ID:** `TASK-Dtf-004`

## TASK-Dtf-004.P1 Scope

Type: AFK

Add the `:about`/`:prime` op-metadata keys, the builtin `about`/`prime` ops beside `help`, and the op-wide best-effort `source` resolution at projection. Touches `skein.api.weaver` (shares scope with Task 2 — serialized) and `help.clj`/meta projection (serialized after Task 3).

## TASK-Dtf-004.P2 Must implement exactly

- **TASK-Dtf-004.MI1:** Extend SPEC-004.C63a's accepted op-metadata key set (`op_entry.clj:34`) with optional `:about` and `:prime`, each a **non-blank prose string** validated at registration (blank/non-string fails loudly). `:arg-spec` stays optional for raw-envelope ops. Per DELTA-Dtf-002.CC4.
- **TASK-Dtf-004.MI1a (raw-envelope root annotations — carried over from Task 2 review):** Add an optional `:annotations` op-metadata key (closed `{:use-when [] :notes [] :failure-modes []}` sub-map, same structural validation as Task 2's arg-spec annotations) so **raw-envelope ops that declare no arg-spec** have the **root annotation surface** DELTA-Dtf-003.CC2 requires. Extend Task 2's `register-op!`/`replace-op!` glossary-ref existence check to also gather `failure-modes` from this root `:annotations` metadata, and fold it into the help root node's `use-when`/`notes`/ `failure-modes` at projection. Ops that declare an arg-spec carry annotations on the arg-spec node (Task 2), not here.
- **TASK-Dtf-004.MI2:** Install builtin read-class ops `about` and `prime` beside `help` (SPEC-004.C63c): each takes one op-name positional and returns `{about|prime, source}` (DELTA-Dtf-001.CC7). Missing declared prose → loud `discovery/unavailable` (TEN-003). Arity-1: a verb path fails loudly and redirects to `help` (DELTA-Dtf-003.CC4). Registered via the public path, replaceable, maskable, reload-reinstalled. Per DELTA-Dtf-002.CC6.
- **TASK-Dtf-004.MI3:** Op-wide `source` resolved best-effort at **projection** for `help`/`about`/ `prime`: `requiring-resolve` under `with-spool-classloader` (`alpha.clj:499`), read the resolved var's `:file`/`:line`, resolve a readable on-disk path. Always present; `null` in exactly the three cases (resolve fail / no `:file`+`:line` / non-readable file); unrelated projection errors are **not** swallowed. Placed once in the envelope (`help`) or the `{about|prime, source}` object. Per DELTA-Dtf-002.CC2.

## TASK-Dtf-004.P3 Done when

- **TASK-Dtf-004.DW1:** Tests cover `:about`/`:prime` accept/blank-reject; `about`/`prime` present/missing/arity-1-redirect; source success `{file,line}` and each null case under the spool classloader without swallowing unrelated errors, passing under `clojure -M:test` on the co-located test namespace(s) for the changed weaver/help code.
- **TASK-Dtf-004.DW2:** `check-op-return!` coverage for `about`/`prime`. `clojure -M:smoke`, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check`, `make api-docs` green.

## TASK-Dtf-004.P4 Out of scope

- **TASK-Dtf-004.OS1:** `--help` grammar (Task 5); transform slot (Task 6). `about`/`prime` output is never transformed.

## TASK-Dtf-004.P5 References

- **TASK-Dtf-004.REF1:** DELTA-Dtf-002.CC2/CC4/CC6; DELTA-Dtf-001.CC7; DELTA-Dtf-003.CC4; PLAN-Dtf-001.PH3.
- **TASK-Dtf-004.REF2:** `op_entry.clj:34`, `src/skein/api/weaver/alpha.clj:499,591-608`, `src/skein/core/weaver/help.clj`.
