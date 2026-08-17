# Task 1: notes writer value/ref/prompt renderer on skein.api.notes.alpha

**Document ID:** `TASK-Nwt-001` **Slice:** `PLAN-Nwt-001.PH1` (writer family, keystone) · **Depends on:** none (lands first — everything hangs off it)

## TASK-Nwt-001.P1 Scope

Type: AFK

Accrete the notes _writer_ value family onto the blessed `skein.api.notes.alpha` surface beside the untouched `note!`/`notes` primitive: a writer carries a target (or a thunk), a default decoration, and an author; it serializes to a plain-data ref; and it renders through a single prompt fragment. This is the keystone — the CLI passthrough (Tasks 2/3), task tier (Tasks 4/5), and every prompt-site absorption (Tasks 10/11) hang off it (`DELTA-Nwt-001.C1`–`C5`, `PROP-Nwt-001.G1`, `PLAN-Nwt-001.A1`, `A2`).

**Owned files (disjoint from Tasks 2/3):**

- `src/skein/api/notes/alpha.clj`
- `test/skein/notes_test.clj`

## TASK-Nwt-001.P2 Must implement exactly

- **TASK-Nwt-001.MI1 (writer):** Add `(writer runtime target-or-thunk {:keys [decoration by]})` returning a writer value. `target-or-thunk` is a strand-id string OR a 0-arg fn returning one (the target may not exist at construction time). `:decoration` is a map of string attr-key → string value defaulted onto every write; `:by` is the default author. Construction validates these shapes and fails loudly naming the offending field (`DELTA-Nwt-001.C1`). The writer _wraps_ `note!` — do not touch the `note!`/`notes` signatures (`notes/alpha.clj:59` `note!`; `DELTA-Nwt-001.C1`, `PROP-Nwt-001.NG2`).
- **TASK-Nwt-001.MI2 (write!):** Add `(write! w text {:keys [decoration by round]})` appending a note through `note!`. Per-call `:decoration` shallow-merges per key OVER the writer default; per-call `:by` overrides the writer default; `:round` passes through. A thunk target resolves at each `write!`; a missing/deleted target fails loudly with the existing "Note target strand not found" (`notes/alpha.clj:63`; `DELTA-Nwt-001.C2`). `note!` already folds non-`:by`/`:round` opts into decorating attrs (`notes/alpha.clj:66`), so compose over that merge — no primitive change.
- **TASK-Nwt-001.MI3 (writer-ref):** Add `(writer-ref w)` returning the plain-data ref `{:target <resolved id> :decoration <merged map> :by <author>}`. A thunk resolves exactly once, at ref time, and the ref freezes that id (`DELTA-Nwt-001.C3`).
- **TASK-Nwt-001.MI4 (no ref->writer):** Ship NO `ref->writer` — the constructor already reconstructs via `(writer runtime (:target ref) {:decoration (:decoration ref) :by (:by ref)})`; accretion-only surface adds no sugar without a named consumer (`DELTA-Nwt-001.C3`).
- **TASK-Nwt-001.MI5 (writer-ref->prompt):** Add `(writer-ref->prompt ref)` as the single renderer of the note-writing instruction fragment (`agent note <target> "<text>" --by <author> --attr k=v …`). Validate the ref shape and fail loudly naming the offending field; malformed refs never render silently. Render only the _write_ instruction — no read/`agent notes` string (`DELTA-Nwt-001.C4`, `PLAN-Nwt-001.R2`).
- **TASK-Nwt-001.MI6 (no ctx-dispatch):** Add no route fn / ctx-schema dispatch on the blessed surface — the writer is a data-first value (`DELTA-Nwt-001.C5`, `PROP-Nwt-001.NG1`, settled 2026-07-10).

## TASK-Nwt-001.P3 Done when

- **TASK-Nwt-001.DW1:** `writer`/`write!`/`writer-ref`/`writer-ref->prompt` live beside a byte-identical `note!`/`notes`; `write!` merges per-call decoration over the writer default; `writer-ref` resolves a thunk exactly once; `writer-ref->prompt` is the sole renderer of the write fragment; no `ref->writer` ships (`DELTA-Nwt-001.C1`–`C5`).
- **TASK-Nwt-001.DW2:** Tests cover the negative cases: `writer` rejects a non-string/non-fn target and a non-map decoration; `write!`/`writer-ref->prompt` reject a malformed decoration/ref naming the field; a thunk returning a non-string (nil, keyword, number) fails loudly at resolution — in both `write!` and `writer-ref` — naming the bad return, not later in downstream `note!`/`show` calls; `write!` on a missing thunk target throws "Note target strand not found"; `writer-ref` thunk resolves once (`DELTA-Nwt-001.C3`, `PLAN-Nwt-001.AA1`, `AA9`; change-review-758179fb finding 5).
- **TASK-Nwt-001.DW3:** Cold focused gate green: `clojure -M:test skein.notes-test`.
- **TASK-Nwt-001.DW4:** `make fmt-check lint reflect-check` pass at zero findings (`PLAN-Nwt-001.V3`).

## TASK-Nwt-001.P4 Out of scope

- **TASK-Nwt-001.OS1:** The CLI `--attr` verb parse on `note`/`agent note` (Tasks 2/3).
- **TASK-Nwt-001.OS2:** The `note/kind` vocab key (Task 2).
- **TASK-Nwt-001.OS3:** Converging any prompt site on `writer-ref->prompt` (Tasks 10/11); this task only publishes the renderer.
- **TASK-Nwt-001.OS4:** No `note!`/`notes` signature change (accretion only, `PROP-Nwt-001.NG2`).

## TASK-Nwt-001.P5 References

- **TASK-Nwt-001.REF1:** `DELTA-Nwt-001.C1`–`C5`; `PROP-Nwt-001.G1`, `NG1`, `NG2`.
- **TASK-Nwt-001.REF2:** `PLAN-Nwt-001.A1`, `A2`, `PH1`, `AA1`, `AA9`, `R2`.
- **TASK-Nwt-001.REF3:** `src/skein/api/notes/alpha.clj:59` (`note!`), `:63` (target-not-found), `:66` (opts→decoration fold); `test/skein/notes_test.clj`.
