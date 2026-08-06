(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :spools ['millstrand.spools/batteries]})
(runtime/module! runtime :millstrand/spools-workflow
                 {:ns 'millstrand.spools.workflow
                  :spools ['millstrand.spools/workflow]
                  :after [:millstrand/spools-batteries]})
(runtime/module! runtime :millstrand/spools-workflow-cli
                 {:ns 'millstrand.spools.workflow.cli
                  :spools ['millstrand.spools/workflow]
                  :after [:millstrand/spools-workflow]})
