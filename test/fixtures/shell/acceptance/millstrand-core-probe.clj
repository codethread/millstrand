(do
  (require '[clojure.data.json :as json]
           '[millstrand.api.current.alpha :as current]
           '[millstrand.api.runtime.alpha :as runtime])
  (let [status (runtime/status (current/runtime))]
    (println
     (json/write-str
      {:basis-fingerprint (:basis-fingerprint status)
       :module-keys (mapv name (keys (:modules status)))
       :last-refresh (select-keys (:last-refresh status) [:status :mode])}))))
