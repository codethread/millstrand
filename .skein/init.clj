;; Startup entrypoint for the repo's canonical coordination world. Every concern
;; is a stable runtime module (DELTA-OlrDrt-001.CC1): the module key is its owner
;; identity, `:after` orders the dependency-first graph, and a full `refresh!`
;; re-reads this file to recollect the whole graph. Startup-file collection only
;; STAGES declarations — no source load, publication, or reconcile runs here — so
;; this file holds no imperative effects; each concern's registrations live in its
;; module's contribution and lifecycle declarations are collected from its
;; authoring forms. Declarations carry only a source target and world policy.
;;
;; File-per-concern map (each is one module):
;;   config.clj        — named queries + shared policy validation helpers
;;   workflows.clj     — hand-authored workflow definitions
;;   workflows_land.clj— the land policy op: merge lock, merge queue, lane moves
;;   harnesses.clj     — harness seats + routing policy
;;   guide.clj         — the guide op: surface questions answered by a run
;;   reviewers.clj     — reviewer rosters
;;   attention.clj     — chime attention rules
;;   nvd_scan.clj      — NVD scan cron job
;;   kanban_tracker.clj— devflow<->kanban tracker binding
;;   module_adapters.clj — repo election of the batteries help transform
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
                 {:file "module_adapters.clj"
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
;; harnesses.clj contributes its seats over the :pi harness that agent-run
;; publishes, as the workspace-owned partitions of agent-run's tool/alias kinds,
;; so it orders after both peers. Two lifecycle resources own the singleton
;; review/task contract slots and clear them on removal.
(runtime/module! runtime :harnesses
                 {:file "harnesses.clj"
                  :spools ['ct.spools/delegation 'ct.spools/agent-run]
                  :after [:skein/spools-shuttle :skein/spools-delegation]
                  :required? true})
;; guide.clj publishes the `guide` op, which spawns its answer as an agent run on
;; a seat harnesses.clj registers, so it orders after both. Nothing else consumes
;; it: dropping this declaration and refreshing removes the op and nothing more.
(runtime/module! runtime :guide
                 {:file "guide.clj"
                  :spools ['ct.spools/agent-run]
                  :after [:skein/spools-shuttle :harnesses]
                  :required? true})
;; The declarative reviewer roster stays a small git-reviewable data document,
;; collected as the workspace-owned partition of delegation's roster kind.
;; Roster harness aliases resolve at review time, not registration time, so order
;; relative to harnesses.clj is not load-bearing.
(runtime/module! runtime :reviewers
                 {:file "reviewers.clj"
                  :spools ['ct.spools/delegation]
                  :after [:skein/spools-delegation]
                  :required? true})

;; --- chime notification engine + this repo's attention rules ----------------
;; Chime is vocabulary-agnostic; attention.clj contributes this repo's attention
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
                 {:file "attention.clj"
                  :spools ['skein.spools/chime 'ct.spools/agent-run]
                  :after [:skein/spools-chime :skein/spools-shuttle]
                  :required? true})

;; --- kanban board + devflow tracker binding ---------------------------------
;; kanban is an external git-distributed spool. The board loads independently;
;; the process-lifetime tracker seed below joins it to devflow after both spools
;; are active. Kanban v16 deliberately exposes no tracker unbind operation.
(runtime/module! runtime :skein/spools-kanban
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]
                  :required? true})
(runtime/module! runtime :kanban/tracker
                 {:file "kanban_tracker.clj"
                  :spools ['codethread/kanban 'codethread/devflow]
                  :after [:skein/spools-kanban :skein/spools-devflow]
                  :required? true})

;; --- cron timer engine + the NVD scan job -----------------------------------
;; Cron is a generic weaver timer engine. Its collected open-kind and lifecycle
;; declarations own job publication and scheduling; nvd_scan.clj contributes a
;; job through `defjob`, so it is ordered after cron.
(runtime/module! runtime :skein/spools-cron
                 {:ns 'skein.spools.cron
                  :spools ['skein.spools/cron]
                  :required? true})
;; The NVD scan job is its own module (not part of config.clj) so config_test's
;; direct config.clj load never registers the job or seeds against real gh.
(runtime/module! runtime :nvd-scan
                 {:file "nvd_scan.clj"
                  :spools ['skein.spools/cron]
                  :after [:skein/spools-cron :skein/spools-kanban]
                  :required? true})

;; --- config queries/helpers and hand-authored workflows ---------------------
;; config.clj authors named queries with defquery and public validation helpers
;; reused by workflows.clj. It is required: a guarded-but-optional module would
;; drop the query surface.
(runtime/module! runtime :config
                 {:file "config.clj"
                  :required? true})
;; workflows.clj authors the land/story definitions and the delegate-pipeline and
;; macros-demo patterns. The land policy op is its sibling below. It reuses
;; config.clj's public validation helper, so it orders after :config as well as
;; the workflow and delegation spools.
(runtime/module! runtime :workflows
                 {:file "workflows.clj"
                  :spools ['skein.spools/workflow 'ct.spools/delegation]
                  :after [:skein/spools-workflow :skein/spools-delegation
                          :config]
                  :required? true})
;; workflows_land.clj authors the narrow land policy op: the merge lock, the
;; merge queue in front of it, and the kanban lane moves. It is a sibling rather
;; than part of workflows.clj because the policy outgrew the definitions it
;; drives; it references no Var there, so the order below is for readers.
(runtime/module! runtime :workflows-land
                 {:file "workflows_land.clj"
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows]
                  :required? true})

;; The code executor's lifecycle resource scans ready gates when opened. It must load after
;; workflows.clj so every persisted code/fn symbol owned there can resolve on
;; the initial scan.
(runtime/module! runtime :skein/spools-code
                 {:ns 'skein.spools.executors.code
                  :spools ['skein.spools/workflow]
                  :after [:skein/spools-workflow :workflows]
                  :required? true})

;; The subagent gate executor activates last: its lifecycle resource runs an initial gate
;; scan, so every harness alias harnesses.clj registers must already exist or a
;; durable ready gate would be stamped gate/error on every cold start.
(runtime/module! runtime :skein/spools-treadle
                 {:ns 'ct.spools.executors.subagent
                  :spools ['ct.spools/agent-run]
                  :after [:skein/spools-shuttle :skein/spools-workflow
                          :harnesses :workflows]
                  :required? true})
