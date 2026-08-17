# Rename to Skein: textile-metaphor vocabulary and namespace move

**Document ID:** `RFC-006` **Status:** Accepted **Date:** 2026-06-26 **Related:** [PHILOSOPHY](../PHILOSOPHY.md), [TENETS](../TENETS.md), [SPEC-001 Strand Model](../specs/strand-model.md), [SPEC-002 CLI](../specs/cli.md), [SPEC-003 REPL API](../specs/repl-api.md), [SPEC-004 Weaver Runtime](../specs/daemon-runtime.md), [PRD-001 Runtime Transformations](../prd/runtime-transformations.md)

## RFC-006.P1 Problem

The project shipped as a "todo" graph but has grown (per [PRD-001](../prd/runtime-transformations.md)) into a general attributed-DAG core where the only durable facts are records, their open JSON attributes, and typed edges; everything else is runtime transformation. The naming no longer matches that reality, and it is inconsistent:

- the public Go CLI binary is `todo`;
- internal Clojure namespaces are `todo.*` (`todo.daemon.api`, `todo.repl`, `src/todo/`);
- blessed runtime libraries are `atom.*.alpha` (`atom.libs.alpha`, `atom.graph.alpha`, `atom.views.alpha`);
- the stored unit is a `task`; storage is `tasks` / `task_edges` in `tasks.sqlite`;
- runtime worlds live under `atom` config/state/data dirs.

"todo" misdescribes a neutral primitive that users should stamp meaning onto with their own attributes, and the split between `todo.*` and `atom.*` is incoherent. We should settle one identity and vocabulary now, before more libraries and specs harden these names.

## RFC-006.P2 Goals

- **RFC-006.G1:** One coherent product, CLI, and namespace identity.
- **RFC-006.G2:** A maximally neutral stored primitive so userland supplies domain meaning via attributes.
- **RFC-006.G3:** Vocabulary that reinforces the pure-core / runtime-transformation split: durable facts vs derived structure.
- **RFC-006.G4:** A product brand that is distinct and searchable in the AI-agent space, not a head-on collision with an incumbent.
- **RFC-006.G5:** Drop legacy names outright per [TEN-000@1](../TENETS.md) without compatibility shims or data migration.

## RFC-006.P3 Non-goals

- **RFC-006.NG1:** No change to storage engine or contract semantics beyond renames and the deliberate lifecycle/retention simplification captured by the follow-up proposal; SQLite persistence, the acyclic invariant ([TEN-005](../TENETS.md)), and open attributes keep their current meaning.
- **RFC-006.NG2:** No new behavior; this is a rename, not a query/graph/view feature.
- **RFC-006.NG3:** Does not add new CLI verbs; edge creation stays on the existing `--edge` flag.
- **RFC-006.NG4:** Does not touch remote access, auth, sandboxing, or multi-user concerns.

## RFC-006.P4 Options

Naming explored three axes: the product brand, the stored-unit word, and the daemon word. Earlier candidates failed on real, checked collisions.

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-006.O1 | Keep status-quo mixed naming (`todo.*` code, `atom.*` libs, `task` unit) | No work | Incoherent; "todo" is inaccurate; bakes confusion into more specs/libs |
| RFC-006.O2 | Unit/product `atom(s)`, daemon `reactor` | Neutral primitive; coherent chemistry vocab | `atom` collides head-on with `clojure.core/atom`; bare-symbol shadowing is a constant code-readability tax |
| RFC-006.O3 | Product `Strands`, unit `strand`, daemon `weaver` | Organic; strands connect to strands; clean Clojure namespace | **Product** collides with AWS **Strands Agents** (dominant open-source agent SDK, same domain), defeating searchability (G4) |
| RFC-006.O4 | Product `Weft` | Coherent textile word | Saturated in-domain: multiple AI-agent CLIs/task tools already named Weft (weftcli, letsweft, others) |
| RFC-006.O5 | **Product `Skein`, unit `strand`, daemon `weaver`, namespace root `skein.*`** | Distinct code/spec identity; `strand`/`skein`/`weaver` each carry one role; no Clojure clash; "skein" also means an interconnected web — i.e. the graph itself | Bare word has out-of-domain uses and later local checks found likely web/GitHub handle collisions; public release-home selection should be separate from this code/spec rename |

## RFC-006.P5 Recommendation

- **RFC-006.REC1:** Adopt **RFC-006.O5**. The product is **Skein**; the Clojure namespace root is `skein.*` (namespace root tracks product identity, as the repo already does with `todo.*`). Artifact/package coordinates are publishing decisions and are not part of this rename scope. The stored unit is a **strand**, and the public CLI binary is `strand`. The long-lived daemon is the **weaver** — the engine that works loose strands into a whole, matching PRD-001's "almost everything is runtime transformation over durable facts" thesis. Each word has exactly one role, avoiding the near-duplicate confusion of an `atom`/`atoms` style split. Keep the metaphor at the semantic surface; the schema stays literal so agents still grep durable facts ([TEN-001](../TENETS.md), [TEN-003](../TENETS.md)).

