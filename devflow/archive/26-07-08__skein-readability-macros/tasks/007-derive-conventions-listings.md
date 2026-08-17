# Task 7: Derive devflow-conventions listings from remembered entries

**Document ID:** `TASK-Srm-007`

## TASK-Srm-007.P1 Scope

Type: AFK

Replace the hand-maintained config-owned `:ops` and `:queries` name listings in `devflow-conventions-op` with a derivation from the entries `install-ops!`/`install-queries!` remembered, removing the fourth name repetition (RFC-020.Q2). The non-config entries and all authored prose stay hand-written, and the `devflow-conventions` output stays byte-identical, entry order included.

## TASK-Srm-007.P2 Must implement exactly

- **TASK-Srm-007.MI1:** In `devflow-conventions-op`, derive the config-owned `:ops` entries (each mechanical `{:name ... :help ...}` plus any authored extra fields such as `:manual`/`:purpose`) from the `skein.macros.ops` remembered-entry accessor for the `config` namespace, in the remembered (author) order.
- **TASK-Srm-007.MI2:** Derive the config-owned `:queries` entries (`{:name ... :usage ...}`) from the `skein.macros.queries` remembered-entry accessor for the `config` namespace, in the remembered order.
- **TASK-Srm-007.MI3:** Keep the non-config entries hand-authored and in their current positions: the `:ops` entries for ops this file does not register (`kanban`, `agent`, `land`, and any other non-config op), and the `:queries` entries `kanban-cards` and `kanban-unstarted`. The `:spools` and `:patterns` blocks stay hand-authored and unchanged.
- **TASK-Srm-007.MI4:** The rendered `devflow-conventions` JSON must be byte-identical to before this slice — same entries, same fields, same order. If a byte-identical derivation cannot hold the current order or interleaving, stop and record it in the plan Developer Notes and fall back to the RFC-020.Q2 hand-authored listing rather than reordering the output.

## TASK-Srm-007.P3 Done when

- **TASK-Srm-007.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green; a test asserts the full `devflow-conventions` output is unchanged (add one if none exists).
- **TASK-Srm-007.DW2:** Each config op/query name now appears once in source (in its `defop`/`defquery` block), not repeated in a hand-authored conventions listing.
- **TASK-Srm-007.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check reflect-check` is green.
- **TASK-Srm-007.DW4:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-007.P4 Out of scope

- **TASK-Srm-007.OS1:** Deriving the `:spools` or `:patterns` blocks — they stay authored.
- **TASK-Srm-007.OS2:** `attention.clj` (task 8).

## TASK-Srm-007.P5 References

- **TASK-Srm-007.REF1:** [PLAN-Srm-001.PH3](../skein-readability-macros.plan.md), PLAN-Srm-001.A4, PLAN-Srm-001.R1.
- **TASK-Srm-007.REF2:** `devflow-conventions-op` `:ops`/`:queries` in `.skein/config.clj`; the remembered-entry accessors from TASK-Srm-002 (MI5) and TASK-Srm-003 (MI6).
- **TASK-Srm-007.REF3:** RFC-020.Q2 and RFC-020.OUT1 (derive mechanical listings; authored prose stays authored; hand-authored listing is the recorded fallback).
