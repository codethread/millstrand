# Strand Model delta for xijst-edge-removal

**Document ID:** `DELTA-Xer-001` **Root spec:** [strand-model.md](../../../specs/strand-model.md) (`SPEC-001`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Xer-001`) **Status:** Merged **Last Updated:** 2026-07-21 **Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version: `DELTA-Xer-001` for v1, `DELTA-Xer-001@2` for v2. Omit `@1`. Prefix every nested point ID with the full document ID (`DELTA-Xer-001.P1`) so references are globally grepable.

## DELTA-Xer-001.P1 Summary

The batch graph mutation primitive gains a second edge op, `:remove`, beside the shipped `:upsert`, and every edge outcome becomes a uniform before/after transition. Both changes land in `SPEC-001.P6` (batch graph mutation). Nothing else in the strand model moves: `SPEC-001.P5` (edges) keeps its invariant list unchanged because removal adds no new invariant, and `SPEC-001.P7` (readiness) is untouched because a removal that unblocks work is legal domain policy, not an engine promise.

The change is source-visible through this one delta; `devflow/specs/alpha-surface.md` (`SPEC-005.C2`) needs no delta, because `batch` is already a blessed namespace and the op accretes within its subnamespace under `TEN-000@1`. The `skein.api.batch.alpha/apply!` docstring and the user docs (`docs/reference.md`, `docs/tutorial.md`) carry the same grammar, but those are implementation and documentation edits owned by the plan, not durable spec contract, so they are not spec deltas.

## DELTA-Xer-001.P2 Contract changes

- **DELTA-Xer-001.CC1** (edit, `SPEC-001.P6`, the "Batch edge entries…" paragraph, current line 74): the edge grammar names both `:upsert` and `:remove`, fixes remove identity and its pre-bound-ref restriction, and states the fail-loud-on-absence and submitted-order rules. Token-level rewrite of the whole paragraph.

    Old:

    ```text
    Batch edge entries are explicit operations addressed by local refs. The initial supported operation is `{:op :upsert ...}` with `:from`, `:to`, `:type`, and optional `:attributes`. Edge upsert inserts a missing edge or replaces attributes on an existing matching `(from, to, type)` edge. Edge upsert validates endpoint refs, target existence after create/update resolution, valid relation name, JSON object attributes, universal self-edge prohibition, declared-relation acyclicity, and that neither endpoint is burned in the same payload. Omitted edges do not imply deletion or replacement; unsupported edge operations fail loudly.
    ```

    New:

    ```text
    Batch edge entries are explicit operations addressed by local refs, one of two closed ops. `{:op :upsert ...}` carries `:from`, `:to`, `:type`, and optional `:attributes`; it inserts a missing edge or replaces attributes on an existing matching `(from, to, type)` edge, and either endpoint may be a ref created earlier in the same payload. `{:op :remove ...}` carries exactly `:from`, `:to`, and `:type` besides `:op` — no `:attributes`, no other keys — and deletes the exact `(from, to, type)` edge; both endpoints must be top-level pre-bound `:refs`, never a ref created earlier in the same payload. Edge attributes are neither a selector nor a precondition for removal. Every edge op validates endpoint refs, a valid relation name, and that neither endpoint is burned in the same payload; upsert additionally validates target existence after create/update resolution, JSON object attributes, universal self-edge prohibition, and declared-relation acyclicity, none of which a remove can violate because deleting a row only shrinks the edge set. A remove whose exact edge is absent — including a wrong direction or a wrong relation type — fails after a clean post-lock lookup with `ex-data` exactly `{:from submitted-from :to submitted-to :from-id resolved-from-id :to-id resolved-to-id :type submitted-type}` and rolls the whole batch back; absence is strict, never idempotent, with no ignore-missing flag. Edge ops execute in submitted `:edges` order inside the one batch transaction, so an ordered `:remove` then `:upsert` of one identity is a deterministic program. Omitted edges do not imply deletion or replacement; unsupported edge operations fail loudly.
    ```

- **DELTA-Xer-001.CC2** (edit, `SPEC-001.P6`, the "A batch result…" paragraph, current line 76): every edge outcome becomes the uniform before/after transition, and the result, hook, and event carry equal ordered transition vectors. Rewrite of the paragraph.

    Old:

    ```text
    A batch result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes.
    ```

    New:

    ```text
    The public `skein.api.batch.alpha/apply!` result includes the final ref table and normalized summaries of created strands, updated before/after rows, burned ids with pre-delete rows, and edge outcomes. Each edge outcome is one transition with exactly `:op`, `:from`, `:to`, `:type`, `:before`, and `:after`: `:from` and `:to` are the submitted local refs and `:type` the submitted relation text. `:before` and `:after` are each either `nil` or a normalized edge row with exactly `:from_strand_id`, `:to_strand_id`, `:edge_type`, and a decoded-map `:attributes` — durable ids and the full attribute map, never storage JSON. `apply!` normalizes the storage-shaped core result before the hook, returned result, and event. An upsert carries the pre-image row (or `nil` when the edge is new) in `:before` and the written row in `:after`; a remove carries the removed row in `:before` and `nil` in `:after`. Outcomes stay aligned to submitted `:edges` order, and the result `:edges`, the pre-commit hook's `:batch/edge-ops`, and the `:batch/applied` event's `:batch/edges` are equal ordered vectors of these transitions, with no `:edge` alias.
    ```

## DELTA-Xer-001.P3 Design decisions

These record the settled contract from `PROP-Xer-001`, the decision note (`xh4nd`), and the council synthesis (`3773y`). They are captured here so promotion carries the rationale; they are not reopened at implementation.

### DELTA-Xer-001.D1 Exact identity, strict fail-loud on absence

- **Decision:** `:remove` names identity `(from, to, type)` alone and deletes only that row. A clean post-lock lookup that finds no matching row — wrong direction and wrong relation type included — fails with `ex-data` exactly `{:from submitted-from :to submitted-to :from-id resolved-from-id :to-id resolved-to-id :type submitted-type}` and rolls the whole batch back.
- **Rationale:** `TEN-003` (fail loudly): a stale remover that finds the edge already gone must reread and reconcile, not silently succeed. Edge attributes are neither identity, selector, nor a compare-and-set precondition, and `:remove` does not submit them.
- **Rejected:** An ignore-missing/idempotent flag; attribute-matched or compare-and-set removal. Privileged idempotent cutover migrations keep their own lower-level path, out of this contract.

### DELTA-Xer-001.D2 Top-level pre-bound refs only

- **Decision:** Both `:remove` endpoints must resolve through the top-level `:refs` map. A remove rejects a ref created earlier in the same payload even when an earlier upsert created the edge; upsert keeps its ability to name newly created refs. Neither endpoint may be burned in the same payload.
- **Rationale:** Removal targets an edge that already exists in the committed graph; binding it to durable pre-bound refs keeps the target unambiguous and the identity resolvable before any create resolution.
- **Rejected:** Letting remove name freshly created refs, which would only ever match an edge minted in the same payload — a case an ordered program expresses without the ambiguity.

### DELTA-Xer-001.D3 Ordered same-transaction state machine

- **Decision:** Edge ops execute in submitted `:edges` order, each at its vector position, inside the single batch transaction. Any failing op — an absent remove, a rejected upsert, a burn conflict, or the pre-commit hook — rolls back every strand, edge, and burn mutation in the payload.
- **Rationale:** Ordered execution is what makes a legal program commit and an illegal order roll back: a `serves` swap from one source to a new target or a DAG reversal only succeeds when the remove precedes the upsert (`PROP-Xer-001.T1`, `PROP-Xer-001.T2`). Replacement is caller-composed from the ordered pair, not a new op.
- **Rejected:** A hidden remove-first phase, blanket duplicate-identity rejection, a generic replace/rewire op, and nested batch stages — each would change shipped edge-vector semantics and belongs to a separate grammar redesign.

### DELTA-Xer-001.D4 One uniform transition, existing channels only

- **Decision:** Every edge outcome is the before/after transition of `CC2`, replacing the upsert-only `:edge` outcome with no compatibility alias. The db result remains storage-shaped; `apply!` normalizes it before the public result `:edges`, the `:batch/apply-before-commit` hook's `:batch/edge-ops`, and the `:batch/applied` event's `:batch/edges` carry the same ordered vector by value, including before-images and after-images.
- **Rationale:** One outcome shape lets a hook or caller read every edge transition — new, replaced, or removed — the same way (`PROP-Xer-001.G2`). It is one shape change threaded through the existing plumbing, not a new channel.
- **Rejected:** A `:edge` compatibility alias (`TEN-000@1` drops old ideas without migration); a new `:edge/removed` event; a graph projector shipped in core; a durable audit or outbox promise. Post-commit enqueue stays bounded and non-transactional — recovery records belong to caller and domain workflows.

### DELTA-Xer-001.D5 No new invariant

- **Decision:** Core adds no connectivity, reachability, root, stable-root, readiness, degree, or lineage-completeness guard. `SPEC-001.P5` keeps exactly its shipped edge invariants and `SPEC-001.P7` its readiness definition. Exact removal may legally strand a node, create a root, hide a subtree from a rooted view, detach a note's sole linkage, erase a `serves` delegation, or make blocked work ready.
- **Rationale:** `TEN-004` (less is more) and `3773y` §2: none of those outcomes is engine corruption; they are domain policy owned by hooks and callers. A registered hook is where a world requires approval before a `depends-on` removal unblocks work, or a replacement pair before a `serves` retire.
- **Rejected:** Any whole-graph structural promise, and any change to `supersede!` rewiring — replacement stays caller-composed.

## DELTA-Xer-001.P4 Open questions

- **DELTA-Xer-001.Q1:** None. `PROP-Xer-001.P9` records the three questions (absent-edge behavior, contention versus absence, outcome key placement) as resolved by the decision; this delta reflects the settled answers.
