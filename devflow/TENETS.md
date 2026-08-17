# Project Tenets

TEN ids use `@N` versions. A bare TEN id means its latest version. Bump `@N` only when the tenet's normative meaning changes, never for editorial changes. When a version is superseded, replace its text here with a one-line supersession note; git history holds the superseded text.

`TEN-000` is currently `TEN-000@1`. Do not add new bare `TEN-000` references. Bare `TEN-000` references in git history name the original meaning from before the `@1` tag.

- **TEN-000@1**: This is alpha software.
    - All apis, contracts, db schemas, are subject to change. Changes can and should drop old ideas without migration plans should a better approach be presented.
- **TEN-001**: This is primarily an LLM coding agent tool.
    - All apis should favour their consumption (raw informative structure data over pretty ascii and layouts).
    - The apis exposed should offer flexibility over invariants
- **TEN-002**: Agents are trusted to not abuse their power.
    - In giving agents this tool, we expose helper functions along with clear guidance, such that they will use the 'blessed path' and not corrupt data or remove userland invariants without their user's sign-off. This allows us to keep the system malleable and flexible, without the need to constrain every possible misuse of the system
- **TEN-003**: FAIL LOUDLY.
    - we have a deliberately loose api contract via attributes, this provides flexibility, and we can build this tolerance into the system. However when we get something we do not expect, we do not try to work around it or choose 'sensible defaults'. We FAIL LOUDLY. If this provides api friction or poor ergonomics, we solve that in other ways like persisted config or better api design.
- **TEN-004**: Less is More.
    - We expose the minimum possible surface area over api, and what we expose, we make reliable. Everything else we delegate to userland via our attributes and query language
- **TEN-005**: Declared structural relations are DAGs.
    - The engine guarantees each declared acyclic relation is independently acyclic, and every engine traversal walks exactly one such relation or is explicitly cycle-aware. Annotation edges carry no acyclicity guarantee and may form cycles; consumers must not assume whole-graph acyclicity.
- **TEN-006**: The CLI is a thin JSON control surface; the weaver/REPL is the rich semantic surface.
    - The scripted CLI should expose simple commands, string flags, JSON machine output, and named handles to weaver-owned behavior. It should not parse, author, or debug rich Clojure/EDN userland structures. A trusted transform registered in weaver config may render help output (SPEC-004.C106), but the machine schema — the versioned help envelope — stays the single contract, `--json` is the raw floor the CLI always relays, and the CLI itself still authors or debugs no userland structure.
    - Complex query definitions, runtime customization, inspection, and debugging belong in trusted weaver config and REPL workflows. The CLI can invoke those capabilities by stable names and simple JSON-shaped params.
    - The engine may translate between JSON wire data and Clojure-native/EDN data internally, but that translation is hidden behind weaver APIs.
    - Why we hold this over a richer generic CLI algebra: [`ADR-001`](./adrs/0001-thin-cli-over-generic-algebra.md).
- **TEN-007**: Storage complexity is the core's burden; the attribute map is the contract.
    - A strand _has_ an attribute map. How those attributes are physically stored is an implementation detail owned entirely by `millstrand.core.*`. No consumer above `millstrand.core.*` — `millstrand.api.*`, spool authors, the CLI JSON wire format, the query language, events, or views — may depend on the physical shape of attribute storage.
    - This is deliberate deep-module discipline: a simple interface (a map) over an implementation free to absorb whatever complexity scale and performance demand. Keeping the storage representation hidden is what lets it change under a stable contract (TEN-000@1).
