# Devflow Philosophy

The tool's runtime model is closer to Emacs than to a stateless command-line utility.

Start the weaver as the local application core. It owns the live database connection and runtime state for that weaver lifetime. Users who want customized runtime behavior should load trusted Clojure config at weaver startup, reload their own files while working, or experiment through the REPL.

The CLI is a convenience surface for common, scriptable operations. It should stay small, predictable, and suitable for lower-privilege workers that should use known structures without receiving broad REPL access. Do not grow the CLI into a parallel configuration or extension system when the weaver config and REPL already provide that role.

Design implications:

- Runtime customization belongs in trusted startup files and REPL workflows.
- CLI commands should cover common strand operations and safe consumption of existing weaver state.
- Prefer weaver-owned in-memory runtime state over ad hoc per-client state when multiple clients need to share behavior during one weaver lifetime.
- Do not persist runtime behavior unless the feature explicitly calls for durable storage.
- Keep user-authored behavior data-first where possible, and fail loudly when a CLI worker references weaver state that has not been loaded.

## No harness is home

Millstrand assumes several agent harnesses and privileges none. A harness is a replaceable seat; coordination lives above it, in workspace config and spools, so switching provider changes a config entry, not the process.

Design implications:

- Core and spools never depend on a specific provider's features or file formats; harness names are data in workspace config.
- Anything a harness must know arrives as strand data — instructions, notes, gates — not as harness-specific prompt files.
- No feature may require a particular harness; the surface stays drivable by any JSON-capable client.

## Prose guides, code decides

Instruction prose cannot be tested or debugged, so it must not carry critical behavior. Load-bearing behavior is code — ops with declared arg-specs, workflows compiled to strands, spools with test suites — and prose is guidance around that code.

Design implications:

- A convention that matters ships as a function or declared data with tests, not as a paragraph an agent may skip.
- Step instructions are data on the strands that own them, versioned with the workflow.
- When prose and registered behavior disagree, one of them is the bug; fix it at the source rather than patching both.

## The work record is not the source of truth

The code and its documentation are the durable record of a project. Millstrand's strands are a working memory beside them: they let a returning human or a cold agent pick up where work stopped. Some trackers (beads, for example) treat their own database as the project's truth; Millstrand does not.

Millstrand therefore aims at resumability, never replayability. Resuming means reading the handover and moving forward from the current state of the code. Replaying, in the sense of rolling back a change and re-deriving it from recorded events, assumes an event log that agent work cannot honestly provide: an agent hands over a finished change, and while file edits could in principle be traced, one shell command breaks the chain. Recording a history that pretends otherwise would be a false promise.

Design implications:

- Handover quality matters more than history depth: notes exist for the next reader, not for a permanent record.
- Old strands and archived attributes are memory and teaching material, not authority. When the record disagrees with the code, the code wins.
- Core owes no replay, audit, or event-sourcing features. Anything that reads history that way is userland, and should say so.
