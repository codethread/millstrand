(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime :identity-source
                 {:ns 'millhouse.identity})
(runtime/module! runtime :kanban-source
                 {:ns 'millhouse.kanban
                  :after [:identity-source]})
