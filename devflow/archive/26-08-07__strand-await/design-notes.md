# strand await — converged design notes (pre-proposal)

Source: design session 2026-08-03 (user ct + fable coordinator), two sol-med cross-vendor review rounds (runs `gogrc` round 1: point-flag design, verdict rethink; `5ojmq` round 2, resumed session: query-cardinality design, verdict ship-with-changes). Rendered design doc: /tmp/rich-strand-await-final-design.html (rich-response artifact; content mirrored below in brief).

## Surface

    strand await --query <name> [--param k=v ...]
                 [--min-count N] [--max-count N]
                 [--timeout-secs 1800]

Blocks until the registered named query's result count sits inside the inclusive band at one poll snapshot. Timeout is data (exit 0, reason=timeout), matching workflow/land await. Contract wording is cardinality-not-completion: close, replace, and burn all exit a result set identically.

Idioms: --max-count 0 (until empty), --min-count 1 (until nonempty), --min-count N (quorum), both equal (exact).

## Decisions (each argued in-session; see design doc for full rationale)

- Registered named queries only; the DSL already provides :and/:or/:not/:in/:missing/:edge/in and :edge/out — no new grammar, no condition language.
- No ad hoc CLI queries (EDN string or JSON where-form): considered and rejected; the REPL is the ad hoc surface (TEN-006). No unsafe-tier op either.
- No --ready flag: readiness is expressible in the grammar. The full canonical readiness expression is

      [:and [:= :state "active"]
            [:not [:edge/out "depends-on" [:= :state "active"]]]]

    (both conjuncts required: ready = the strand itself active AND no active depends-on target; the :not clause alone is only the blocker-free fragment). Verified compiling to the same NOT EXISTS shape as the ready path (db.clj ~1795). Export this full expression at the graph api seam (54qun compose seam) so config composes it by reference.

- No point sugar (strand await <id> --state/--attr) in v1: deferred; batteries ships id-parameterised queries covering the point cases instead.
- Batteries ships a small coordination-query set out of the box, one spelling per selection (ADR-001.P5). The definitions (all verified compiling against src/skein/core/query.clj):

      strand-closed   {:params [:id]
                       :where [:and [:= :state "closed"] [:= :id [:param :id]]]}
      strand-active   {:params [:id]
                       :where [:and [:= :state "active"] [:= :id [:param :id]]]}
      children-active {:params [:parent]
                       :where [:and [:= :state "active"]
                                    [:edge/in "parent-of" [:= :id [:param :parent]]]]}
      blockers-active {:params [:id]
                       :where [:and [:= :state "active"]
                                    [:edge/in "depends-on" [:= :id [:param :id]]]]}

- Band flags: two non-negative ints, min<=max, at least one bound, --min-count 0 alone rejected (vacuous). No comparator strings.
- Result: one flat map {operation query reason count min_count max_count elapsed_ms}; both bounds nullable (whichever was omitted); no ids sample (races the probe).
- Resolve query + coerce params once before polling (frozen definition); missing params fail on first evaluation, never become a timeout.
- Mechanics: skein/defop, :hook-class :read, :deadline-class :unbounded, poll-until! on the runtime Clock, 1000ms poll, ~50min cap-and-reissue note, --timeout-secs bounded at Long/MAX_VALUE/1000.
- Band evaluation (supersedes sol round 2's count(*)-projection blocker): each tick decides the band with one limit-bounded lean read of the existing selection surface (LIMIT max+1, or min when only a minimum is given). No count read exists anywhere; sol's objections dissolve because the probe's limit is the band bound (the 500-row lean cap cannot corrupt it) and hydration is bounded at band+1 lean rows with attribute values never read. Result count is exact when the probe returns under its limit and clamped whenever the probe fills — including min-only satisfaction, which reports the cap even when more match. Single-statement snapshot per poll unchanged.
- Domain awaits (agent/workflow/land) are NOT subsumed: supervision, attention derivation, and queue ordering are not queries. Conformance is the convention layer (--timeout-secs, unbounded deadline, timeout-as-data, poll-until!, cap-and-reissue) — codify as an "await-shaped ops" section in docs/spools/writing-shared-spools.md.

## ADR-001 gate (must be addressed in the proposal)

ADR-001 parked generic await: "waits for a second genuine consumer, sharing poll-until! meanwhile." This design is not the parked O2 form (--until <cond> condition grammar); it adds zero grammar. But the gate is consumer-driven: the proposal must name genuine current consumers (candidate classes: agent-to-agent coordination on arbitrary strands — the motivating ask; worker idle loops over ready-composed queries; lock/queue observation) or explicitly re-park. The sol-high debate (run 4uwzd) asked for "two or more domains sharing identical snapshot, completion, timeout, supervision, and return semantics" — the proposal should answer with the snapshot/timeout/return contract above, which is uniform by construction, and concede supervision stays domain-owned.
