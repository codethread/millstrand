# Spool-suite CI gate: run the pinned external spool suites against skein-src HEAD

**Document ID:** `PROP-ssc-001`

**Last Updated:** 2026-07-11

**Related RFCs:** None

**Related root specs:** None

**Related brief:** [brief.md](./brief.md) (scope is the contract)

**Related feature:** [`devflow/feat/unify-spool-classpath/`](../26-07-11__unify-spool-classpath/) — this gate was extracted from that work, which moved `skein.spools.workflow` off Skein's main classpath and thereby broke `devflow.spool`'s standalone suite. **Kanban:** card `yhqfh` (p2).

**Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `PROP-Dwr-001` for v1 and `PROP-Dwr-001@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `PROP-Dwr-001.P1` or `PROP-Dwr-001@2.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

**Reading context.** A _spool_ here is an external Clojure library (`codethread/devflow.spool`, `codethread/kanban.spool`) that skein-src pins as a `:test` git dependency and that, in turn, tests itself against a live skein-src checkout. The two directions of consumption are asymmetric, and only one is currently gated. This proposal frames the missing gate at product level; the implementation plan owns the workflow, make-target, and land mechanics.

## PROP-ssc-001.P1 Problem

skein-src and the spools consume each other in opposite directions, and only one direction is tested:

- **skein-src → spools, at a pin.** skein-src pins `devflow.spool` and `kanban.spool` as frozen `:test` git dependencies (kept synchronized with `.skein/spools.edn`). skein-src's own suite exercises the spools **at those frozen shas**.
- **spools → skein-src, at HEAD.** Each spool's own `:test` alias resolves skein-src as a **live** sibling checkout (`../skein-src`). The spool suites test whatever skein-src currently is, not a pin.

skein-src CI runs only the first direction. The second is untested, so a skein-src change can silently break a downstream spool suite with no signal in skein-src's own gates. **This is not hypothetical.** The `unify-spool-classpath` feature moved `skein.spools.workflow` off Skein's main classpath into a per-spool root. `skein.spools.devflow` requires `skein.spools.workflow`, so `devflow.spool`'s standalone suite — which assembles its classpath from what a live skein-src checkout _exports_ — lost the namespace and broke. `kanban.spool` worked around it by adding the moved workflow root to its own `:test` alias; `devflow.spool` never did. Both the breakage and the partial fix were found by hand. No skein-src gate saw it, and none will see the next one.

The asymmetry the existing suite structurally cannot cover: skein-src's suite loads the spools **at the pin** onto skein-src's _own_ classpath, which always carries every spool root, so the workflow namespace is always present there. A spool's _standalone_ suite assembles a _different_ classpath — its own sources plus only what a live skein-src checkout exports — and that is the classpath the move broke. Only running a spool's own suite against HEAD reproduces it. Critically, `devflow.spool`'s pinned suite cannot even reach `skein.spools.workflow` from a plain sibling layout, because that source moved to a root skein-src exports only to its _own_ test classpath, not to a live-checkout consumer — so the gate must arrange that root when it runs the devflow suite (see P4).

## PROP-ssc-001.P2 Goals

- **PROP-ssc-001.G1:** A blocking CI job runs `devflow.spool`'s and `kanban.spool`'s own suites against skein-src HEAD on every PR and every push to main, failing the build when HEAD breaks a pinned spool suite.
- **PROP-ssc-001.G2:** The spool shas live in exactly one place — skein-src's `deps.edn` `:test` pins. The workflow reads them at job time; it never restates a sha.
- **PROP-ssc-001.G3:** Fail loudly and legibly (TEN-003). A red run names which spool failed, at which sha, and the single command that reproduces it locally.
- **PROP-ssc-001.G4:** One local-reproduction surface, reused by CI, the land step, and manual runs (TEN-004) — no copy of the layout-and-run logic per call site.

## PROP-ssc-001.P3 Non-goals

- **PROP-ssc-001.NG1:** No pin-bumping automation. The gate tests HEAD against the _current_ pins; advancing a sha (and re-synchronizing `.skein/spools.edn`) stays a deliberate human change (brief "Deliberately not built").
- **PROP-ssc-001.NG2:** No changes to `codethread/devflow.spool` or `codethread/kanban.spool`. They already declare the sibling-layout `:test` defaults this job consumes; this feature depends on those defaults, it does not edit them. (Consequently the devflow-suite arrangement in P4 is a skein-src-side run-time step, not a spool edit.)
- **PROP-ssc-001.NG3:** No new coordinate grammar and no persisted cross-repo paths. The layout — including the devflow workflow-spool root — is arranged at run time, not written into any committed config (mirrors the spools' own `:local/root "../skein-src"` convention).
- **PROP-ssc-001.NG4:** Not a replacement for skein-src's own suite. This gate is additive; it tests the spool→HEAD direction the existing suite structurally cannot (P1).

## PROP-ssc-001.P4 Proposed scope

- **PROP-ssc-001.S1:** A blocking spool-suite gate that runs both pinned spool suites (`devflow.spool`, `kanban.spool`) against skein-src HEAD, on every PR and every push to main, as a peer of the existing quality gates.
- **PROP-ssc-001.S2:** Sha resolution from skein-src's `deps.edn` `:test` pins at job time — the single source of truth — so the pins are never duplicated into the workflow file.
- **PROP-ssc-001.S3:** Arrangement of the sibling layout each spool's `:test` alias expects (a `skein-src` resolving to the candidate at HEAD beside each spool checkout), **plus** — for the `devflow.spool` run only — the moved workflow-spool root, which its pinned `:test` alias does not carry and which a live skein-src checkout does not export. `kanban.spool` already carries that root in its own alias and needs no extra arrangement. This corrects the earlier assumption that a plain sibling layout suffices for both suites: empirically, `devflow.spool`'s suite is red on a clean HEAD until the workflow-spool root is supplied (verified by red→green reproduction; see the design note on this task strand).
- **PROP-ssc-001.S4:** A single local-reproduction surface (a make target if warranted) that owns the layout, arrangement, and run, reused by CI, the land gate, and manual runs — no per-call-site copy of the logic.
- **PROP-ssc-001.S5:** Failure attribution: a red run names which spool failed, at which sha, and the one command that reproduces it locally.
- **PROP-ssc-001.S6:** A decision on whether the land `merge-local-verify` gate gains the same check, with the cost of omission stated (correctness is preserved by the push-to-main run either way; the decision is about how early the signal arrives).

## PROP-ssc-001.P5 Open questions

- **PROP-ssc-001.Q1:** CI checkout mechanism — does the CI job open-code the spool checkouts natively (for per-repo, GitHub-native caching and shallow fetch) and share only the sha-resolution and workflow-root arrangement with the local surface, or does it call the single local-reproduction surface directly (maximizing single-source at the cost of native caching)? Both satisfy every goal; the trade-off is caching cleanliness versus one home for the layout logic. Resolve at the spec/plan stage.
