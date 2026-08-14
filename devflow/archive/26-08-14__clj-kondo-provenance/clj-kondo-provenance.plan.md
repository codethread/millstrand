# Plan: clj-kondo hook provenance and repository hygiene

**Document ID:** `PLAN-Khp-001`
**Feature:** `clj-kondo-provenance`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** None
**Root specs:** None; the existing authoring contract is in [`docs/spools/writing-shared-spools.md`](../../../docs/spools/writing-shared-spools.md)
**Feature specs:** None
**Status:** Shipped
**Last Updated:** 2026-08-14
**Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Nested point IDs use the full document ID.

## PLAN-Khp-001.P1 Goal and scope

Apply the ownership model approved in [proposal.md](./proposal.md) across Millhouse, Millstrand, Agent Harness, Notebook, and Standup. The work removes overlapping clj-kondo definitions, keeps external imports reviewable, removes tracked cache artifacts, and adds workflow checks that prevent recurrence. Millstrand remains unmarked.

## PLAN-Khp-001.P2 Approach

- `PLAN-Khp-001.A1` Repair and land Millhouse first because it owns the Workflow, Chime, Cron, and millstrand-workflows roots used by the consumer tasks.
- `PLAN-Khp-001.A2` Keep producer self-consumption and external consumer imports distinct. Producers use resource export paths; consumers regenerate imports once from their pinned dependency classpath and review the result.
- `PLAN-Khp-001.A3` Run Notebook and Standup cache removal independently because those changes do not depend on producer coordinates.
- `PLAN-Khp-001.A4` After Millhouse lands, update the declared and resolved Millhouse coordinates in Millstrand and Agent Harness to that commit. Regenerate the Workflow import from that resolved classpath, record the pin, and remove only mappings covered by the imported export or proven obsolete by source search.
- `PLAN-Khp-001.A5` Land each repository through its own policy, then audit all in-scope canonical checkouts for dirty status, tracked caches, import drift, and overlapping mappings.

## PLAN-Khp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| `PLAN-Khp-001.AA1` | Millhouse root lint config and hooks | Remove Millstrand and self-owned duplicate analysis while retaining repository policy hooks. |
| `PLAN-Khp-001.AA2` | Millhouse millstrand-workflows root | Add provenance and cleanliness checks to publishing and bump routines, with tests and public docs. |
| `PLAN-Khp-001.AA3` | Millstrand repository lint configuration | Consume the pinned Millhouse Workflow export and remove obsolete local shims. |
| `PLAN-Khp-001.AA4` | Agent Harness repository lint configuration | Consume the pinned Millhouse Workflow export instead of a local approximation. |
| `PLAN-Khp-001.AA5` | Notebook and Standup repository metadata | Remove tracked cache artifacts and add ignore coverage. |

## PLAN-Khp-001.P4 Contract and migration impact

- `PLAN-Khp-001.CM1` Public macro behavior does not change. The lint model moves to its existing owner-published source.
- `PLAN-Khp-001.CM2` Millhouse workflow guidance becomes stricter: a bump is incomplete when imports drift, producer-owned symbols are remapped locally, cache files are tracked, or the final tree is dirty.
- `PLAN-Khp-001.CM3` No root-spec delta is needed because the work enforces the current authoring guide rather than changing a Millstrand API or runtime contract.

## PLAN-Khp-001.P5 Implementation phases

### PLAN-Khp-001.PH1 Producer and independent hygiene work

Outcome: Millhouse has one source for each owned or imported hook surface, its workflow routines enforce that rule, and Notebook and Standup no longer track clj-kondo caches.

### PLAN-Khp-001.PH2 Consumer adoption

Outcome: Millstrand and Agent Harness consume the verified Millhouse Workflow export from their pinned coordinate and no longer carry overlapping local mappings.

### PLAN-Khp-001.PH3 Cross-repository acceptance and landing

