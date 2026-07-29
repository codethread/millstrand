# Spec projection Plan

**Document ID:** `PLAN-Spj-001`
**Feature:** `spec-projection`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [alpha-surface.md](../../specs/alpha-surface.md),
[repl-api.md](../../specs/repl-api.md)
**Feature specs:** [specs/alpha-surface.delta.md](./specs/alpha-surface.delta.md),
[specs/repl-api.delta.md](./specs/repl-api.delta.md),
[specs/workflow-spool.delta.md](./specs/workflow-spool.delta.md)
**Status:** Reviewed
**Last Updated:** 2026-07-29
**Configuration identification:** Document IDs must be ordered as document type, short
name, sequential id, then optional version. Prefix every nested point ID with the full
document ID.

## PLAN-Spj-001.P1 Goal and scope

Ship `skein.api.spec.alpha` (the shared spec-over-wire documentation projection) together
with its two forcing consumers — the workflow spool's params/choice-input/failure
surfaces and pattern explain/weave validation — in one branch, per epic uruz0's entry
unit (cards a4gss, 9wz9l, 6q82n).

## PLAN-Spj-001.P2 Approach

- **PLAN-Spj-001.A1:** Build the module first as a story-file api module
  (SPEC-003.C19a: publics lead, 96 cols, `quality.api-form`): lift the workflow spool's
  form-graph walk verbatim as the fidelity floor, then layer the interpreting contract
  walk, template renderer, and problems view over the same `s/form` data. All walks are
  data-only; var metadata reads are the only resolution side effect.
- **PLAN-Spj-001.A2:** Wire consumers in the same run, adjusting the module wherever a
  consumer exposes a gap (that co-design is why the trio is one unit). Workflow spool:
  `internal/specs.clj` delegates to the module and keeps only spool-local plumbing
  (JSON boundary, `require-spec!` reasons); `internal/discovery.clj` params-view gains
  `contract`/`template`; new `choices` CLI verb + return spec in `workflow.clj`/`cli.clj`.
  Patterns: replace the summary trio and regex missing-key detection; fdef on `explain`.
- **PLAN-Spj-001.A3:** Docs and registries move with the code: SPEC-005.C2 list,
  SPEC-003 new clause, `spools/workflow.md`, batteries/pattern docs, api-docs
  regeneration, and the delta record already staged in `specs/`.

## PLAN-Spj-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Spj-001.AA1 | `src/skein/api/spec/alpha.clj` | New blessed module |
| PLAN-Spj-001.AA2 | `spools/workflow/src/.../internal/specs.clj` | Delegate to module |
| PLAN-Spj-001.AA3 | `spools/workflow/src/.../internal/discovery.clj` | params-view fields |
| PLAN-Spj-001.AA4 | `spools/workflow/src/skein/spools/workflow.clj` + `cli.clj` | `choices` verb |
| PLAN-Spj-001.AA5 | `src/skein/api/patterns/alpha.clj` | Projection adoption + fdef |
| PLAN-Spj-001.AA6 | root specs, `spools/workflow.md`, api docs | Contract updates |

## PLAN-Spj-001.P4 Contract and migration impact

- **PLAN-Spj-001.CM1:** Wire-shape breaks sanctioned by TEN-000@1 and recorded in the
  three deltas plus the PROP-Wcd-001.S11 appendix: `pattern explain` loses
  `:spec-form`/`:summary`/`:required`/`:optional`; weave failures stop leaking raw
  `s/explain-data`; workflow failure payloads gain the projection fields. No data
  migration — all changes are read/projection surfaces.

## PLAN-Spj-001.P5 Implementation phases

### PLAN-Spj-001.PH1 Module

Outcome: `skein.api.spec.alpha` with focused cold tests covering every node kind,
recursion, opacity fallback, doc enrichment, and the no-invocation proof.

### PLAN-Spj-001.PH2 Workflow adoption

Outcome: show params, `workflow choices`, and failure payloads speak the projection;
return specs, CLI help, and focused tests updated; defer-discovery decision in docs.

### PLAN-Spj-001.PH3 Pattern adoption

Outcome: pattern explain and weave-input failures speak the projection; delegate-pipeline
and agent-plan benchmarks covered; fdef registered.

### PLAN-Spj-001.PH4 Docs and gates

Outcome: root specs merged from deltas, spool docs and api docs regenerated, all quality
gates green.

## PLAN-Spj-001.P6 Validation strategy

- **PLAN-Spj-001.V1:** Cold `clojure -M:test` per touched namespace slice; full locked
  suite at queue acceptance; `(cd cli && go test ./...)`; `clojure -M:smoke`;
  `make dash-check fmt-check lint reflect-check docs-check`; `make api-docs` after
  docstring changes; live check of `strand workflow show intake`, `strand workflow
  choices` on a disposable-world run, and `strand pattern explain delegate-pipeline`.

## PLAN-Spj-001.P7 Risks and open questions

- **PLAN-Spj-001.R1:** `s/form` output differs across spec shapes (aliases collapse,
  anonymous fns print as `(fn [%] ...)`); mitigated by the opaque fallback — anything
  surprising degrades to printed form, never to a wrong claim.
- **PLAN-Spj-001.R2:** Devflow/kanban external spools read `spec-forms`; entry shape is
  kept accretive (new keys only), so pinned spool suites stay green.

## PLAN-Spj-001.P8 Task context

- **PLAN-Spj-001.TC1:** Cards a4gss/9wz9l/6q82n carry the binding constraints and
  done-when lists; the S11 appendix in `devflow/feat/s9i26-flow-cli/proposal.md` holds
  the node schema/grammar decisions; benchmark specs are
  `.skein/workflows.clj` `::delegate-pipeline-input` and the pinned delegation spool's
  `::agent-plan-input`.

## PLAN-Spj-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
