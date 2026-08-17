# Op help convention Proposal

**Document ID:** `PROP-OpHelp-001` **Last Updated:** 2026-07-05 **Related RFCs:** None **Related root specs:** [SPEC-003.P5b Blessed op argv parser](../../specs/repl-api.md), [SPEC-002 CLI Surface](../../specs/cli.md), [SPEC-004.C63a-d op registry](../../specs/daemon-runtime.md)

## PROP-OpHelp-001.P1 Problem

Even with subcommands declared as data (shipped `SPEC-003.C64/C65`), there is no help _invocation_ convention: `strand kanban help`, `strand kanban -h`, and `strand kanban --help` all produce domain errors, and every multi-verb spool op must hand-roll (or forget) that translation. Separately, the Go CLI HTML-escapes error details (`<` for `<`) because `cli/internal/client/client.go` renders ex-data with `json.Marshal` default escaping, making usage strings unreadable — the user's original evidence showed both problems compounding. Finally, the motivating op (`kanban`) still registers raw-envelope with hand-rolled dispatch, so it benefits from none of the feature-1 primitive.

## PROP-OpHelp-001.P2 Goals

- **PROP-OpHelp-001.G1:** `strand <op> help|-h|--help` returns the same projection as `strand help <op>` for every op that declares `:subcommands`, uniformly, with zero per-spool code.
- **PROP-OpHelp-001.G2:** CLI error details print byte-faithful JSON (no `<` escapes).
- **PROP-OpHelp-001.G3:** The kanban op declares its verbs via `:subcommands`, so the user's exact pain commands yield help or structured available-names errors end to end.
- **PROP-OpHelp-001.G4:** No existing valid invocation changes meaning (TEN-003: no silent hijacking of legitimate argv).

## PROP-OpHelp-001.P3 Non-goals

- **PROP-OpHelp-001.NG1:** No help translation for flat (positional/flag-only) arg-spec ops — `strand add help` must keep meaning "add a strand titled help"; `strand help <op>` remains their discovery path.
- **PROP-OpHelp-001.NG2:** No translation for raw-envelope ops — they own their argv semantics (`agent` keeps its `about` manual convention).
- **PROP-OpHelp-001.NG3:** Bare `strand <op>` (missing subcommand) stays a loud non-zero structured error — machine callers must not receive exit-0 help for a malformed invocation; the error already carries available names.
- **PROP-OpHelp-001.NG4:** No Go dispatcher parsing of op argv (SPEC-002.C30 verbatim-argv contract untouched); no `agents`/`treadle` spool migrations.

## PROP-OpHelp-001.P4 Proposed scope

- **PROP-OpHelp-001.S1:** Weaver-side help routing: when an op's arg-spec declares `:subcommands` and the invocation's argv is **exactly one token** — `help`, `-h`, or `--help` — with **no attached payloads**, the invocation returns the op's help detail (the `strand help <op>` projection) instead of a parse error. Any extra argv or payloads falls through to normal parsing and its loud errors — malformed calls must never become exit-0 help. These become reserved subcommand names (declaring them fails loudly).
- **PROP-OpHelp-001.S2:** Go CLI error rendering stops HTML-escaping details JSON.
- **PROP-OpHelp-001.S3:** Kanban spool migrates to declared `:subcommands` (verbs unchanged; `about` stays); hand-rolled dispatch/usage errors deleted.
- **PROP-OpHelp-001.S4:** Root spec deltas: SPEC-003.P5b (reserved help tokens, routing contract), SPEC-004.C63 area (invocation behavior), SPEC-002 (help convention + error-detail encoding pinned). Kanban behavior doc (`spools/kanban.md`) updated.

## PROP-OpHelp-001.P5 Open questions

- **PROP-OpHelp-001.Q1:** Should help-token routing live in `parse` (parser returns a help marker) or in `op!` (dispatch short-circuits to the help projection before parse)? Leaning `op!`: help is a registry projection, not a parse result, and the parser stays pure data-in/data-out.
- **PROP-OpHelp-001.Q2:** Exit semantics: help via `<op> help` returns the detail as a normal single-result (exit 0)? Leaning yes — it is a successful discovery request, unlike a missing subcommand.
- **PROP-OpHelp-001.Q3:** Does kanban's `about` doc survive as-is, or fold into per-subcommand docs? Leaning keep `about` (rich manual) and let `help` show the structural surface — they serve different depths.
