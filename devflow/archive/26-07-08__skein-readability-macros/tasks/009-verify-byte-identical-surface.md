# Task 9: Byte-identical surface verification

**Document ID:** `TASK-Srm-009`

## TASK-Srm-009.P1 Scope

Type: AFK

Prove the whole refactor changed no runtime identity: the registered ops, queries, chime rules, generated `help`, and `devflow-conventions` output are identical to the status-quo config. This is the feature's acceptance gate (RFC-020.C1, PROP-SkeinReadabilityMacros-001.S4). Run the deterministic in-process verification here; run the live disposable-world CLI diff too when this harness may start a weaver, and otherwise hand it to the coordinator with a recorded reason (PLAN-Srm-001.R3).

## TASK-Srm-009.P2 Must implement exactly

- **TASK-Srm-009.MI1:** In-process verification (always). Assert the registered runtime surface is unchanged through an isolated `:publish? false` runtime with the converted `.skein` config loaded (extend the `config_test` fixture and the startup fixture that loads `attention.clj`). Cover: the registered op set and each op's metadata/generated `help <op>`; each named query's definition and its rows against seeded strands; the registered chime rule keys and at least one rule firing; and the full `devflow-conventions` output. Where a status-quo baseline is needed, capture it from the pre-refactor config (e.g. `git show` of the base revision loaded into a second runtime, or a committed golden snapshot) and assert byte-identical.
- **TASK-Srm-009.MI2:** Live disposable-world diff (RFC-020.C1) when the harness permits starting a weaver. Create a world with `ws=$(mktemp -d)` guarded `${ws:?}`, `mill init --workspace "${ws:?}"` it with the branch `.skein` config, `mill weaver start --workspace "${ws:?}"`, and capture through the CLI against that world: `strand --workspace "${ws:?}" help`, `help <op>` for every op, `agent harnesses`, `agent rosters`, `pattern list`, `devflow-conventions`, each named query's rows, and a chime rule firing. Repeat on the status-quo config and assert byte-identical output. `mill weaver stop --workspace "${ws:?}"` when done. Never touch the canonical world; hold the path in a shell variable and guard every expansion with `${ws:?}`.
- **TASK-Srm-009.MI3:** If this run is contractually barred from starting mills/weavers, do not attempt MI2. Record in the plan Developer Notes that the live disposable-world diff is deferred to the coordinator, and rely on MI1 plus the smoke suite as the in-loop evidence — mirroring how the op-only-cli feature handled its weaver-barred verification slices.
- **TASK-Srm-009.MI4:** Run the full quality gates as the final acceptance: `clojure -M:test` (under the flock lock), `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check`, all green at zero findings.

## TASK-Srm-009.P3 Done when

- **TASK-Srm-009.DW1:** The in-process verification (MI1) passes and is committed as a test that guards the surface against regression.
- **TASK-Srm-009.DW2:** The live disposable-world diff (MI2) passes with byte-identical output, or is explicitly deferred per MI3 with a reason recorded in the plan Developer Notes.
- **TASK-Srm-009.DW3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` are all green.
- **TASK-Srm-009.DW4:** One atomic commit for any test additions; nothing pushed; `git status --short` shows no generated SQLite or runtime metadata artifacts.

## TASK-Srm-009.P4 Out of scope

- **TASK-Srm-009.OS1:** Any further macro or config change — this slice verifies, it does not convert. If verification finds a drift, record it and route the fix back to the owning slice rather than patching here.
- **TASK-Srm-009.OS2:** Root-spec promotion, archiving, or landing — those are coordinator finish-stage steps.

## TASK-Srm-009.P5 References

- **TASK-Srm-009.REF1:** [PLAN-Srm-001.PH5](../skein-readability-macros.plan.md), PLAN-Srm-001.V1/V2/V3, PLAN-Srm-001.R3.
- **TASK-Srm-009.REF2:** RFC-020.C1 (verification recipe); PROP-SkeinReadabilityMacros-001.S4 (three acceptance gates).
- **TASK-Srm-009.REF3:** `test/skein/config_test.clj` `with-config-runtime`/`copy-config-dir!` fixtures as the in-process pattern.
