(ns me.workflows.review
  "Millstrand's full repository review, before shared landing."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.handoff :as handoff]
            [me.workflows.review-evidence]
            [me.workflows.support :as support]
            [millhouse.spools.land.support :as land-support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]))

(s/def ::feature support/non-blank-string?)
(s/def ::branch support/non-blank-string?)
(s/def ::worktree support/non-blank-string?)
(s/def ::card support/non-blank-string?)
(s/def ::review-target support/non-blank-string?)
(s/def ::review-id support/non-blank-string?)
(s/def ::review-params
  (s/keys :req-un [::feature ::branch ::worktree ::review-target ::review-id]
          :opt-un [::card]))

(defn- key-for [params] (handoff/key-for "review" params))

(workflow/defworkflow millstrand-review
  "Dispatch, verify and resolve the full review roster before landing handoff."
  {:entrypoints #{:start}
   :param-spec ::review-params
   :defaults {}
   :param-docs {:feature "Work identity carried into landing."
                :branch "Committed and pushed branch."
                :worktree "Absolute path to the branch worktree."
                :card "Optional kanban card; stays claimed during agent work."
                :review-target "External task receiving review and resolution evidence."
                :review-id "Unique pass identity; use a fresh value after material changes."}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Review: " branch))
   {:attributes {"workflow/family" "millstrand-review"}}
   (land-support/shell-gate
    :ci-green "Validate the pushed branch before review" []
    (fn [{:keys [branch]}]
      (land-support/sh-gate land-support/land-quality-gate-script "review-quality" branch))
    5400 "Commit and push the clean branch. Fix failed checks, then clear gate/error to retry.")
   (workflow/gate
    :dispatch "Freeze and dispatch the review roster" :code :depends-on [:ci-green]
    :attributes {"code/fn" "me.workflows.review-evidence/dispatch!"
                 "delivery/key" key-for
                 "code/params" #(assoc (select-keys % [:branch :worktree]) :key (key-for %))}
    (format-alpha/prose
     "
       Persist the frozen range, roster snapshot and real Harnesses dispatch
       response on this gate. Reuse that receipt on retry. An interrupted dispatch
       without a response requires coordinator reconciliation, not another fanout.
     "))
   (workflow/step
    :wait "Await the dispatched reviewer runs" :self :depends-on [:dispatch]
    (format-alpha/prose
     "
       Read review/dispatch on this step's dispatch prerequisite. Await every
       selection.runs id in the originating workspace with agent-run-settled.
       A bounded timeout is pending, not failure; continue waiting on those ids.
       Do not relaunch, stop reviewers, or replace the dispatch selection.

       Complete only after each run has settled. The next gate independently
       checks real status, result and frozen change. A failed reviewer blocks
       verification; record the failure and request a new review pass after repair.
       A glob-only no-applicable selection needs no wait and is not a review pass.
     "))
   (workflow/gate
    :verify "Verify settled review evidence" :code :depends-on [:wait]
    :attributes {"code/fn" "me.workflows.review-evidence/verify!"
                 "delivery/key" key-for
                 "code/params" #(hash-map :key (key-for %))}
    "Check actual Harnesses runs against dispatch, never a summary sentinel or refreshed roster.")
   (workflow/step
    :synthesize "Synthesize the verified findings" :self :depends-on [:verify]
    (format-alpha/prose
     "
       Read code/result from the verify prerequisite. Deduplicate its actual
       reviewer results into a P1/P2 verdict with paths and lines. Record that
       synthesis on this step, naming the frozen base/head and every run id.
       For no-applicable-reviewer, explicitly record that no reviewer ran;
       do not manufacture a no-findings verdict.
     "))
   (workflow/step
    :resolve-review "Resolve the review findings" :self :depends-on [:synthesize]
    (format-alpha/prose
     "
       Read the synthesis and verification receipts in this run's subgraph.
       Resolve or explicitly adjudicate every P1/P2 finding on review-target.
       Commit and push repairs. Material changes require a fresh frozen review
       pass; record its accepted evidence before completing this resolution.
       Preserve the original selection even for no-applicable-reviewer outcomes.
     "))
   (land-support/shell-gate
    :final-ci-green "Validate the resulting branch HEAD" [:resolve-review]
    (fn [{:keys [branch]}]
      (land-support/sh-gate land-support/land-quality-gate-script "land-quality" branch))
    5400 "Validate the actual pushed HEAD after resolution. Fix failures and clear gate/error to retry.")
   (workflow/checkpoint
    :delivery "Select authorized delivery" :depends-on [:final-ci-green] :kind :agent
    :choices [{:key :land :label "User authorized landing"}
              {:key :report :label "Return validated work to the coordinator"}]
    :attributes {"workflow/instruction"
                 (format-alpha/prose
                  "
                    Record the user's actual authorization reference before
                    choosing land. Without permission choose report. This choice
                    does not authenticate a human or authorize a merge itself.
                  ")})
   (workflow/step
    :prepare-delivery "Prepare accepted delivery custody" :self :depends-on [:delivery]
    :attributes {"handoff/identity" #(select-keys % [:feature :card :branch :worktree])}
    (format-alpha/prose
     "
       Read the delivery checkpoint's recorded outcome, not a remembered prompt.
       For land, prepare handoff/request with workflow=land, explicit params,
       receiving owner and the actual user authorization reference. The launch
       gate reuses handoff-<this-step-id> and records acceptance.

       For report, record handoff/report with owner, evidence (the coordinator's
       acknowledgment reference) and the validated head. Keep the card open and
       resources intact. The next gate validates that terminal handoff receipt.
       Neither outcome means merged or gives cleanup permission.
     "))
   (workflow/gate
    :accept-delivery "Accept delivery custody" :code :depends-on [:prepare-delivery]
    :attributes {"code/fn" "me.workflows.handoff/deliver!"
                 "delivery/key" #(handoff/key-for "review-delivery" %)
                 "code/params" #(hash-map :key (handoff/key-for "review-delivery" %))}
    "Require the recorded report acceptance or the exact accepted Land run before closing.")))
