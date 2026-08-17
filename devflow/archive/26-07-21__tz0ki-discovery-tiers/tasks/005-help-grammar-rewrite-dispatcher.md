# Task 5: --help grammar (weaver rewrite + Go dispatcher)

**Document ID:** `TASK-Dtf-005`

## TASK-Dtf-005.P1 Scope

Type: AFK

Implement the `--help` grammar: the weaver trailing-`--help` rewrite for all ops (superseding the C63e sole-token alias) and the Go dispatcher pre-op `--help <op>` error. File-disjoint from the `help.clj` projection chain (weaver rewrite lives in the socket/dispatch path; Go in `dispatch.go`).

## TASK-Dtf-005.P2 Must implement exactly

- **TASK-Dtf-005.MI1:** The weaver rewrites a **trailing** `--help`/`-h` **flag** token (final token, no other flags, no attached payloads) after the first op token to the `help` op, for **all** ops (flat, subcommand, raw-envelope), resolving **before lifecycle-hook gating** (read-class projection; mutating hooks do not fire; handler never called). Only the flag forms rewrite. Any other argv shape → normal parsing / loud errors. Supersedes SPEC-004.C63e's subcommand-only sole-token alias. Per DELTA-Dtf-001.CC5 / DELTA-Dtf-002.CC3.
- **TASK-Dtf-005.MI2:** The **bare word** `help`/`about`/`prime` in verb position is the retired `<op> help`/`about`/`prime` sugar (alpha, TEN-000@1) — **not** rewritten; it fails with the loud redirect to `strand help <op>`. `help`/`-h`/`--help` stay reserved subcommand names (SPEC-003.C65).
- **TASK-Dtf-005.MI3:** Go dispatcher (`cli/internal/dispatch/dispatch.go`): `strand --help`/`-h`/bare `strand` with **no op** stays usage; once an op is named, pre-op `--help <op>` is an **error** redirecting to `strand help <op>` or trailing `--help`. `--json` leading-only within the help surface. The dispatcher still ships verbatim argv and parses no arg-spec (SPEC-002.C30). Per DELTA-Dtf-001.CC5/CC6.

## TASK-Dtf-005.P3 Done when

- **TASK-Dtf-005.DW1:** Weaver tests: trailing `--help` across flat/subcommand/raw ops × extra flags × payloads × non-final position; hook-bypass preserved. Go tests: no-op usage vs op-present pre-op error; `--json` position. The co-located weaver test namespace(s) pass under `clojure -M:test`, and `(cd cli && go test ./...)` green.
- **TASK-Dtf-005.DW2:** `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check` green.

## TASK-Dtf-005.P4 Out of scope

- **TASK-Dtf-005.OS1:** The transform slot/rendering (Task 6); the help envelope projection (Task 1).

## TASK-Dtf-005.P5 References

- **TASK-Dtf-005.REF1:** DELTA-Dtf-001.CC5/CC6; DELTA-Dtf-002.CC3; PLAN-Dtf-001.PH4/A5.
- **TASK-Dtf-005.REF2:** `src/skein/core/weaver/socket.clj:273-288`, `src/skein/core/weaver/ help.clj:54-67`, `cli/internal/dispatch/dispatch.go:55-60,108-163`.
