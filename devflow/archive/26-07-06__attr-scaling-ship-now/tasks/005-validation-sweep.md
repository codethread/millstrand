# Task 005: Full validation sweep + smoke (ASSN-PLAN-001.PH4 / P6)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`. **Depends on Task 004.**

Read first: `attr-scaling-ship-now.plan.md` (ASSN-PLAN-001 `PH4`, `P6` V1–V7, `R3`). This is the pre-merge green gate for the whole feature.

## Scope

Run every blocking gate and the smoke demo; fix only what the gates surface (no new feature scope). Confirm the WAL sidecars and runtime artifacts are cleaned.

- `dev/skein/smoke.clj` (`AA7`): ensure smoke exercises **lean `list` + full `show`** (ASSN-DELTA-002.CC1/CC2) and cleans `-wal`/`-shm`/`.sqlite` state (`R3`, `V7`). Extend it if that coverage is missing.

## Validation — run all, all must pass

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
make fmt-check
make lint
make reflect-check
make docs-check
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test
(cd cli && go test ./...)          # expected inert (V3), run to confirm no regression
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke   # V4: lean list + full show
git status --short                 # V7: no generated sqlite/-wal/-shm/runtime artifacts
```

## Acceptance

- All gates green; smoke green with lean-list/full-show coverage; `git status --short` clean of generated SQLite/WAL/SHM/runtime metadata.
- The blocking undeclared-key invariant gate (from Task 003) is present and green in `clojure -M:test`.

## Guardrails

- Never start/stop/restart or reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`); smoke uses its own disposable `--workspace`. Never `--no-verify`.
