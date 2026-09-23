# Config files brief

**Feature:** `config-files` **Card:** `q46b1` **Workflow run:** `config-files` **Captured:** 2026-09-23 **Scope of this delivery:** Design documents only; implementation awaits proposal sign-off.

## User request

Expose a filesystem-backed authoring form for simple spool configuration. The motivating examples are Codethread's agent aliases and reviewer lenses, currently authored as Clojure maps and forms. Keep ordinary Clojure available for complex composition.

The user asked to take the agreed direction through Devflow and prepare the required documents. This is not authorization to implement the runtime feature, migrate the external catalogs, or approve the resulting proposal on the user's behalf.

## Agreed direction

- Supply `defconfigfile`, `use-configfile!`, and `defconfigfile!` with the existing inert/selection/bang distinction.
- Declare a directory, filename pattern, and whether nested grouping directories are included. Resolve files from the selected workspace, not the caller's cwd.
- Support Markdown with YAML frontmatter and standalone YAML mappings. Markdown adds its unchanged body as `:doc` to the parsed configuration; YAML preserves authored field names, with `doc` recommended for consistency. Both formats share a document envelope separating path provenance from configuration fields.
- Use established Clojure/Java libraries wherever possible, sharing the YAML parser between frontmatter and standalone YAML. Do not write replacement parsers for established formats.
- Invoke a callback once per file; the coordinator owns iteration and retains each successful resource handle for paired cleanup.
- Reload by removing old resources and loading new files. Do not diff names, detect renames, restore an old snapshot, or add a transaction across the batch.
- Continue after independent file failures, retain successful handles, and expose the failures. A callback remains responsible for effects created before it throws without returning a handle.
- A failed close must remain visible and must not be treated as successful removal. Do not reopen the affected collection until its retained handles close.
- Load on startup and explicit refresh. No filesystem watcher, automatic retry, or content-fingerprint cache.
- Preserve `{{varname}}` tokens in Markdown frontmatter/body and YAML string values. `config/hydrate` is an opt-in literal recursive replacement helper using caller-supplied values, with no environment lookup or evaluation.
- Hydration leaves unresolved tokens intact and performs one replacement pass. Domain validation follows user-controlled hydration; the generic loader checks document structure rather than interpreting a spool's domain schema.
- Unknown configuration fields may be ignored by the domain adapter. Invalid recognised values still fail.
- Use dependency ordering, not numeric priority. A consumer may pass `{:after #{:agent-harness}}` to a typed lifecycle selection. Explicit `:after` replaces declaration defaults; an empty set clears them. The declaration Var is unchanged.
- Generalise that selection override consistently to seed, resource, reconcile, and config-file families. Existing module `:after` remains the cross-module composition boundary.

## Existing behaviour inspected

Codethread's shared alias catalog already owns registrations through resource open/close callbacks. Harnesses' reviewer declarations store symbolic seat names and resolve them when listing or starting reviews, not while the declaration is defined. Quoting produces data; the consumer implementation decides when lookup occurs.

Millstrand currently preserves healthy unchanged resource declarations. Its lifecycle selection grammar accepts only declaration Vars, not options. File-backed rebuilds and selection-local dependency overrides therefore require explicit runtime and authoring changes rather than a wrapper around unchanged behaviour.

## Details submitted for approval

The proposal and deltas make the small remaining defaults explicit: module-lifetime config resources, a missing directory as an error, an existing empty directory as an empty configuration, keyword-keyed string interpolation values, and preservation of current startup readiness rules. Best effort means independent files are attempted and refresh reports partial results; it does not promise a ready Weaver after failed startup configuration.

No root spec is changed by this documentation packet. No RFC is needed for an unresolved architectural alternative; the discussion selected the teardown/rebuild and consumer-owned ordering direction.
