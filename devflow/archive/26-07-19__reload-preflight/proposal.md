# Reload preflight proposal

**Document ID:** `PROP-Rpf-001` **Last Updated:** 2026-07-19 **Related RFCs:** None **Related root specs:** [Weaver Runtime](../../specs/daemon-runtime.md) (SPEC-004.C46, SPEC-004.C96, SPEC-004.C44c, SPEC-004.C44d, SPEC-004.C44a, SPEC-004.C44f)

## PROP-Rpf-001.P1 Problem

`runtime/reload!` is documented as the safe alternative to a weaver restart, but with an unsyncable spool config on disk it destroys the world it was asked to refresh. Observed live (card w77oj, 2026-07-19): `reload-config!` clears every weaver-lifetime registry and the public approved-spool sync results (the in-generation baselines of SPEC-004.C44f persist), then re-runs `init.clj`, whose `sync!` refuses the non-additive diff — a previously successful root repointed at a new git sha, per SPEC-004.C44c — and throws. The refusal is correct, but it lands after the clear: the weaver is left with built-in ops only and empty public sync state, and no in-JVM recovery exists — replaying `init.clj` without `sync!` fails the required-module guards, and the local overlay cannot re-pin a git generation. The canonical weaver had to be replaced, which is exactly the outcome `reload!` exists to avoid. The same wedge follows from any config `sync!` throws on as a whole: a non-additive diff (SPEC-004.C44c, C44f), an invalid config shape, or an atomically failing Maven universe (SPEC-004.C44a, C44b). Per-root `:failed` outcomes are not in this class: `sync!` records them and succeeds, so they cannot wedge a reload.

## PROP-Rpf-001.P2 Goals

- **PROP-Rpf-001.G1:** A `reload!` refused at preflight — because the on-disk config is one the sync phase would throw on as a whole — leaves registries, public sync state, module-use state, and the event system untouched. The only permitted mutation on that path is PROP-Rpf-001.G2's pending-generation record.
- **PROP-Rpf-001.G2:** A preflight refusal of a non-additive config records the same `:pending-generation` a refused `sync!` records (SPEC-004.C44d), so the restart remedy stays discoverable through `syncs`.

## PROP-Rpf-001.P3 Non-goals

- **PROP-Rpf-001.NG1:** Guarantees about startup files that fail after preflight passes. SPEC-004.C96 continues to govern mid-file failures (keep what loaded, rethrow); preflight does not prove that `init.clj` or a later `sync!` call will succeed.
- **PROP-Rpf-001.NG2:** Turning per-root `:failed` sync outcomes into reload refusals. They succeed the sync today and continue to.
- **PROP-Rpf-001.NG3:** Accepting git coordinates in `spools.local.edn`, or any change to overlay schema.
- **PROP-Rpf-001.NG4:** In-JVM application of non-additive diffs. The generation model and its restart cutover are unchanged.

## PROP-Rpf-001.P4 Proposed scope

- **PROP-Rpf-001.S1:** `reload!` refuses before mutating: when the on-disk approved config is one the sync phase would throw on as a whole (non-additive diff, invalid config, atomically failing Maven universe), the reload throws that failure with the world untouched apart from the pending-generation record of PROP-Rpf-001.G2. A config outside that class reaches the existing clear-and-reload path unchanged.
- **PROP-Rpf-001.S2:** SPEC-004.C46 and SPEC-004.C96 state the refusal contract: the registry-clearing phase of `reload!` is only reachable with a config the sync phase accepts.

## PROP-Rpf-001.P5 Open questions

None.
