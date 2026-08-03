# Strand await proposal

**Document ID:** `PROP-Sawt-001`
**Status:** Approved
**Approved:** 2026-08-03 — Q1 resolved as option (b): the ADR-001 gate is reinterpreted as not binding this design, which adds no condition grammar; the op is a mechanism with minimal surface composing over already-present selection APIs (rationale: ct at sign-off)
**Related RFCs:** [`2026-06-24-task-query-dsl`](../../rfcs/2026-06-24-task-query-dsl.md)
**Related root specs:** [`repl-api.md`](../../specs/repl-api.md), [`strand-model.md`](../../specs/strand-model.md) (P9 query grammar), [`daemon-runtime.md`](../../specs/daemon-runtime.md)
**Related decisions:** [`ADR-001`](../../adrs/0001-thin-cli-over-generic-algebra.md) (P5 parks generic await); design notes with full session provenance in [`design-notes.md`](./design-notes.md)

Once approved this document is frozen: it records the intent agreed at sign-off, not what was later built. Implementation change lives in the spec deltas, the plan, and code.

## PROP-Sawt-001.P1 Problem

Agents coordinating over the strand graph have no blocking primitive for conditions on strands themselves. Waiting for a sibling task to close, for a plan's children to finish, for a dependency set to clear, or for a lock strand to disappear is done today with caller-rolled sleep loops around `list`/`ready`/`show`. The shipped awaits (`workflow await`, `agent await`, `land await`) each cover one domain and cannot express these graph-level waits.

ADR-001.P5 parked this op: "generic `await` waits for a second genuine consumer, sharing `poll-until!` meanwhile." Whether that gate is now met is the sign-off decision this proposal puts to the human (P6.Q1). The design differs from the form ADR-001 rejected in that it adds no condition grammar at all.

## PROP-Sawt-001.P2 Goals

- **PROP-Sawt-001.G1:** A batteries op `strand await` that blocks until a registered named query's result count sits inside an inclusive `--min-count`/`--max-count` band, with `--param` binding and `--timeout-secs`, returning timeout as data (exit 0) like the existing awaits.
- **PROP-Sawt-001.G2:** The contract is set-cardinality waiting at one poll snapshot — never "strand completion". Close, replace, and burn all exit a result set identically. User-facing documentation states this distinction wherever the op is taught.
- **PROP-Sawt-001.G3:** A batteries-shipped set of id-parameterised coordination queries (`strand-closed`, `strand-active`, `children-active`, `blockers-active`) so the common waits work with zero config authoring. Each ships with a documented operator procedure (P5 shows them), satisfying the live-consumer rule (PROP-Rqc-001.G1) and one-spelling-per-selection (ADR-001.P5).
- **PROP-Sawt-001.G4:** Waiting is cheap enough to poll: each check reads no more than the band needs to decide, however many strands match.
- **PROP-Sawt-001.G5:** Trusted config can compose the readiness rule into its own queries by reference to one canonical definition, so "ready-flavored" waits never re-encode the scheduling rule.
- **PROP-Sawt-001.G6:** The conventions all await-shaped ops share (timeout flag, unbounded deadline, timeout-as-data, clock-driven polling, the cap-and-reissue habit) are written down once in the spool authoring documentation, with this op as the reference implementation.

## PROP-Sawt-001.P3 Non-goals

- **PROP-Sawt-001.NG1:** No ad hoc queries at the CLI — no EDN string or JSON where-form submission. The REPL is the ad hoc surface (TEN-006); this was weighed and rejected during design, including an unsafe-tier variant.
- **PROP-Sawt-001.NG2:** No query grammar growth. The op consumes the frozen boundary grammar as-is.
- **PROP-Sawt-001.NG3:** No point-flag sugar (`strand await <id> --state/--attr`) in this feature. The shipped query set covers the point cases by name; sugar returns only if real usage demands it.
- **PROP-Sawt-001.NG4:** No `--ready` flag. Readiness is expressible in the grammar and composes into registered queries (G5).
- **PROP-Sawt-001.NG5:** The domain awaits are not subsumed or migrated. Supervision (`agent await`), attention derivation (`workflow await`), and queue ordering (`land await`) are not queries; they conform to the shared conventions (G6) and keep their surfaces.
- **PROP-Sawt-001.NG6:** No result id sampling. A sample read would race the count and break the single-snapshot promise.
- **PROP-Sawt-001.NG7:** No aggregation surface. Await introduces no count read anywhere — the band is decided by a bounded read of the existing selection surface — and no aggregate (count, sum, grouping) is added on the CLI or at the api tier.
- **PROP-Sawt-001.NG8:** No admission control or waiter caps in v1; the poll interval and a documented expected-concurrency envelope are the operational stance (P6.Q2).

