# CLI Surface delta for discovery-tier factoring

**Document ID:** `DELTA-Dtf-001` **Root spec:** [cli.md](../../../specs/cli.md) (SPEC-002) **Feature:** [../proposal.md](../proposal.md) **Related:** [RFC-Dtf-001](../../../rfcs/2026-07-20-discovery-tier-factoring.md); DELTA-Dtf-002 (daemon-runtime), DELTA-Dtf-003 (repl-api) **Status:** Merged **Last Updated:** 2026-07-21

## DELTA-Dtf-001.P1 Summary

Reworks the discovery surface (SPEC-002.C39) around one canonical, versioned, **fractal** help schema that is verbose by default and sliceable per verb, replaces the `<op> help` sole-token alias grammar with a trailing-`--help` rewrite, adds builtin `about`/`prime` meta-verbs beside `help`, and tightens the dispatcher's pre-op `--help` grammar (SPEC-002.C34/ C15) so a named op with a leading `--help` is an error rather than static usage. The transform, source-pointer, glossary, and op-metadata mechanics land in DELTA-Dtf-002 (SPEC-004); the arg-spec/return-shape projection and per-node annotation authoring land in DELTA-Dtf-003 (SPEC-003). This delta owns the wire contract a `strand` consumer sees.

## DELTA-Dtf-001.P2 Contract changes

- **DELTA-Dtf-001.CC1 (canonical help response envelope):** `strand help <op>` returns one **response envelope** — never a bare node — with a stable top level so `jq` paths and transform input are unambiguous:

    ```
    {schema-version, operation {name, provenance, stream?, deadline-class, hook-class, raw-envelope},
     source, glossary, node}
    ```

    - `schema-version` — a positive integer versioning the **help-schema contract itself** (DELTA-Dtf-001.D3), independent of any release/build identity and of `protocol_version`.
    - `operation` — op-wide facts that are not per-verb: `name` and the registry envelope metadata (`provenance`, `stream?`, `deadline-class`, `hook-class`, `raw-envelope`). These live here, not on the recursive node, so the node is genuinely uniform at every depth.
    - `source` — the op-wide handler pointer (DELTA-Dtf-002.CC2): always present, `null` or `{file, line}`. It is op-wide (one handler symbol per op), never repeated per verb.
    - `glossary` — the referenced-term closure for this response (DELTA-Dtf-002.CC5): a map of the outcome names any node under `node` references, to their canonical definitions.
    - `node` — the fractal node (CC2).

- **DELTA-Dtf-001.CC2 (fractal node shape):** `node` is a normalized, self-similar shape; slicing to a verb (`strand help <op> <verb>`) returns the **same shape** under `node`, not a distinct schema or code path. Every key is always present with defined empty/null semantics so one recursive renderer needs no per-level branches:

    | key | type | semantics |
    | --- | --- | --- |
    | `name` | string | node name (op name at root; subcommand name for a child). |
    | `doc` | string | declared doc; `""` when undeclared. |
    | `invocation` | `{mode, flags[], positionals[]}` | `mode` is `"declared"` or `"raw-envelope"`; `flags`/`positionals` are always arrays (`[]` when none or when a subcommand parent delegates to children). |
    | `returns` | return-shape explain, or `null` | JSON-safe `:returns` projection for this node (SPEC-003.C60b); `null` when undeclared. |
    | `use-when` | string array | authored annotations (DELTA-Dtf-003.CC2); `[]` when none. |
    | `notes` | string array | authored annotations; `[]` when none. |
    | `failure-modes` | string array of glossary **names** | outcome-name references only (DELTA-Dtf-002.CC5); `[]` when none. |
    | `children` | node array | child verb nodes, same shape; `[]` for a flat/leaf node. |

    `invocation.mode` distinguishes a raw-envelope op (undeclared argv) from a declared op with no arguments — both otherwise have empty `flags`/`positionals`. The root node carries **no** op-wide metadata or source (those are in the envelope, CC1).

- **DELTA-Dtf-001.CC3 (no-arg catalog is versioned, not a second schema):** `strand help` with no op returns `{schema-version, ops[]}` where each entry is a **shallow per-op envelope** — `{operation, source, node}` with the same structure as the detail envelope (CC1) but a **summary node**: `name` and `doc` populated, `invocation` at its declared mode with empty flags/positionals, `returns` `null`, annotations `[]`, `children` `[]`. Op-wide facts stay in each entry's `operation` and `source` exactly as in CC1 — never merged onto the node — so the catalog reuses both the envelope and node contracts unchanged. It carries the same `schema-version` and is part of the one documented schema family (SPEC-002.C39, RFC-Dtf-001.O1), never an accidental second undocumented shape.

- **DELTA-Dtf-001.CC4 (default rendering + `--json` floor):** With no registered default help transform, `strand help` output IS the raw canonical envelope JSON. A registered default help transform (DELTA-Dtf-002.CC1) receives the **full envelope** (CC1) and renders help instead; `--json` is the **sole** opt-out back to the raw envelope and the raw floor when none is registered. `--json` always bypasses, so a failing transform never bricks help (the transform failure surfaces loudly, DELTA-Dtf-002.CC1). The per-invocation `-t/--transform` selector flag is **not shipped**. `--json` is **leading-only** within the help surface; other flags on the help surface are invalid (`strand help ... --foo` fails).

