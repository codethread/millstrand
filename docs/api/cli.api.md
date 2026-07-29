
-----
# <a name="skein.api.cli.alpha">skein.api.cli.alpha</a>


Blessed declarative argv parser for weaver ops (SPEC-003-D003.C1/C2).

  An arg-spec is minimal EDN data (no functions) describing an op's flags and
  positionals. `parse` turns an envelope's argv plus attached payloads into a
  data map of keyword arg names to parsed values, or throws a loud structured
  `ex-info`. `explain` renders the same arg-spec as JSON-safe help data for the
  `help <op>` projection. The namespace is pure: no registry, socket, or runtime
  coupling and no module-level state.

  An arg-spec is a fractal node tree (DELTA-Lhc-001.CC1). A **leaf** node
  declares no `:subcommands`; its `:flags`/`:positionals` are optional (a
  doc-only leaf is valid) and it carries required `:hook-class`/
  `:deadline-class` metadata as peers of `:doc` (DELTA-Lhc-001.CC2):

    {:op <keyword-or-string> ; optional, echoed into errors and help
     :doc <string>           ; optional summary
     :hook-class :read | :mutating
     :deadline-class :standard | :unbounded
     :flags {<name-kw> <flag-spec>}
     :positionals [<positional-spec> ...]} ; trailing may be variadic

  An **interior** node instead declares `:subcommands`, mapping non-blank name
  strings to nested nodes of the same shape at any depth; it may not also
  declare `:flags`/`:positionals` or class metadata, and an empty
  `:subcommands {}` is invalid:

    {:op <keyword-or-string>
     :doc <string>
     :subcommands {<name-string> <node> ...}}

  Parsing a subcommand arg-spec routes on argv tokens recursively: each token
  selects a child node until a leaf is reached, and the remaining argv parses
  against the leaf. The result merges the leaf's parsed args with `:subcommand`
  bound to the full routing path as a vector of name strings (always a vector,
  at every depth; DELTA-Lhc-001.CC3). Missing or unknown routing tokens — and
  structural failures at depth — carry the canonical error context: `:op`
  (string), `:path` (tokens successfully walked, `[]` at the root), `:token`
  (the offending token, nil when missing), and `:available` (child names at the
  failing node). The names in `reserved-subcommand-names` and the `subcommand`
  arg name are rejected at every level.

  A flag-spec is a map with:

    :type      :string | :int | :boolean | :boolean-token | :map
               - :string/:int/:boolean-token consume a typed value
               - :boolean is a presence form (`--flag` -> true, no value)
               - :map accumulates `--flag key=value` tokens into a map
    :repeat?   truthy -> repeatable, values collect into a vector
    :required? truthy -> must appear
    :parse     :json | :jsonl ; parse the resolved string value
    :spec      qualified keyword or symbol naming a registered clojure.spec
               of the parsed value — documentation the help projection
               embeds (SPEC-003.C23c); the parser never consults it
    :doc       <string>

  A positional-spec is a map with :name (keyword), :type, :required?,
  :variadic? (trailing only, collects remaining tokens into a vector), :parse,
  :spec, and :doc.

  Payload references (SPEC-003-D003.C2): after argv parsing, any whole string
  value equal to `:stdin` or `:payload/<name>` resolves to the matching entry in
  the envelope payloads map. Matching is whole-value only (a value that merely
  contains `:stdin` as a substring is untouched). A reference with no matching
  payload throws; an attached payload that no reference consumed throws.
  Payload references and `:parse` declarations apply unchanged inside every
  nested level.




## <a name="skein.api.cli.alpha/explain">`explain`</a>
``` clojure
(explain arg-spec)
```
Function.

Render `arg-spec` as JSON-safe help data.

  Includes arguments, types, docs, required flags, and payload-parse
  declarations for the `help <op>` projection; nested subcommands render
  recursively to their declared depth (DELTA-Lhc-001.CC3).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L152-L159">Source</a></sub></p>

## <a name="skein.api.cli.alpha/parse">`parse`</a>
``` clojure
(parse arg-spec argv)
(parse arg-spec argv payloads)
```
Function.

Parse `argv` against `arg-spec`, resolving payload references from `payloads`.

  Returns a map of keyword arg names to parsed values. For subcommand arg-specs,
  argv tokens route recursively to the invoked leaf and the result includes
  `:subcommand` bound to the full routing path vector (DELTA-Lhc-001.CC3).
  Throws a structured `ex-info` (ex-data carries `:reason` plus the offending
  token/flag and the op) on any violation: unknown flags, missing required args,
  type violations, duplicate non-repeat flags, malformed key=value tokens,
  trailing unconsumed tokens, missing/unknown subcommands (with the canonical
  `:op`/`:path`/`:token`/`:available` context), dangling or unused payload
  references, and malformed :json/:jsonl payloads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L116-L136">Source</a></sub></p>

## <a name="skein.api.cli.alpha/reserved-subcommand-names">`reserved-subcommand-names`</a>




Subcommand names reserved from op declaration for the help grammar.

  The single source of truth for the reserved set: registration/parse/explain
  validation here blocks any op from declaring these as subcommands at any
  depth (DELTA-Lhc-001.CC1). The weaver rewrites only the dash-prefixed flag
  forms (`--help`/`-h`) of a trailing token to the `help` op
  (DELTA-Dtf-002.CC3); the bare word `help` stays reserved but is the retired
  sugar that flows to normal parsing.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L75-L84">Source</a></sub></p>

## <a name="skein.api.cli.alpha/resolve-leaf">`resolve-leaf`</a>
``` clojure
(resolve-leaf arg-spec argv)
```
Function.

Walk `argv`'s routing tokens through `arg-spec` to the invoked leaf node.

  Returns `{:node <leaf-node> :path <path-vector>}` without parsing the leaf's
  flags or positionals: the walk consumes exactly the routing tokens, so
  callers such as the socket payload-hook gate and deadline lookup
  (DELTA-Lhc-002.CC3/CC4) can read leaf metadata before any hook runs. A flat
  arg-spec resolves to its own root at path `[]`. Missing or unknown routing
  tokens fail loudly with the canonical `:op`/`:path`/`:token`/`:available`
  context.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L138-L150">Source</a></sub></p>

## <a name="skein.api.cli.alpha/validate!">`validate!`</a>
``` clojure
(validate! arg-spec)
```
Function.

Validate any parser arg-spec shape, returning it unchanged on success.

  Validates the fractal node tree recursively (DELTA-Lhc-001.CC1/CC2): interior
  nodes may not declare `:flags`/`:positionals` or class metadata, leaves may
  carry `:hook-class`/`:deadline-class`, reserved names are rejected at every
  level, and an empty `:subcommands {}` is invalid. Throws structured `ex-info`
  on malformed specs so op registration fails before help or invocation can
  drift from the contract.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L86-L101">Source</a></sub></p>

## <a name="skein.api.cli.alpha/validate-annotations!">`validate-annotations!`</a>
``` clojure
(validate-annotations! op annotations)
```
Function.

Structurally validate a standalone annotation sub-map for `op`, returning it.

  The same closed-shape check `validate!` applies to an arg-spec node's
  `:annotations` (closed `use-when`/`notes`/`failure-modes` keys, each an array of
  non-blank strings), exposed for the raw-envelope root annotation surface an op
  declares outside any arg-spec (DELTA-Dtf-002.MI1a). Purely structural: the
  glossary-ref existence check for `failure-modes` names runs at registration
  (DELTA-Dtf-003.CC2).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L103-L114">Source</a></sub></p>