Outcome: repository quality boundaries pass, producer changes land before dependent consumers, imports match landed pins, and all in-scope canonical trees are clean.

## PLAN-Khp-001.P6 Validation strategy

- `PLAN-Khp-001.V1` Millhouse runs `make quality`; its root config and hook files contain no mapping or function for Millstrand's `defop`, `defquery`, `defpattern`, `defhook`, `defhandler`, or `defbin`, and no duplicate of its own Workflow, Chime, or Cron export.
- `PLAN-Khp-001.V2` Millstrand runs `make fmt-check lint reflect-check docs-check`; Agent Harness runs `make quality`. Each repository records the landed Millhouse commit in its declared coordinate, resolves that same commit, and has a byte-identical `.clj-kondo/imports/millhouse.spools/workflow` snapshot with no local Workflow remap.
- `PLAN-Khp-001.V3` Notebook and Standup run `make test`; `git ls-files | rg '(^|/)\.clj-kondo/\.cache/'` returns no matches; their diffs contain only cache removals and ignore coverage.
- `PLAN-Khp-001.V4` Every repository runs `git diff --check` and finishes its task worktree with empty `git status --short` output after commit.
- `PLAN-Khp-001.V5` After landing, repeat the import comparisons, overlap search, tracked-cache search, and Git-status audit in the five canonical checkouts.

## PLAN-Khp-001.P7 Risks and open questions

- `PLAN-Khp-001.R1` Generating imports against the wrong classpath can produce a plausible but stale snapshot. Workers record the pinned producer commit and the coordinator verifies the bytes before closing each task.
- `PLAN-Khp-001.R2` The canonical Millhouse checkout began with two untracked generated files. They remain preserved until the producer branch lands; cleanup targets only those audited byte-identical artifacts.
- `PLAN-Khp-001.R3` Large cache deletions can hide unrelated edits. Notebook and Standup workers own only cache paths and ignore files, and the coordinator reviews their name-status diffs before landing.
- `PLAN-Khp-001.Q1` None.

## PLAN-Khp-001.P8 Task context

- `PLAN-Khp-001.TC1` Kanban feature `e44fr` is the cold-start work record. Audit evidence is on task `mqf7a`; the delegated agent plan is `ajqy1`.
- `PLAN-Khp-001.TC2` Millhouse task `0zy82` blocks Millstrand task `d82ih` and Agent Harness task `2sh4k`. Notebook task `vpsof` and Standup task `5p3cz` are independent.
- `PLAN-Khp-001.TC3` Workers commit but do not land. The coordinator verifies tasks, closes them to release dependencies, and drives each repository's landing policy.
- `PLAN-Khp-001.TC4` The pre-mutation canonical baseline was: Millhouse had only `.clj-kondo/imports/millhouse.spools/workflow/config.edn` and `.clj-kondo/imports/millhouse.spools/workflow/hooks/millhouse/spools/workflow.clj_kondo` untracked; Millstrand, Agent Harness, Notebook, and Standup had empty `git status --short`. Speccy's two untracked specification files are outside the five-repository scope. Any other baseline path must be preserved and reported, not deleted.

## PLAN-Khp-001.P9 Developer Notes

### PLAN-Khp-001.DN1 Coordinator setup — 2026-08-11

- The user approved the audit scope before the devflow promotion. The coordinator provisioned disjoint worktrees and created the five agent-plan tasks while the proposal review ran. This plan records the same audited slices; no additional scope was introduced.
- Speccy has unrelated untracked specification files and remains outside this feature.

### PLAN-Khp-001.DN2 Task normalization — 2026-08-11

- Notebook task `vpsof` and Standup task `5p3cz` finished before the strand-native devflow task stage. They remain immutable closed agent-plan records with their AFK implementation contracts, validation evidence, commits, and PR notes.
- The three remaining active tasks were normalized to the devflow AFK body and attribute contract without changing their repository scope or dependency edges.
