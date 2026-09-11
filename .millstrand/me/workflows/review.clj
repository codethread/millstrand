(ns me.workflows.review
  "The repository's shared final code review, before landing."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [ct.spools.delegation :as agents]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.spools.workflow :as workflow]
            [me.workflows.support :as support]))

(s/def ::non-blank-string support/non-blank-string?)
(s/def ::feature ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::review-target ::non-blank-string)
(s/def ::review-id ::non-blank-string)
(s/def ::commit-range
  (s/and ::non-blank-string
         #(boolean (re-matches #"(?i)[0-9a-f]{40}\.\.[0-9a-f]{40}" %))))
(s/def ::files (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::change-context
  (s/and :ct.spools.delegation/change-context
         #(s/valid? ::commit-range (:commit-range %))
         #(s/valid? ::files (:files %))))
(s/def ::review-params
  (s/keys :req-un [::feature ::branch ::worktree
                   ::review-target ::review-id ::change-context]
          :opt-un [::card]))

(defn handoff-instruction
  "Describe the repository review handoff with the caller's known work identity."
  [params]
  (str (format-alpha/prose
        "
          Commit and push the validated branch, then start the shared final review
          with `strand workflow start <new-review-run-id> --workflow review --params`
          and the following JSON. Fill the task id, unique review id, and current
          merge-base-to-HEAD range and changed files. Review targets a task, never
          a kanban card. Review owns the full roster and final validation; land
          owns merging and finishing the card. Complete the handoff step after
          starting review.
        " {})
       "\n\n"
       (json/write-str
        (merge (select-keys params [:feature :branch :worktree :card])
               {:review-target "<task-id>" :review-id "<unique-review-id>"
                :change-context {:commit-range "<base-sha>..<head-sha>"
                                 :files ["<changed-file>"]}}))))

(defn- review-specs
  "Build and validate the gate-ready change-review specs for one review run."
  [{:keys [review-target review-id change-context]}]
  (let [target (weaver/show (current/runtime) review-target)]
    (when (= "true" (attr-get target :kanban/card))
      (fail! "Change review targets a task strand, never a kanban card"
             {:review-target review-target :kanban/card "true"}))
    (when-not (or (= "true" (attr-get target :kanban/task))
                  (= "task" (attr-get target :kind)))
      (fail! "Change review target must be a task strand"
             {:review-target review-target
              :kanban/task (attr-get target :kanban/task)
              :kind (attr-get target :kind)}))
    (agents/roster-review-specs
     :change-review
     {:target review-target
      :review-id review-id
      :change-context change-context})))

(defn- reviewer-specs
  "Return loop items for the change review fan-out."
  [params]
  (mapv #(assoc % :id (:name %)) (:reviewers (review-specs params))))

(defn- synthesis-specs
  "Return the single synthesis item as a loop collection."
  [params]
  [(assoc (:synthesizer (review-specs params)) :id :synthesis)])

(defn- item-attr
  "Read a string-keyed roster attribute from a loop item."
  [item key]
  (get (:attrs item) key))

(workflow/defworkflow review
  (format-alpha/prose
   "
     Run the shared final review for implemented work before landing.

     Story and fix hand committed, validated branches here. A pull request
     is optional. This workflow owns the full change-review roster,
     findings resolution, and validation of the resulting pushed HEAD. It
     finishes with reviewed work; it does not itself authorize a merge.
   " {})
  {:entrypoints #{:start} :param-spec ::review-params :defaults {}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Review: " branch))
   {:attributes {"workflow/family" "review"}}
   (support/card-gate :review-card "Move the optional card into review" []
                      "me.workflows.card-actions/review!")
   (support/shell-gate :ci-green "Validate the pushed branch before review" [:review-card]
                       (fn [{:keys [branch]}]
                         (support/sh-gate support/land-quality-gate-script "review-quality" branch))
                       5400
                       "Commit and push the clean branch. Fix failed checks, then clear gate/error to retry.")
   (workflow/gate :reviewer
                  (fn [{:keys [item]}] (str "Review change: " (:name item)))
                  :subagent
                  :depends-on [:ci-green]
                  :loop {:each reviewer-specs}
                  :attributes {"agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
                               "agent-run/prompt" (fn [{:keys [item]}] (:prompt item))
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "panel/blackboard" (fn [{:keys [item]}]
                                                    (item-attr item "panel/blackboard"))
                               "review/roster" (fn [{:keys [item]}]
                                                 (item-attr item "review/roster"))
                               "panel/pass" (fn [{:keys [item]}]
                                              (item-attr item "panel/pass"))
                               "review/focus" (fn [{:keys [item]}]
                                                (item-attr item "review/focus"))
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: run one declared change-review seat and append its
                                  findings to the review target.
                                "
                                {})})
   (workflow/gate :review-synthesis
                  "Synthesize the change review findings"
                  :subagent
                  :depends-on [:reviewer]
                  :loop {:each synthesis-specs}
                  :attributes {"agent-run/harness" (fn [{:keys [item]}] (name (:harness item)))
                               "agent-run/prompt" (fn [{:keys [item]}] (:prompt item))
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "panel/blackboard" (fn [{:keys [item]}]
                                                    (item-attr item "panel/blackboard"))
                               "review/roster" (fn [{:keys [item]}]
                                                 (item-attr item "review/roster"))
                               "panel/pass" (fn [{:keys [item]}]
                                              (item-attr item "panel/pass"))
                               "panel/synthesis" (fn [{:keys [item]}]
                                                   (item-attr item "panel/synthesis"))
                               "workflow/instruction"
                               (format-alpha/prose
                                "
                                  Machine gate: synthesize this pass's reviewer notes into one
                                  verdict.
                                "
                                {})})

   (workflow/step :resolve-review "Resolve the review findings" :self
                  :depends-on [:review-synthesis]
                  :attributes {"workflow/instruction"
                               (format-alpha/prose
                                "
                                  Read the synthesis on the review task, resolve its findings,
                                  and commit and push repairs. Obtain focused follow-up review
                                  where repairs materially change the reviewed code. Complete
                                  this step when ready for final validation.
                                " {})})
   (support/shell-gate :final-ci-green "Validate the reviewed branch HEAD" [:resolve-review]
                       (fn [{:keys [branch]}]
                         (support/sh-gate support/land-quality-gate-script "land-quality" branch))
                       5400
                       "Validate the actual pushed HEAD after review repairs. Fix failures and clear gate/error to retry.")
   (workflow/step :handoff-land "Hand the reviewed work to landing" :self
                  :depends-on [:final-ci-green]
                  :attributes {"workflow/instruction"
                               (fn [params]
                                 (str
                                  (format-alpha/prose
                                   "
                                     Record the resolved review and validation on the work task.
                                     When the user's instruction includes landing, start
                                     `strand workflow start <new-land-run-id> --workflow land
                                     --params` with the JSON below. Land accepts an existing PR
                                     or resolves one from the branch, and reuses this review.
                                     Otherwise report the reviewed work for the user's decision.
                                     Complete this review run after recording the handoff.
                                   " {})
                                  "\n\n"
                                  (json/write-str
                                   (select-keys params [:feature :branch :worktree :card]))))})))
