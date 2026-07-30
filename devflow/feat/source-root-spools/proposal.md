# Batteries as an ordinary spool via `:skein/source-root` coordinates Proposal

**Document ID:** `PROP-Srs-001`
**Last Updated:** 2026-07-23
**Related RFCs:** None (design settled with the user in the 2026-07-23 session; decision record is [brief.md](./brief.md) on card `u4a24`, revised after cross-vendor review runs zlo0t/b8o2k)
**Related root specs:** [`daemon-runtime.md`](../../specs/daemon-runtime.md) (SPEC-004.C42, C44/C44c/C44f, C48@2, C49@2, C50/C50a, C94a), [`repl-api.md`](../../specs/repl-api.md) (SPEC-003.C63), [`cli.md`](../../specs/cli.md) (mill bootstrap surface), [`alpha-surface.md`](../../specs/alpha-surface.md) (spool index tier)
**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PROP-Dwr-001.P1` or `PROP-Dwr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## PROP-Srs-001.P1 Problem

`skein.spools.batteries` is the one spool that does not behave like a spool. SPEC-004.C50a carves it out as a classpath exception: its source rides `deps.edn :paths`, and the canonical `.skein/init.clj` plus the template `mill init` writes activate it through a bare `require` and a guardless `module!` instead of an approved `spools.edn` coordinate. The exception exists because the approved coordinate kinds are a closed set (SPEC-004.C42/C48@2/C49@2/C94a) of `:local/root` — resolved against the config dir, portable only when that dir happens to live inside the skein checkout — and `:git`, which pins a sha divorced from the installed weaver.

The costs are structural, not cosmetic. Batteries is invisible in `spools.edn` and guarded by nothing; a workspace can mask or replace it by editing trusted startup forms (SPEC-003.C63), but that opt-out is incoherent — source approval and activation are never represented as a `spools.edn` consent edge, and the default template opts every fresh world in silently. The refresh machinery carries a batteries-shaped exception through its classpath classification. Meanwhile this repo's own `.skein/spools.edn` — and sibling repos' worlds — lean on `{:local/root "../spools/<name>"}` entries that encode the fragile layout assumption the exception was invented to avoid writing down.

## PROP-Srs-001.P2 Goals

- **PROP-Srs-001.G1:** Every activated spool, batteries included, is approved and loaded through the one `spools.edn` → `:spools`-guarded module path; no batteries-specific classpath exception remains (direct trusted REPL/test requires stay legitimate, and the generic classpath-ownership machinery serving SPEC-004.C50 stays).
- **PROP-Srs-001.G2:** A machine-portable, committable coordinate kind exists for spools shipped with the running skein source, resolved against the mill-resolved checkout SPEC-004.C50 already binds — never against cwd, config dir, or request state — and usable by any workspace on any machine.
- **PROP-Srs-001.G3:** Fresh worlds still get the base strand command surface: `mill init` seeds the batteries coordinate and module declaration by default, and removing the entry is a supported, visible opt-out.
- **PROP-Srs-001.G4:** No `..`-shaped `:local/root` entries naming shipped skein spools remain in this repo's or sibling repos' committed `.skein` worlds; the migration itself dogfoods the new kind. `..` roots naming anything else are surfaced with a recommendation, never silently converted.
- **PROP-Srs-001.G5:** Rewriting a live world's coordinate text to the new kind is not, by itself, a non-additive refusal when the canonical resolved root is unchanged. The independent SPEC-004.C44c changed-source and C44f Maven-baseline guards, and pending-generation behavior, are preserved intact and pinned by tests.

## PROP-Srs-001.P3 Non-goals

- **PROP-Srs-001.NG1:** No change to `:git` or genuine workspace-local `:local/root` semantics (e.g. `skein.macros/macros {:local/root "spools/macros"}` stays as-is).
- **PROP-Srs-001.NG2:** No preservation of the C50a "zero-config base surface" guarantee: a hand-rolled `{:spools {}}` world without the seeded entry deliberately has no batteries ops (accepted trade under TEN-000@1; no migration shim).
- **PROP-Srs-001.NG3:** No general "load arbitrary source paths" escape hatch: the new kind resolves only beneath the checkout's `spools/` directory, enforced by canonical containment after symlink resolution.
- **PROP-Srs-001.NG4:** No packaging/distribution change for externally-published spools (devflow, kanban, agent-run stay git-pinned), and no weakening of C44c/C44f non-additive classes.

## PROP-Srs-001.P4 Proposed scope

- **PROP-Srs-001.S1:** A third approved coordinate kind, `{:skein/source-root "spools/<name>"}`, resolved at sync time against the running weaver's skein source checkout via a promoted, specified locator authority (the SPEC-004.C50 mill-resolved checkout); relative paths only, canonical containment beneath `<checkout>/spools`, loud failure when the checkout is unavailable or the path escapes (TEN-003).
- **PROP-Srs-001.S2:** Batteries becomes an ordinary approved spool: a seeded `spools.edn` coordinate plus a `:spools`-guarded module declaration; its source leaves the weaver's base classpath (retained on the test alias and fmt/lint paths).
- **PROP-Srs-001.S3:** `mill init` seeds fresh worlds with the batteries coordinate and guarded module declaration, replacing the classpath `require` template.
- **PROP-Srs-001.S4:** Sync-diff classification treats coordinate kind/text as identity-neutral when the canonical resolved root is unchanged — preserving and testing today's root-path comparison rather than adding an exemption — with C44c/C44f guards and a batteries cutover (launch-classpath generation → synced provider wins; only a fresh generation removes classpath ownership) covered by tests.
- **PROP-Srs-001.S5:** Root specs and docs are rewritten to the new model: SPEC-004.C50a replaced; C42/C48@2/C49@2 admit the kind and distinguish non-fetching shipped-source resolution from git acquisition; C44's outcome kind/coordinate fields carry it; C94a admits it while C94a.1 forbids it inside spool-root `deps.edn`; SPEC-003.C63 prose moves to the consent-edge model; spools/README's classpath-exception section is replaced with the shipped-spool coordinate story; the consumer sweep in brief.md Scope (smoke fixtures, config/spools tests, module_adapters, batteries READMEs/docstring/api.md, chime/cron wording, integration test) lands with it.
- **PROP-Srs-001.S6:** This repo's four `../spools/*` entries and all sibling-repo `..` local roots naming shipped skein spools migrate to the new kind, landed to their respective mains.

## PROP-Srs-001.P5 Open questions

- **PROP-Srs-001.Q1:** None — design settled with the user; see brief.md for the decided trade-offs and the reviewed consumer sweep.
