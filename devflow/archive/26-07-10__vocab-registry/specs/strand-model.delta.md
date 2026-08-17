# Strand Model delta for vocab-registry

**Document ID:** `SPEC-Vr-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Vr-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged — applied at `PLAN-Vr-001.S9` **Last Updated:** 2026-07-10

## SPEC-Vr-001.P1 Summary

F4 adds a runtime-owned vocabulary registry (`skein.api.vocab.alpha`, `PROP-Vr-001.C2`) that records which owner declares each attribute namespace and edge type. The registry is **guidance data, not a model change** — it declares no new relation, adds no acyclic declaration, and writes no attribute (`PROP-Vr-001.G1`, `NG1`, `NG2`). So this delta touches no `SPEC-001` model semantics; it only gives two existing sentences a concrete referent. `SPEC-001.P4` already states that attribute-namespace "ownership is registered in the runtime, not encoded in the key" (line 34) — a registry the spec named before it existed (`PROP-Vr-001.P1`); this delta names `skein.api.vocab.alpha` as that registry and records that the third-party-prefix rule is backed by its duplicate-owner install failure (`PROP-Vr-001.C3`, `C9`, `C10`). The `SPEC-001.P5` relations advisory-catalog paragraph (line 56) gains one sentence noting `vocab.alpha` reflects that catalog as owned `:edge` declarations (`PROP-Vr-001.C5`, `C10`). These are exact editing instructions applied at implementation time; they add no acyclic relation and no `db.clj` change (contrast `SPEC-Np-001`, which added `notes` to `shipped-acyclic-relations`).

## SPEC-Vr-001.P2 Contract changes

- **SPEC-Vr-001.CC1** (edit, `SPEC-001.P4`, line 34): the attribute-namespace prose gains a concrete referent for the runtime ownership registry and backs the third-party-prefix rule with the duplicate-owner install failure (`PROP-Vr-001.C10` bullet 1, `C3`, `C9`). Two in-sentence insertions only; the surrounding contract (namespaces name concepts not owners, self-describing compound nouns, prefix convention) is unchanged.

    Old:

    ```text
    Attribute namespaces name concepts, not owners. A namespace segment identifies the concept the attribute describes (`agent-run/…`, `review/…`, `panel/…`, `note/…`, `gate/…`), never the spool that happens to write it; ownership is registered in the runtime, not encoded in the key. Names that ride durable strand data or worker prompts must be self-describing compound nouns a cold reader can decode from `strand show` alone; contributor-internalized names — namespaces, directories, local `:as` aliases — may stay short. Third-party spools qualify their attribute namespaces with a project prefix so they never collide with the core vocabulary.
    ```

    New:

    ```text
    Attribute namespaces name concepts, not owners. A namespace segment identifies the concept the attribute describes (`agent-run/…`, `review/…`, `panel/…`, `note/…`, `gate/…`), never the spool that happens to write it; ownership is registered in the runtime — the `skein.api.vocab.alpha` registry records which owner declares each namespace — not encoded in the key. Names that ride durable strand data or worker prompts must be self-describing compound nouns a cold reader can decode from `strand show` alone; contributor-internalized names — namespaces, directories, local `:as` aliases — may stay short. Third-party spools qualify their attribute namespaces with a project prefix so they never collide with the core vocabulary; a colliding namespace claim fails loudly at install through the registry's duplicate-owner edge.
    ```

- **SPEC-Vr-001.CC2** (edit, `SPEC-001.P5`, line 56): the relations advisory-catalog paragraph gains one sentence noting `vocab.alpha` reflects the shipped edge catalog as owned `:edge` declarations, one source, no fork (`PROP-Vr-001.C5`, `C10` bullet 1, `Q3`). Insert the new sentence immediately after the "documentation-only … remain valid userland annotations." sentence and before the closing "As an `skein.api.*.alpha` namespace …" sentence, keeping the catalog's own compatibility statement last. Token-level insertion; `relations.alpha` itself is unchanged (`PROP-Vr-001.C11`).

    Old:

    ```text
    The blessed `skein.api.relations.alpha` namespace ships a source-visible advisory catalog of this relation vocabulary for agents, config, and REPL workflows: `catalog` data plus `relation`, `operational-relations`, and `annotation-relations` lookups, each entry carrying the relation's family (operational battery vs behavior-free annotation), direction gloss, declared-acyclicity flag, and help text. It is documentation-only — not a storage allowlist or runtime relation-semantics registry — so relation names outside the catalog remain valid userland annotations. As an `skein.api.*.alpha` namespace it carries accretion-based compatibility within the subnamespace.
    ```

    New:

    ```text
    The blessed `skein.api.relations.alpha` namespace ships a source-visible advisory catalog of this relation vocabulary for agents, config, and REPL workflows: `catalog` data plus `relation`, `operational-relations`, and `annotation-relations` lookups, each entry carrying the relation's family (operational battery vs behavior-free annotation), direction gloss, declared-acyclicity flag, and help text. It is documentation-only — not a storage allowlist or runtime relation-semantics registry — so relation names outside the catalog remain valid userland annotations. The `skein.api.vocab.alpha` registry reflects this catalog as owned `:edge` declarations (owner `:skein/core`) so the shipped edge vocabulary has one source and never forks. As an `skein.api.*.alpha` namespace it carries accretion-based compatibility within the subnamespace.
    ```

## SPEC-Vr-001.P3 Dispositions (no change)

- **No acyclic-relation change.** The registry declares no relation and no acyclic edge; it reflects the _existing_ catalog. The `SPEC-001.P5` shipped declared-acyclic enumeration (line 48) is untouched — unlike `SPEC-Np-001.CC1`, which added `notes`. There is therefore no companion `src/skein/core/db.clj` `shipped-acyclic-relations` edit (`PROP-Vr-001.C11` final bullet).
- **No attribute-namespace roster edit.** The `SPEC-001.P4` example roster (`agent-run/…`, `review/…`, `panel/…`, `note/…`, `gate/…`, line 34) already names the concept-vocabularies the seed declares; F4 registers ownership of the namespaces the spec already lists, it does not introduce a new attribute vocabulary, so the roster needs no addition.
- **The edge seed** (`PROP-Vr-001.C5`) reads `skein.api.relations.alpha/catalog` and changes nothing in `relations.alpha`; its `test/skein/relations_test.clj` catalog-set assertion is untouched (applied as a code/no-op fact in the plan, not a strand-model edit — mirrors `SPEC-Np-001.P3`).

## SPEC-Vr-001.P4 Flagged (out of scope for F4)

- **SPEC-Vr-001.F1:** No `note/*`-shape or ownership sentence is added to the strand model for the registry's declaration _shape_ (`:kind`/`:name`/`:owner`/`:keys`/`:doc`, `PROP-Vr-001.C1`). That shape is the surface of the new `skein.api.vocab.alpha` namespace, whose alpha-surface disposition is `SPEC-Vr-002`; the strand model names the registry and what it records, not how a declaration map is keyed.
- **SPEC-Vr-001.F2:** No write-time-enforcement statement. The registry gates no write (`PROP-Vr-001.NG1`, `C13`); the `SPEC-001.P4`/`P5` contract that undeclared namespaces and userland annotation relations still write and read is unchanged. The one hard edge is the cross-owner install failure (`CC1`), which is an install-time activation fact, not a strand-model write rule.
