# Strand Model delta for run-usage

**Document ID:** `SPEC-Ru-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Ru-001`) **Contract:** [../brief.md](../brief.md) **Status:** No change — kept for delta-set completeness **Last Updated:** 2026-07-10

## SPEC-Ru-001.P1 Summary

**No strand-model contract change.** F-Ru records four new usage figures on the run strand — `agent-run/cost-usd`, `agent-run/tokens-total`, `agent-run/tokens`, `agent-run/usage-source` (`PROP-Ru-001.C1`) — captured at completion by the agent-run engine. These are new _keys_ under the `agent-run/*` attribute namespace the model already names, not a new namespace, relation, acyclic edge, or storage kind. `SPEC-001` governs the shape of the attribute-namespace vocabulary and its ownership discipline, neither of which moves: the keys are JSON `TEXT` on the existing `attributes` table (`PROP-Ru-001.NG3`), declared through the F4 registry the strand model already names (`SPEC-Vr-001.CC1`; `PROP-Ru-001.C6`). This delta exists to record that the new `agent-run/*` keys fit the existing strand model without changing it.

## SPEC-Ru-001.P2 Contract changes

- None. `SPEC-001.P4` states attribute namespaces "name concepts, not owners" and lists `agent-run/…` in its example roster; adding usage keys under that already-rostered namespace introduces no new concept-vocabulary, so the roster needs no addition (mirrors `SPEC-Vr-001.P3` "no attribute-namespace roster edit"). `SPEC-001.P4` further states "ownership is registered in the runtime … not encoded in the key" and — after `SPEC-Vr-001.CC1` — names `skein.api.vocab.alpha` as that registry; the usage keys are declared by extending the existing engine-owned `agent-run` declaration through that same registry (`PROP-Ru-001.C6`), which is the model behaving exactly as its prose already describes, not a change to it.
- No acyclic-relation change and no `src/skein/core/db.clj` `shipped-acyclic-relations` edit: F-Ru declares no relation and adds no edge (contrast `SPEC-Np-001.CC1`, which added `notes`). The `SPEC-001.P5` shipped declared-acyclic enumeration is untouched.

## SPEC-Ru-001.P3 Flagged (out of scope for F-Ru)

- **SPEC-Ru-001.F1:** No attribute-shape or invariant clause is added for the nested `agent-run/tokens` breakdown map (`{input, output, cache-read, cache-write, reasoning}`, `PROP-Ru-001.C1`). Attribute _values_ are opaque JSON `TEXT` to the model (`SPEC-001` names namespaces and ownership, not per-key value schemas); the breakdown's shape is a spool concern documented in `spools/agent-run.api.md`, not a strand-model invariant.
- **SPEC-Ru-001.F2:** No wall-time attribute and therefore no model clause for one. Duration is derived at query time from the existing `agent-run/started-at`/`agent-run/finished-at` instants (`PROP-Ru-001.C1`, `Q5`); no `agent-run/duration-*` key is stored, so the model gains no durable duration contract.
