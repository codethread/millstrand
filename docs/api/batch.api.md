
-----
# <a name="skein.api.batch.alpha">skein.api.batch.alpha</a>


Explicit-runtime API for applying batch graph mutations.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns transactional batch persistence, attribute
  normalization through transform hooks, the pre-commit validation gate, and the
  batch plus per-strand event fanout. The SQL batch engine lives in
  `skein.core.db`; the shared lifecycle and dispatch plumbing in
  `skein.core.weaver.*`.




## <a name="skein.api.batch.alpha/apply!">`apply!`</a>
``` clojure
(apply! runtime payload)
(apply! runtime payload req-ctx)
```
Function.

Apply one transactional batch graph mutation payload to `runtime`.

  The payload is a map of `:refs` (unqualified non-blank keyword -> existing
  strand id), `:strands`
  (patches keyed by `:ref`), `:edges` (ordered edge ops between refs), and
  `:burn` (refs to burn); `skein.core.db/normalize-batch-payload!` is the
  grammar authority and rejects malformed payloads loudly.

  An `:edges` entry is one of two closed ops. `{:op :upsert ...}` carries
  `:from`, `:to`, `:type`, and optional `:attributes`; it inserts a missing
  edge or replaces attributes on the matching `(from, to, type)` edge, and
  either endpoint may be a ref created earlier in the same payload.
  `{:op :remove ...}` carries exactly `:from`, `:to`, and `:type` — no
  `:attributes`, no other keys — and deletes that exact `(from, to, type)`
  edge; both endpoints must be top-level pre-bound `:refs`, never a ref
  created earlier in the payload. A remove whose exact edge is absent — a
  wrong direction or wrong relation type included — fails loudly and rolls the
  whole batch back. Edge ops execute in submitted `:edges` order inside the
  one transaction, so an ordered `:remove` then `:upsert` of one identity is a
  deterministic program.

  Each returned edge outcome is one transition with exactly `:op`, `:from`,
  `:to`, `:type`, `:before`, and `:after`. `:from` and `:to` are the submitted
  local refs and `:type` the submitted relation text; `:before` and `:after`
  are each `nil` or a normalized edge row with `:from_strand_id`,
  `:to_strand_id`, `:edge_type`, and a decoded-map `:attributes` — durable ids
  and the full attribute map, never storage JSON. An upsert carries its
  pre-image (or `nil` when the edge is new) in `:before` and the written row
  in `:after`; a remove carries the removed row in `:before` and `nil` in
  `:after`. There is no `:edge` alias. The result `:edges`, the
  `:batch/apply-before-commit` hook's `:batch/edge-ops`, and the
  `:batch/applied` event's `:batch/edges` are equal ordered transition
  vectors.

  Normalizes strand attributes through the `:attributes/normalize` transform
  hooks, persists the batch atomically, runs the `:batch/apply-before-commit`
  validation gate inside the transaction, then enqueues the batch event
  followed by the per-strand created/updated/burned fanout.

  The published shapes are the `::normalized-payload` grammar (whose `::edge-op`
  is the closed `::upsert-edge`/`::remove-edge` alternative) and the `::result`
  contract (whose `:created`/`:updated`/`:burned` entries are the closed
  `::strand-row`/`::updated-row`/`::burned-row` shapes and whose `:edges` are
  `::edge-transition`s over `::edge-row`s). Every published map boundary is
  closed: an unexpected key at the payload or result top level, on an edge row,
  or on a lifecycle entry fails loudly at the seam.
  `skein.core.db/normalize-batch-payload!` stays the grammar authority for
  malformed public input, rejecting it with detailed errors; apply! then
  consults `::normalized-payload` on that authority's output, and `::result` on
  the transactional engine's output before the pre-commit hook, events, and
  return. Those two seam checks only catch impossible drift and never weaken the
  authority's rejections. A caller-supplied `req-ctx` conforms to
  `::skein.core.specs/request-context`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/batch/alpha.clj#L25-L101">Source</a></sub></p>
