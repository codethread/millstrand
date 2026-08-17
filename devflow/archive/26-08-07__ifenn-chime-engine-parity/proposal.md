# Chime Engine Parity Proposal

**Document ID:** `PROP-Chp-001` **Last Updated:** 2026-07-23 **Related RFCs:** None **Related root specs:** [SPEC-004 daemon-runtime](../../specs/daemon-runtime.md) (C65, C74a, C76), [SPEC-003 repl-api](../../specs/repl-api.md)

## PROP-Chp-001.P1 Problem

Chime's module lifecycle never registers its engine. `contribute` returns `{}` (rule kinds only) and `reconcile` only baselines rules; the `:chime/registration-barrier` pre-commit hook and `:chime/engine` event handler are registered only by the legacy `install!`, which production no longer calls — `.millstrand/init.clj` activates chime via `runtime/module!`. Verified against the live canonical weaver: `events/handlers` lists agent-run/shell/subagent engines and no `:chime/engine`. Event-driven chime notifications (attention rules, HITL checkpoints, agent failures) are silently dead in the canonical world.

The escape route: chime tests activate via `install!`, production via modules — no test covers the module path end to end. TASK-Olr-008.MI4 deliberately kept the handler/hook as identity-stable live state, but their registration was never moved onto the module path.

## PROP-Chp-001.P2 Goals

- **PROP-Chp-001.G1:** Chime activated via `runtime/module!` has a working engine: hook and event handler registered on an applied contribution, unregistered on removal.
- **PROP-Chp-001.G2:** A module-activated end-to-end regression test guards the module path so this parity-bug class cannot silently recur for chime.
- **PROP-Chp-001.G3:** SPEC-004.C74a describes module activation instead of the stale `install!` framing.
- **PROP-Chp-001.G4:** The canonical world's chime engine is verified live (`events/handlers` shows `:chime/engine`; a real attention rule fires).

## PROP-Chp-001.P3 Non-goals

- **PROP-Chp-001.NG1:** Deleting chime's `install!` — that is the in-tree installer-removal feature of epic waq0l.
- **PROP-Chp-001.NG2:** Moving the hook/handler into contributed `:hooks`/`:events` entries — the epic's ADR feature decides that; this fix takes the gate-2-blessed "singletons may stay reconcile" path (note mtl40) and must not block on the ADR.
- **PROP-Chp-001.NG3:** Notifier policy changes or parity work in other spools.

## PROP-Chp-001.P4 Proposed scope

- **PROP-Chp-001.S1:** Chime's `reconcile` branches on the module contribution status as the shell executor models, while preserving chime's existing rule reconciliation on every branch that runs it: applied registers the hook and event handler and performs the existing baseline/effective-view reconciliation; removed unregisters both and still reconciles the rule view (empty effective view published, seen-notification entries cleaned) so stale rules never stay visible to direct `scan!` calls or later reactivation; any other status fails loudly (TEN-003).
- **PROP-Chp-001.S2:** Repeated reconcile stays idempotent per SPEC-004.C65/C76 (duplicate keys replace prior entries) — verified by test, not assumed.
- **PROP-Chp-001.S3:** A regression test activates chime on a disposable runtime via `runtime/module!` (not `install!`), emits a strand mutation, and asserts the handler fired and the hook is present; it also asserts repeated application stays singular (no duplicate hook/handler entries) and that module removal eliminates both `:chime/engine` and `:chime/registration-barrier` and retracts the visible rule view.
- **PROP-Chp-001.S4:** SPEC-004.C74a is rewritten for module activation, keeping the chime-spool-contract deferral sentence.

## PROP-Chp-001.P5 Open questions

- **PROP-Chp-001.Q1:** None — design constraints are settled by the epic study note (decisions 2 and 8) and the card scope.
