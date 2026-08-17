# daemon-runtime delta for 8wwjk-leaf-hook-class

**Document ID:** `DELTA-Lhc-002` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-22

## DELTA-Lhc-002.P1 Summary

Registration requires explicit leaf classes on every route into the registry, the payload-hook gate and deadline resolution resolve from the invoked leaf, and unresolvable verbs fail loudly before hooks. Breaking under TEN-000@1.

## DELTA-Lhc-002.P2 Contract changes

- **DELTA-Lhc-002.CC1 (amends SPEC-004.C63a):** The op metadata map loses its `:hook-class`/`:deadline-class` defaults, and the authoring rule is single- source: an op that declares an `:arg-spec` authors classes **only** in the arg-spec's leaf nodes (a flat arg-spec root is its leaf — DELTA-Lhc-001.CC2), and registration opts carrying either class key alongside an arg-spec fail loudly. A **raw-envelope** op (no arg-spec) **must** declare both class keys in registration opts — the only shape that authors them there. Violations fail loudly at `register-op!`/`replace-op!` naming the op and, for node-level violations, the canonical DELTA-Lhc-001.CC3 error context.
- **DELTA-Lhc-002.CC2 (amends SPEC-004.C63d, closes the publication seam):** One **canonical entry validator** — owned by the op-entry module, the same structural/class/recursion/returns checks `register-op!` runs — validates entries on **every** route before they become effective: direct registration, and module publication at generation reconcile (glossary-ref checks per DELTA-Lhc-001.CC5). The core registry's storage-side entry spec stays thin (storage is not the validation tier); publication invokes the canonical validator from the publishing side. Hand-assembled entry constructors — batteries' contribution path, guild, text-search, workspace `workflows.clj`, the workspace `defop` macro, and test fixtures — conform by construction; none may re-create a class default.
- **DELTA-Lhc-002.CC3 (amends SPEC-004.C80):** `:payload/received` hooks gate socket `invoke` by the **invoked leaf's** `:hook-class`: the gate walks the envelope argv tokens through the op's arg-spec to the leaf before running hooks. `:read` leaves skip payload hooks; `:mutating` leaves run them. A missing or unknown verb token at any depth fails loudly **before any hook runs** — the same pre-hook policy as an unknown op name — carrying the op, the path walked, and the available children. Flat and raw-envelope ops gate on their own (mandatory) class; nothing defaults.
- **DELTA-Lhc-002.CC4 (amends SPEC-004.C26b and C63b's deadline sentence):** The single-result deadline default comes from the **invoked leaf's** `:deadline-class`; envelope `timeout` still overrides. C63b's "such ops must register `:deadline-class :unbounded`" reads at leaf granularity: the **blocking leaf** declares `:unbounded`, and sibling verbs keep their own classes. Stream-class ops remain unbounded, consistent with the leaf-level `:unbounded` requirement (DELTA-Lhc-001.CC2).
- **DELTA-Lhc-002.CC5 (amends SPEC-004.C63b):** The dispatch-owned `:operation` label amendment derives from the parsed `:subcommand` **path vector** joined with spaces. The "parsed nested `:action`" special-case is retired: grammars that encoded verbs as positionals migrate to real nested subcommands.
- **DELTA-Lhc-002.CC6 (amends SPEC-004.C108):** The trailing `--help`/`-h` flag rewrite keeps its C108 trigger (final token, no other flags, no payloads) and rewrites `strand <op> <tokens...> --help` to `help <op> <tokens...>` for all op shapes, still resolving before hook gating as a read-class registry projection. The rewritten help resolves `<tokens...>` as a node path per DELTA-Lhc-001.CC6 — interior and leaf nodes are both valid targets — and a token that names no child fails loudly in help with the canonical error context (so `spool add <url> --help` fails naming `add`'s children as none, never silently parsing). The retired-sugar redirect and its declared-subcommand exemption evaluate against the first-level child names as today; reserved-name validation at every depth (DELTA-Lhc-001.CC1) keeps `help` undeclarable anywhere.

## DELTA-Lhc-002.P3 Design decisions

### DELTA-Lhc-002.D1 Pre-hook loud failure instead of a fallback class

- **Decision:** No class exists for "the op as a whole"; unresolvable invocations fail loudly before payload hooks.
- **Rationale:** Nothing invocable resolves to a non-leaf (a verb-less invocation of a subcommand op is already a loud parse error), and SPEC-004.C80 already fails unknown ops pre-hook — this extends that policy down the tree rather than inventing a conservative gating class that would never gate a successful invocation.
- **Rejected:** Gating unresolvable invocations with a derived superclass (dead path — the invocation fails at parse regardless; running mutation hooks for a doomed request only adds noise).

## DELTA-Lhc-002.P4 Open questions

- None.
