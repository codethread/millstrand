# Task 2: Glossary registry + annotation validation + existence check

**Document ID:** `TASK-Dtf-002`

## TASK-Dtf-002.P1 Scope

Type: AFK

Add the net-new runtime glossary registry, the arg-spec annotation sub-map **structural** validation, and the **existence** check at op registration. Authoring/validation path only — the help-render closure is Task 3. File-disjoint from Task 1 (no `help.clj` edits).

## TASK-Dtf-002.P2 Must implement exactly

- **TASK-Dtf-002.MI1:** A net-new, runtime-owned, **reload-cleared** glossary registry with public `register-glossary-outcome!` (loud on collision naming both registrants), `replace-glossary-outcome!` (requires the name to exist), and a read/introspection projection — op-registry style (SPEC-004.C63a), cleared by `reload!` before config re-runs. Qualified, stable outcome names; a changed-semantics redefinition is rejected (use a new name). Per DELTA-Dtf-002.CC5/CC7. Place in a distinct `skein.api.runtime`-tier namespace (do not collide with the Task 6 transform-slot ns).
- **TASK-Dtf-002.MI2:** Arg-spec **structural** validation of the closed annotation sub-map (`use-when` string[], `notes` string[], `failure-modes` outcome-name string[]) in `skein.api.cli` (SPEC-003.C64/C63d): closed keys, correct types, **no runtime dependency**. Per DELTA-Dtf-003.CC2.
- **TASK-Dtf-002.MI3:** **Unconditional glossary-ref existence** check at `register-op!`/`replace-op!` (SPEC-004.C63d) against the runtime glossary: every `failure-modes` name must reference an already-registered outcome, else fail loudly. Enforces the load-order contract (DELTA-Dtf-002.CC7).

## TASK-Dtf-002.P3 Done when

- **TASK-Dtf-002.DW1:** Focused tests cover register/replace/introspect, reload-clear, loud collision, structural-shape failure, and missing-ref registration failure, and pass under `clojure -M:test` on the co-located test namespace(s) for the glossary/validation code you add.
- **TASK-Dtf-002.DW2:** `clojure -M:smoke` and `make fmt-check lint reflect-check docs-check` green; `make api-docs` run if any `skein.api.*.alpha` docstring changed; no stray artifacts.

## TASK-Dtf-002.P4 Out of scope

- **TASK-Dtf-002.OS1:** Help-render glossary closure (Task 3); the `help.clj` projection is untouched here.
- **TASK-Dtf-002.OS2:** The transform slot (Task 6) — a separate runtime surface.
- **TASK-Dtf-002.OS3:** "Concepts" beyond failure outcomes (deferred, DELTA-Dtf-002.Q1).

## TASK-Dtf-002.P5 References

- **TASK-Dtf-002.REF1:** DELTA-Dtf-002.CC5/CC7/Q2; DELTA-Dtf-003.CC2; PLAN-Dtf-001.PH2/AA3/AA4.
- **TASK-Dtf-002.REF2:** `src/skein/api/cli/internal/validation.clj`; `src/skein/api/weaver/alpha.clj` (`register-op!` `:591-608`); vocab-registry (distinct) `src/skein/api/vocab/alpha.clj`.
