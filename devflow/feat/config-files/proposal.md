# Config files proposal

**Document ID:** `PROP-Cff-001` **Status:** Draft **Approved:** — **Related RFCs:** [Lifecycle authoring history](../../rfcs/2026-07-28-lifecycle-authoring-forms.md); its Proposed text is historical context, not the current lifecycle contract. **Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md) **Brief:** [User decisions](brief.md)

Once approved, this proposal is frozen. Implementation changes belong in the spec deltas, plan, and code.

## PROP-Cff-001.P1 Problem

Simple spool configuration currently requires Clojure even when each entry is a small map plus prose. Codethread's agent aliases and reviewer lenses are examples: Markdown files would be easier to edit and group without moving their domain behaviour into a new configuration language.

A spool also cannot know the complete configuration its consumer composes. The consumer needs to select reusable declarations and set their execution dependencies without mutating the original Vars or teaching each spool about its neighbours.

## PROP-Cff-001.P2 Goals

- **PROP-Cff-001.G1:** Configure the common case through Markdown files with YAML frontmatter and an unchanged Markdown body.
- **PROP-Cff-001.G2:** Give each file an explicit resource boundary: one open callback, one retained successful handle, and paired cleanup.
- **PROP-Cff-001.G3:** Keep reload understandable: drop the old resources, then load the current files. Expose partial success without promising rollback.
- **PROP-Cff-001.G4:** Let consumers own ordering through selection-local `:after`, using the existing lifecycle and module dependency vocabulary.
- **PROP-Cff-001.G5:** Preserve literal interpolation tokens for optional, caller-controlled hydration before domain validation.

## PROP-Cff-001.P3 Non-goals

- **PROP-Cff-001.NG1:** No watcher, background retry, fingerprints, incremental diff, rename tracking, transactional batch, or last-good configuration restoration.
- **PROP-Cff-001.NG2:** No YAML/TOML standalone configuration formats, template expressions, environment lookup, includes, typed substitutions, or embedded code execution.
- **PROP-Cff-001.NG3:** No generic alias, reviewer, registry-ownership, or cross-file reference model. Domain adapters keep their existing validation and registration policy.
- **PROP-Cff-001.NG4:** No numeric priority or per-file dependency graph. Clojure remains the path for complex setup.
- **PROP-Cff-001.NG5:** No migration of Codethread/Harnesses catalogs in this feature, no new CLI, and no weakening of Weaver startup readiness.

## PROP-Cff-001.P4 Proposed scope

- **PROP-Cff-001.S1:** Add the `millstrand.api.config.alpha` authoring family `defconfigfile`, `use-configfile!`, and `defconfigfile!`. Definitions are inert; selection belongs to the consuming module. Define a callable at the exact authored Var name and retain only printable declaration data and qualified callable symbols.
- **PROP-Cff-001.S2:** Declare a selected-workspace-relative directory, filename glob, and recursive grouping policy. Match and process files deterministically. No file read or callback happens while defining or selecting a declaration.
- **PROP-Cff-001.S3:** Invoke the open callback with the explicit-runtime lifecycle context and one parsed document. Retain its returned handle only after normal return. Close successful handles on reload, declaration/module removal, and runtime shutdown, including cleanup after failed startup.
- **PROP-Cff-001.S4:** Every config-file collection reached by lifecycle execution rebuilds on refresh, even when its declaration and files are unchanged. Earlier lifecycle failures can leave later collections not-attempted. After successful teardown, file failures do not prevent attempts of other files in that collection. Report failures and retain successful handles. A failed close retains its handle and prevents that collection from reopening; there is no automatic retry loop.
- **PROP-Cff-001.S5:** Preserve `{{varname}}` tokens during parsing. A pure `config/hydrate` helper performs one literal recursive replacement pass using caller-supplied strings, leaves unresolved tokens intact, and leaves provenance unchanged. The callback performs domain validation after any hydration. Unknown frontmatter fields are tolerated and may be ignored by the adapter; invalid recognised values fail.
- **PROP-Cff-001.S6:** Add a leading selection-options map closed to `:after` to `use-seed!`, `use-resource!`, `use-reconcile!`, and `use-configfile!`. An explicit set replaces the declaration's default; an empty set clears it. No declaration Var or other consumer is changed. Effect ordering remains module-local; `runtime/module! :after` orders modules.
- **PROP-Cff-001.S7:** Make the limits visible: callbacks own partial acquisition before throwing, reload may expose missing or partial registrations, and ordering is not a promise that every referenced entry exists. Preserve current startup failure rules; a partial explicit refresh remains inspectable in the running runtime.

## PROP-Cff-001.P5 Examples

### PROP-Cff-001.E1 Define a reusable file collection

The new API below is proposed. `register-seat!` and `close-seat!` are domain-adapter functions: the former validates the supplied document and returns the registration handle; the latter removes that registration through the domain API.

```clojure
(config/defconfigfile agent-harness
  "Register one agent seat per Markdown file."
  {:location {:path "agents/seats"
              :filename "*.md"
              :recursive? true}
   :format :markdown
   :close 'acme.seats/close-seat!}
  (fn [{:keys [runtime]} configfile]
    (let [seat (config/hydrate {:cwd "/work/project"} configfile)]
      (register-seat! runtime seat))))
```

An input file can contain:

```markdown
---
parent: pi
model: openai-codex/gpt-5.6-terra
cwd: "{{cwd}}"
---

Review changes in {{cwd}}. Keep findings actionable.
```

The adapter decides whether the filename names the alias, whether the body becomes documentation or instructions, and which frontmatter fields it recognises. Those are not loader conventions.

### PROP-Cff-001.E2 Compose independent spools

```clojure
(config/use-configfile! seats/agent-harness)
(config/use-configfile! {:after #{:agent-harness}}
                       reviews/reviewer-files)

(lifecycle/use-resource! {:after #{:reviewer-files}}
                         execution/review-service)
```

These selections belong to one consumer module. The `:after` entries name effect IDs, not namespace aliases. A different consumer may choose a different order without redefining any library declaration. A consumer composing separate modules uses module `:after` instead.

### PROP-Cff-001.E3 Reload with a bad file

A collection previously opened three seats. Refresh closes all three, then attempts the current files. One file has malformed frontmatter; two open successfully. The result reports the failed path and the two successful opens, and the runtime retains only those two returned handles. The old version of the bad seat is not restored. After fixing the file, another explicit refresh closes those two handles and rebuilds the collection again.

## PROP-Cff-001.P6 Open questions

No direction-level question remains from the discussion. The draft submits these first-version defaults for human sign-off: config resources have module lifetime; a missing or unreadable root is an error; an existing empty root means no resources; interpolation accepts keyword-to-string bindings; and startup still refuses a degraded configuration. Declaration and file-level details are staged in the spec deltas, not yet shipped contracts.
