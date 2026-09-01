;; Startup entrypoint for the repo's canonical coordination world.
;;
;; Dependency spools remain independent runtime modules. All repository-owned
;; Clojure lives under me/ and is loaded through the single :me/config module.
;; Its sibling namespaces define authoring Vars; me/config.clj selects them into
;; one owner-complete contribution.
;;
;; Gitignored init.local.clj is layered after this file on startup and refresh.
;; Read docs/reference.md before changing this config, and smoke-test changes in
;; a disposable world first.
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is a workspace dependency. Its `millstrand/defop!` forms publish
;; the CLI partition and its lifecycle seed owns the failure glossary.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries})

;; --- workflow engine + shell executor -------------------------------------
;; The engine's collected open-kind and lifecycle declarations own Workflow
;; definition/executor publication and its process-lifetime vocabulary seed.
(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow})
(runtime/module! runtime :millstrand/spools-unsafe-text-search
                 {:ns 'millstrand.spools.unsafe-text-search})
;; Devflow is an ordinary workspace dependency. Its contribution is the stage
;; `defworkflow` entries its load collects.
(runtime/module! runtime :millstrand/spools-devflow
                 {:ns 'ct.spools.devflow
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- peer coordination spools -----------------------------------------------
;; These sibling modules collect owner-complete contributions from source.
;; Named lifecycle resources own their runtime setup and removal.
(runtime/module! runtime :millhouse/spools-identity
                 {:ns 'millhouse.spools.identity
                  :required? true})
(runtime/module! runtime :millstrand/spools-shuttle
                 {:ns 'ct.spools.agent-run
                  :after [:millhouse/spools-identity]
                  :required? true})
(runtime/module! runtime :millstrand/spools-harness-core
                 {:ns 'ct.spools.harness-core
                  :after [:millhouse/spools-identity]
                  :required? true})
(runtime/module! runtime :millstrand/spools-codex-harness
                 {:ns 'ct.spools.codex-harness
                  :after [:millstrand/spools-harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-agent-cli
                 {:ns 'ct.spools.agent-cli
                  :after [:millstrand/spools-harness-core :millstrand/spools-codex-harness]
                  :required? true})
(runtime/module! runtime :millstrand/spools-delegation
                 {:ns 'ct.spools.delegation
                  :after [:millstrand/spools-shuttle]
                  :required? true})
(runtime/module! runtime :millstrand/spools-bench
                 {:ns 'ct.spools.bench
                  :after [:millstrand/spools-shuttle]
                  :required? true})

;; --- repo policy over the peer spools ---------------------------------------
;; Codethread publishes the shared harness tools and seat aliases. This
;; repository keeps reviewer rosters and task/review policy local.
(runtime/module! runtime :codethread/config-agents
                 {:ns 'ct.spools.codethread.agents
                  :after [:millstrand/spools-shuttle]
                  :required? true})
(runtime/module! runtime :codethread/config-help
                 {:ns 'ct.spools.codethread.help
                  :after [:millstrand/spools-batteries]
                  :required? true})
(runtime/module! runtime :codethread/config-devflow
                 {:ns 'ct.spools.codethread.devflow
                  :required? true})
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :after [:millstrand/spools-devflow :millstrand/spools-kanban :millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :codethread/config
                 {:ns 'ct.spools.codethread.config
                  :after [:codethread/config-agents
                          :codethread/config-help
                          :codethread/config-devflow
                          :millstrand/spools-batteries
                          :millstrand/spools-shuttle
                          :millstrand/spools-delegation
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
                          :millstrand/spools-delegation
                          :millstrand/spools-shuttle
                          :millstrand/spools-kanban
                          :millhouse/spools-chime
                          :millhouse/spools-cron
                          :codethread/config]
                  :required? true})

(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; Activate the consolidated providers after every workflow definition so the
;; executors' initial scans can resolve all persisted gate symbols.
(runtime/module! runtime :millhouse/spools-workflow-providers
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow :me/config :codethread/ralph]
                  :required? true})

;; The subagent gate executor activates last: its lifecycle resource runs an initial gate
;; scan, so every harness alias Codethread agents registers must already exist or a
;; durable ready gate would be stamped gate/error on every cold start.
(runtime/module! runtime :millstrand/spools-treadle
                 {:ns 'ct.spools.executors.subagent
                  :after [:millstrand/spools-shuttle :millhouse/spools-workflow
                          :codethread/config :me/config :codethread/ralph]
                  :required? true})
