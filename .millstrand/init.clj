;; Startup entrypoint for the repo's canonical coordination world.
;;
;; Dependency spools remain independent runtime modules. Repository-owned
;; Clojure lives under me/: me/config.clj selects the runtime policy into one
;; owner-complete contribution, and me/reviewers layers the repository lens over
;; the shared reviewer catalog.
;;
;; Gitignored init.local.clj is layered after this file on startup and refresh.
;; Read docs/reference.md before changing this config, and smoke-test changes in
;; a disposable world first.
(require '[millhouse.config.bootstrap :as codethread]
         '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is a workspace dependency. Its `millstrand/defop!` forms publish
;; the CLI partition and its lifecycle seed owns the failure glossary.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries})

(runtime/module! runtime :millstrand/spools-unsafe-text-search
                 {:ns 'millstrand.spools.unsafe-text-search})

;; Register shared identity, Workflow, Harnesses, aliases, reviewers, Kanban,
;; and landing before repository-specific policy. Executor activation remains
;; deliberately last.
(codethread/register! runtime)

;; Devflow is an ordinary workspace dependency. Its contribution is the stage
;; `defworkflow` entries its load collects.
(runtime/module! runtime :millstrand/spools-devflow
                 {:ns 'millhouse.devflow
                  :after [:millhouse/workflow]
                  :required? true})

;; --- repo policy over the peer spools ---------------------------------------
;; Codethread publishes shared harness tools, aliases, and review lenses. This
;; repository keeps its adapter election and workspace-specific policy local.
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'millhouse.devflow-kanban-adapter
                  :after [:millstrand/spools-devflow
                          :millhouse/kanban
                          :millhouse/workflow]
                  :required? true})
(runtime/module! runtime :millhouse/config
                 {:ns 'millhouse.config
                  :after [:millstrand/spools-batteries
                          :devflow/kanban-adapter]
                  :required? true})
;; --- chime notification engine ---------------------------------------------
;; Chime is vocabulary-agnostic. The local attention rules are selected later by
;; :me/config, while init.local.clj binds each developer's notifier.
(runtime/module! runtime :millhouse/chime
                 {:ns 'millhouse.chime
                  :required? true})

;; --- cron timer engine ------------------------------------------------------
;; Cron owns job publication and scheduling. :me/config selects the local NVD
;; scan job after this module is available.
(runtime/module! runtime :millhouse/cron
                 {:ns 'millhouse.cron
                  :required? true})
;; --- repository config ------------------------------------------------------
;; All repository-owned config is loaded and selected by this one module.
(runtime/module! runtime :me/config
                 {:file "me/config.clj"
                  :after [:millstrand/spools-batteries
                          :millhouse/workflow
                          :millhouse/kanban
                          :millhouse/land
                          :millhouse/chime
                          :millhouse/cron
                          :millhouse/config]
                  :required? true})

;; Reviewer declarations are repository policy over Harnesses' shared
;; reviewer kind and alias catalog.
(runtime/module! runtime :me/reviewers
                 {:file "me/agents/reviewers.clj"
                  :after [:me/config
                          :millhouse/harnesses
                          :millhouse/config-reviewers]
                  :required? true})

;; --- repository automatic delivery ----------------------------------------
;; The workflow and its dispatcher are repository policy. Keep the dispatcher
;; after its workflow and shared Harnesses aliases, so configuration validates
;; a fully registered delivery surface before it admits a card.
(runtime/module! runtime :me/auto-run-workflows
                 {:file "me/auto_run_workflows.clj"
                  :after [:me/config
                          :millhouse/land]
                  :required? true})
(runtime/module! runtime :me/auto-run
                 {:file "me/auto_run.clj"
                  :after [:me/auto-run-workflows
                          :millhouse/harnesses]
                  :required? true})

;; Activate the consolidated providers after every workflow definition so the
;; executor's initial scan can resolve all persisted gate symbols.
(runtime/module! runtime :millhouse/workflow-providers
                 {:ns 'millhouse.workflow.spool
                  :after [:millhouse/workflow
                          :millhouse/land
                          :me/config
                          :me/auto-run-workflows]
                  :required? true})

;; Activate the sole Harnesses agent executor after all shared and local
;; aliases, reviewers, workflows, and provider executors are reconciled.
(codethread/register-executor!
 runtime [:devflow/kanban-adapter
          :millhouse/config
          :me/config
          :me/reviewers
          :me/auto-run
          :millhouse/workflow-providers])
