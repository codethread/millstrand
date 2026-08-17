# Task 13: canonical HISTORY rewrite + weaver restart (HITL, cu3wz)

**Document ID:** `TASK-Np-013` **Slice:** `PLAN-Np-001.S13` (`PROP-Np-001.C10.3`, `C13.1`–`C13.3`) **Harness:** coordinator **Type:** HITL **Depends on:** TASK-Np-011 (rehearsed clean), TASK-Np-012 (and the branch landed on main via the land pipeline)

## TASK-Np-013.P1 Scope

Type: HITL

Coordinator-only execution of the rehearsed HISTORY rewrite against the canonical `.skein` world, then a weaver restart so the rewired note surface loads. The restart runs under the standing pre-authorization recorded as strand `cu3wz` (card `ah5vu`, notes `u9jtn`/`fls7n`), so it does not re-ask for sign-off — **but it remains a ceremony hard stop:** quiet board, backup, rehearsal-passed, and post-cutover smoke are all mandatory (`PROP-Np-001.C13.2`). Never delegated to a worker; never started autonomously.

## TASK-Np-013.P2 Must implement exactly

- **TASK-Np-013.MI1:** Precondition: F3 landed on main, Task 11's rehearsal recorded clean, Task 12 green on the landed head.
- **TASK-Np-013.MI2:** Quiesce the board (`PROP-Np-001.C10.3`, `C13`): no in-flight note writers mid-transition.
- **TASK-Np-013.MI3:** Back up the live db (resolve via `./bin/mill weaver status --workspace <canonical>` → `database_path`; copy aside), then run `scripts/cutover/note_primitive.clj` against it with an explicit `--db` target. The 67 dangling notes (no live target) are skipped and stay inert (`PROP-Np-001.C11`, `Q4`).
- **TASK-Np-013.MI4:** Restart the canonical weaver under `cu3wz` (no fresh sign-off required, but the ceremony hard stop stands), then run the `PROP-Np-001.C13.3` post-cutover smoke: `strand notes <target>` returns notes from every writer, `strand kanban card <card>` shows its handovers, `strand agent notes <target>` agrees with `strand notes`, and `strand kanban board` renders clean.
- **TASK-Np-013.MI5:** Record the cutover (backup path, row/edge counts, smoke results) as a note on card `7azzl`; only then is `kanban finish` in play.

## TASK-Np-013.P3 Done when

- **TASK-Np-013.DW1:** `PROP-Np-001.DW5` proven — the canonical world is rewritten, the restart ran under `cu3wz` with full ceremony, the C13.3 smoke checks pass, and an audit note is on the card.

## TASK-Np-013.P4 Out of scope

- **TASK-Np-013.OS1:** Any code or doc change.

## TASK-Np-013.P5 References

- **TASK-Np-013.REF1:** `PLAN-Np-001.S13`, `PLAN-Np-001.CM3`, `PLAN-Np-001.V5`; `PROP-Np-001.C10.3`, `C13.1`–`C13.3`, `R2`.
- **TASK-Np-013.REF2:** F2 ceremony: `devflow/feat/agent-engine-primitives/cutover-ceremony.md`; `cu3wz` (the standing pre-authorization record).