## PROP-Sawt-001.P4 Proposed scope

- **PROP-Sawt-001.S1:** Batteries gains the `await` op: `--query <name>`, repeatable `--param k=v`, `--min-count N`, `--max-count N`, `--timeout-secs N`. Validation fails loudly before any blocking: at least one bound; bounds non-negative with `min ≤ max`; a lone `--min-count 0` rejected as vacuous; `--timeout-secs` non-negative and bounded, defaulting to the shipped awaits' 1800. The query name and params are checked up front with the same failure vocabulary as `list --query`/`ready --query`; a missing referenced param is an immediate failure, never a timeout.
- **PROP-Sawt-001.S2:** One flat result map for both outcomes: `operation`, `query`, `reason` (satisfied | timeout), `count`, `min_count`, `max_count`, `elapsed_ms`. Whichever bound was omitted is null in the result; the other fields are always present. `count` is the observed match count from the bounded probe: exact whenever the probe returns fewer rows than its limit, clamped at the limit whenever the probe fills (satisfying `--min-count N` reports `N` even when more match) — await reports the band decision, never a progress meter.
- **PROP-Sawt-001.S3:** Each poll decides the band with one limit-bounded read of the existing selection surface — the limit is what the band needs (`max+1`, or `min` when only a minimum is given) — so the work per check is bounded by the band, not by how many strands match, and no new read surface exists. The probe observes the registered predicate exactly, none of the optional overlays the row-returning reads offer, and each probe is one statement's snapshot, distinct from any read taken after waking.
- **PROP-Sawt-001.S4:** The canonical readiness definition becomes composable by trusted config (G5).
- **PROP-Sawt-001.S5:** Batteries registers the shipped coordination queries (G3); each idiom is documented as an operator procedure where batteries ops are taught.
- **PROP-Sawt-001.S6:** The spool authoring documentation gains the await-shaped-ops convention (G6).
- **PROP-Sawt-001.S7:** The durable contracts above (op surface, result shape, band-probe semantics, readiness composition, shipped query set) land as root spec deltas staged with this feature.

## PROP-Sawt-001.P5 Surface examples

The proposed surface, shown as the waits it exists for. These examples are the contract under sign-off; S1 owns the validation rules.

```sh
# Wait for one strand to close (shipped query; replaced/burned time out rather than lie)
strand await --query strand-closed --param id=tk42 --min-count 1

# "When it's no longer active" — any exit from the active set: close, replace, or burn
strand await --query strand-active --param id=kb07 --max-count 0

# Fan-in: no child of the plan remains active
strand await --query children-active --param parent=pl88 --max-count 0

# My dependencies have cleared
strand await --query blockers-active --param id=tk42 --max-count 0

# Reviewer quorum: proceed at two verdicts (config-registered query)
strand await --query review-verdicts --param target=fx91 --min-count 2

# Worker idle loop over a ready-composed config query, with timeout-as-data re-issue
while :; do
  out=$(strand await --query work-ready --min-count 1 --timeout-secs 1500)
  [ "$(jq -r .reason <<<"$out")" = satisfied ] && break
done
```

Both outcomes return the same flat map at exit 0; `reason` distinguishes them, and an omitted bound is null. When a probe hits its limit, `count` reports the clamped observation — a timeout here with forty children still active says `count: 1`, "still nonempty", never forty:

```json
{"operation": "await", "query": "children-active", "reason": "satisfied",
 "count": 0, "min_count": null, "max_count": 0, "elapsed_ms": 4012}
```

## PROP-Sawt-001.P6 Open questions

- **PROP-Sawt-001.Q1:** The ADR-001 gate, put plainly for decision. ADR-001.P5 parked generic await pending "a second genuine consumer." The evidence today: one demonstrated request (agent-to-agent coordination on arbitrary strands — the ask that started this feature), and prospective classes with no current owner or call site (coordinator fan-in over plans and dependency sets; lock and queue observation outside the landing path; worker idle loops). The mechanics the `4uwzd` debate demanded — shared snapshot, timeout, and return semantics — are uniform by construction here, but that is compatibility, not demand. The human signs off one of: (a) the named classes satisfy the gate as consumers; (b) the gate is waived or reinterpreted for this design because it adds no grammar, with the waiver recorded; (c) the feature re-parks until a second consumer exists with a concrete owner. Approving this proposal means explicitly choosing (a) or (b); choosing (c) is an abort of this run, not an edit to it.
- **PROP-Sawt-001.Q2:** What expected-concurrency envelope do we document and test before fleet-scale idle loops are recommended?
