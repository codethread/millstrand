# Brief: blessed note primitive — core notes relation, note/* shape, batteries surface

Kanban: card `7azzl`, feature 3 of epic `kaans` (agent-layer redesign). Source: the card body (epic design recorded 2026-07-09). F1 (`agent-layer-rename`, `26o9g`) and F2 (`agent-engine-primitives`, `ah5vu`) are landed on main and the canonical world is cut over to both (F2 squash `3b99997`; `serves` relation live).

## Problem

Annotation is not yet a graph concept. Notes are written three ways that do not compose:

1. Kanban notes are closed `parent-of` children of a card (`kanban/note` marker, text in `body`).
2. Agent notes write a `notes` edge with `note/*` attrs (post-F1 names on active strands; 1408 historical shuttle-era notes keep old keys as memory).
3. Readers are per-writer: `kanban card` shows only kanban notes, `agent notes` only agent notes — the m630j false-loss: notes "disappear" when read through the other surface.

Two data defects ride along: the `note/for` attr and the `notes` edge can diverge on target deletion (67 dangling notes observed 2026-07-09), and pre-cutover history is unreadable by any unified surface without a re-key.

## Scope (the contract)

1. **One declared `notes` relation** (core): a closed strand attached to a target is append-only memory. Documented in `docs/skein.md` beside `parent-of`/`depends-on`. This also removes the kanban `parent-of` overload — consistent with F2's structure-only `parent-of`.
2. **Minimal blessed shape** (core owns shape + relation): `note/text`, `note/by`, `note/at`, optional `note/round`. Note strands stay open to decorating attrs — spools own meaning (`kanban/handover=true`, `review/pass`, `note/round`).
3. **Batteries surface**: `strand note <id> "text"` (write) and `strand notes <id>` (read) — the unified read surface walks the declared relation regardless of writer. Resolves `m630j` (linked, depended-on-by).
4. **Kanban notes migrate onto the blessed shape**: kind=note strand keeps `kanban/note` + `kanban/handover` as decorating attrs; `body` → `note/text`; `parent-of` edge → `notes` edge. `delegation` note verbs delegate to the same primitive.
5. **Fix the cascade disagreement**: `note/for` attr vs `notes` edge must not diverge on target deletion.
6. **One-shot HISTORY rewrite** (not just active strands — deliberate divergence from the F1/F2 active-only rule): all shuttle-era and kanban notes re-keyed (measured at design capture 2026-07-09: 1408 + 308; the world is live and counts drift — 1600 + 356 by proposal time — so the rewrite counts at cutover time, never from these figures), else the unified read surface recreates the m630j false-loss on every pre-cutover note. Rehearsed on a data copy per the F2 ceremony (`mill init` the disposable world, resolve its own `database_path`, copy, stamp, weaver-backed smoke).

## Deliberately not built

- Structured review findings: tags-in-text (53% of notes) stays; decorating attrs are the landing spot if it graduates.

## Migration

Same rehearse-on-a-copy-then-live ceremony as F1/F2 (`scripts/cutover/`, ceremony doc, quiet board, backup, restart). The restart is executed under the standing pre-authorization recorded as strand `cu3wz` (card `ah5vu`, notes `u9jtn`/`fls7n`); ceremony discipline applies in full.

## Related

- `m630j` (depended-on-by): unify kanban and agent notes — resolved by this feature's read surface.
- `kbcjt` (adjacent, not in scope): large-attr scaling (306KB max note text).
- F4 (`41pna`), F5 (`2mp13`) follow in the epic.
