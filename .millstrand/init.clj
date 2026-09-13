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
(require '[ct.spools.codethread.bootstrap :as codethread]
         '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is a workspace dependency. Its `millstrand/defop!` forms publish
;; the CLI partition and its lifecycle seed owns the failure glossary.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries})

(runtime/module! runtime :millstrand/spools-unsafe-text-search
                 {:ns 'millstrand.spools.unsafe-text-search})

;; Register shared identity, Workflow, Harnesses, aliases, and reviewers before
;; repository-specific policy. Executor activation remains deliberately last.
(codethread/register! runtime)

;; Devflow is an ordinary workspace dependency. Its contribution is the stage
;; `defworkflow` entries its load collects.
(runtime/module! runtime :millstrand/spools-devflow
                 {:ns 'ct.spools.devflow
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- repo policy over the peer spools ---------------------------------------
;; Codethread publishes shared harness tools, aliases, and review lenses. This
;; repository keeps its adapter election and workspace-specific policy local.
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :after [:millstrand/spools-devflow :millstrand/spools-kanban :millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :codethread/config
                 {:ns 'ct.spools.codethread.config
                  :after [:millstrand/spools-batteries
                          :devflow/kanban-adapter]
                  :required? true})
;; --- chime notification engine ---------------------------------------------
;; Chime is vocabulary-agnostic. The local attention rules are selected later by
;; :me/config, while init.local.clj binds each developer's notifier.
(runtime/module! runtime :millhouse/spools-chime
                 {:ns 'millhouse.spools.chime
                  :required? true})

;; --- kanban board -------------------------------------------------------------
;; Kanban is the Millhouse root that owns this workspace's board surface.
(runtime/module! runtime :millstrand/spools-kanban
                 {:ns 'millhouse.spools.kanban
                  :required? true})
;; --- cron timer engine ------------------------------------------------------
;; Cron owns job publication and scheduling. :me/config selects the local NVD
;; scan job after this module is available.
(runtime/module! runtime :millhouse/spools-cron
                 {:ns 'millhouse.spools.cron
                  :required? true})
;; --- repository config ------------------------------------------------------
;; All repository-owned config is loaded and selected by this one module.
(runtime/module! runtime :me/config
                 {:file "me/config.clj"
                  :after [:millstrand/spools-batteries
                          :millhouse/spools-workflow
                          :millstrand/spools-kanban
                          :millhouse/spools-chime
                          :millhouse/spools-cron
                          :codethread/config]
                  :required? true})

;; Reviewer declarations are repository policy over Harnesses' shared
;; reviewer kind and alias catalog.
(runtime/module! runtime :me/reviewers
                 {:file "me/agents/reviewers.clj"
                  :after [:me/config
                          :millstrand/spools-harnesses
                          :codethread/config-reviewers]
                  :required? true})

;; Activate the consolidated providers after every workflow definition so the
;; executor's initial scan can resolve all persisted gate symbols.
(runtime/module! runtime :millhouse/spools-workflow-providers
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow :me/config]
                  :required? true})

;; Activate the sole Harnesses agent executor after all shared and local
;; aliases, reviewers, workflows, and provider executors are reconciled.
(codethread/register-executor!
 runtime [:devflow/kanban-adapter
          :codethread/config
          :me/config
          :me/reviewers
          :millhouse/spools-workflow-providers])
