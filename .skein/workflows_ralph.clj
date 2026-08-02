(ns workflows-ralph
  "The one-card-per-iteration Ralph workflow (family \"ralph\").

  Ralph is the coordinator of its epic: each run orients from live kanban
  state, claims exactly one feature, drives that feature to a landed card, and
  stops at a judgment point that closes the epic only when no feature cards
  remain. The Go binary supplies the polling loop; this workflow owns the work
  discipline inside one iteration. Claim metadata persisted in
  `workflow/context` conforms to `:workflows-ralph/ralph-context`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as format-alpha]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when value is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::epic non-blank-string?)
(s/def ::ralph-iterate-params (s/keys :req-un [::epic]))
(s/def :ralph/feature non-blank-string?)
(s/def :ralph/branch non-blank-string?)
(s/def :ralph/worktree non-blank-string?)
(s/def :ralph/card non-blank-string?)
(s/def ::ralph-context
  (s/and
   (s/keys :req [:ralph/feature :ralph/branch :ralph/worktree :ralph/card])
   #(= (:ralph/feature %) (:ralph/card %))))

(workflow/defworkflow ralph-iterate
  "Run one Ralph iteration for an epic (family \"ralph\").

  The iteration is deliberately one-card wide: orient from the epic's live
  frontier, claim exactly one feature, work its tasks with the repo's note and
  cold-gate discipline, run a quick roster review, hand the branch to the
  registered coordinator `land` workflow, and finish that feature card. The
  final judgment closes the epic only when its feature frontier is empty.
  Params: `epic` (required epic strand id)."
  {:entrypoints #{:start}
   :param-spec ::ralph-iterate-params
   :defaults {}
   :example {:epic "epic-id"}
   :param-docs {:epic "Epic strand id whose feature cards this iteration drives."}}
  (workflow/workflow
   (fn [{:keys [epic]}] (str "Ralph iteration: " epic))
   {:attributes {"workflow/family" "ralph"
                 "ralph/epic" (fn [{:keys [epic]}] epic)}}
   (workflow/step :orient
                  (fn [{:keys [epic]}] (str "Orient Ralph on epic " epic))
                  :self
                  :attributes {"workflow/action-ref" "ralph.orient"
                               "workflow/instruction"
                               (fn [{:keys [epic]}]
                                 (format-alpha/reflow
                                  (format
                                   "|Read `strand kanban card %s`, then run `strand ready --query
                                    |kanban-epic-pending --param epic=%s`. This live frontier is
                                    |the source of truth for the next feature card; do not choose
                                    |from memory or from a stale prompt. After choosing a feature,
                                    |run `strand ready --query kanban-feature-work --param
                                   |feature=<feature-id>` to see its direct task frontier."
                                   epic epic)))})
   (workflow/step :claim-feature
                  (fn [{:keys [epic]}] (str "Claim exactly one feature under " epic))
                  :self
                  :depends-on [:orient]
                  :attributes {"workflow/action-ref" "ralph.claim-feature"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Choose exactly ONE ready feature card from the epic
                                   |frontier. Claim it with your owner, branch, and worktree:
                                   |`strand kanban claim <feature-id> --owner <name> --branch
                                   |<branch> --worktree <absolute-path>`. Record the chosen
                                   |feature id and the branch/worktree on this doing-task, then
                                   |persist all four values on the run root before completing
                                   |this step with one mutation:
                                   |`strand workflow complete <run-id> --context
                                   |'{\"ralph/feature\":\"<feature-id>\",\"ralph/branch\":\"<branch>\",
                                   |\"ralph/worktree\":\"<worktree>\",\"ralph/card\":\"<feature-id>\"}'`.
                                   |Do not use `workflow next` here: it cannot carry this context.
                                   |Do not claim a second feature in this iteration."))})
   (workflow/step :work-tasks
                  (fn [_] "Work the claimed feature's ready tasks")
                  :self
                  :depends-on [:claim-feature]
                  :attributes {"workflow/action-ref" "ralph.work-tasks"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Drive the claimed feature one ready task at a time. Read the
                                 |doing-task body and latest note before acting; append decisions,
                                 |findings, and resume points with `strand kanban note` as you go.
                                 |Use the repo's registered workflow or agent surface for real
                                 |delegation, keep sibling file scopes disjoint, verify each
                                 |implemented task yourself, and close it only after its
                                 |validation is green.")})
   (workflow/step :slice-gates
                  (fn [_] "Run cold validation for the claimed feature slice")
                  :self
                  :depends-on [:work-tasks]
                  :attributes {"workflow/action-ref" "ralph.slice-gates"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Run the focused cold test command for every namespace touched
                                 |by the slice (`clojure -M:test <ns...>`), then the relevant
                                 |blocking quality gates. Warm output is not a Done-when gate.
                                 |Fix failures in the claimed worktree, commit the validated
                                 |slice, and re-run the checks before completing this step.")})
   (workflow/step :quick-review
                  (fn [_] "Run the quick roster review")
                  :self
                  :depends-on [:slice-gates]
                  :attributes {"workflow/action-ref" "ralph.quick-review"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Run a quick registered roster review over the feature's doing
                                 |task, never its kanban card: `strand agent review <task-id>
                                 |--roster change-review --cwd <worktree> --base origin/main`.
                                 |Read and resolve every finding, record the verdict on the task,
                                 |and re-run the slice's cold validation after any fix. The full
                                 |land roster remains the coordinator land workflow's concern.")})
   (workflow/step :handoff-land
                  (fn [_] "Hand the feature branch to coordinator land")
                  :self
                  :depends-on [:quick-review]
                  :attributes {"workflow/action-ref" "ralph.handoff-land"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|Ralph is the epic's coordinator. Start the registered land
                                 |workflow for the claimed feature using the durable values in
                                 |this run's `workflow/context`. First read `strand show <run-id>`
                                 |and validate the map against
                                 |`:workflows-ralph/ralph-context`; require that feature and card
                                 |match. If any value is missing, blank, or inconsistent, fail loudly:
                                 |record the exact context and stop without starting land. Otherwise
                                 |copy those exact values into: `strand workflow start <land-run-id>
                                 |--workflow land --params '{\"feature\":\"<ralph/feature>\",
                                 |\"branch\":\"<ralph/branch>\",\"worktree\":\"<ralph/worktree>\",
                                 |\"card\":\"<ralph/card>\"}'`.
                                 |Drive that run through its sign-off and merge train; do not
                                 |merge directly or treat the Ralph loop as the land policy.")})
   (workflow/step :finish-feature
                  (fn [_] "Finish the landed feature card")
                  :self
                  :depends-on [:handoff-land]
                  :attributes {"workflow/action-ref" "ralph.finish-feature"
                               "workflow/instruction"
                               (format-alpha/reflow
                                "|After the land run has completed its cleanup, re-read this run's
                                 |`workflow/context` and use its validated `ralph/card` value.
                                 |Verify that exact feature card is closed with
                                 |`kanban/outcome=done`. If it is still active, finish it with
                                 |`strand kanban finish <ralph/card> --outcome done`; leave a
                                 |concise summary note on the card's doing-task. If the context is
                                 |missing or does not match the landed card, stop and record the
                                 |mismatch instead of guessing.")})
   (workflow/checkpoint :epic-judgment
                        (fn [{:keys [epic]}]
                          (str "Close " epic " only if no feature cards remain"))
                        :depends-on [:finish-feature]
                        :kind :agent
                        :choices [{:key :close-epic
                                   :label "Close the epic"
                                   :description
                                   (format-alpha/reflow
                                    "|Re-run `strand ready --query kanban-epic-pending --param
                                     |epic=<epic-id>`. Choose this only when the frontier is
                                     |empty: then `strand kanban finish <epic-id> --outcome done`
                                     |and record the evidence in the epic note.")}
                                  {:key :next-iteration
                                   :label "Leave the epic open"
                                   :description
                                   (format-alpha/reflow
                                    "|The epic still has active feature cards. Record the live
                                     |frontier and resume point on the epic, leave it open, and
                                     |end this one-card iteration so the Go loop can start the
                                    |next `ralph-iterate` run.")}]
                        :attributes {"workflow/decision-point" "ralph-epic-judgment"})))
