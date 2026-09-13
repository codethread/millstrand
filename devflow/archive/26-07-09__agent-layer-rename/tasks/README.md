# agent-layer-rename — task queue & pour manifest

**Feature:** `agent-layer-rename` (feature 1 of epic `kaans`)

**Contract source of truth:** `../brief.md` rename table (never re-derived — `PLAN-Alr-001.TC1`), `PROP-Alr-001`, `PLAN-Alr-001` (as amended). Task docs are `TASK-Alr-001..022`; the machine index is `index.yml`.

This queue is derived exactly from `PLAN-Alr-001.P5`/`P8` (the 8-phase, token-class-sweep plan). It is **22 tasks**: the plan's ~21-task sketch plus one explicit `hitl` canonical-cutover task (TASK-Alr-022), which the coordinator runs with the user at the weaver restart — the plan folds that execution into PH6's coordinator note, but it is surfaced here as its own gated task.

## Pour order & serialization

- **PH0 (serial spine root):** `001` runs alone and first. No mutating sweep starts until its base gate is clean (`PLAN-Alr-001.PH0/R1`).
- **PH1 (strictly serial, one compile-coupled unit):** `002 → 003 → 004 → 005`. PH1 is a single atomic move; the Clojure tree is not globally green between `002` and `005`. Each task gates its own family's focused suite; **`005` owns the PH1-exit full gate** (`make build`, all four focused suites, `make reflect-check`, whole-tree class-1 grep). `002` is config-text-only (no Clojure gate); `003`/`004`/`005` do the `git mv` + `ns` rewrites the new paths point at.
- **PH2 (fan-out after PH1):** `006`, `007`, `008` parallelize (disjoint source families: agent-run / subagent-gate / delegation). `009` (workflow-core reads) **serializes after `006`+`007`** because its projections and focused tests read the renamed run/gate markers those two tasks produce — landing it earlier would assert against a half-renamed marker vocabulary. `010` (event kws) likewise **serializes after `006`+`007`** because it edits their two files — never two mutators in one file (`PLAN-Alr-001.TC2`).
- **PH3 (fan-out after PH2):** `011`, `012` parallelize (doc triads vs judgment prose). `013` (mkdocs) and `014` (api-docs regen) each depend on `011`.
- **PH4 (fan-out after PH2):** `015` (.skein config), `016` (agent-dash), `017` (bench/chime) parallelize on disjoint files. `017` needs `006`+`007`+`008` — chime notifies on `review/*`, `panel/*`, and `note/*`, so it must wait on the delegation-side split (`008`) too.
- **PH5 (after PH2):** `018` (spec deltas) parallelizes with PH3/PH4.
- **PH6a (after PH2):** `019` (cutover script) parallelizes with PH3/PH4/PH5; `020` (rehearsal + ceremony doc) depends on `019`.
- **PH7 (join):** `021` (acceptance) depends on every landing task (`011–020`). It is the **only** task that runs the full locked suite (`flock … clojure -M:test`) and the full quality battery.
- **PH6 canonical execution:** `022` (`hitl: true`) depends on `020`+`021` — coordinator-run, user at the weaver restart. **Not delegable.**

Every delegated worker gets **one file family** and commits **atomically, no push**. Iterate with `make test-warm` (never a gate); the per-task slice gate is a cold focused `clojure -M:test <ns…>`; the full locked suite runs only in `021` (`PLAN-Alr-001.A6/V1`).

