# Strand Model delta for note-primitive

**Document ID:** `SPEC-Np-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Np-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-10

## SPEC-Np-001.P1 Summary

This is the F3 behavioral delta for the relation vocabulary. F3 declares `notes` — a note strand attached to its target — as the single durable encoding of that attachment (`PROP-Np-001.C1`), the same accretion the epic already applied to `serves` in F2. The strand model's Edges section (`SPEC-001.P5`) names the shipped declared-acyclic relation set in one sentence (line 48) and, after F2, carries a `serves`/`parent-of` operational-relation paragraph in the next (line 50). This delta ADDS `notes` to the shipped declared-acyclic set and ADDS one contract sentence introducing `notes` as a core-owned operational relation. The `note/*` attribute namespace already appears in the P4 attribute-namespace roster (line 34, `note/…`) and needs no edit — F3 formalizes the _edge_, not the attribute vocabulary, which is already named. These are exact editing instructions applied at implementation time.

## SPEC-Np-001.P2 Contract changes

- **SPEC-Np-001.CC1** (edit, `SPEC-001.P5`, line 48): add `notes` to the shipped declared-acyclic enumeration. This is the doc mirror of adding `"notes"` to `shipped-acyclic-relations` in `src/skein/core/db.clj` (`db.clj:217`, currently `#{"depends-on" "parent-of" "supersedes" "serves"}`; `PROP-Np-001.C1` "acyclicity"). Token addition only; the surrounding acyclicity contract (self-edge prohibition, cycle rejection, userland extension) is unchanged.

    Old:

    ```text
    A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, `supersedes`, and `serves` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.
    ```

    New:

    ```text
    A `depends-on` edge from strand `A` to strand `B` means `A` is blocked by `B` while `B` is active. Shipped storage initialization declares `depends-on`, `parent-of`, `supersedes`, `serves`, and `notes` acyclic. Userland may declare additional acyclic relations before writing edges of that relation.
    ```

- **SPEC-Np-001.CC2** (ADD, `SPEC-001.P5`, after line 50): add one contract sentence naming `notes` as a core-owned operational relation (`PROP-Np-001.C1`, `C9` bullet 1). Insert as a new paragraph immediately after the `serves`/`parent-of` paragraph (line 50) and before the self-edge paragraph (line 52), keeping both operational-relation classifications together. Verbatim text to add:

    > The `notes` relation is a core-owned operational edge from a closed note strand to the target it annotates (note `--notes--> target`), recording append-only memory attached to that target; it is the single durable encoding of that attachment and is declared acyclic. A note's content lives in self-describing `note/*` attributes, never in a target-pointing attribute, so the edge is the sole linkage and nothing else names the target. The blessed shape is `note/text` (the content) and `note/at` (the write time), with optional `note/by` and `note/round`; note strands are born closed and stay open to decorating attributes owned by their writers.

    Rationale for placement: `SPEC-001.P5` is the Edges section that governs the named relation vocabulary and its acyclicity, so the `notes` classification belongs beside the `serves` one rather than in a spool doc. The behavioral consumers of the relation (the `note!`/`notes` primitive, the batteries `note`/`notes` verbs, the kanban/delegation writers) stay in their respective spool docs and `spools/batteries.md` (`PROP-Np-001.C9`, `SPEC-005.C3`/`C4`).

## SPEC-Np-001.P3 Companion (code, not a doc edit here)

- The advisory catalog `src/skein/api/relations/alpha.clj` gains a matching `notes` operational entry (`:family :operational`, `:direction "note --notes--> target"`, `:declared-acyclic? true`, help text), beside the `serves` entry (`alpha.clj:26`; `PROP-Np-001.C2`), and its `test/skein/relations_test.clj` catalog-set assertion updates with it. The catalog is source-visible alpha code, not part of this root-spec index; `SPEC-001.P5` line 54 already describes the catalog generically (it enumerates no entries), so adding one entry changes no strand-model contract text and is carried in the implementation plan, not this delta.

## SPEC-Np-001.P4 Flagged (out of scope for F3)

- **SPEC-Np-001.F1:** No `note/*`-shape contract is added to the strand model. The blessed note shape (`note/text`, `note/at`, optional `note/by`/`note/round`, decorating attrs; `PROP-Np-001.C3`) and the `note!`/`notes` primitive (`PROP-Np-001.C4`) are the surface of the new `skein.api.notes.alpha` namespace, whose disposition is recorded in the alpha-surface delta (`SPEC-Np-002`). The strand model names the relation and already lists the `note/…` attribute namespace (line 34); it does not describe how the primitive writes or reads it.
- **SPEC-Np-001.F2:** No cascade-mechanics statement is added. The cascade-divergence fix (`PROP-Np-001.C8`) rests on the `strand_edges` FK `ON DELETE CASCADE` (`db.clj:177`), which `SPEC-005.C8` classifies as internal storage mechanics ("cascade mechanics") beyond the declared model. The contract-visible half — the edge is the sole linkage and no attribute points at the target — is stated in `SPEC-Np-001.CC2`; the FK behavior that makes it structurally true stays internal.
