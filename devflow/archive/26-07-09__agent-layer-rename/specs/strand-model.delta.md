# Strand Model delta for agent-layer-rename

**Document ID:** `SPEC-Alr-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Alr-001`) **Rename table:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-09

## SPEC-Alr-001.P1 Summary

This is the F1 mechanical-rename delta (no behavior change). The strand model's attribute section carries one example that names the renamed `shuttle/*` vocabulary; it moves to `agent-run/*`. The feature also **institutionalizes** the naming rule that motivated the rename, so this delta ADDS one contract statement to the attribute section (`SPEC-001.P4`). These are exact editing instructions applied at implementation time.

## SPEC-Alr-001.P2 Contract changes

- **SPEC-Alr-001.CC1** (edit, `SPEC-001.P4`, line 32): the namespace round-trip example renames `shuttle/*` → `agent-run/*`. Token swap only; the round-trip contract is unchanged.

    Old:

    ```text
    Namespaced userland vocabularies such as `workflow/*` and `shuttle/*` therefore round-trip without collapsing distinct namespaces onto the same local name.
    ```

    New:

    ```text
    Namespaced userland vocabularies such as `workflow/*` and `agent-run/*` therefore round-trip without collapsing distinct namespaces onto the same local name.
    ```

- **SPEC-Alr-001.CC2** (ADD, `SPEC-001.P4` Attributes): add the institutionalized naming rule as a new contract paragraph, inserted immediately after the paragraph ending `…collapsing distinct namespaces onto the same local name.` (line 32 as renamed by CC1). This is the naming rule from `brief.md` ("Naming rule (institutionalized by this feature)") and `PROP-Alr-001.G2`. Verbatim text to add:

    > Attribute namespaces name concepts, not owners. A namespace segment identifies the concept the attribute describes (`agent-run/…`, `review/…`, `panel/…`, `note/…`, `gate/…`), never the spool that happens to write it; ownership is registered in the runtime, not encoded in the key. Names that ride durable strand data or worker prompts must be self-describing compound nouns a cold reader can decode from `strand show` alone; contributor-internalized names — namespaces, directories, local `:as` aliases — may stay short. Third-party spools qualify their attribute namespaces with a project prefix so they never collide with the core vocabulary.

    Rationale for placement: `SPEC-001.P4` is the spec section that governs attribute keys and namespaces, so the attribute-namespace naming rule belongs here rather than scattered across spool docs.

## SPEC-Alr-001.P3 Flagged (out of scope for F1)

- **SPEC-Alr-001.F1:** None. Both changes are a token swap and an additive statement of an already-decided rule; no strand-model behavior contract changes. Behavioral rework of the renamed markers (dropping `agent-run/run`, the `serves` edge) is `PROP-Alr-001.NG3` / F2, not this spec.
