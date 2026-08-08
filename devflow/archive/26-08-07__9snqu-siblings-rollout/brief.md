# Brief: sibling spools mechanical rollout (9snqu)

Feature 5 of epic waq0l (retire spool `install!`). The user's standing brief is the epic body plus its AUTHORITY grant (recorded 2026-07-23, note lpy54): drive to completion without human waits; automated review gates still run; sibling releases publish tags/shas directly (nqiog precedent).

Mechanically retire `install!` across the sibling spool repos that need only deletion + test conversion. The version plan drifted from the card (note j2s4g): iteration 10 already shipped test-only compat releases devflow v4 (`7135d8c`) and kanban v8 (`af95849`) converting the suites' in-tree workflow/guild activation to image-mode `module!`; this feature owns each repo's OWN installer deletion and remaining own-installer call sites.

Per repo:

- **devflow.spool → v5:** delete the no-op `install!` metadata shim (`devflow.clj:815-828`); export the base module declaration datum (`devflow/module`, mirroring skein-src's ADR-003.P7 pattern); convert the two own-installer test call sites and route publication to the module path.
- **kanban.spool → v9:** delete `install!` (`kanban.clj:1574`) — verified fully covered by `contribute`/`reconcile` (ops+pattern+queries contributed; vocab+spool-state reconciled). `install-peering!` stays: a recorded imperative opt-in (commit 5e7cb5c). Export the datum; convert `kanban_test.clj`'s `with-kanban` fixture and `kanban_peering_test.clj`'s four call sites; update peering prereq remedies that name deleted installers (both `ct.spools.kanban/install!` and the already-deleted `skein.spools.guild/install!`) plus the test regexes asserting them.
- **agent-harness.spool → v13 (test-only):** fast-forward to origin (`c7d2266`); replace the dead `requiring-resolve 'ct.spools.delegation/install!` generation guards (`agent_run_test.clj:53`, `subagent_test.clj:87`) with delegation module activation so the `agent spend` op path is actually exercised again; tidy stale installer prose in test strings/comments. Cookbook debt (7fg72) already shipped at v12 (`75b8a23`).
- **notebook.spool + notes:** verify-only; both grep-clean of `install!` — record on the card.

Release discipline per docs/spools/writing-shared-spools.md: suites green against skein-src main; `bin/compat-alarm` vs previous marker — deleting an exported fn IS a break for devflow v5 / kanban v9; TEN-000@1 + ADR-003 authorize it, recorded explicitly per release (NG6 precedent). Annotated tags, peeled shas on the card. No consuming pin bumps here (rtnfv). skein-src untouched except the `test/skein/config_ops_test.clj` conversion (off `ct.spools.kanban/install!`), which review round 1 reshaped into a v8-compatible edit landing with this feature.
