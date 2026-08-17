# Alpha Surface delta for unify-spool-classpath

**Document ID:** `DELTA-usc-as-001` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-usc-001`) **Contract:** [../brief.md](../brief.md) **Status:** Merged **Last Updated:** 2026-07-11

## DELTA-usc-as-001.P1 Summary

`SPEC-005` draws the alpha contract line partly in terms of a classpath/opt-in split that this feature removes. Two clauses change: `SPEC-005.C3` (the reference-spool and authoring-helper clause, root spec line 13) is redrawn so reference spools are in-contract as **opt-in** spools with `batteries` the single documented classpath exception, and the `util`/`format` sentence is rewritten because both namespaces leave the `skein.spools.*` family; `SPEC-005.C2`'s enumerated blessed set gains one namespace (`skein.api.spool.alpha`) as `util`'s new home. No other clause changes: `SPEC-005.C4` (userland repo-local spools) and `SPEC-005.C10` (frozen trained vocabulary) are untouched. This delta states only those changes; it does not restate `SPEC-005`.

## DELTA-usc-as-001.P2 Contract changes

- **DELTA-usc-as-001.CC1 — `SPEC-005.C2`: add `skein.api.spool.alpha` to the blessed set.** `PROP-usc-001.C1` promotes `skein.spools.util` out of the spool family into a new blessed authoring-helper namespace under `src/` (`skein.api.spool.alpha`). It joins the `SPEC-005.C2` enumeration of blessed `skein.api.*.alpha` namespaces and follows the same accretion-based within-subnamespace compatibility. The promotion is a **deliberate compat commitment, not a mechanical move**: landing `util` in the accretion-compatible blessed tier freezes its spool-authoring surface — `fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until-deadline!` — as compat-governed API. `SPEC-005.C3` already enumerated exactly this set as the authoring surface, so the commitment names what is already relied on; any post-promotion shape change to those helpers is a compat break, not an internal edit.

- **DELTA-usc-as-001.CC2 — `SPEC-005.C3`: reference spools are in-contract as opt-in spools; `batteries` is the one documented classpath exception.** Remove the premise "Classpath-shipped reference spools are in-contract through their spool docs." The reference spools shipped in the Skein tree — `bobbin`, `carder`, `ephemeral`, `executors/shell`, `guild`, `loom`, `roster`, `selvage`, `text-search`, `workflow` — stay in-contract through their `spools/*.md` docs, but are now loaded **opt-in** (approved `spools.edn` coordinate → `runtime/sync!` → `:spools`-guarded `runtime/use!`), not off the shipped classpath. `batteries` is the **single documented classpath exception**: the non-escalating base strand command surface every world needs at zero config, kept on the source classpath and loaded by an explicit `require`, with its rationale written in `spools/README.md` (`PROP-usc-001.G2`/`.C2`). The clause no longer claims any reference spool ships on the weaver classpath.

- **DELTA-usc-as-001.CC3 — `SPEC-005.C3`: rewrite the `util`/`format` authoring-helper sentence.** The sentence naming `skein.spools.util` and `skein.spools.format` as `skein.spools.*` authoring helpers no longer holds. `skein.spools.format` is **deleted**: its `fill`/`reflow` names already exist as the blessed `skein.api.format.alpha` (`SPEC-005.C2`), and its six spool callers repoint there (`PROP-usc-001.C1`). `skein.spools.util` is **promoted** to blessed `skein.api.spool.alpha` (DELTA-usc-as-001.CC1). After this change no authoring helper lives under `skein.spools.*`; the family is exactly "activatable spools" and nothing else, and no blessed `skein.api.*.alpha` namespace requires a `skein.spools.*` namespace (`PROP-usc-001.G3`/`DW2`).

## DELTA-usc-as-001.P3 Design decisions

### DELTA-usc-as-001.D1 Reference spools stay in-contract, only their load mechanism changes

- **Decision:** Redraw `SPEC-005.C3` around the load mechanism (opt-in vs. the batteries exception), not around tier membership. The reference spools remain in-contract via their `spools/*.md` docs; nothing moves to `SPEC-005.C4` userland or out of contract.
- **Rationale:** The feature unifies _how_ spools load, not _what_ Skein ships as blessed reference material. Conflating the two would silently reclassify shipped reference spools as userland. The only contract-line movement is `util`/`format` leaving `skein.spools.*` (CC1/CC3), which is the tier-inversion fix `PROP-usc-001.C1` requires.
- **Rejected:** Moving the opt-in reference spools into `SPEC-005.C4` (userland). They ship in the Skein tree with blessed docs and are maintained as in-contract examples; `C4` is for repo-local approved spools with their own cadence.

### DELTA-usc-as-001.D2 `batteries` is the one exception, named in the contract index

- **Decision:** `SPEC-005.C3` records `batteries` as the single documented classpath exception, with the rationale itself owned by `spools/README.md`.
- **Rationale:** `SPEC-005` is a contract _index_ — it says where each contract lives, not the full rationale. Naming the exception here and pointing at `spools/README.md` keeps the index honest without duplicating the "very good reason" prose.
- **Rejected:** A source-relative or absolute bootstrap coordinate for batteries; both are rejected in `PROP-usc-001.C2` (`SPEC-004.C41`/`.C42` grounds, see the daemon-runtime delta), so the alpha-surface index has exactly one exception to name.

## DELTA-usc-as-001.P4 Open questions

- **DELTA-usc-as-001.Q1 (resolved at merge):** `util`'s blessed home shipped as `skein.api.spool.alpha` exactly as drafted; no rename was requested at promotion.
