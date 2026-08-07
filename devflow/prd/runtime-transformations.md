# Runtime transformations

**Document ID:** `PRD-001` **Related:** [Devflow Philosophy](../PHILOSOPHY.md), [Strand Model](../specs/strand-model.md), [CLI Surface](../specs/cli.md), [REPL API](../specs/repl-api.md), [Weaver Runtime](../specs/daemon-runtime.md)

Skein treats persisted strands and edges as durable facts, and treats richer behavior as runtime transformation over those facts.

SQLite remains the long-term persistence layer for the strand graph. The weaver is the live application core. Trusted Clojure config, activated spools, and REPL workflows define runtime behavior. The selected workspace is the user-owned runtime spool workspace: `init.clj` boots trusted code, `spools.edn` approves local roots and sha-pinned git coordinates, and helper namespaces expose blessed workflows.

## Goals

- Store durable facts only: strands, attributes, and edges belong in SQLite; projections, filters, scores, and workflows belong in weaver runtime/config/REPL.
- Expose set-oriented graph helpers for query-id selection, batch hydration, ancestor-root traversal, and subgraph expansion.
- Expose named read surfaces for trusted Clojure workflows: named queries for declarative graph reads, read-class registered ops for arbitrary shaped reads.
- Keep the CLI thin: it may consume named weaver behavior, but it does not author it or load libraries.

## Runtime helper namespaces

Skein ships source-visible helper namespaces:

- `skein.api.runtime.alpha` for approved local-root sync and module activation.
- `skein.api.graph.alpha` for graph/query helpers such as `query-ids!`, `strands-by-ids`, `ancestor-root-ids`, and `subgraph`.

These helpers run in trusted startup config or connected REPL workflows.

## Example: repository-owned ready strands

A user tracks repository ownership in strand attributes:

```clojure
(require '[skein.api.weaver.alpha :as weaver])

(weaver/register-query!
 'ready-for-repo
 '{:where [:and
           [:= [:attr :repo] [:param :repo]]
           [:= :state "active"]]})
```

The CLI can consume the registered query while the same weaver is running:

```sh
strand ready --query ready-for-repo --param repo=skein
```

## Example: feature read op

Assume a world uses user attributes such as `example_category="feature"` and `parent-of` edges from feature roots to child work. Startup config can register a read-class op:

```clojure
(ns my.millstrand.reads
  (:require [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]))

(defn active-feature-dags [{:op/keys [runtime args]}]
  (let [seed-ids (graph/query-ids runtime 'active-owned {:repo (:repo args)})
        feature-root-ids (graph/ancestor-root-ids runtime seed-ids {:where [:= [:attr :example_category] "feature"]})
        graph-data (graph/subgraph runtime feature-root-ids)]
    {:features (graph/strands-by-ids runtime feature-root-ids)
     :graph graph-data}))

(defn install! []
  (let [rt (current/runtime)]
    (graph/register-query!
     rt
     'active-owned
     '{:params [:repo]
       :where [:and
               [:= [:attr :repo] [:param :repo]]
               [:= :state "active"]]})
    (weaver/register-op! rt 'active-feature-dags
                         {:doc "Active feature DAGs for a repo"
                          :hook-class :read
                          :arg-spec {:op "active-feature-dags"
                                     :flags {:repo {:type :string
                                                    :doc "Repo whose active features to expand."}}}}
                         'my.millstrand.reads/active-feature-dags)))
```

The flagship shape remains: query ids -> graph expansion -> batch hydration -> Clojure shaping -> optional named read-op invocation.

## Non-goals

- No CLI package manager or behavior-authoring commands.
- No untrusted plugin sandbox.
- No core category, outcome, or workflow taxonomy; worlds store those concepts in attributes when they need them.
