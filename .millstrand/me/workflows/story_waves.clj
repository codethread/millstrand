(ns me.workflows.story-waves
  "Durable launch and join receipts for finite Story module waves."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::modules (s/coll-of support/non-blank-string? :kind vector? :distinct true))

(defn launch!
  "Start/reuse each recorded module wave, serialize its entry, and retain receipts."
  [{:keys [key params]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.story-waves/launch!" key)
        source (evidence/dependency! gate)
        modules (attr-get source :story/modules)]
    (require-valid! ::modules modules "Record a finite distinct story/modules vector")
    (loop [remaining (seq (map-indexed vector modules)) previous nil receipts []]
      (if-let [[index module] (first remaining)]
        (let [run-id (str "wave-" (:id source) "-" index)
              request (assoc params :module module)
              roots #(weaver/list rt [:and [:= [:attr "workflow/run-id"] run-id]
                                      [:= [:attr "story/source"] (:id source)]] {})]
          (when (empty? (roots))
            (workflow/start! run-id :story-wave request
                             {:root-attributes {"story/source" (:id source)
                                                "story/request" request}}))
          (let [root (evidence/single! (roots) "Expected the accepted wave root")
                _ (when-not (= request (evidence/data (attr-get root :story/request)))
                    (fail! "Module wave request changed" {:run-id run-id}))
                entry (evidence/single!
                       (filterv #(= "true" (attr-get % :story/entry))
                                (:strands (graph/subgraph rt [(:id root)])))
                       "Expected one module-wave entry")
                receipt {:run-id run-id :root (:id root) :module module}]
            (when previous (workflow/bond! (:root previous) (:id entry)))
            (recur (next remaining) receipt (conj receipts receipt))))
        (do
          (weaver/update! rt (:id gate) {:attributes {:story/children receipts}})
          {:children receipts})))))

(defn join!
  "Require all recorded module waves to finish before the parent can hand off."
  [{:keys [key]}]
  (let [gate (evidence/gate! "me.workflows.story-waves/join!" key)
        launch (-> gate evidence/dependency! evidence/dependency!)
        children (evidence/data (attr-get launch :story/children))]
    (when-not (vector? children)
      (fail! "Missing module-wave launch receipt" {:launch (:id launch)}))
    (doseq [{:keys [run-id root]} children]
      (let [strand (weaver/show (current/runtime) root)]
        (when-not (and (= run-id (attr-get strand :workflow/run-id))
                       (= "closed" (:state strand)) (workflow/done? run-id)
                       (every? #(not= "active" (:state %))
                               (:strands (graph/subgraph (current/runtime) [root]))))
          (fail! "Module wave has not completed" {:run-id run-id :root root}))))
    {:status "joined" :children children}))
