(ns me.workflows.ralph
  "Millstrand's Ralph handoff through the shared final review."
  (:require [ct.spools.codethread.ralph :as ralph]
            [me.workflows.review :as review]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(workflow/defworkflow ralph-iterate
  "Implement and validate one feature card, then hand it to shared review.

  Keeps the shared Ralph card loop and epic judgment. The repository handoff
  starts review before land, using the feature, branch, and worktree recorded
  when this iteration claimed its card."
  (select-keys ralph/ralph-iterate [:entrypoints :param-spec :defaults])
  (update ralph/ralph-iterate :steps
          (fn [steps]
            (mapv (fn [step]
                    (if (= :finish-feature (:id step))
                      (assoc step
                             :title "Hand the validated feature to shared review"
                             :attributes
                             (assoc (:attributes step) "workflow/instruction"
                                    (str "Read the chosen feature, branch, and worktree from this "
                                         "iteration's claim note and substitute them below. "
                                         "Leave the feature and epic open until landing finishes. "
                                         (review/handoff-instruction
                                          {:feature "<feature-id>" :card "<feature-id>"
                                           :branch "<branch>" :worktree "<absolute-worktree>"}))))
                      step))
                  steps))))

(defn select-review-handoff!
  "Route Ralph iterations through the repository's review handoff.

  Like the Devflow kanban adapter, use a lifecycle seed to repoint the shared
  name in the direct registry layer while retaining the shared module's tools."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (workflow/register-workflow! :ralph-iterate 'me.workflows.ralph/ralph-iterate))
  {:repointed :ralph-iterate})

(lifecycle/defseed review-handoff
  "Select the local Ralph handoff after the shared Ralph module is available."
  {:apply 'me.workflows.ralph/select-review-handoff!})
