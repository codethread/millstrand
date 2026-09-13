# CLI Surface delta for patterned weave

**Document ID:** `DELTA-001` **Root spec:** [CLI Surface](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-27

## DELTA-001.P1 Summary

The public `strand` CLI gains a mutation command, `weave`, that reads JSON from stdin and asks the selected weaver to apply a trusted named pattern. It also gains a read-only pattern inspection command so callers can discover the JSON input shape expected by a registered pattern. The CLI does not define patterns and does not parse rich graph DSLs; it submits data to daemon-owned runtime patterns.

## DELTA-001.P2 Contract changes

- **DELTA-001.CC1:** Add `weave` and `pattern explain` to the command vocabulary:
    - `strand [--config-dir <dir>] weave --pattern <name>`
    - `strand [--config-dir <dir>] pattern explain <name>`
- **DELTA-001.CC2:** `weave` requires exactly one non-blank `--pattern` value and accepts no positional arguments.
- **DELTA-001.CC3:** `weave` reads exactly one JSON value from stdin. Empty stdin, malformed JSON, or trailing non-whitespace after the JSON value fails before the weaver mutation request.
- **DELTA-001.CC4:** The JSON payload may be any JSON value. Object payloads are expected for most patterns, but shape validation belongs to the trusted pattern function.
- **DELTA-001.CC5:** The CLI sends the pattern name and decoded JSON payload to the weaver JSON socket operation allowlist entry for weaving.
- **DELTA-001.CC5a:** `pattern explain <name>` accepts exactly one pattern name positional argument, no stdin payload, and no mutation flags. It sends only the name to the weaver JSON socket read-only pattern explanation operation.
- **DELTA-001.CC6:** On success, `weave` emits one JSON result containing created strand rows and a string ref map following the existing batch primitive shape: `{"created":[...],"refs":{"impl":"abc12"}}`.
- **DELTA-001.CC7:** Missing patterns, pattern failures, malformed pattern return values, batch validation failures, unresolved refs, nonexistent durable targets, cycles, and database errors fail non-zero through the existing structured weaver error path.
- **DELTA-001.CC8:** `weave` has no `--file`, `--edn`, inline payload flag, dry-run flag, or public pattern registration flags in the MVP.
- **DELTA-001.CC9:** On success, `pattern explain <name>` emits JSON with the pattern name, registered function symbol as a string, registered input spec name, and ecosystem-derived caller guidance from the input spec. Prefer existing spec tooling such as JSON Schema generation and example generation over a Skein-specific schema language. The output is guidance for constructing JSON input, not executable Clojure and not a promise that every possible spec predicate has a lossless JSON Schema equivalent.
- **DELTA-001.CC10:** Missing patterns or patterns without an input spec fail non-zero; callers should not have to guess a shape.

## DELTA-001.P3 Design decisions

### DELTA-001.D1 Stdin-only JSON input

- **Decision:** The CLI reads one JSON value from stdin.
- **Rationale:** This preserves the thin JSON control surface and lets agents use pipes/heredocs without growing file/input mode flags.

### DELTA-001.D2 Named pattern invocation only

- **Decision:** The CLI invokes an already-registered pattern by name; it does not define patterns.
- **Rationale:** Pattern definitions are trusted runtime behavior and belong in config/REPL workflows, not the low-privilege CLI.

### DELTA-001.D3 Pattern explanation is read-only caller guidance

- **Decision:** Expose `strand pattern explain <name>` rather than adding pattern listing or mutation commands.
- **Rationale:** Agents need the shape of the payload they should send, while pattern ownership and registration remain trusted runtime concerns. The explanation should lean on established spec/schema tooling rather than a custom Skein schema model.

## DELTA-001.P4 Open questions

- **DELTA-001.Q1:** Decide the exact explanation fields after spiking `clojure.spec.alpha` built-ins plus `metosin/spec-tools` JSON Schema and error serialization for JSON-object-oriented specs.
