# Skein authoring surface plan

Card `obacf` builds on contribution replay commit `c24dfffd7fdb0348dbc8c40a06e9c3ff5c469f27`.

## Selected-universe inventory

| Kind ID | Provider module | Existing form | Retained public route | Internal grammar | Files owned here |
| --- | --- | --- | --- | --- | --- |
| `:ops` | core runtime | workspace `defop` prototype | `skein.api.skein.alpha/defop` | `skein.core.contribution/::op-options`, `::op-entry` | Skein API and tests |
| `:queries` | core runtime | workspace `defquery` prototype | `skein.api.skein.alpha/defquery` | `skein.core.contribution/::query-options`, `::query-entry` | Skein API and tests |
| `:patterns` | core runtime | workspace `defpattern`/`defp` prototypes | `skein.api.skein.alpha/defpattern`; no short alias | `skein.core.contribution/::pattern-options`, `::pattern-entry` | Skein API and tests |
| `:hooks` | core runtime | raw contribution maps | `skein.api.skein.alpha/defhook` | `skein.core.contribution/::hook-options`, `::hook-entry` | Skein API and tests |
| `:events` | core runtime | raw contribution maps | `skein.api.skein.alpha/defhandler` | `skein.core.contribution/::handler-options`, `::event-entry` | Skein API and tests |
| `:skein.spools.workflow/definitions` | Workflow | `defworkflow` | `defworkflow` | `skein.spools.workflow/defworkflow`, `::workflow-options`, `::definition` | Workflow form/spec tests and docs |
| `:skein.spools.workflow/executors` | Workflow | raw contribution maps | `defexecutor` | `skein.spools.workflow/defexecutor`, `::executor-options`, `::executor-entry` | Workflow form/spec tests and docs |
| `:skein.spools.cron/jobs` | Cron | `defjob` | `defjob` | `skein.spools.cron/defjob`, `::job-options`, `::job` | Cron form/spec tests and docs |
| `:skein.spools.chime/rules` | Chime | workspace `defrule` prototype | `defrule` | `skein.spools.chime/defrule`, `::rule-options`, `::rule-entry` | Chime form/spec tests and docs |

Guild's declaration kind is outside the selected migration program. It remains callback-owned until a card explicitly adds it. The external agent-run, delegation, and bench kinds are handoffs to card `842qy`.

## Surface decisions

`skein.api.skein.alpha` is the small public home for the five core kinds and is conventionally required `:as skein`. It exposes only `defop`, `defquery`, `defpattern`, `defhook`, and `defhandler`. Each form validates its closed option map and complete entry through internal `skein.core.contribution` plumbing before calling `collect-entry!`. The split keeps the author-facing namespace about Skein vocabulary rather than publication representation. `collect-entry!` stays the low-level generated-entry seam for provider implementations. `declare-kind!` remains the provider seam and does not author entries.

Workflow, Cron, and Chime retain domain-owned forms because their declaration vocabularies and specs belong to those providers. `defpattern` remains the only pattern form; the workspace-only `defp` alias does not earn a second public name. No generic `defentry` is added.

Generated core entries are not a second public authoring grammar. Skein and provider implementations may use the internal kind-specific constructors before `collect-entry!`; a domain that needs user-authored generated entries exposes and validates its own domain factory or batch form.

## Migration-window manifest

`test/skein/config_test.clj` owns the selected-module generation table. Every row is exactly `:legacy` or `:forms`. This card installs the table and assertions; later migration cards change only their assigned rows. Card `u3k2z` removes the table after the last row reaches `:forms`.

## Validation

The focused cold command is:

```sh
clojure -M:test skein.api.skein-test skein.spools.workflow-test skein.spools.cron.runtime-test skein.chime-test skein.core.weaver.modules-test
```

`skein.config-test` is an add-libs shard and runs only through the full suite.

Queue acceptance uses:

```sh
flock -w 3600 /tmp/skein-test.lock clojure -M:test
```
