# Generation migration docs Proposal

**Document ID:** `PROP-Gmd-001`

**Last Updated:** 2026-07-12

**Related RFCs:** design record strand `5bbrd` (point 7 one-time restart; `lwp6n` rider (b) drain-or-retry)

**Related root specs:** [`devflow/specs/daemon-runtime.md`](../../specs/daemon-runtime.md) SPEC-004.C44c–C44f

This is the **docs half** of card `3pqk1`, closing out the `5bbrd` design after the `c5kss` stateless-resolution, `w92pn` diff-classification, and `ypy3h` version-bump epic landed. The one-time canonical-weaver restart itself is a human decision the coordinator handles; this feature only documents it.

## PROP-Gmd-001.P1 Problem

The landed epic changed how a weaver picks up config changes, but the user-facing docs still describe the old model. Two facts have no home in `docs/skein.md`: that a weaver generation is a process boundary with a drain-or-retry cost for running agent runs, and that a weaver predating stateless resolution needs one restart to shed its `add-libs`/basis residue. The pickup-ladder hard rule in `CLAUDE.md`/`AGENTS.md` also lists the restart triggers without the new pending-generation trigger, and at least one doc still names the deleted `add-libs` mechanism.

## PROP-Gmd-001.P2 Goals

- **PROP-Gmd-001.G1:** Document the weaver-generation model in `docs/skein.md`: process boundary, additive vs non-additive sync classification, the recorded `:pending-generation`, and the drain-or-retry discipline for running agent runs.
- **PROP-Gmd-001.G2:** Document the one-time migration restart that sheds pre-stateless residue, framed as a human decision.
- **PROP-Gmd-001.G3:** Correct the `CLAUDE.md`/`AGENTS.md` pickup ladder so a recorded pending generation is a listed restart trigger.
- **PROP-Gmd-001.G4:** Sweep the docs the epic could have falsified (retained-root, add-libs, sync semantics) and correct or flag surviving stale wording.

## PROP-Gmd-001.P3 Non-goals

- **PROP-Gmd-001.NG1:** Performing or scheduling the canonical-weaver restart. It stays a human decision under the existing sign-off rule.
- **PROP-Gmd-001.NG2:** Any behavior change. This feature is prose only; the epic already shipped the behavior.
- **PROP-Gmd-001.NG3:** Re-fixing docs the epic already corrected (the `writing-shared-spools.md` retained-root caution was updated by `c5kss`).

## PROP-Gmd-001.P4 Proposed scope

- **PROP-Gmd-001.S1:** A generation/cutover section in `docs/skein.md` covering the process boundary, additive vs non-additive sync, the pending-generation record and remedy, the stop behavior (`mill weaver stop` sends SIGTERM then SIGKILL and does not drain; headless orphans are recovered at the next start by `reconcile!` — reset to `pending` for respawn or marked `exhausted` when attempts are spent; interactive sessions of any backend survive and are adopted by the next generation), the coordinator's `strand agent await` drain-or-accept-retry choice, and the one-time migration restart.
- **PROP-Gmd-001.S2:** A surgical edit to the `CLAUDE.md`/`AGENTS.md` pickup-ladder hard rule adding the pending-generation restart trigger, in the existing hard-rule tone.
- **PROP-Gmd-001.S3:** A sweep result: correct or flag any surviving falsified statement (e.g. a doc still naming `add-libs`) the epic did not already fix.

## PROP-Gmd-001.P5 Open questions

- **PROP-Gmd-001.Q1:** None. The card body is the settled contract.
