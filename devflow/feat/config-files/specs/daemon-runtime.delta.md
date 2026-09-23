# Weaver Runtime delta for config files

**Document ID:** `DELTA-Cff-002` **Root spec:** [Weaver Runtime](../../../specs/daemon-runtime.md) **Feature:** [Proposal](../proposal.md) **Status:** Draft **Last Updated:** 2026-09-23

## DELTA-Cff-002.P1 Summary

Extend the lifecycle coordinator with a module-owned collection of file-backed resources. Declaration validation and contribution publication keep their existing boundaries. Executing a selected config-file declaration reads workspace data and manages successful per-file handles after publication. This is an explicit exception to preservation of healthy unchanged lifecycle declarations, not a new registry publication model.

## DELTA-Cff-002.P2 Contract changes

### DELTA-Cff-002.CC1 Workspace discovery and format parsing

Resolve `:location :path` relative to the explicitly selected workspace, never the process cwd, Git root, or library checkout. The authored path is relative and may not lexically escape the workspace. This resolution rule is not a security sandbox. `:filename` is a basename glob, not another directory traversal expression. With `:recursive? false`, match immediate regular-file children; with true, include nested grouping directories. Do not follow directory symlinks during traversal; a file symlink resolving to a regular file may be read. Sort matched relative paths lexically using `/` separators before opening any file.

A missing root, unreadable root, non-directory root, or failed traversal is a collection-level error, never an empty default. A readable existing root with no matches is an empty configuration. Read matched files as UTF-8 and close directory streams/readers within discovery/loading; neither callbacks nor retained resource state receive lazy filesystem iterators or open file descriptors.

For `:markdown`, a frontmatter block starts with a first-line `---` delimiter and ends with a subsequent standalone `---` line. Without an opening delimiter, frontmatter is `{}` and the entire file is body. An opening delimiter without a closing delimiter is malformed. An empty block is `{}`; a present non-empty block must decode to one YAML mapping. Preserve the body text after the closing delimiter line, including its remaining whitespace and line endings, and place it in `:config :doc` as specified by DELTA-Cff-001.CC3.

For `:yaml`, parse the entire file as one YAML mapping and use it directly as `:config`. An empty file, scalar, sequence, or multiple-document stream is an error; an explicit `{}` is an empty configuration. YAML block strings use normal YAML parsing semantics rather than Markdown's raw-body preservation. No prose field is added or required; `doc` is only the recommended naming convention.

Both paths share the same YAML parser and data mapping. Reject duplicate mapping keys, non-string mapping keys, unsupported tagged values, and structures that cannot become DELTA-Cff-001.CC3's data shape. YAML is parsed as data, never as executable Clojure or custom object construction. Tokens are ordinary text; authors quote values where YAML syntax requires it.

### DELTA-Cff-002.CC2 Startup, refresh, and passive operations

Open selected config-file collections reached by normal module lifecycle execution. On refresh, every reached collection tears down and rebuilds even if source and declarations are unchanged; an earlier lifecycle failure can leave a later collection not-attempted. Full refresh considers all selected owners. Isolated targeted refresh uses the existing owner/dependant selection, leaving unrelated owners alone. Managed pooled refresh retains its host-wide coordination and targeted-refresh rejection; each member reads its own selected workspace and retains its own handles.

Definition, selection, ordinary `require`, code-only reload, `runtime/status`, dry-run `runtime/plan`, and fresh-generation probes do not discover config files or invoke open/close callbacks. Plans show a collection-level rebuild intention, not a claim that files were parsed or domain-validated. Image activation replays source declarations but normal execution still reads the selected workspace's current files. It never reuses a file snapshot from another module collection, runtime, or pool member.

Dependency-basis and declaration-validation refusals still precede publication and leave live resources unchanged. Config-file contents are read only after those checks and publication. A failed probe or an effect-free plan does not predict whether later file loading will succeed.

### DELTA-Cff-002.CC3 Remove old resources, then load current files

For each participating module, close its scheduled old config-file collections in reverse effect dependency order before opening replacements in forward dependency order. Within a collection, close retained handles in reverse successful-open order, then discover the current files and call open sequentially in sorted path order. A change to filename, directory, recursion, callback, or selection order uses the same teardown/rebuild boundary. There is no filename identity matching, rename tracking, fingerprint comparison, or preservation of unchanged file handles.

Retain one handle record for every normal open return, including nil. Each record keeps the old resolved close callable and original provenance until close succeeds. Do not re-resolve a replacement close implementation to release an older handle. A removed declaration or module cleans up from retained state without rereading removed source or config files. Normal runtime shutdown and failed-startup cleanup attempt all retained config-file handles through that same resource ownership boundary.