- **RFC-006.REC2:** Canonical vocabulary mapping:

    | Concept | New name | Was |
    | --- | --- | --- |
    | Product / brand | **Skein** | atom / todo |
    | Clojure namespace root / `src/` root / world dirs | **`skein`** | `todo` / `atom` |
    | Long-lived daemon / runtime engine | **weaver** | daemon |
    | Public CLI binary | **`strand`** | `todo` |
    | The durable stored unit (a noun) | **strand** | task |
    | Typed, directed edge | **edge** (literal; created via `--edge`) | edge |

The connected-subgraph concept previously sketched as "molecule" is dropped; do not force a metaphor for derived structure. Connected subgraphs remain ordinary results of `skein.graph.alpha`, unnamed at the contract level. Edge types (`depends-on`, `related-to`, `parent-of`, `supersedes`) are unchanged.

- **RFC-006.REC3:** Rationale for `skein` over `strand` as the namespace root: the top-level namespace is the published artifact identity, so it tracks the product (Skein), not a domain entity. `skein.graph.alpha` reads as "Skein's graph library"; `strand.graph.alpha` would read as a library merely about strands. The unit word still appears everywhere it matters — the `strand` binary, the `strands` table, and function names like `add-strand!` — without owning the namespace root. This also keeps the contested `strand` token (AWS adjacency) out of the published artifact name.

## RFC-006.P6 Consequences

- **RFC-006.C1:** CLI ([SPEC-002](../specs/cli.md)): Go binary `todo` → `strand`; `daemon start|repl|stop|status` subcommands → `weaver start|repl|stop|status` under the `strand` binary, including `weaver repl --stdin`. Edge creation stays on `--edge edge-type:to-id`, e.g. `strand add --edge depends-on:<id>`; no new `bond` verb.
- **RFC-006.C2:** Internal Clojure namespaces ([SPEC-003](../specs/repl-api.md), [SPEC-004](../specs/daemon-runtime.md)): `todo.*` → `skein.*` (`todo.daemon.api` → `skein.weaver.api`, `todo.repl` → `skein.repl`, `src/todo/` → `src/skein/`).
- **RFC-006.C3:** Blessed runtime libraries (SPEC-004.P9/P10, PRD-001 examples): `atom.libs.alpha` / `atom.graph.alpha` / `atom.views.alpha` → `skein.libs.alpha` / `skein.graph.alpha` / `skein.views.alpha`.
- **RFC-006.C4:** Storage ([SPEC-001](../specs/strand-model.md)): table `tasks` → `strands`; `task_edges` stays the edge table (rename to `strand_edges`); default db `tasks.sqlite` → `skein.sqlite`. Replace task-oriented `status` / `final_at` with core `active` / `inactive_at` and add `ephemeral`; deactivating an ephemeral strand deletes it and its incident edges instead of retaining history. Outcome subtypes such as done, failed, or cancelled belong in JSON attributes when a world wants them.
- **RFC-006.C5:** Runtime worlds (SPEC-004.P2/P3): config `~/.config/atom` → `~/.config/skein`, state `~/.local/state/atom` → `~/.local/state/skein`, data `~/.local/share/atom` → `~/.local/share/skein`. Socket/metadata files rename `daemon.sock`/`daemon.json`/`daemon.edn` → `weaver.sock`/`weaver.json`/`weaver.edn`.
- **RFC-006.C6:** Specs: rename `task-model.md` → `strand-model.md`; refresh vocabulary across `cli.md`, `daemon-runtime.md` (→ weaver runtime framing), `repl-api.md`, and PRD-001. Promote on feature finish, not in this RFC.
- **RFC-006.C7:** No Clojure-core symbol hazard: `strand`/`skein`/`weaver` do not shadow any `clojure.core` name, so the `atom`-style bare-symbol caution does not apply.
- **RFC-006.C8:** Per [TEN-000@1](../TENETS.md), drop legacy names with no compatibility aliases and no data migration; existing local worlds/dbs are disposable.
- **RFC-006.C9:** Publish identity should use the distinctive brand in code and specs (`skein.*`), but public domain/GitHub handle claims are out of scope for the rename feature. Local checks found `skein.dev`, `getskein.dev`, `github.com/skein`, and `github.com/skein-dev` occupied; any public release-home decision needs explicit owner confirmation in a later release feature.

## RFC-006.P7 Outcome

- **RFC-006.OUT1:** Accepted 2026-06-26 by the project owner. Direction: brand the product **Skein** with namespace root `skein.*`; name the stored unit and CLI **strand**; name the daemon **weaver**; keep edge creation on `--edge`; drop the `molecule`/`bond` metaphor. Earlier candidates were rejected on checked collisions: `atom` (Clojure-core clash), `Strands` (AWS Strands Agents), `Weft` (saturated AI-agent brand). Follow-up: [Skein Rename Proposal](../feat/skein-rename/proposal.md) owns the feature scope, spec deltas for SPEC-001 (→ strand-model), SPEC-002, SPEC-003, SPEC-004 (→ weaver runtime), and PRD-001, plus a plan that sequences the binary, namespace, library, schema, and world-dir renames. The proposal resolves the deferred implementation items: use `strand weaver ...` rather than a standalone daemon binary, rename socket/metadata artifacts to `weaver.sock`/`weaver.json`/`weaver.edn`, and exclude public domain/GitHub handle claims from the rename scope unless the owner confirms them in a later release feature.
