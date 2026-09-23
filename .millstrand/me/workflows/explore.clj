(ns me.workflows.explore
  "The open-ended exploration workflow (family `explore`)."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]
            [me.workflows.support :as support]
            [me.workflows.evidence :as evidence]
            [me.workflows.handoff :as handoff]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::topic ::non-blank-string)
(s/def ::card ::non-blank-string)

(s/def ::authorization ::non-blank-string)
(s/def ::decision-input (s/keys :req-un [::authorization]))
(s/def ::explore-params (s/keys :req-un [::topic] :opt-un [::card]))

(defn accept!
  "Validate acknowledged outcome custody against the exact exploration trail."
  [{:keys [key]}]
  (let [gate (evidence/gate! "me.workflows.explore/accept!" key)
        action (evidence/dependency! gate)
        decision (evidence/dependency! action)
        trail (-> decision evidence/dependency! evidence/dependency!)
        receipt (evidence/data (attr-get action :explore/receipt))]
    (when-not (and (= (:outcome receipt) (attr-get decision :workflow/outcome))
                   (= (:card receipt) (attr-get trail :explore/card))
                   (= (:worktree receipt) (attr-get trail :explore/worktree))
                   (every? support/non-blank-string?
                           ((juxt :card :worktree :owner :evidence :artifact) receipt)))
      (fail! "Exploration outcome lacks accepted custody for its recorded trail"
             {:action (:id action) :receipt receipt}))
    receipt))

(workflow/defworkflow explore
  "Explore a topic, then execute and record its user-chosen disposition."
  {:entrypoints #{:start}
   :param-spec ::explore-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [topic]}] (str "Explore: " topic))
   {:attributes {"workflow/family" "explore"}}
   (workflow/step
    :leave-trail "Record the exploration trail" :self
    (fn [{:keys [card]}]
      (format-alpha/prose
       "
         {claim}

         Record explore/card and explore/worktree on this step. Use the exact
         claimed card and branch worktree, not a cwd-derived guess. The later
         disposition reads this durable trail. Preserve branch and worktree until
         the next owner accepts custody; do not give the exploring worker cleanup.
       " {:claim (if card
                   (format "Inspect ownership and claim existing card `%s` if permitted." card)
                   "Create a topic card and claim it with your identity and branch worktree.")})))
   (workflow/step :explore
                  (fn [{:keys [topic]}] (str "Explore: " topic))
                  :self
                  :depends-on [:leave-trail]
                  :attributes {"workflow/action-ref" "explore.explore"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Explore under the kanban note discipline: findings,
                                   |decisions, and dead ends go on the card's doing-task as
                                   |you go — the notes are what outlives the session.
                                   |Sketches and artifacts commit to the worktree branch.
                                   |Complete this step when the thread reaches a natural
                                   |stop, with a summary note of where it stands."))})
   (workflow/checkpoint :thread-fate
                        (fn [{:keys [topic]}] (str "Decide the fate of: " topic))
                        :depends-on [:explore]
                        :kind :human
                        :choices [{:key :promote-to-devflow-brief
                                   :label "Promote to a devflow brief"
                                   :input {:spec ::decision-input :doc "Actual user decision reference."}
                                   :description
                                   (format-alpha/reflow
                                    "|The exploration earned feature work: distill the note
                                     |trail into a brief and start a devflow intake run,
                                     |naming the card as source context.")}
                                  {:key :park
                                   :label "Park the thread"
                                   :input {:spec ::decision-input :doc "Actual user decision reference."}
                                   :description
                                   (format-alpha/reflow
                                    "|Worth keeping, not worth driving now: note the resume
                                     |point on the card and leave the card and worktree in
                                     |place for a later session.")}
                                  {:key :abandon
                                   :label "Abandon the thread"
                                   :input {:spec ::decision-input :doc "Actual user decision reference."}
                                   :description
                                   (format-alpha/reflow
                                    "|A dead end: note why on the card, `strand kanban
                                     |finish <card> --outcome abandoned`, and hand cleanup
                                     |to an independent owner after settlement.")}]
                        :attributes
                        {"workflow/decision-point" "explore-thread-fate"
                         "workflow/instruction"
                         "Ask the user which fate they authorize and record the conversation reference. A human label or supplied identity does not prove approval."})
   (workflow/step
    :follow-through "Carry out the chosen exploration outcome" :self
    :depends-on [:thread-fate]
    (format-alpha/prose
     "
       Read thread-fate's workflow/outcome and the original leave-trail attributes
       through this run's subgraph. Do not infer a card or worktree from cwd.

       For promotion, write the brief and prepare an intake handoff naming the
       source card, branch and worktree. Obtain the receiving coordinator's
       acknowledgment or the actual intake run/root acceptance receipt.

       For park, record findings and a precise resume point on the card; preserve
       branch and worktree. Obtain acknowledgment from the owner keeping custody.

       For abandon, record the user's reason and card disposition. Hand cleanup
       to an independent coordinator with an explicit resource inventory. Do not
       remove your own worktree or a resource a live/unsettled worker still uses.
       Acceptance transfers cleanup custody; it does not claim deletion happened.

       Record explore/receipt here with outcome, card, worktree, owner, evidence
       (acceptance reference), and artifact (brief, resume note or abandonment
       disposition/cleanup inventory). No receipt means the decision is unfinished.
     "))
   (workflow/gate
    :accept-outcome "Verify accepted exploration custody" :code
    :depends-on [:follow-through]
    :attributes {"code/fn" "me.workflows.explore/accept!"
                 "delivery/key" #(handoff/key-for "explore" %)
                 "code/params" #(hash-map :key (handoff/key-for "explore" %))}
    (format-alpha/prose
     "
       Check the recorded acceptance matches the chosen outcome and exact trail.
       The prior step must obtain the receiving owner's acknowledgment, not just
       an intended launch. Missing receipts block this code gate.
       Parked resources remain intact; abandonment cleanup belongs to the named
       independent owner after settlement and explicit deletion permission.
     "))))

;; ---------------------------------------------------------------------------
