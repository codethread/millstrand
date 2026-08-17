# Brief: cut the views namespace (b8vld)

Card b8vld (p1, gates the v1 stamp b3v1r): decide whether `skein.api.views.alpha` ships into the accretion-only v1 or is cut.

## Decision

User decision 2026-07-18: **CUT, aggressively.**

Grounds, confirmed against the live tree:

- Zero first-party consumers: no in-repo spool, no sibling spool, no batteries op, no CLI weave. Only wiring is three client RPC dispatch slots, one smoke section, and tests.
- Views are a strictly weaker duplicate of read-class ops: `register-op!` with `:hook-class :read` gives everything a view gives plus doc, arg-spec, return shape, CLI weave, provenance, loud collision failure, deadline class, and streaming. Declarative graph reads are already covered by named queries.
- The audit counterweight (out-of-tree client on `:views`/`:view!`) is closed: the Go CLI has zero references, `skein.userland.alpha` names views only as unwrapped surface, and the sibling-spool sweep found nothing.
- Under the accretion regime re-entry is free: a future view mechanism returns as a new root namespace at zero cost, while keeping it now freezes an unused mechanism forever.

## Scope

Remove the mechanism whole: namespace, client RPC slots, weaver registry state, smoke section, tests, spec contracts (SPEC-004.C51/C56–C59 and every views mention in the root specs), and generated API docs. No replacement surface is introduced.
