# Task 001: L0a storage pragmas (ASSN-PLAN-001.PH1)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`.

Read first: `devflow/feat/attr-scaling-ship-now/attr-scaling-ship-now.plan.md` (ASSN-PLAN-001, esp. `PH1`, `A6`, `AA1` pragma portion, `CM1`, `R3`) and `devflow/feat/attr-scaling-ship-now/specs/daemon-runtime.delta.md` (ASSN-DELTA-003.CC1, `D2`) and `strand-model.delta.md` (ASSN-DELTA-001.CC8).

## Scope

L0a only — WAL/mmap/cache pragmas on the weaver datasource. **No** schema, contract, or read-shape change. This is `AA1`'s pragma portion; the `indexed_attr_keys` registry is Task 003, not here.

- `src/skein/core/db.clj`: the `datasource` open path opens every SQLite database with `journal_mode=WAL`, a non-zero `mmap_size`, and an enlarged `cache_size`, applied on open for every world (ASSN-DELTA-001.CC8, ASSN-DELTA-003.CC1). File and `sqlite-memory` storage share one open path (ASSN-DELTA-003.CC1). Storage identity, single-storage-handle (SPEC-004.C91), and incompatible-schema fail-loud (SPEC-004.C91b) stay unchanged. Pick concrete `mmap_size`/`cache_size` values (ASSN-DELTA-003.Q1 leaves them to implementation; evidence base cites 3–5× write / −25–50% scan wins).
- `-wal`/`-shm` sidecar files (ASSN-PLAN-001.R3): extend the generated-artifact cleanup in `dev/skein/smoke.clj` and any test teardown so `git status --short` stays clean (V7). Do not commit any `-wal`/`-shm`/`.sqlite` runtime files.

## Acceptance

- Pragmas apply on open for file and memory storage; no API/read-shape/schema effect. `ensure-current-schema!` still validates only `strands`/`strand_edges`.
- Focused `db` tests cover the pragma-on-open behavior **by asserting the queried-back pragma values per storage kind**: file-backed storage must report `journal_mode=wal` (anything else fails — a real WAL failure must never pass silently); `sqlite-memory` is expected to report `journal_mode=memory` (SQLite ignores WAL for in-memory databases; assert that fallback explicitly so it can never mask a file-backed failure). Full suite green.
- `git status --short` shows no generated SQLite/WAL/SHM artifacts (V7).

## Validation

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test skein.core.db-test
make fmt-check && make lint && make reflect-check
git status --short   # must be clean of sqlite/-wal/-shm
```

## Guardrails

- Never start/stop/restart or reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`). Any live check uses a disposable `--workspace` world. No `mill`/`weaver` start/stop.
- Never `--no-verify`.
