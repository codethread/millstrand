(ns ct.workflows.explore
  "The open-ended exploration workflow (family `explore`)."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]
            [ct.workflows.support :as support]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::topic ::non-blank-string)
(s/def ::card ::non-blank-string)

;; explore: the open-ended exploration workflow (family `explore`)
;;
;; The zero-ceremony light mode: its whole value is that exploration always
;; leaves a kanban card + worktree trail a later session can resume. Three
;; strands only — leave a trail, explore under note discipline, decide the
;; thread's fate at a human checkpoint. No reviews, no gates, no
;; continuations: the checkpoint choices carry the follow-through as
;; instruction text, and the run ends there.
;; ---------------------------------------------------------------------------

(s/def ::topic ::non-blank-string)
(s/def ::explore-params (s/keys :req-un [::topic] :opt-un [::card]))

(workflow/defworkflow! explore
  "Run the open-ended EXPLORE workflow (family \"explore\").

  The zero-ceremony mode for exploration with an agent: claim a kanban
  card and a worktree so the thread survives the session, explore under
  the kanban note discipline, and end at a human checkpoint that
  promotes the findings to a devflow brief, parks the thread, or
  abandons it. The card + worktree pair plus the note trail is the
  deliverable a later session resumes from. Params: `topic` (required),
  `card` (optional existing card id to claim instead of pouring one)."
  {:entrypoints #{:start}
   :param-spec ::explore-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [topic]}] (str "Explore: " topic))
   {:attributes {"workflow/family" "explore"}}
   (workflow/step :leave-trail
                  (fn [{:keys [topic]}] (str "Card + worktree trail for: " topic))
                  :self
                  :attributes {"workflow/action-ref" "explore.leave-trail"
                               "workflow/instruction"
                               (fn [{:keys [card]}]
                                 (format-alpha/reflow
                                  (str
                                   (if card
                                     (format
                                      "|Claim card `%s` for this exploration: `strand kanban
                                       |claim %s --owner <name> --branch <branch> --worktree
                                       |<path>`, with a worktree created for the branch."
                                      card card)
                                     "|Pour a kanban card for the topic (`strand kanban add`)
                                      |and claim it with owner, branch, and a worktree created
                                      |for the branch.")
                                   "\n|Then stamp the trail on this step before completing
                                    |it: `strand update <this-step-id> --attributes
                                    |'{\"explore/card\":\"<card-id>\",
                                    |\"explore/worktree\":\"<path>\"}'` — the stamp is the
                                    |run's durable link to the trail; a later session
                                    |resumes from the card it names, not from prose.")))})
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
                                   :description
                                   (format-alpha/reflow
                                    "|The exploration earned feature work: distill the note
                                     |trail into a brief and start a devflow intake run,
                                     |naming the card as source context.")}
                                  {:key :park
                                   :label "Park the thread"
                                   :description
                                   (format-alpha/reflow
                                    "|Worth keeping, not worth driving now: note the resume
                                     |point on the card and leave the card and worktree in
                                     |place for a later session.")}
                                  {:key :abandon
                                   :label "Abandon the thread"
                                   :description
                                   (format-alpha/reflow
                                    "|A dead end: note why on the card, `strand kanban
                                     |finish <card> --outcome abandoned`, and remove the
                                     |worktree.")}]
                        :attributes {"workflow/decision-point" "explore-thread-fate"})))

;; ---------------------------------------------------------------------------