The guarantee is scoped to the effects being transitioned. It does not restart otherwise preserved ordinary resources merely because a config-file dependency was rebuilt, and it does not suspend concurrent readers, running agents, event delivery, or arbitrary external activity. Callers can observe the reload gap and a partial new configuration. Resources needing uninterrupted availability, captured-object rebinding, or a larger atomic cluster belong in ordinary Clojure lifecycle code.

### DELTA-Cff-002.CC4 Best-effort loading and retained cleanup

After teardown succeeds, a file read, parse, hydration, domain-validation, or open failure records that file's error and processing continues with the next file in the collection. Successful opens remain live and are retained even if another file fails. A missing or unreadable root reports a collection error after the old resources have closed; the old configuration is not restored. There is no preflight parse-and-rollback promise.

A callback that throws has not supplied a resource handle. It owns cleanup of any partial side effects before throwing. The loader neither invents a handle nor runs close for failed opens. Duplicate domain names and cross-owner registration collisions are the domain adapter's responsibility, not a new loader arbitration mechanism.

On teardown, attempt every independent retained handle in the collection even if one close fails. Forget only successful closes. Keep each failed handle with its original close callable, report it, and do not discover/open a replacement collection while any old handle remains. Respect existing reverse-dependency cleanup blocking: do not close a prerequisite that a failed dependent may still use. A later explicit refresh or removal attempt retries retained cleanup; there is no timer or retry loop, and successfully closed handles are not closed again.

Best effort is within the file collection. Once all files have been attempted, a degraded collection participates in the existing lifecycle module failure policy: remaining application steps in that module may be reported not-attempted. This feature adds no success/availability meaning to `:after` and no cross-module lookup of domain references. Ordering alone cannot establish that an alias exists.

### DELTA-Cff-002.CC5 Honest status and startup readiness

Refresh and status report config-file outcomes under their existing module/effect identity. A collection outcome is closed to required `:kind :configfile`, `:status`, and `:files`, plus optional `:root`, `:phase`, and `:error`. `:files` is a vector of closed maps requiring `:path`, `:phase`, and `:status`, with optional `:error`. Paths are original relative file paths; `:root`, when present, is the resolved root. Phases and statuses use the existing lifecycle vocabularies. Dry-run intentions use `:planned` and an empty file vector, without implying discovery. Root/traversal failures use the collection-level error rather than inventing a file.

Each error is closed to required string `:class`, nullable string `:message`, and optional `:cause` containing only that cause's class and nullable message. Do not copy arbitrary domain `ex-data`, throwable objects, or stack objects into these outcomes. Retained handles, document maps, and interpolation bindings stay outside the projection. Exception message text is preserved as authored; this is not a promise to redact secrets embedded by a callback in its message. Public specs validate these closed collection, file, and error projections when constructing refresh/status output, rather than relying only on the permissive enclosing module map.

A collection with any file or cleanup failure is degraded, and a running runtime's explicit refresh reports the existing partial aggregate outcome. Successful file handles must remain retained even when that aggregate is degraded. A completed rebuild is reported as work performed, not unchanged merely because declaration data was identical.

Startup keeps the existing readiness rule: an initial partial/degraded module refresh fails startup and publishes no ready runtime metadata. Best-effort file attempts provide diagnostics but do not admit a partially configured Weaver. Failed startup must close successful file handles before discarding its runtime. Ordinary shutdown remains best effort and reports close failures; no cleanup survival across process death is promised.

## DELTA-Cff-002.P3 Design decisions

### DELTA-Cff-002.D1 Rebuild without snapshots

Reading on explicit execution and rebuilding all resources keeps mutable filesystem input out of printable declarations and avoids a second diff engine. The cost is deliberate reload churn and a visible availability gap. A malformed replacement file can remove a previously working value.

### DELTA-Cff-002.D2 Reuse coordinator ownership

The lifecycle coordinator owns dependency validation, per-runtime retention, teardown, and outcome projection. File-backed resources extend that boundary; they do not add generic effect callbacks to the registry kernel or change owner-complete contribution publication. A small internal collection handle is bookkeeping for cleanup, not a durable desired-state store.

### DELTA-Cff-002.D3 Keep startup and refresh distinct

TEN-003 requires errors to stay visible. Explicit refresh already permits partial outcomes in an existing runtime; startup requires a fully applied initial refresh. This feature does not use best effort as a reason to silently weaken readiness or add a configurable error-policy matrix.

## DELTA-Cff-002.P4 Open questions

None blocking proposal review. Parser choice and the exact internal retention representation belong to implementation; use established Clojure/Java libraries wherever possible rather than writing replacement YAML or Markdown parsers. The chosen libraries must satisfy these contracts without adding fallback behaviour.
