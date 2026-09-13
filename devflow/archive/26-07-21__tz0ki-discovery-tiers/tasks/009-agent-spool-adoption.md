# Task 9: agent-harness.spool adoption + producer tests/docs

**Document ID:** `TASK-Dtf-009`

## TASK-Dtf-009.P1 Scope

Type: AFK

Adopt the discovery-tier pattern on the `agent`/`delegation` op family in the **separate** `agent-harness.spool` repo (local checkout `/Users/ct/dev/projects/agent-harness.spool`), redistribute its structured `about`, and update producer tests/docs. Cross-repo: work in that repo on a branch; no tag/release here (Task 10).

## TASK-Dtf-009.P2 Must implement exactly

- **TASK-Dtf-009.MI1:** The `agent`/`delegation` family adopts the pattern: per-verb help via arg-spec, cross-verb narrative moved to `:about` prose, shared lifecycle failure outcomes moved to the glossary and referenced by `failure-modes` name; `:prime` where run-first orientation helps. Register shared outcomes in the spool's `install!` **before** its ops (DELTA-Dtf-002.CC7).
- **TASK-Dtf-009.MI2:** Redistribute the current structured `agent about` (`{operation, concepts, verbs}`) into per-verb help / glossary / `about`-prose per RFC-Dtf-001.C3. The verbose whole-tree `about` fetch is eliminated.
- **TASK-Dtf-009.MI3:** Update producer tests, run `make api-docs` for the spool, and update the spool README / discovery docs. Classify the v7→v8 compatibility break per PLAN-Dtf-001.CM3 (record the published-name behavior change and the new Skein API dependency floor); note any producer compatibility check/alarm that must be updated before a tag.
- **TASK-Dtf-009.MI4 (grammar-compat fix — from recon `bh2ez`):** The `bench` op's test in this repo (`test/ct/spools/bench_test.clj` `bench-op-declares-subcommands-and-routes-loudly`, ~:750/:752) asserts the retired `strand bench help` sole-token alias; update it to expect the loud `discovery/help-grammar` redirect (or use `strand help bench`), same as the kanban fix (Task 13). This is grammar-compat, not full bench adoption. Also NOTE (do NOT fix as part of this feature): recon found an **unrelated, pre-existing** flaky liveness failure `reap-manual-leaves-the-session-to-the-human` (`test/ct/spools/agent_run_test.clj:595`, a `process-alive?` timing flake) — confirm it is unrelated to the grammar change and flaky (re-run), and report it; do not let it block, but do not paper over a real regression either. `devflow.spool` needs NO change (flat ops, passes — recon `bh2ez`).

## TASK-Dtf-009.P3 Done when

- **TASK-Dtf-009.DW1:** The agent spool's own test suite and api-docs are green in that repo; a `check-op-return!`-style pass covers changed `agent` leaves.
- **TASK-Dtf-009.DW2:** The compatibility classification and API-floor note are recorded (in the spool repo change and/or PLAN-Dtf-001 Developer Notes) for Task 10.

## TASK-Dtf-009.P4 Out of scope

- **TASK-Dtf-009.OS1:** Cutting/pushing the release tag (Task 10, HITL); bumping the coordinate here (Task 11). Do not edit this repo's `.skein/spools.edn` in this task.

## TASK-Dtf-009.P5 References

- **TASK-Dtf-009.REF1:** DELTA-Dtf-001/002/003; RFC-Dtf-001.C3/C4; PLAN-Dtf-001.PH6/CM3/AA8.
- **TASK-Dtf-009.REF2:** `agent-harness.spool` `delegation/` (op family); pinned as `ct.spools/agent-run` v7 in `.skein/spools.edn`.
