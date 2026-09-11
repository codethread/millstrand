(ns me.workflows.land-actions
  "Short, repeatable card updates used by the landing workflows."
  (:require [millhouse.spools.kanban :as kanban]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- card-view [id]
  (let [card (weaver/show (current/runtime) id)]
    (when-not (= "true" (attr-get card :kanban/card))
      (fail! "Landing requires a kanban card" {:card id}))
    card))

(defn review!
  "Move an optional card into review; an already-reviewed card is unchanged."
  [{:keys [card]}]
  (when card
    (case (attr-get (card-view card) :kanban/lane)
      "in_review" nil
      "claimed" (kanban/review! (current/runtime) card)
      (fail! "Landing card must be claimed or in review" {:card card})))
  nil)

(defn rework!
  "Return an optional card to claimed after abort; repeat calls are harmless."
  [{:keys [card]}]
  (when card
    (case (attr-get (card-view card) :kanban/lane)
      "claimed" nil
      "in_review" (kanban/rework! (current/runtime) card)
      (fail! "Aborted landing card must be claimed or in review" {:card card})))
  nil)

(defn finish!
  "Finish an optional card after housekeeping, accepting an existing done result."
  [{:keys [card]}]
  (when card
    (let [view (card-view card)]
      (if (= "closed" (:state view))
        (when-not (= "done" (attr-get view :kanban/outcome))
          (fail! "Landing card closed with a different outcome" {:card card}))
        (kanban/finish! (current/runtime) card {"--outcome" "done"}))))
  nil)
