# JVM pool restart admission plan

**Document ID:** `PLAN-Jpra-001`
**Feature:** `jvm-pool-restart-admission`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md)
**Feature specs:** [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md)
**Status:** Shipped
**Last Updated:** 2026-09-11

## PLAN-Jpra-001.P1 Goal and scope

Repair pooled admission so retained isolated lifecycle history cannot replace current runtime identity, then align pooled restart persistence with the existing host-scoped contract. Preserve grouped readiness proof, restart diagnostics, status semantics, peer sent-once classification, and isolated restart behavior.

## PLAN-Jpra-001.P2 Approach

- **PLAN-Jpra-001.A1:** Split current runtime metadata reads from lifecycle-decorated status reads. Pool startup and host rediscovery use the raw identity path before endpoint proof. User-facing isolated status keeps its existing restart overlay.
- **PLAN-Jpra-001.A2:** Add the closed `millstrand.jvm-pool-restart/v1` record defined by the feature delta beneath the existing pool host directory. Keep current and previous hosts separate, freeze ordered registered and pending sets at the membership revision, and retain per-member identities needed for status and peer interruption classification.
- **PLAN-Jpra-001.A3:** Make pooled restart write the host record once at each transition boundary. Record `probing` before probe work and `restarting` under closed admission before signalling the old host. Every stop, artifact-cleanup, marker-cleanup, and launch exit records either the admitted running host or a failed unavailable host with honest stop evidence.
- **PLAN-Jpra-001.A4:** Project a host record only after resolving current pool ownership. Shared transition fields appear through admitted and pending selections; current member metadata remains authoritative for member identity, and pending selections gain no runtime identity.
- **PLAN-Jpra-001.A5:** Publish the canonical pool record path in pooled metadata and extend peer discovery with all-or-none pool identity. Planned-restart classification resolves that path and matches the interrupted peer against its previous member identity, preserving sent-once ambiguity without member-local record copies.
- **PLAN-Jpra-001.A6:** Keep valid member-local isolated `restart.json` files untouched. Hosting-mode resolution determines whether status and peer paths consult an isolated record or the pool host record. Alpha-era pooled member records receive no migration; a positively identified old pooled probe record is inert while pooled and rejected loudly rather than presented as isolated history.

## PLAN-Jpra-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Jpra-001.AA1 | `cli/cmd/mill` lifecycle and status | Separate raw identity reads, add the pool record boundary, and project host transitions by pool ownership. |
| PLAN-Jpra-001.AA2 | `cli/internal/jvmpool` | Reuse the canonical pool host directory for restart persistence. |
| PLAN-Jpra-001.AA3 | `src/millstrand/api/peers` and core specs | Carry optional pool identity and classify planned pooled replacement from the host record. |
| PLAN-Jpra-001.AA4 | Go and Clojure lifecycle tests | Add mode-transition, rediscovery, status, malformed-record, failure, and peer interruption regressions. |
| PLAN-Jpra-001.AA5 | JVM-pool integration acceptance | Exercise a formerly isolated restarted newcomer and verify first-attempt admission plus host-level record placement. |

## PLAN-Jpra-001.P4 Contract and migration impact

- **PLAN-Jpra-001.CM1:** The durable changes are staged in [the Weaver runtime delta](./specs/daemon-runtime.delta.md). The pool restart record is a new closed host artifact. Alpha-era per-member pooled records are not migrated; old records positively identified by their generated pool-probe workspace are rejected outside pooled mode rather than reclassified.
- **PLAN-Jpra-001.CM2:** Existing isolated `restart.json` remains valid and in place. Pool status ignores it while the workspace belongs to a pool, then isolated status can read it again if the workspace later opts out.
- **PLAN-Jpra-001.CM3:** Pooled runtime metadata and peer rows gain the canonical host record path plus all-or-none pool and host identity. Isolated rows and call behavior retain their existing shape and semantics.

## PLAN-Jpra-001.P5 Implementation phases

### PLAN-Jpra-001.PH1 Identity and persistence boundary

Outcome: pooled admission and rediscovery consume undecorated runtime evidence, and a validated host restart record can be read, written, replaced, cached, and rejected loudly when malformed.

