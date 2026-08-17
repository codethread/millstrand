# Task 2: Worker seat and pi-main rename sweep

**Document ID:** `TASK-HarnessAliasRegistries-002`

## TASK-HarnessAliasRegistries-002.P1 Scope

Type: AFK

Roster/docs phase PLAN-HarnessAliasRegistries-001.PH2: the repo reads as seats over tools.

## TASK-HarnessAliasRegistries-002.P2 Must implement exactly

- **TASK-HarnessAliasRegistries-002.MI1:** `.skein/harnesses.clj`: replace the `:pi-main` alias with seat `:worker` (`:alias-of :pi`, `:extra-args ["--agent" "main"]`, capability-statement `:doc` naming it the preferred delegation seat). Keep the file's capability-not-role doc convention; update the ns docstring if it mentions the old name.
- **TASK-HarnessAliasRegistries-002.MI2:** Sweep every other `pi-main` reference to `worker`: `.skein/config.clj` (~line 610 doc string), `.skein/init.clj` (~line 142 comment), `spools/shuttle/treadle.md` (~line 41), `spools/agents/README.md` (~lines 31, 253), `spools/shuttle.cookbook.md` honest-source lines if they name it, `test/skein/config_test.clj` fixtures/assertions, the golden `test/skein/surface_baseline.edn` entry embedding the config doc string, and the live lookup paths review found: the bonkai skill text (`.claude/skills/bonkai/SKILL.md` and its `.agents/skills/bonkai/` copy) and the active `devflow/feat/attr-scaling-ship-now/tasks/` `signoff-payload.json` + `index.yml` harness fields (those would delegate against a name that no longer resolves). Re-grep `pi-main` repo-wide afterward; only historical devflow archive/notes may keep it.
- **TASK-HarnessAliasRegistries-002.MI3:** `spools/shuttle/README.md` documents the two-registry contract: one harness per tool, aliases as seats, alias-first resolution, lawful same-name shadowing, migration note. Adjust `spools/shuttle.cookbook.md` registry recipe if it shows the old single-registry framing.
- **TASK-HarnessAliasRegistries-002.MI4:** Regenerate `spools/*.api.md` via `make api-docs` after docstring changes.

## TASK-HarnessAliasRegistries-002.P3 Done when

- **TASK-HarnessAliasRegistries-002.DW1:** Targeted namespaces green (`skein.shuttle-test`, `skein.config-test`, `skein.agents-test`), then the full gates: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`, `clojure -M:smoke`, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check`, `make api-docs` idempotent, docs-site builds (`make docs-site`).
- **TASK-HarnessAliasRegistries-002.DW2:** Live smoke in a disposable workspace (own `ws=$(mktemp -d)`, guard every expansion with `${ws:?}`, never the canonical `.skein` world): after `mill init`/weaver start with this branch's config, `strand agent harnesses` shows harness `pi` and seat `worker`; spawn a trivial `sh`-style run via seat name and via unshadowed harness name; register a same-named shadow alias in that world and confirm it resolves alias-first. Stop that weaver by recorded PID when done.
- **TASK-HarnessAliasRegistries-002.DW3:** Work committed on `harness-alias-registries` (atomic, why-focused, HEREDOC), status set implemented; do not close the strand, do not land, do not touch the canonical weaver.

## TASK-HarnessAliasRegistries-002.P4 Out of scope

- **TASK-HarnessAliasRegistries-002.OS1:** Engine changes (task 1 owns `shuttle.clj`); landing; canonical-world reload (coordinator owns it, PLAN-HarnessAliasRegistries-001.R1).

## TASK-HarnessAliasRegistries-002.P5 References

- **TASK-HarnessAliasRegistries-002.REF1:** Plan `devflow/feat/harness-alias-registries/harness-alias-registries.plan.md` (PH2, V1, V3, R1/R2); proposal `proposal.md` (S5, G3, NG2).
- **TASK-HarnessAliasRegistries-002.REF2:** The reference sweep list was verified by grep at plan time; re-verify rather than trusting line numbers.