- **DELTA-Dtf-001.CC4a (verbatim relay of transform output):** A registered default help transform's output is relayed **verbatim** (bytes as-is — text stays text, never JSON-re-quoted), per DELTA-Dtf-002.CC1. The weaver's single-result success frame carries an optional `verbatim` boolean — present/`true` only for a transform-rendered `help` result — and the `strand` client honors it by writing the raw string bytes (single trailing newline if absent), instead of the default byte-faithful JSON single-result relay (SPEC-002.C4/C36). It is a backward-compatible frame extension alongside the stream `header`/`done` framing: absent (the default) means the ordinary JSON relay. With no transform, or under `--json`, the result is the raw canonical envelope map relayed as JSON as today; `about`/ `prime` (JSON objects) are never verbatim. No other op's relay changes.

- **DELTA-Dtf-001.CC5 (`--help` must trail, post-op):** After an op name, `--help`/`-h` is sugar the weaver rewrites to the `help` op, valid **only** as the final token with no other flags; any other shape (`strand agent --foo --help`, a non-final `--help`, attached payloads) is a concise redirect error pointing at `strand help ...`. This supersedes SPEC-004.C63e's subcommand-only sole-token `<op> help|-h|--help` alias: the trailing-`--help` rewrite applies to **all** ops (flat, subcommand, and raw-envelope), and the `<op> help` / `<op> about` / `<op> prime` verb-position sugar is migration-only, retired in alpha (TEN-000@1). Detail in DELTA-Dtf-002.CC3.

- **DELTA-Dtf-001.CC6 (dispatcher pre-op `--help` grammar):** `strand --help` / `-h` / bare `strand` with **no op** stays dispatcher usage printed by the Go dispatcher (SPEC-002.C34/C15). Once an op is named (arity ≥ 1), `--help` **must trail**: `strand --help <op>` is now an **error** (was: static usage — `dispatch.go` set the help bit on any pre-op `--help` and ignored trailing tokens). This is a **Go dispatcher grammar change**: the dispatcher must distinguish no-op `--help` (usage) from op-present pre-op `--help` (error redirecting to `strand help <op>` or trailing `--help`). The dispatcher still ships verbatim argv and interprets no arg-spec (SPEC-002.C30, TEN-006).

- **DELTA-Dtf-001.CC7 (`about` / `prime` meta-verbs):** Two builtin meta-verbs join `help`: `strand about <op>` returns `{"about": "<prose>", "source": <source>}` and `strand prime <op>` returns `{"prime": "<prose>", "source": <source>}` — each a JSON object (never a bare string) so keys may be added later without a breaking conversion, with the op-wide `source` (CC1, always present, `null` or `{file, line}`). Both are **arity-1** (op-level): a verb path (`strand about agent delegate`) fails loudly and redirects to `help` (DELTA-Dtf-003.CC4). Declared prose must be non-blank; absence of a declared `about`/`prime` returns a loud `discovery/unavailable` outcome (TEN-003), never empty success. Content is prose in a minimal envelope; structure is promoted _out_ into help/glossary, never modelled inside `about` (RFC-Dtf-001.O5, PROP-Dtf-001.NG4).

## DELTA-Dtf-001.P3 Design decisions

### DELTA-Dtf-001.D1 Reword the "help is never hand-written" invariant

- **Decision:** Restate SPEC-002.C39's characterization as "one declared, versioned schema, uniformly projected; renderings are transforms over it." A trusted, registered transform may render help, but the **machine schema (the CC1 envelope) is the single versioned contract**, and no op hand-authors usage or dispatch output.
- **Rationale:** The default-transform slot (DELTA-Dtf-002.CC1) deliberately introduces rendering; the invariant must reserve room for a future user-declared help adapter without reopening "help is generated, not authored." The versioned envelope, not any rendering, is what consumers `jq` and script against.
- **Rejected:** A compact second "index" schema (reopens "how do we display each nesting level" and forks the contract, RFC-Dtf-001.O1); a per-call `-t` transform flag (no demand, RFC-Dtf-001.O2/NG2).

### DELTA-Dtf-001.D2 TEN-006 stays intent-true; wording lightly adjusts

- **Decision:** TEN-006 (thin JSON CLI) holds: the **default** help output is JSON and any transform is a **user choice** registered in trusted config. Adjust TEN-006's wording to acknowledge that a trusted-registered transform may render help output, without weakening "the CLI does not author or debug rich userland structures." This is an **editorial** clarification, not a normative change — no `@N` bump (TENETS.md versioning rule).
- **Rationale:** Rendering lives in daemon config, not the CLI; the CLI only carries the `--json` floor and relays bytes.
- **Rejected:** Bumping TEN-006 to a new normative version (intent unchanged); shipping the transform selector on the CLI (grows the CLI into an extension surface, PHILOSOPHY).

### DELTA-Dtf-001.D3 `schema-version` versions the help schema, not the release/build identity

- **Decision:** The envelope's `schema-version` is a positive integer identifying the **help-schema contract** and bumps only when that contract changes shape. It is distinct from the "one shared release/build identity across mill/strand/core" (a separate concern, mechanism out of scope, PROP-Dtf-001.NG3, DELTA-Dtf-002.D2) and from `protocol_version` (frame wire-compat, SPEC-004.C23b). Additive, accretion-safe growth (SPEC-002.C4a) does not bump `schema-version`; a breaking reshape does.
- **Rationale:** The three cadences are independent; conflating them forces spurious bumps. A consumer keys transform/`jq` logic off the schema contract, not off a build stamp.
- **Rejected:** Reusing the shared release version or `protocol_version` as the help-schema version.

## DELTA-Dtf-001.P4 Open questions

- **DELTA-Dtf-001.Q1 (dispatcher change home):** RESOLVED — the Go dispatcher grammar change (CC6) ships **in this feature** (small, tightly coupled to CC5's `--help` semantics; PLAN-Dtf-001.A5/PH4, sol-med concurs). PROP-Dtf-001.Q5 closed.