The `make test-warm` target ships with the tiered-test-validation queue that Task `001` rebases onto (it does not exist on this branch's pre-rebase base). Every `make test-warm` reference in these tasks therefore assumes the PH0 rebase has landed; because `001` is the root of the serial spine and every task blocks on it transitively, this holds by construction. `001` verifies the target resolves before any sweep starts.

## Worker/harness routing

`build` (Claude Opus seat) for all code/config/build tasks; `worker` (pi-main seat) for docs/spec prose sweeps. `022` is coordinator/`hitl` — no harness.

| id  | title                                       | harness            | depends_on              |
| --- | ------------------------------------------- | ------------------ | ----------------------- |
| 001 | PH0 rebase + shard/suite reconcile          | build              | —                       |
| 002 | PH1a build-config lockstep                  | build              | 001                     |
| 003 | PH1b agent-run + executors/subagent move    | build              | 002                     |
| 004 | PH1c delegation move                        | build              | 003                     |
| 005 | PH1d executors/shell move + PH1-exit gate   | build              | 004                     |
| 006 | PH2a agent-run/* run attrs + markers        | build              | 005                     |
| 007 | PH2b gate/* (incl. gate/step)               | build              | 005                     |
| 008 | PH2c review/panel/note split                | build              | 005                     |
| 009 | PH2d workflow/outcome-notes + workflow-core | build              | 006, 007                |
| 010 | PH2e event-type kw rename                   | build              | 006, 007                |
| 011 | PH3a doc-triad git mv + links               | worker             | 006, 007, 008, 009, 010 |
| 012 | PH3b judgment prose sweep                   | worker             | 006, 007, 008, 009, 010 |
| 013 | PH3c mkdocs.yml nav                         | worker             | 011                     |
| 014 | PH3d make api-docs regen                    | build              | 011                     |
| 015 | PH4a .skein config sweep                    | build              | 006, 007, 008, 009, 010 |
| 016 | PH4b scripts/agent-dash rename              | build              | 006, 007, 008, 009, 010 |
| 017 | PH4c bench/chime consumer reconcile         | build              | 006, 007, 008           |
| 018 | PH5 apply spec deltas                       | worker             | 006, 007, 008, 009, 010 |
| 019 | PH6a cutover script                         | build              | 006, 007, 008, 009, 010 |
| 020 | PH6b rehearsal + ceremony doc               | worker             | 019                     |
| 021 | PH7 acceptance / atomic-landing gate        | build              | 011–020                 |
| 022 | PH6 canonical cutover (weaver restart)      | coordinator (hitl) | 020, 021                |

## Weave sketch (`strand weave --pattern agent-plan`)

When implementation opens, weave the queue from this shape. `depends_on` values are sibling `key`s resolved to strand ids at weave time; set `cwd` to the worktree `/Users/ct/dev/projects/skein-src__agent-layer-rename` (edit/commit ONLY under `devflow/feat/agent-layer-rename/` is the _authoring_ rule for this tasks stage — the implementation tasks below edit the live tree per their doc scopes). Each task `body` should hand the worker its `task_file` as the contract.

```json
{
	"feature": "agent-layer-rename",
	"title": "Agent-layer vocabulary rename (mechanical, no behavior change)",
	"body": "Apply the brief rename table across the live tree as one atomic landing per PLAN-Alr-001. Each child's contract is its tasks/NNN-*.md doc; do not re-derive the rename table.",
	"tasks": [
		{
			"key": "001",
			"title": "PH0 rebase + shard/suite reconcile",
			"harness": "build",
			"depends_on": [],
			"body": "See tasks/001-rebase-shard-reconcile.md (TASK-Alr-001)."
		},
		{
			"key": "002",
			"title": "PH1a build-config lockstep",
			"harness": "build",
			"depends_on": ["001"],
			"body": "See tasks/002-build-config-lockstep.md (TASK-Alr-002)."
		},
		{
			"key": "003",
			"title": "PH1b agent-run + executors/subagent move",
			"harness": "build",
			"depends_on": ["002"],
			"body": "See tasks/003-agent-run-subagent-move.md (TASK-Alr-003)."
		},
		{
			"key": "004",
			"title": "PH1c delegation move",
			"harness": "build",
			"depends_on": ["003"],
			"body": "See tasks/004-delegation-move.md (TASK-Alr-004)."
		},
		{
			"key": "005",
			"title": "PH1d executors/shell move + PH1-exit gate",
			"harness": "build",
			"depends_on": ["004"],
			"body": "See tasks/005-shell-move-suites-ph1-gate.md (TASK-Alr-005)."
		},
		{
			"key": "006",
			"title": "PH2a agent-run/* run attrs + markers",
			"harness": "build",
			"depends_on": ["005"],
			"body": "See tasks/006-attr-agent-run.md (TASK-Alr-006)."
		},
		{
			"key": "007",
			"title": "PH2b gate/* incl gate/step",
			"harness": "build",
			"depends_on": ["005"],
			"body": "See tasks/007-attr-gate.md (TASK-Alr-007)."
		},
		{
			"key": "008",
			"title": "PH2c review/panel/note split",
			"harness": "build",
			"depends_on": ["005"],
			"body": "See tasks/008-attr-review-panel-note.md (TASK-Alr-008)."
		},
		{
			"key": "009",
			"title": "PH2d workflow/outcome-notes + workflow-core",
			"harness": "build",
			"depends_on": ["006", "007"],
			"body": "See tasks/009-attr-workflow-outcome-notes.md (TASK-Alr-009)."
		},
		{
			"key": "010",
			"title": "PH2e event-type kw rename",
			"harness": "build",
			"depends_on": ["006", "007"],
			"body": "See tasks/010-event-type-keywords.md (TASK-Alr-010)."
		},
		{
			"key": "011",
			"title": "PH3a doc-triad git mv + links",
			"harness": "worker",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/011-docs-triad-move.md (TASK-Alr-011)."
		},
		{
			"key": "012",
			"title": "PH3b judgment prose sweep",
			"harness": "worker",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/012-docs-judgment-prose.md (TASK-Alr-012)."
		},
		{
			"key": "013",
			"title": "PH3c mkdocs.yml nav",
			"harness": "worker",
			"depends_on": ["011"],
			"body": "See tasks/013-mkdocs-nav.md (TASK-Alr-013)."
		},
		{
			"key": "014",
			"title": "PH3d make api-docs regen",
			"harness": "build",
			"depends_on": ["011"],
			"body": "See tasks/014-api-docs-regen.md (TASK-Alr-014)."
		},
		{
			"key": "015",
			"title": "PH4a .skein config sweep",
			"harness": "build",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/015-skein-config-sweep.md (TASK-Alr-015)."
		},
		{
			"key": "016",
			"title": "PH4b scripts/agent-dash rename",
			"harness": "build",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/016-agent-dash-rename.md (TASK-Alr-016)."
		},
		{
			"key": "017",
			"title": "PH4c bench/chime consumer reconcile",
			"harness": "build",
			"depends_on": ["006", "007", "008"],
			"body": "See tasks/017-bench-chime-consumer-reconcile.md (TASK-Alr-017)."
		},
		{
			"key": "018",
			"title": "PH5 apply spec deltas",
			"harness": "worker",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/018-spec-delta-application.md (TASK-Alr-018)."
		},
		{
			"key": "019",
			"title": "PH6a cutover script",
			"harness": "build",
			"depends_on": ["006", "007", "008", "009", "010"],
			"body": "See tasks/019-cutover-script.md (TASK-Alr-019)."
		},
		{
			"key": "020",
			"title": "PH6b rehearsal + ceremony doc",
			"harness": "worker",
			"depends_on": ["019"],
			"body": "See tasks/020-rehearsal-ceremony-doc.md (TASK-Alr-020)."
		},
		{
			"key": "021",
			"title": "PH7 acceptance / atomic-landing gate",
			"harness": "build",
			"depends_on": ["011", "012", "013", "014", "015", "016", "017", "018", "019", "020"],
			"body": "See tasks/021-acceptance-gate.md (TASK-Alr-021). Only task that runs the full locked suite."
		},
		{
			"key": "022",
			"title": "PH6 canonical cutover at user-signed weaver restart",
			"hitl": true,
			"depends_on": ["020", "021"],
			"body": "See tasks/022-canonical-cutover-hitl.md (TASK-Alr-022). Coordinator-run, user at the restart — NOT delegable."
		}
	]
}
```

Note: `022` carries no `harness` (it is not a shuttle-delegated run) and `hitl: true` so the treadle stops for the human at the weaver restart.
