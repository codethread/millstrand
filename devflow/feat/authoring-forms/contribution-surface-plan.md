# Skein authoring surface plan

Card `obacf` builds on contribution replay commit `c24dfffd7fdb0348dbc8c40a06e9c3ff5c469f27`.

## Selected-universe inventory

| Kind ID | Provider module | Existing form | Retained public route | Owning namespace and spec | Files owned here |
| --- | --- | --- | --- | --- | --- |
| `:ops` | core runtime | workspace `defop` prototype | `defop` | `skein.api.skein.alpha/defop`, `::op-options`, `::op-entry` | Skein API and tests |
| `:queries` | core runtime | workspace `defquery` prototype | `defquery` | `skein.api.skein.alpha/defquery`, `::query-options`, `::query-entry` | Skein API and tests |
| `:patterns` | core runtime | workspace `defpattern`/`defp` prototypes | `defpattern`; no short alias | `skein.api.skein.alpha/defpattern`, `::pattern-options`, `::pattern-entry` | Skein API and tests |
| `:hooks` | core runtime | raw contribution maps | `defhook` | `skein.api.skein.alpha/defhook`, `::hook-options`, `::hook-entry` | Skein API and tests |
| `:events` | core runtime | raw contribution maps | `defhandler` | `skein.api.skein.alpha/defhandler`, `::handler-options`, `::event-entry` | Skein API and tests |
| `:skein.spools.workflow/definitions` | Workflow | `defworkflow` | `defworkflow` | `skein.spools.workflow/defworkflow`, `::workflow-options`, `::definition` | Workflow form/spec tests and docs |
| `:skein.spools.workflow/executors` | Workflow | raw contribution maps | `defexecutor` | `skein.spools.workflow/defexecutor`, `::executor-options`, `::executor-entry` | Workflow form/spec tests and docs |
| `:skein.spools.cron/jobs` | Cron | `defjob` | `defjob` | `skein.spools.cron/defjob`, `::job-options`, `::job` | Cron form/spec tests and docs |
| `:skein.spools.chime/rules` | Chime | workspace `defrule` prototype | `defrule` | `skein.spools.chime/defrule`, `::rule-options`, `::rule-entry` | Chime form/spec tests and docs |

Guild's declaration kind is outside the selected migration program. It remains callback-owned until a card explicitly adds it. The external agent-run, delegation, and bench kinds are handoffs to card `842qy`.

## Surface decisions

`skein.api.skein.alpha` is the small common home for the five core kinds. Each form validates its closed option map and complete entry before calling `collect-entry!`. It adds owner-complete source/image replay and omission semantics that direct registration and ordinary function composition do not provide. `collect-entry!` stays the low-level generated-entry seam. `declare-kind!` remains the provider seam and does not author entries.

Workflow, Cron, and Chime retain domain-owned forms because their declaration vocabularies and specs belong to those providers. `defpattern` remains the only pattern form; the workspace-only `defp` alias does not earn a second public name. No generic `defentry` is added.

Generated code calls the public, kind-specific declaration constructors before `collect-entry!`. This gives generated batches the same closed grammar and registry-owned candidate validation as source forms.

## Migration-window manifest

`test/skein/config_test.clj` owns the selected-module generation table. Every row is exactly `:legacy` or `:forms`. This card installs the table and assertions; later migration cards change only their assigned rows. Card `u3k2z` removes the table after the last row reaches `:forms`.

## Validation

The focused cold command is:

```sh
clojure -M:test skein.api.skein-test skein.spools.workflow-test skein.cron-test skein.chime-test skein.weaver-test
```

`skein.config-test` is an add-libs shard and runs only through the full suite.

Queue acceptance uses:

```sh
flock -w 3600 /tmp/skein-test.lock clojure -M:test
```
