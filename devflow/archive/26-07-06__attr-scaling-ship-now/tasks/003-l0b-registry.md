# Task 003: L0b hot-key registry, literal-path compilation, invariant gate (ASSN-PLAN-001.PH3)

Feature `attr-scaling-ship-now`, branch `attr-scaling-ship-now`, worktree `/Users/ct/dev/projects/skein-src__attr-scaling-ship-now`. **Depends on Task 002.**

Read first: `attr-scaling-ship-now.plan.md` (ASSN-PLAN-001 `PH3`, `A3`, `A4`, `A5`, `AA1`, `AA2`, `AA3`, `AA4`, `AA7`, `TC4`, `TC7`, `R2`) and the deltas: `strand-model.delta.md` (ASSN-DELTA-001.CC9–CC14, `D4`, `D5`), `daemon-runtime.delta.md` (ASSN-DELTA-003.CC2–CC5, `D1`).

## Scope

L0b declared hot-key registry, the shared key spec, literal-path compilation for declared keys, and the blocking undeclared-key gate.

- `src/skein/core/specs.clj` (`AA2`): add `::indexed-attr-key` — one owned spec pinning the declared-key character class to the existing safe `relation-name-pattern` class (`#"[a-z0-9][a-z0-9._/-]*"`; first char alphanumeric, thereafter alphanumeric + `.` `_` `/` `-`), structurally excluding single/double quote, backslash, whitespace, and control chars (ASSN-DELTA-001.D5, `TC4`).
- `src/skein/core/db.clj` (`AA1`): add `indexed_attr_keys` to `schema-sql` as a durable single `TEXT PRIMARY KEY` table with `CREATE TABLE IF NOT EXISTS`, mirroring `acyclic_relations` (ASSN-DELTA-001.CC9, ASSN-DELTA-003.CC2). Add `declare-indexed-attr-key!` / `list-indexed-attr-keys` / `indexed-attr-key?` mirroring the `acyclic_relations` helpers but with **no late-declaration guard** (`D4`, `TC4`). `declare-indexed-attr-key!` validates the key against `::specs/indexed-attr-key` and on failure throws `ex-info` with the **canonical** ex-data `{:key <value> :spec ::specs/indexed-attr-key :allowed-pattern <regex-source>}` (ASSN-DELTA-001.D5); it is idempotent for an already-declared key and installs (`CREATE INDEX IF NOT EXISTS`) an expression index over `json_extract(attributes, '$."<key>"')` (ASSN-DELTA-001.CC10).
- `src/skein/core/query.clj` (`AA3`): `compile-field` consults the durable registry (source of truth) and emits a **literal** JSON path (`json_extract(t.attributes, '$."<key>"')`) for a declared key, the existing bound-parameter form (`json_extract(t.attributes, ?)`) for an undeclared key (ASSN-DELTA-001.CC12/CC13). Before literal emission it **re-validates** the key against the same `::specs/indexed-attr-key` spec, failing loud with the same canonical ex-data rather than splicing an unvalidated key (ASSN-DELTA-001.CC12, ASSN-DELTA-003.CC5). Result semantics identical to the bound form.
- `src/skein/api/weaver/alpha.clj` (`AA4`): two trusted semantic ops paralleling declare/list acyclic relations — declare one indexed attribute key, list declared keys (ASSN-DELTA-003.CC3). Trusted Clojure config/REPL surface only: **no** public JSON socket op and **no** `strand` command (ASSN-DELTA-003.CC4, ASSN-DELTA-002.CC5).
- Tests (`AA7`, under `test/skein/**` — e.g. `test/skein/core/db_test.clj` and a query-compile test ns; add one if none exists): the `::indexed-attr-key` spec's rejection of metacharacter-bearing keys with the canonical ex-data shape, asserted at **both** `declare-indexed-attr-key!` **and** `compile-field`; declared-key literal-path + expression-index behavior; the blocking undeclared-key gate below; an EXPLAIN QUERY PLAN assertion confirming a declared-key predicate uses its expression index (`R2`). Isolated weaver worlds.

## Hard acceptance bar — undeclared-key invariant (blocking, structural)

**An undeclared attribute key is never slower or less capable than today.** Ship a **blocking, structural** regression gate in `clojure -M:test` (not a timing benchmark — `TC7`, ASSN-DELTA-001.CC14, `D2`) asserting for an undeclared key:

- Compiled SQL is **byte-identical** to today's bound-parameter form.
- Full predicate-type capability is preserved across `:=`, `:!=`, `:<`, `:<=`, `:>`, `:>=`, `:in`, `:exists`, `:missing`, and logical composition (ASSN-DELTA-001.CC13).

Declaration adds capability to declared keys; it must never remove capability from any key. A metacharacter-bearing declared key must be rejected fail-loud at both the declaration op and `compile-field` with the canonical ex-data.

## Validation

```sh
cd /Users/ct/dev/projects/skein-src__attr-scaling-ship-now
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test skein.core.db-test skein.core.query-compile-test
make fmt-check && make lint && make reflect-check
git status --short
```

## Guardrails

- Never start/stop/restart or reload the canonical weaver (workspace `/Users/ct/dev/projects/skein-src/.skein`); use disposable `--workspace` worlds.
- Never `--no-verify`.
