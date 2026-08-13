(do
  (require '[millstrand.api.current.alpha :as current]
           '[millstrand.api.runtime.alpha :as runtime])
  (let [rt (current/runtime)]
    (println
     (pr-str
      (runtime/module! rt :kanban-adapter
                       {:file "adapter.clj"
                        :spools ['codethread/kanban]
                        :after [:kanban-source]})))))
