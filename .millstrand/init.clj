;; Startup entrypoint for the repo's canonical coordination world. Every concern
;; is a stable runtime module (DELTA-OlrDrt-001.CC1): the module key is its owner
;; identity, `:after` orders the dependency-first graph, and a full `refresh!`
;; re-reads this file to recollect the whole graph. Startup-file collection only
;; STAGES declarations — no source load, publication, or reconcile runs here — so
;; this file holds no imperative effects; each concern's registrations live in its
;; module's contribution and lifecycle declarations are collected from its
;; authoring forms. Declarations carry only a source target and world policy.
;;
;; File-per-concern map (each Clojure file is one module):
;;   policy/config.clj            — named queries + shared validation helpers
;;   workflows/                   — workflow definitions, policy, support, and scripts
;;   agents/guide.clj             — guide op: surface questions answered by a run
;;   agents/reviewers.clj         — reviewer rosters
;;   agents/delegation_contracts.clj — workspace task and review contracts
;;   notifications/attention.clj  — chime attention rules
;;   jobs/nvd_scan.clj            — NVD scan cron job
;;   adapters/module.clj          — repo election of the batteries help transform
;;   Codethread roots             — shared agents, spool-bump, Devflow setup, and Ralph
;;
;; Gitignored init.local.clj is layered after this file on startup and every
;; refresh; a module key it redeclares shadows the one here and wins, and it binds
;; each developer's chime notifier. Read docs/reference.md before changing this
;; config; smoke-test changes in a disposable world first.
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is approved as a shipped source-root spool by default. The module
;; guard keeps source loading behind that visible approval; its `millstrand/defop`
;; forms publish the CLI partition and its lifecycle seed owns the failure
;; glossary those operations reference.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :spools ['millstrand.spools/batteries]})

(runtime/module! runtime :module-adapters
                 {:file "adapters/help.clj"
                  :after [:millstrand/spools-batteries]})

;; --- workflow engine + shell executor -------------------------------------
;; The engine's collected open-kind and lifecycle declarations own Workflow
;; definition/executor publication and its process-lifetime vocabulary seed.
(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow
                  :spools ['millhouse.spools/workflow]})
;; Millhouse's workspace workflow extensions are a separate root over the
;; engine, and must collect after the engine's module has published its base.
(runtime/module! runtime :millhouse/spools-millstrand-workflows
                 {:ns 'millhouse.spools.millstrand-workflows
                  :spools ['millhouse.spools/millstrand-workflows
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})
;; The generic worker CLI is a separate, opt-in module of the same spool: the
;; engine ships no verbs, and this declaration is what puts the root `workflow`
;; op (list/show/start/ready/complete/choose/defer/await) on the surface for
;; every registered definition. Its collected lifecycle declaration seeds the
;; failure glossary. Dropping it and refreshing removes the verb.
(runtime/module! runtime :millhouse/spools-workflow-cli
                 {:ns 'millhouse.spools.workflow.cli
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]})
;; The shell executor has its own approved root and fulfils :shell gates
;; by running the gate command directly. Collected forms publish the :shell executor
;; symbol and its query. Lifecycle resources own the worker pool and initial scan;
;; ordered after workflow, which owns the executor registry it contributes into.
(runtime/module! runtime :millhouse/spools-shell
                 {:ns 'millhouse.spools.executors.shell
                  :spools ['millhouse.spools.executors/shell
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]})
;; UNSAFE spool: unsafe-text-search reaches past the blessed api.* contract into
;; millstrand.core.db to LIKE-search titles and attribute values, including archived
;; rows the query language cannot see. It is a maintained, in-the-open example of
;; rule-breaking (see spools/unsafe-text-search.md), activated here so it stays
;; exercised. Its `millstrand/defop` form contributes the search operation; it owns no
;; live resource and therefore declares no lifecycle effect.
(runtime/module! runtime :millstrand/spools-unsafe-text-search
                 {:ns 'millstrand.spools.unsafe-text-search
                  :spools ['millstrand.spools/unsafe-text-search]})
;; devflow is an external git-distributed spool: activation is gated on the
;; approved codethread/devflow coordinate (spools.edn pin or a developer's
;; spools.local.edn checkout), never on an incidental classpath copy. Its whole
;; contribution is the stage `defworkflow` entries its load collects.
(runtime/module! runtime :millstrand/spools-devflow
                 {:ns 'ct.spools.devflow
                  :spools ['codethread/devflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- peer coordination spools -----------------------------------------------
;; These sibling modules collect owner-complete contributions from source.
;; Named lifecycle resources own their runtime setup and removal.
(runtime/module! runtime :millstrand/spools-shuttle
                 {:ns 'ct.spools.agent-run
                  :spools ['ct.spools/agent-run]
                  :required? true})
(runtime/module! runtime :millstrand/spools-harness-core
                 {:ns 'ct.spools.harness-core
                  :spools ['ct.spools/harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-codex-harness
                 {:ns 'ct.spools.codex-harness
                  :spools ['ct.spools/codex-harness 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-agent-cli
                 {:ns 'ct.spools.agent-cli
                  :spools ['ct.spools/agent-cli 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core :millstrand/spools-codex-harness]
                  :required? true})
(runtime/module! runtime :millstrand/spools-delegation
                 {:ns 'ct.spools.delegation
                  :spools ['ct.spools/delegation]
                  :after [:millstrand/spools-shuttle]
                  :required? true})
(runtime/module! runtime :millstrand/spools-bench
                 {:ns 'ct.spools.bench
                  :spools ['ct.spools/bench]
                  :after [:millstrand/spools-shuttle]
                  :required? true})

;; --- repo policy over the peer spools ---------------------------------------
;; Codethread publishes the shared harness tools and seat aliases. This
;; repository keeps reviewer rosters and task/review policy local.
(runtime/module! runtime :codethread/agents
                 {:ns 'ct.spools.codethread.agents
                  :spools ['codethread/agents 'ct.spools/delegation 'ct.spools/agent-run]
                  :after [:millstrand/spools-shuttle :millstrand/spools-delegation]
                  :required? true})
;; agents/guide.clj publishes the `guide` op, which spawns its answer as an agent run on
;; a shared Codethread seat, so it orders after the shared agents module.
(runtime/module! runtime :guide
                 {:file "agents/guide.clj"
                  :spools ['ct.spools/agent-run]
                  :after [:millstrand/spools-shuttle :codethread/agents]
                  :required? true})
;; The declarative reviewer roster stays a small git-reviewable data document,
;; collected as the workspace-owned partition of delegation's roster kind.
;; Roster harness aliases resolve at review time, not registration time, so order
;; relative to the shared Codethread agents module is not load-bearing.
(runtime/module! runtime :reviewers
                 {:file "agents/reviewers.clj"
                  :spools ['ct.spools/delegation]
                  :after [:millstrand/spools-delegation]
                  :required? true})
;; The delegation contracts are workspace policy over the shared agent-run and
;; delegation spools. Keep this resource after their shared Codethread agents
;; contribution so it binds the exported worker/review contract text in order.
(runtime/module! runtime :delegation-contracts
                 {:file "agents/delegation_contracts.clj"
                  :spools ['ct.spools/agent-run 'ct.spools/delegation]
                  :after [:codethread/agents :millstrand/spools-shuttle
                          :millstrand/spools-delegation]
                  :required? true})

;; --- chime notification engine + this repo's attention rules ----------------
;; Chime is vocabulary-agnostic; notifications/attention.clj contributes this repo's attention
;; rules (HITL checkpoints, kanban completion, and parked runs) with defrule, and
;; each developer binds how they are notified in
;; gitignored init.local.clj. Chime's defresource owns its handler, mutation
;; barrier, and visible rule view as one atomic boundary. Unbound chime records
;; loud notifier-missing errors.
(runtime/module! runtime :millhouse/spools-chime
                 {:ns 'millhouse.spools.chime
                  :spools ['millhouse.spools/chime]
                  :required? true})
(runtime/module! runtime :attention
                 {:file "notifications/attention.clj"
                  :spools ['millhouse.spools/chime 'ct.spools/agent-run]
                  :after [:millhouse/spools-chime :millstrand/spools-shuttle]
                  :required? true})

;; --- kanban board + devflow adapter -----------------------------------------
;; kanban is an external git-distributed spool. The adapter integrates the
;; board with devflow after both spools are active.
(runtime/module! runtime :millstrand/spools-kanban
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]
                  :required? true})
(runtime/module! runtime :millstrand/spools-devflow-kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :spools ['codethread/devflow-kanban-adapter
                           'codethread/devflow 'codethread/kanban]
                  :after [:millstrand/spools-devflow :millstrand/spools-kanban]
                  :required? true})
;; Codethread's setup root elects the external adapter's decompose seed only
;; after both external Devflow and Kanban adapter roots are active.
(runtime/module! runtime :codethread/devflow-setup
                 {:ns 'ct.spools.codethread.devflow-setup
                  :spools ['codethread/devflow-setup]
                  :after [:millstrand/spools-devflow-kanban-adapter]
                  :required? true})
;; --- cron timer engine + the NVD scan job -----------------------------------
;; Cron is a generic weaver timer engine. Its collected open-kind and lifecycle
;; declarations own job publication and scheduling; jobs/nvd_scan.clj contributes a
;; job through `defjob`, so it is ordered after cron.
(runtime/module! runtime :millhouse/spools-cron
                 {:ns 'millhouse.spools.cron
                  :spools ['millhouse.spools/cron]
                  :required? true})
;; The NVD scan job is its own module (not part of policy/config.clj) so config_test's
;; direct policy/config.clj load never registers the job or seeds against real gh.
(runtime/module! runtime :nvd-scan
                 {:file "jobs/nvd_scan.clj"
                  :spools ['millhouse.spools/cron]
                  :after [:millhouse/spools-cron :millstrand/spools-kanban]
                  :required? true})

;; --- config queries/helpers and hand-authored workflows ---------------------
;; policy/config.clj authors named queries with defquery and public validation helpers.
(runtime/module! runtime :config
                 {:file "policy/config.clj"
                  :required? true})
;; Shared script sources load before the focused workflow modules.
(runtime/module! runtime :workflows.support
                 {:file "workflows/support.clj"
                  :after [:config]
                  :required? true})
;; workflows/common.clj owns the shared authoring patterns.
(runtime/module! runtime :workflows
                 {:file "workflows/common.clj"
                  :spools ['millhouse.spools/workflow 'ct.spools/delegation]
                  :after [:millhouse/spools-workflow :millstrand/spools-delegation
                          :config :workflows.support]
                  :required? true})
;; Each concrete workflow definition owns one focused source module. Keeping
;; these modules independent lets a change to one routine refresh its own
;; contribution without growing a broad definitions file.
(runtime/module! runtime :workflows.land
                 {:file "workflows/land.clj"
                  :spools ['millhouse.spools/workflow 'ct.spools/delegation]
                  :after [:millhouse/spools-workflow :millstrand/spools-delegation
                          :reviewers :workflows.support]
                  :required? true})
(runtime/module! runtime :codethread/spool-bump
                 {:ns 'ct.spools.codethread.spool-bump
                  :spools ['codethread/spool-bump 'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :workflows.story
                 {:file "workflows/story.clj"
                  :spools ['millhouse.spools/workflow 'ct.spools/delegation]
                  :after [:millhouse/spools-workflow :millstrand/spools-delegation
                          :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.explore
                 {:file "workflows/explore.clj"
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.fix
                 {:file "workflows/fix.clj"
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow :workflows.support]
                  :required? true})
;; workflows/land_policy.clj owns the narrow land policy op: merge lock, merge queue,
;; and kanban lane moves. It loads after the land definitions it drives.
(runtime/module! runtime :workflows.land-policy
                 {:file "workflows/land_policy.clj"
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow :workflows
                          :workflows.land]
                  :required? true})
;; Ralph remains an independent one-card-per-iteration workflow owned by
;; Codethread; landing policy and reviewer evidence stay local to this repo.
(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :spools ['codethread/ralph 'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; The code executor's lifecycle resource scans ready gates when opened. It must load after
;; every workflow definition so persisted code/fn symbols resolve on the initial scan.
(runtime/module! runtime :millhouse/spools-code
                 {:ns 'millhouse.spools.executors.code
                  :spools ['millhouse.spools.executors/code
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow :workflows
                          :workflows.land :codethread/spool-bump
                          :workflows.story :workflows.explore :workflows.fix
                          :codethread/ralph]
                  :required? true})

;; The subagent gate executor activates last: its lifecycle resource runs an initial gate
;; scan, so every harness alias Codethread agents registers must already exist or a
;; durable ready gate would be stamped gate/error on every cold start.
(runtime/module! runtime :millstrand/spools-treadle
                 {:ns 'ct.spools.executors.subagent
                  :spools ['ct.spools/agent-run]
                  :after [:millstrand/spools-shuttle :millhouse/spools-workflow
                          :codethread/agents :reviewers :workflows :workflows.land
                          :workflows.story :codethread/ralph]
                  :required? true})
