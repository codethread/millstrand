# Task 1: Split harness and alias registries with alias-first resolution

**Document ID:** `TASK-HarnessAliasRegistries-001`

## TASK-HarnessAliasRegistries-001.P1 Scope

Type: AFK

Engine phase PLAN-HarnessAliasRegistries-001.PH1 in `skein.spools.shuttle`: tools and seats stop sharing one registry.

## TASK-HarnessAliasRegistries-001.P2 Must implement exactly

- **TASK-HarnessAliasRegistries-001.MI1:** Spool-state gains `:alias-registry (atom {})` beside `:harness-registry`; `state-version` bumps 2→3. `defharness!` writes only the harness registry, `defalias!` only the alias registry; re-registration within a registry replaces, and the same name may exist in both registries at once.
- **TASK-HarnessAliasRegistries-001.MI2:** `migrate-state` splits a preserved mixed `:harness-registry` into the two atoms, asserting each entry matches exactly one shape (alias: `:alias-of` present; harness: `:argv` present) and failing loudly with the offending entry in `ex-data` when an entry matches neither or both.
- **TASK-HarnessAliasRegistries-001.MI3:** `resolve-harness` and the listing's root walk share one lookup rule: prefer an unvisited alias, else the harness registry, else fail. `alias pi -> harness pi` terminates at the tool. Missing names keep `:error-class "harness-not-found"` (recovery deferral keys off it) and now list both registries' available names; a genuine alias cycle fails with a distinct error that is NOT classed `harness-not-found`.
- **TASK-HarnessAliasRegistries-001.MI4:** `harnesses` returns the concatenation of both registries' entries sorted by name — never merged by name first, or the same-name shadow pair would silently collapse to one row (a same-named tool and seat both appear; `:kind` distinguishes) — and alias entries keep carrying `:harness`/`:harness-doc` resolved via the new rule, still best-effort (broken chains omit the keys rather than failing the listing).
- **TASK-HarnessAliasRegistries-001.MI5:** `register-default-harnesses!` keeps its keep-existing semantics against the harness registry. `defharness!`/`defalias!`/ns docstrings state the two-registry contract and resolution order.
- **TASK-HarnessAliasRegistries-001.MI7:** Registry entry shapes become clojure.specs that validation actually consults: `::harness-def` and `::alias-def` data specs, checked by `defharness!`/`defalias!` and by the migrate split's exactly-one-shape assertion, replacing the equivalent hand-rolled structural checks. Keep manual checks only for what a spec cannot express (closed key sets already enforced, resume argv placeholder membership, cross-registry semantics), and reference each spec from its owning docstring.
- **TASK-HarnessAliasRegistries-001.MI6:** Tests in `test/skein/shuttle_test.clj`: same-name shadow resolves alias-first and terminates at the tool; unshadowed harness resolves directly; alias cycle fails with the non-not-found error; missing name fails `harness-not-found` listing both registries; listing union shows both same-named entries with root docs on aliases; migration test proving a v2-shaped mixed registry splits with nothing dropped and a malformed entry fails loudly. Update `state-shape-matches-declared-version` for v3.

## TASK-HarnessAliasRegistries-001.P3 Done when

- **TASK-HarnessAliasRegistries-001.DW1:** Targeted namespaces green: `skein.shuttle-test`, `skein.config-test`, `skein.agents-test` (runner has no filter — use the inline `:focus` alias override from PLAN-HarnessAliasRegistries-001.TC1).
- **TASK-HarnessAliasRegistries-001.DW2:** `make fmt-check lint reflect-check` clean; `make api-docs` regenerated if any public docstring changed; `git status --short` free of runtime artifacts.
- **TASK-HarnessAliasRegistries-001.DW3:** Work committed on `harness-alias-registries` in this worktree (atomic commits, why-focused messages, HEREDOC), status set implemented; do not close the strand, do not land.

## TASK-HarnessAliasRegistries-001.P4 Out of scope

- **TASK-HarnessAliasRegistries-001.OS1:** Any `.skein` config change, the `pi-main`→`worker` rename, doc/README/cookbook sweep, surface baseline, and full-suite/smoke runs — task 2 and land discipline own those.

## TASK-HarnessAliasRegistries-001.P5 References

- **TASK-HarnessAliasRegistries-001.REF1:** Plan `devflow/feat/harness-alias-registries/harness-alias-registries.plan.md` (A1–A4, TC1); proposal `proposal.md` (S1–S4).
- **TASK-HarnessAliasRegistries-001.REF2:** Code anchors: `spools/shuttle/src/skein/spools/shuttle.clj` ~80–160 (state version/migrate), ~349–534 (defharness!/defalias!/resolve-harness/ root-harness/harnesses/register-default-harnesses!); `test/skein/shuttle_test.clj` `harness-registry-validates-and-resolves-aliases`.
