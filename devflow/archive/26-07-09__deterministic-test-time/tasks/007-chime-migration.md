# Task 7: Migrate chime onto notifier + lane settle

**Document ID:** `TASK-Dtt-007`

## TASK-Dtt-007.P1 Scope

Type: AFK

Replace chime's fixed inter-poll sleeps with the existing deterministic notifier-thread join plus `await-quiescent!`, and resolve PROP-Dtt-001.Q2 (RFC-Dtt-001.REC6, PLAN-Dtt-001.PH5). chime carries no timer and is not a clock consumer. It stays in `serial-namespaces` here.

## TASK-Dtt-007.P2 Must implement exactly

- **TASK-Dtt-007.MI1:** In `test/skein/chime_test.clj`, replace the fixed sleeps (`:191,:211,:231`) with `await-notifier-threads!` (`:42-51`) for notifier-worker settling plus `await-quiescent!` for any event-lane settling.
- **TASK-Dtt-007.MI2:** Keep the real notifier subprocess; no chime engine change (`chime.clj:109-133` per-dispatch daemon threads stay).
- **TASK-Dtt-007.MI3:** Resolve Q2: default is that every notifier assertion tolerates the settle signal and `skein.chime-test` is deterministic as a whole namespace. If a specific assertion genuinely resists the settle signal, split it into a dedicated test namespace that stays in `serial-namespaces` with a why-serial comment (precedent: the `skein.chime-sync-test` split) — the runner schedules whole namespaces, so a lone "serial test" inside a parallel namespace is not implementable. Record the split and its reason in the plan's Developer Notes; do not weaken the assertion.

## TASK-Dtt-007.P3 Done when

- **TASK-Dtt-007.DW1:** `clojure -M:test skein.chime-test` is green with no fixed inter-poll sleeps in the notification-settling paths.
- **TASK-Dtt-007.DW2:** `make fmt-check lint` pass for the touched test namespace.
- **TASK-Dtt-007.DW3:** `test/skein/test_runner.clj` is untouched (no graduation here).

## TASK-Dtt-007.P4 Out of scope

- **TASK-Dtt-007.OS1:** Moving `skein.chime-test` to `parallel-namespaces` (Task 9).
- **TASK-Dtt-007.OS2:** A clock seam for chime (it arms no timer), treadle, reed, weaver-test.

## TASK-Dtt-007.P5 References

- **TASK-Dtt-007.REF1:** PROP-Dtt-001.Q2, RFC-Dtt-001.REC3/REC6 (chime row), PLAN-Dtt-001.PH5.
- **TASK-Dtt-007.REF2:** `chime.clj:109-133`; `chime_test.clj:42-51,191,211,231`.
