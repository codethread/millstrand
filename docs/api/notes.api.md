---

# <a name="millstrand.api.notes.alpha">millstrand.api.notes.alpha</a>

Explicit-runtime cross-spool note primitive for strand memory.

A note is an immutable, born-closed strand (memory, not work) linked to its target by an outgoing `notes` annotation edge. `note!` writes that strand and edge; `notes` walks the incoming `notes` edges to a target and projects each note in `note/at` order. The link is the edge alone — no `note/for` attribute — so a target's deletion cascades the edge and the note becomes unreachable through the read with no dangling pointer, regardless of the decorating attributes a caller layers on its notes.

Note content is immutable by storage enforcement, not convention: `note/text` and `note/at` are declared write-once keys (SPEC-001.P4), so once a note is written its content and timestamp cannot be rewritten, deleted, or archived on any mutation path. Only the caller's decorating attributes stay mutable.

Callers own runtime selection and pass the target weaver runtime as the first argument, per the blessed-namespace convention. `writer-ref->prompt` renders a plain-data `{:target :by :decoration}` ref as a note-writing CLI fragment.

## <a name="millstrand.api.notes.alpha/note!">`note!`</a>

```clojure
(note! runtime target-id text {:keys [by round], :as opts})
```

Function.

Append an immutable note strand to `target-id`'s memory and return its id.

The note is born closed, carries `note/text`, a sub-second `note/at` timestamp, optional `note/by`/`note/round`, and any caller-supplied decorating attrs, and links to the target by an outgoing `notes` edge — never a `note/for` attribute. `note/text` and `note/at` are storage-enforced write-once (SPEC-001.P4): the birth write here is legal, but no later mutation path can rewrite, delete, or archive them. Fails loudly on blank text, a missing target, or a non-integer `:round` (the `note/round` contract is single-typed).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L29-L59">Source</a></sub></p>

## <a name="millstrand.api.notes.alpha/notes">`notes`</a>

```clojure
(notes runtime target-id {:keys [round]})
```

Function.

Return `target-id`'s notes in `note/at` order, optionally one `:round`.

Walks the incoming `notes` edges to the target, so it returns notes from every writer that used the primitive regardless of their decorating attrs. Projects each note as `{:id :note :at}` plus `:by`/`:round` when present. `:round` must be an integer (fails loudly otherwise); ordering parses `note/at` so mixed fractional-precision timestamps still sort chronologically.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L61-L75">Source</a></sub></p>

## <a name="millstrand.api.notes.alpha/writer-ref->prompt">`writer-ref->prompt`</a>

```clojure
(writer-ref->prompt ref)
```

Function.

Render `ref` as the note-writing CLI instruction fragment.

This is the single renderer of the write fragment `agent note <target> "<text>" --by <author> --attr k=v …` — `<text>` stays a placeholder the agent fills in. `ref` must contain a string `:target`, an optional string `:by`, and an optional map of string `:decoration` entries; malformed refs fail loudly naming the offending field. Renders only the write instruction — no read/`agent notes` string.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L77-L102">Source</a></sub></p>
