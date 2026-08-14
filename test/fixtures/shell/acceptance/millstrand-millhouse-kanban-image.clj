(do
  (require '[clojure.data.json :as json]
           '[millstrand.api.current.alpha :as current]
           '[millstrand.api.runtime.alpha :as runtime])
  (let [rt (current/runtime)
        result (runtime/module! rt :kanban-source
                                {:ns 'millhouse.spools.kanban
                                 :load :image
                                 :spools ['millhouse.spools/kanban]})]
    (println
     (json/write-str
      {:module-status (:status result)
       :source-status (get-in (runtime/status rt)
                              [:module/outcomes :kanban-source :source/status])}))))
