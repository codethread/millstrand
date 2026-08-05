# Weaver Runtime delta for the consumer testing contract

**Document ID:** `DELTA-Tst-001`
**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Merged into `SPEC-004.C74b`
**Last Updated:** 2026-08-05

## DELTA-Tst-001.P1 Summary

`skein.test.alpha/await-quiescent!` currently obtains its default timeout by dynamically resolving the repository-only `skein.spools.test-support/await-budget-ms`. That makes the shipped author-side API incomplete on a normal dependency classpath. The default becomes self-contained while repository tests keep their scalable await budget through explicit options.

## DELTA-Tst-001.P2 Contract changes

- **DELTA-Tst-001.CC1 (amends `SPEC-004.C74b`):** The no-options `await-quiescent!` call uses a self-contained 10,000 ms default. `:timeout-ms` remains the explicit positive-integer override. The clause no longer names or resolves `skein.spools.test-support`; repository tests that need `SKEIN_TEST_AWAIT_SCALE` pass their scaled internal budget explicitly.
- **DELTA-Tst-001.CC2:** Lane settlement, timeout failure, dispatch-in-progress coordination, and the exclusion of off-lane completion remain unchanged.

## DELTA-Tst-001.P3 Design decision

### DELTA-Tst-001.D1 Keep repository timing policy out of the shipped API

- **Decision:** The shipped default is local to `skein.test.alpha`. Environment-scaled repository budgets remain in test-only support and are passed explicitly.
- **Rationale:** External users must be able to require and call the shipped helper with only Skein's normal source classpath. A repository test knob is not a consumer dependency.
- **Rejected:** Shipping `skein.spools.test-support`, adding another shared helper namespace, or retaining a dynamic dependency on the test classpath.

## DELTA-Tst-001.P4 Open questions

None.
