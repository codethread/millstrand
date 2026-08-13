(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :kanban-source
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]})
