---

# <a name="millstrand.api.notes.alpha">millstrand.api.notes.alpha</a>

Explicit-runtime cross-spool note primitive for strand memory.

A note is an immutable, born-closed strand (memory, not work) linked to its target by an outgoing `notes` annotation edge. `note!` writes that strand and edge; `notes` walks the incoming `notes` edges to a target and projects each note in `note/at` order. The link is the edge alone — no `note/for` attribute — so a target's deletion cascades the edge and the note becomes unreachable through the read with no dangling pointer, regardless of the decorating attributes a caller layers on its notes.

Note content is immutable by storage enforcement, not convention: `note/text` and `note/at` are declared write-once keys (SPEC-001.P4), so once a note is written its content and timestamp cannot be rewritten, deleted, or archived on any mutation path. The primitive also owns `note/round`; callers cannot supply `note/text`, `note/at`, or `note/round` as decorating attributes, in either string- or keyword-keyed form. Only other caller decorations stay mutable.

Callers own runtime selection and pass the target weaver runtime as the first argument, per the blessed-namespace convention. `writer-ref->prompt` renders a plain-data `{:target :identity/by-identity :decoration}` ref as a note-writing CLI fragment.

## <a name="millstrand.api.notes.alpha/note!">`note!`</a>

```clojure
(note! runtime target-id text {:identity/keys [by-identity], :keys [round], :as opts})
```

Function.

Append an immutable note strand to `target-id`'s memory and return its id.

The note is born closed, carries `note/text`, a sub-second `note/at` timestamp, optional `identity/by-identity`/`note/round`, and any caller-supplied decorating attrs, and links to the target by an outgoing `notes` edge — never a `note/for` attribute. `note/text` and `note/at` are storage-enforced write-once (SPEC-001.P4): the birth write here is legal, but no later mutation path can rewrite, delete, or archive them. The primitive-owned `note/text`, `note/at`, and `note/round` keys are rejected as decorations in both string- and keyword-keyed forms. `:identity/by-identity` accepts a non-blank friendly identity string without registry lookup; absent identity attribution leaves the note valid. Fails loudly on blank text, a missing target, old `:by` attribution, or a non-integer `:round` (the `note/round` contract is single-typed).
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L37-L73">Source</a></sub></p>

## <a name="millstrand.api.notes.alpha/notes">`notes`</a>

```clojure
(notes runtime target-id {:keys [round]})
```

Function.

Return `target-id`'s notes in `note/at` order, optionally one `:round`.

Walks the incoming `notes` edges to the target, so it returns notes from every writer that used the primitive regardless of their decorating attrs. Projects each note as `{:id :note :at}` plus `:by-identity`/`:round` when present. `:round` must be an integer (fails loudly otherwise); ordering parses `note/at` so mixed fractional-precision timestamps still sort chronologically.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L75-L90">Source</a></sub></p>

## <a name="millstrand.api.notes.alpha/writer-ref->prompt">`writer-ref->prompt`</a>

```clojure
(writer-ref->prompt ref)
```

Function.

Render `ref` as the note-writing CLI instruction fragment.

This is the single renderer of the write fragment `strand note <target> "<text>" --by-identity <actor> --attr k=v …` — `<text>` stays a placeholder the agent fills in. The target, actor, and each decoration key-value are POSIX-shell-quoted as one word; embedded single quotes are escaped. `ref` must contain a string `:target`, an optional non-blank `:identity/by-identity` attribute, and an optional map of string `:decoration` entries. The old `:by` field and every other unknown field fail loudly. Renders only the write instruction — no read instruction.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/notes/alpha.clj#L92-L126">Source</a></sub></p>
