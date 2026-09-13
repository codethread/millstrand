# Task 6: Guild docs, cross-references, and full validation

**Document ID:** `TASK-Guild-006`

## TASK-Guild-006.P1 Scope

Type: AFK

Write the guild spool contract doc, wire up index/cross-references, sync CLAUDE.md, and run the full validation suite for the feature.

## TASK-Guild-006.P2 Must implement exactly

- **TASK-Guild-006.MI1:** `spools/guild.md` contract doc in the house spool style (see `spools/workflow.md`, `spools/shuttle/treadle.md`): overview, the `defop!`/`deprecate!`/`install!`/`guild.describe` surface, the version-suffixed dotted naming and additive-evolution conventions, the deprecation rule ("a deprecated stub may explain, redirect, or refuse — never pretend to succeed"), the note that a peering repo's checked-in `init.clj` is effectively a published API file, a worked two-repo example (portable `config.json` names + `skein.api.peers.alpha` discovery + `call!` of a guild op), and see-also links.
- **TASK-Guild-006.MI2:** Add the `skein.spools.guild` row to the `spools/README.md` index table.
- **TASK-Guild-006.MI3:** Sync `CLAUDE.md`: add the guild contract doc to the spool doc list.
- **TASK-Guild-006.MI4:** Brief `skein.api.peers.alpha` mention where blessed alpha helpers are introduced for users (`spools/README.md` loading notes or the guild doc's example — whichever reads naturally; no root spec edits, those merge at finish).
- **TASK-Guild-006.MI5:** Run full validation: `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke`.

## TASK-Guild-006.P3 Done when

- **TASK-Guild-006.DW1:** All three validation commands pass and `git status --short` is clean of runtime artifacts.
- **TASK-Guild-006.DW2:** `spools/guild.md` exists, is linked from the spools index and CLAUDE.md, and its worked example matches the shipped fn signatures exactly.

## TASK-Guild-006.P4 Out of scope

- **TASK-Guild-006.OS1:** Merging feature deltas into root specs (happens at devflow finish/archive).
- **TASK-Guild-006.OS2:** New behavior of any kind.

## TASK-Guild-006.P5 References

- **TASK-Guild-006.REF1:** [proposal](../proposal.md) S4/S6/S7 and resolved Q1–Q4; all three deltas in `../specs/`.
- **TASK-Guild-006.REF2:** `spools/workflow.md` and `spools/shuttle/treadle.md` (doc style), `spools/README.md`, `CLAUDE.md`.
