# Task 15: queue acceptance — full suite + go + smoke + quality gates + fresh-world verify

**Document ID:** `TASK-usc-015`

**Slice:** queue acceptance (`PLAN-usc-001.P6` validation strategy) **Harness:** build **Type:** AFK

**Branch:** `unify-spool-classpath`

## TASK-usc-015.P1 Scope

Type: AFK

Final acceptance for the whole assembled queue: with Tasks 1–14 implemented and committed, run every land-tier gate green on the fully-integrated branch (`PLAN-usc-001.V2`/`.V3`/`.V4`/`.V5`/`.V6`, `PROP-usc-001.V`/`DW`). No feature code changes — only gate runs. A red gate is a defect to route back to the owning slice via a note, not to patch here.

**Owned files:** none (validation only).

## TASK-usc-015.P2 Must implement exactly

- **TASK-usc-015.MI1:** Run the full land-tier gates green: `flock -w 3600 /tmp/skein-test.lock clojure -M:test` (serialized full suite), `(cd cli && go test ./...)`, `clojure -M:smoke`, and `make fmt-check lint reflect-check docs-check` (`PLAN-usc-001.V2`/`V5`).
- **TASK-usc-015.MI2:** Fresh-world verification (`PLAN-usc-001.V3`, `PROP-usc-001.G5`): a real `mill init` world in a disposable `mktemp -d` `--workspace` boots with `{:spools {}}` and gets the batteries command surface. Use repo-local `./bin/mill`; guard every workspace expansion with `${ws:?}`; never touch the canonical `.skein`.
- **TASK-usc-015.MI3:** Confirm the guard-wiring assertion (`PLAN-usc-001.V4`) is present and green in the full suite — load success alone does not satisfy it.
- **TASK-usc-015.MI4:** Confirm `make api-docs` is a clean regen and `git status --short` shows only expected `*.api.md`/doc/spec changes, `spools/src` gone from `deps.edn :paths`, and **no** generated SQLite/runtime artifacts (`PLAN-usc-001.V6`, `PROP-usc-001.DW1`).

## TASK-usc-015.P3 Done when

- **TASK-usc-015.DW1:** All of MI1 green.
- **TASK-usc-015.DW2:** The fresh-world boot (MI2) shows the batteries command surface under `{:spools {}}`.
- **TASK-usc-015.DW3:** `git status --short` is clean of stray generated SQLite / runtime-metadata artifacts and confirms `spools/src` is gone from `deps.edn :paths` (`PROP-usc-001.DW1`–`DW5`).

## TASK-usc-015.P4 Out of scope

- **TASK-usc-015.OS1:** Any feature implementation or fix — those live in Tasks 1–14. On a red gate, record the failure in a note and route it to the owning slice; do not patch here.
- **TASK-usc-015.OS2:** Landing/merge is coordinator-only; this slice stops at green gates + a summary report.

## TASK-usc-015.P5 Commit

- No source commit expected (validation only). If a regen leaves an intended `*.api.md` diff that Task 14 missed, route it back rather than committing from the acceptance slice.

## TASK-usc-015.P6 References

- **TASK-usc-015.REF1:** `PLAN-usc-001.P6` (`V1`–`V6`), `PH4` (heavy gates), `PH5` (docs/api-docs).
- **TASK-usc-015.REF2:** `PROP-usc-001.V` (validation gates), `DW1`–`DW5`; CLAUDE.md "Commands" (locked full suite, go tests, smoke, quality gates).

## TASK-usc-015.P7 Worker contract

- Set `--attr status=implemented` only when every gate above is green; never close this strand; never mutate sibling or parent strands. Never restart the canonical weaver; validation runs in disposable `mktemp -d` worlds. Kill any stuck JVM by PID only. Serialize the full suite on `/tmp/skein-test.lock`.
