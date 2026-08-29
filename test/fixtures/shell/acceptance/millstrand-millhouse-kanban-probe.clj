(do
  (require '[clojure.data.json :as json]
           '[millstrand.api.current.alpha :as current]
           '[millstrand.api.graph.alpha :as graph]
           '[millstrand.api.patterns.alpha :as patterns]
           '[millstrand.api.runtime.alpha :as runtime]
           '[millstrand.api.weaver.alpha :as weaver]
           '[millstrand.core.weaver.bins :as bins])
  (let [rt (current/runtime)
        status (runtime/status rt)]
    (println
     (json/write-str
      {:ops (mapv :name (weaver/ops rt))
       :queries (vec (keys (graph/queries rt)))
       :patterns (mapv :name (patterns/patterns rt))
       :bins (mapv :name (:bins (bins/list-bins rt)))
       :basis-fingerprint (:basis-fingerprint status)
       :module-keys (mapv name (keys (:modules status)))
       :last-refresh (select-keys (:last-refresh status) [:status :mode])
       :source-status (into {}
                            (map (fn [[module outcome]]
                                 [(name module) (:source/status outcome)])
                                 (:modules status)))}))))
