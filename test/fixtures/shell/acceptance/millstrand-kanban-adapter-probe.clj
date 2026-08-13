(do
  (require '[clojure.data.json :as json]
           '[millstrand.api.current.alpha :as current]
           '[millstrand.api.graph.alpha :as graph]
           '[millstrand.api.patterns.alpha :as patterns]
           '[millstrand.api.runtime.alpha :as runtime]
           '[millstrand.api.weaver.alpha :as weaver]
           '[millstrand.core.weaver.bins :as bins]
           '[millstrand.core.weaver.spool-sync :as spool-sync])
  (let [rt (current/runtime)
        status (runtime/status rt)
        syncs (:spools (spool-sync/approved-spool-syncs rt))]
    (println
     (json/write-str
      {:ops (mapv :name (weaver/ops rt))
       :queries (vec (keys (graph/queries rt)))
       :patterns (mapv :name (patterns/patterns rt))
       :bins (mapv :name (:bins (bins/list-bins rt)))
       :lifecycle-modules (mapv name (keys (or (:lifecycle/outcomes status) {})))
       :source-status (into {}
                            (map (fn [[module outcome]]
                                 [(name module) (:source/status outcome)])
                                 (:module/outcomes status)))
       :spools (into {}
                     (map (fn [[lib result]]
                            [(str lib) (-> (select-keys result
                                                       [:git/url :git/tag :git/sha :root :status :kind])
                                           (update :status name)
                                           (update :kind name))])
                          syncs))}))))
