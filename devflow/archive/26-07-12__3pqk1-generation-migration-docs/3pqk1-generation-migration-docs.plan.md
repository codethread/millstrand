# Generation migration docs Plan

**Document ID:** `PLAN-Gmd-001`

**Feature:** `3pqk1-generation-migration-docs`

**Proposal:** [proposal.md](./proposal.md)

**RFC:** design record strand `5bbrd` (point 7 one-time restart; `lwp6n` rider (b) drain-or-retry)

**Root specs:** [daemon-runtime.md](../../specs/daemon-runtime.md) SPEC-004.C44c–C44f

**Feature specs:** None

**Status:** Reviewed

**Last Updated:** 2026-07-12

## PLAN-Gmd-001.P1 Goal and scope

Land the docs half of card `3pqk1`: document the weaver-generation model and its drain-or-retry cost, the one-time migration restart, and correct any pickup-ladder or sweep wording the landed epic falsified. Prose only, no behavior change. The canonical-weaver restart stays a human decision handled outside this feature.

## PLAN-Gmd-001.P2 Approach

- **PLAN-Gmd-001.A1** (generation section): Add a `## Weaver generations and cutover` section to `docs/skein.md`, after the `## Weaver` lifecycle section and before `## CLI`. Cover: a generation is one weaver process lifetime with a boot-created spool classloader; `sync!` classifies changes additive (loads live) vs non-additive (root removal, changed source of a loaded root, loaded-coordinate version bump) which it refuses and records as `:pending-generation` with the C44c remedy text; replacing a weaver ends supervised runs, and `mill weaver stop` does not drain — it sends SIGTERM then SIGKILL, and the next weaver start recovers headless orphans via `reconcile!` (reset to `pending` for respawn, or marked `exhausted` when attempts are spent) while interactive sessions of any backend survive and are adopted; a coordinator drains with `strand agent await` or accepts the respawn/retry cost; and the one-time migration restart that sheds pre-stateless `add-libs`/basis residue, framed as a human decision. Mirror the surrounding voice; cross-reference SPEC-004.C44c–C44f.
- **PLAN-Gmd-001.A2** (pickup ladder): In `CLAUDE.md` and `AGENTS.md` line 57, extend the restart-trigger clause so a `sync!`-recorded pending generation (a refused non-additive diff) is listed alongside the JVM-level triggers, keeping the hard-rule tone. Surgical edit only.
- **PLAN-Gmd-001.A3** (falsification sweep): Confirm `writing-shared-spools.md` (already corrected by `c5kss` 55dfee3) and `docs/skein.md` carry no surviving falsified add-libs/retained-root/sync claims. Flag `library-authoring.md:126` (stale `add-libs` mechanism the epic deleted but this file missed) in the handover note rather than edit test-authoring semantics outside this card's named scope.

## PLAN-Gmd-001.P3 Validation

- `make docs-check` green.
- `make fmt-check lint` green only if anything beyond `.md` changed (it should not).
- docs-style skill sweep over the changed prose (no LLM tells; ≤1 em dash per paragraph; sentence-case headings).
- `git status` clean after an atomic commit.

## PLAN-Gmd-001.P4 Task context

Single slice, one implementer (`suvt1`). No tests beyond the docs gate. Restart remains pending user sign-off and is out of scope.