### PLAN-Jpra-001.PH2 Lifecycle, status, and peer integration

Outcome: collective replacement and peer interruption classification switch atomically to one host record. Every cutover exit has durable state, and admitted, pending, unavailable, compact, detailed, list, and peer projections preserve host and member boundaries without touching isolated records.

### PLAN-Jpra-001.PH3 Process acceptance

Outcome: process-level acceptance proves first-attempt mode transition, rediscovery, failure semantics, peer sent-once classification, legacy-artifact rejection, and single-record persistence.

### PLAN-Jpra-001.PH4 Contract promotion and archive

Outcome: the reviewed delta is merged into the root Weaver runtime spec, user-facing docs remain accurate, and shipped feature artifacts are archived.

## PLAN-Jpra-001.P6 Validation strategy

- **PLAN-Jpra-001.V1:** A focused Go regression begins with a valid running isolated restart record and proves fresh pooled metadata survives grouped admission and Mill rediscovery unchanged.
- **PLAN-Jpra-001.V2:** Pool record tests cover the exact closed shape, state-conditioned fields, atomic replacement, cache invalidation, member ordering, frozen membership projections, hosting-mode lookup, and contradictions.
- **PLAN-Jpra-001.V3:** Lifecycle tests prove probe failure retains the old host and pending newcomer; stop, artifact-cleanup, marker-cleanup, and launch failures each retain honest unavailable-host evidence; successful replacement writes one current running host record.
- **PLAN-Jpra-001.V4:** Status tests compare admitted and pending selections after ordinary operation and Mill rediscovery: host transition evidence is shared, member identities remain distinct, and pending members have no runtime endpoint identity.
- **PLAN-Jpra-001.V5:** Clojure peer tests prove the published path resolves the exact Go-owned record, pooled planned-restart matching requires the previous member Weaver and generation identities, partial pool identity fails loudly, and a sent request is never retried.
- **PLAN-Jpra-001.V6:** Legacy tests prove valid isolated records survive pooled operation and become readable after opt-out, while positively identified alpha-era pooled records are not silently presented as isolated history.
- **PLAN-Jpra-001.V7:** Run focused warm tests while iterating, then the JVM-pool integration acceptance, Go suite, Clojure Done-when suites, and repository formatting, lint, reflection, and documentation gates.

## PLAN-Jpra-001.P7 Risks and open questions

- **PLAN-Jpra-001.R1:** Moving pooled persistence can accidentally remove peer interruption evidence. The peer phase lands with the host record and keeps identity matching as its acceptance boundary.
- **PLAN-Jpra-001.R2:** Host status after failed cutover has no ready marker. Pool membership and the retained host record must be enough to report failure without inventing a live generation.
- **PLAN-Jpra-001.R3:** A malformed host record affects every member. Parse it once at the pool boundary and fail every projection loudly with the same path and reason.

No open question blocks task generation.

## PLAN-Jpra-001.P8 Task context

- **PLAN-Jpra-001.TC1:** The observed failure used old isolated generation `c5f84e76…` and rejected fresh pooled generation `62b772ec…`. The second start succeeded only because failure persistence changed the member-local record from `running` to `failed`.
- **PLAN-Jpra-001.TC2:** Current admission calls the lifecycle-decorated reader, and current pool failure persistence loops over member state directories. Existing integration coverage adds a never-started newcomer, so it misses this mode transition.
- **PLAN-Jpra-001.TC3:** The root contract already names one host-scoped claim, transition, admission gate, and restart record. Treat the current per-member implementation as the divergence to remove, not as a compatibility surface.

## PLAN-Jpra-001.P9 Developer Notes

### PLAN-Jpra-001.DN1 Exploration 4km4t: root cause — 2026-09-11

- A temporary focused Go test reproduced the exact first-attempt admission failure. It was removed after preserving the evidence on the exploration task.

### PLAN-Jpra-001.DN2 Finish and archive — 2026-09-11

- Shipped the host-scoped pooled restart record, undecorated pooled identity
  admission, status projection boundary, peer classification, and process-level
  isolated-to-pooled first-attempt regression.
- No planned scope was cut or deferred. `DELTA-Jpra-001` was merged into the
  Weaver Runtime root spec. No RFCs were linked or archived.
