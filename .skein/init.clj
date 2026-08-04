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
;;   agents/harnesses.clj         — harness seats + routing policy
;;   agents/guide.clj             — guide op: surface questions answered by a run
;;   agents/reviewers.clj         — reviewer rosters
;;   notifications/attention.clj  — chime attention rules
;;   jobs/nvd_scan.clj            — NVD scan cron job
;;   adapters/module.clj          — repo election of the batteries help transform
;;
;; Gitignored init.local.clj is layered after this file on startup and every
;; refresh; a module key it redeclares shadows the one here and wins, and it binds
;; each developer's chime notifier. Read docs/reference.md before changing this
;; config; smoke-test changes in a disposable world first.
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is approved as a shipped source-root spool by default. The module
;; guard keeps source loading behind that visible approval; its `skein/defop`
;; forms publish the CLI partition and its lifecycle seed owns the failure
;; glossary those operations reference.
(runtime/module! runtime :skein/spools-batteries
                 {:ns 'skein.spools.batteries
                  :spools ['skein.spools/batteries]})
;; This repo elects the batteries reference help transform after batteries loads;
;; its lifecycle resource releases the singleton when the module is omitted.
(runtime/module! runtime :module-adapters
                 {:file "adapters/module.clj"
                  :after [:skein/spools-batteries]})

;; --- workflow engine + shell executor -------------------------------------
;; The engine's collected open-kind and lifecycle declarations own Workflow
;; definition/executor publication and its process-lifetime vocabulary seed.
(runtime/module! runtime :skein/spools-workflow
                 {:ns 'skein.spools.workflow
                  :spools ['skein.spools/workflow]})
;; The generic worker CLI is a separate, opt-in module of the same spool: the
;; engine ships no verbs, and this declaration is what puts the root `workflow`
;; op (list/show/start/ready/complete/choose/defer/await) on the surface for
;; every registered definition. Its collected lifecycle declaration seeds the
;; failure glossary. Dropping it and refreshing removes the verb.
(runtime/module! runtime :skein/spools-workflow-cli
                 {:ns 'skein.spools.workflow.cli
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow]})
;; The shell executor ships in the workflow spool root and fulfils :shell gates
;; by running the gate command directly. Collected forms publish the :shell executor
;; symbol and its query. Lifecycle resources own the worker pool and initial scan;
;; ordered after workflow, which owns the executor registry it contributes into.
(runtime/module! runtime :skein/spools-shell
                 {:ns 'skein.spools.executors.shell
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow]})
;; UNSAFE spool: unsafe-text-search reaches past the blessed api.* contract into
;; skein.core.db to LIKE-search titles and attribute values, including archived
;; rows the query language cannot see. It is a maintained, in-the-open example of
;; rule-breaking (see spools/unsafe-text-search.md), activated here so it stays
;; exercised. Its `skein/defop` form contributes the search operation; it owns no
;; live resource and therefore declares no lifecycle effect.
(runtime/module! runtime :skein/spools-unsafe-text-search
                 {:ns 'skein.spools.unsafe-text-search
                  :spools ['skein.spools/unsafe-text-search]})
;; devflow is an external git-distributed spool: activation is gated on the
;; approved codethread/devflow coordinate (spools.edn pin or a developer's
;; spools.local.edn checkout), never on an incidental classpath copy. Its whole
;; contribution is the stage `defworkflow` entries its load collects.
(runtime/module! runtime :skein/spools-devflow
                 {:ns 'ct.spools.devflow
                  :spools ['codethread/devflow]
                  :after [:skein/spools-workflow]
                  :required? true})

;; --- peer coordination spools -----------------------------------------------
;; These sibling modules collect owner-complete contributions from source.
;; Named lifecycle resources own their runtime setup and removal.
(runtime/module! runtime :skein/spools-shuttle
                 {:ns 'ct.spools.agent-run
                  :spools ['ct.spools/agent-run]
                  :required? true})
(runtime/module! runtime :skein/spools-harness-core
                 {:ns 'ct.spools.harness-core
                  :spools ['ct.spools/harness-core]
                  :required? true})
(runtime/module! runtime :skein/spools-codex-harness
                 {:ns 'ct.spools.codex-harness
                  :spools ['ct.spools/codex-harness 'ct.spools/harness-core]
                  :after [:skein/spools-harness-core]
                  :required? true})
(runtime/module! runtime :skein/spools-agent-cli
                 {:ns 'ct.spools.agent-cli
                  :spools ['ct.spools/agent-cli 'ct.spools/harness-core]
                  :after [:skein/spools-harness-core :skein/spools-codex-harness]
                  :required? true})
(runtime/module! runtime :skein/spools-delegation
                 {:ns 'ct.spools.delegation
                  :spools ['ct.spools/delegation]
                  :after [:skein/spools-shuttle]
                  :required? true})
(runtime/module! runtime :skein/spools-bench
                 {:ns 'ct.spools.bench
                  :spools ['ct.spools/bench]
                  :after [:skein/spools-shuttle]
                  :required? true})

;; --- repo policy over the peer spools ---------------------------------------
;; agents/harnesses.clj contributes its seats over the :pi harness that agent-run
;; publishes, as the workspace-owned partitions of agent-run's tool/alias kinds,
;; so it orders after both peers. Two lifecycle resources own the singleton
;; review/task contract slots and clear them on removal.
(runtime/module! runtime :harnesses
                 {:file "agents/harnesses.clj"
                  :spools ['ct.spools/delegation 'ct.spools/agent-run]
                  :after [:skein/spools-shuttle :skein/spools-delegation]
                  :required? true})
;; agents/guide.clj publishes the `guide` op, which spawns its answer as an agent run on
;; a seat agents/harnesses.clj registers, so it orders after both. Nothing else consumes
;; it: dropping this declaration and refreshing removes the op and nothing more.
(runtime/module! runtime :guide
                 {:file "agents/guide.clj"
                  :spools ['ct.spools/agent-run]
                  :after [:skein/spools-shuttle :harnesses]
                  :required? true})
;; The declarative reviewer roster stays a small git-reviewable data document,
;; collected as the workspace-owned partition of delegation's roster kind.
;; Roster harness aliases resolve at review time, not registration time, so order
;; relative to agents/harnesses.clj is not load-bearing.
(runtime/module! runtime :reviewers
                 {:file "agents/reviewers.clj"
                  :spools ['ct.spools/delegation]
                  :after [:skein/spools-delegation]
                  :required? true})

;; --- chime notification engine + this repo's attention rules ----------------
;; Chime is vocabulary-agnostic; notifications/attention.clj contributes this repo's attention
;; rules (HITL checkpoints, agent failures, gate errors, kanban lifecycle, parked
;; runs) with defrule, and each developer binds how they are notified in
;; gitignored init.local.clj. Chime's defresource owns its handler, mutation
;; barrier, and visible rule view as one atomic boundary. Unbound chime records
;; loud notifier-missing errors.
(runtime/module! runtime :skein/spools-chime
                 {:ns 'skein.spools.chime
                  :spools ['skein.spools/chime]
                  :required? true})
(runtime/module! runtime :attention
                 {:file "notifications/attention.clj"
                  :spools ['skein.spools/chime 'ct.spools/agent-run]
                  :after [:skein/spools-chime :skein/spools-shuttle]
                  :required? true})

;; --- kanban board + devflow adapter -----------------------------------------
;; kanban is an external git-distributed spool. The adapter integrates the
;; board with devflow after both spools are active.
(runtime/module! runtime :skein/spools-kanban
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]
                  :required? true})
(runtime/module! runtime :skein/spools-devflow-kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :spools ['codethread/devflow-kanban-adapter
                           'codethread/devflow 'codethread/kanban]
                  :after [:skein/spools-devflow :skein/spools-kanban]
                  :required? true})
;; --- cron timer engine + the NVD scan job -----------------------------------
;; Cron is a generic weaver timer engine. Its collected open-kind and lifecycle
;; declarations own job publication and scheduling; jobs/nvd_scan.clj contributes a
;; job through `defjob`, so it is ordered after cron.
(runtime/module! runtime :skein/spools-cron
                 {:ns 'skein.spools.cron
                  :spools ['skein.spools/cron]
                  :required? true})
;; The NVD scan job is its own module (not part of policy/config.clj) so config_test's
;; direct policy/config.clj load never registers the job or seeds against real gh.
(runtime/module! runtime :nvd-scan
                 {:file "jobs/nvd_scan.clj"
                  :spools ['skein.spools/cron]
                  :after [:skein/spools-cron :skein/spools-kanban]
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
;; workflows/common.clj owns the shared authoring patterns and the stable
;; workflows.common/main-ci-watch code gate function.
(runtime/module! runtime :workflows
                 {:file "workflows/common.clj"
                  :spools ['skein.spools/workflow 'ct.spools/delegation]
                  :after [:skein/spools-workflow :skein/spools-delegation
                          :config :workflows.support]
                  :required? true})
;; Each concrete workflow definition owns one focused source module. Keeping
;; these modules independent lets a change to one routine refresh its own
;; contribution without growing a broad definitions file.
(runtime/module! runtime :workflows.land
                 {:file "workflows/land.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.spool-bump
                 {:file "workflows/spool_bump.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.story
                 {:file "workflows/story.clj"
                  :spools ['skein.spools/workflow 'ct.spools/delegation]
                  :after [:skein/spools-workflow :skein/spools-delegation
                          :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.explore
                 {:file "workflows/explore.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows.support]
                  :required? true})
(runtime/module! runtime :workflows.fix
                 {:file "workflows/fix.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows.support]
                  :required? true})
;; workflows/land_policy.clj owns the narrow land policy op: merge lock, merge queue,
;; and kanban lane moves. It loads after the land definitions it drives.
(runtime/module! runtime :workflows.land-policy
                 {:file "workflows/land_policy.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows
                          :workflows.land]
                  :required? true})
;; Ralph remains an independent one-card-per-iteration workflow.
(runtime/module! runtime :workflows.ralph
                 {:file "workflows/ralph.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows
                          :workflows.land]
                  :required? true})

;; The code executor's lifecycle resource scans ready gates when opened. It must load after
;; every workflow definition so persisted code/fn symbols resolve on the initial scan.
(runtime/module! runtime :skein/spools-code
                 {:ns 'skein.spools.executors.code
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows
                          :workflows.land :workflows.spool-bump
                          :workflows.story :workflows.explore :workflows.fix
                          :workflows.ralph]
                  :required? true})

;; The subagent gate executor activates last: its lifecycle resource runs an initial gate
;; scan, so every harness alias agents/harnesses.clj registers must already exist or a
;; durable ready gate would be stamped gate/error on every cold start.
(runtime/module! runtime :skein/spools-treadle
                 {:ns 'ct.spools.executors.subagent
                  :spools ['ct.spools/agent-run]
                  :after [:skein/spools-shuttle :skein/spools-workflow
                          :harnesses :workflows :workflows.story :workflows.ralph]
                  :required? true})
