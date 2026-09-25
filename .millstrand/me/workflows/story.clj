(ns me.workflows.story
  "Story refactoring with frozen reviews and returning module-wave joins."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.handoff :as handoff]
            [me.workflows.story-review :as review]
            [me.workflows.story-waves]
            [me.workflows.support :as support]
            [millhouse.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]))

(s/def ::feature support/non-blank-string?)
(s/def ::branch support/non-blank-string?)
(s/def ::worktree support/non-blank-string?)
(s/def ::card support/non-blank-string?)
(s/def ::module support/non-blank-string?)
(s/def ::reviewer-harness support/non-blank-string?)
(s/def ::params
  (s/keys :req-un [::feature ::branch ::worktree ::module ::reviewer-harness]
          :opt-un [::card]))
(defn- resolve-review [id dependency]
  (workflow/step
   id "Resolve the adversarial findings" :self :depends-on [dependency]
   (format-alpha/prose
    "
      Read this returning review's child gate harness/result in the run subgraph.
      Require an actual result naming the frozen base/head. Resolve or explicitly
      adjudicate every finding and record that resolution on this step. A reviewer
      reporting findings is not a failed process, but unresolved findings block
      progress. Commit repairs and obtain fresh review for material changes.
    ")))

(defn- validate-wave [dependencies]
  (workflow/step
   :validate "Validate this module wave" :self :depends-on dependencies
   (format-alpha/prose
    "
      Remove this module from quality.api-form/pending for an API conversion.
      Run focused cold tests. Regenerate API docs after docstring changes.
      Commit and record the final revision and verification receipt here.
      This child returns to the parent join; it does not launch full review
      or take custody of the branch, card, landing or cleanup.
    ")))

(workflow/defworkflow story-keep
  "Validate the accepted concern split without repeating its review."
  {:entrypoints #{:call} :param-spec ::params :defaults {}}
  (workflow/workflow "Keep the reviewed split" (validate-wave [])))

(workflow/defworkflow story-fold
  "Fold a reviewed split, freeze the changed revision and review the fold."
  {:entrypoints #{:call} :param-spec ::params :defaults {}}
  (workflow/bind-defers
   (workflow/workflow
    "Fold the reviewed split"
    (workflow/step
     :fold "Fold into one story-ordered file" :self
     (format-alpha/prose
      "
        Merge into one file: real public bodies first, private clusters in reading
        order, leaf mechanics last. Remove stale aliases and declarations; check
        name collisions. Public-surface tests must pass unchanged through the fold.
      "))
    (review/freeze-step :freeze-fold [:fold] "fold")
    (review/review-defer :fold-review :freeze-fold)
    (resolve-review :resolve-fold :fold-review)
    (validate-wave [:resolve-fold]))
   {:fold-review #{:story-review}}))

(workflow/defworkflow story-wave
  "Refactor one module, review the split, then choose and validate its final form."
  {:entrypoints #{:start} :param-spec ::params :defaults {}}
  (workflow/bind-defers
   (workflow/workflow
    (fn [{:keys [module]}] (str "Story wave: " module))
    (workflow/step
     :split "Write the per-concern split" :self
     (format-alpha/prose
      "
        Write the split first so the compiler exposes coupling. Public bodies in
        alpha show sequencing, fan-out and joins; internal/<concern> files own
        mechanics. Follow the Clojure story-file guidance and SPEC-003.C19a.
        Change only this wave's module. Other modules have their own serial child.
      "))
    (workflow/step
     :tests "Test through the public surface" :self :depends-on [:split]
     "Keep the public-surface behavior lock, not tests of extracted internals. Run focused cold tests and record the command/result.")
    (review/freeze-step :freeze-split [:tests] "split")
    (review/review-defer :split-review :freeze-split)
    (resolve-review :resolve-split :split-review)
    (workflow/step
     :measure "Measure the possible single-file fold" :self :depends-on [:resolve-split]
     "Record total content lines minus namespace overhead. Around 500 lines is the tipping point; measure before choosing.")
    (workflow/checkpoint
     :fold-decision "Fold back or keep the split?" :depends-on [:measure] :kind :agent
     :choices [{:key :fold-back :label "Fold into a story-ordered file"}
               {:key :keep-split :label "Keep concern modules"}]
     :attributes {"workflow/instruction"
                  "Choose using the recorded measurement. The next step performs the choice; choosing does not finish the wave."})
    (workflow/defer
     :final-form "Complete the chosen module form" :depends-on [:fold-decision]
     :attributes {"workflow/instruction"
                  (format-alpha/prose
                   "
                     Read fold-decision's workflow/outcome. For fold-back fill
                     with story-fold; for keep-split fill with story-keep.
                     Pass feature, module, branch, worktree and reviewer-harness
                     explicitly. The fold changes code and earns a fresh frozen
                     review; keeping the accepted split does not repeat it.
                     This returning procedure must finish before the wave closes.
                   ")}))
   {:split-review #{:story-review} :final-form #{:story-fold :story-keep}}))

(workflow/defworkflow story
  "Implement a Story, join its module waves, then hand off full review."
  {:entrypoints #{:start} :param-spec ::params :defaults {}
   :param-docs {:feature "Feature intent and work identity."
                :module "Initial module scope for intent review."
                :branch "Actual feature branch, never a placeholder."
                :worktree "Absolute branch worktree."
                :card "Optional existing work card."
                :reviewer-harness "Explicit review seat outside the driver's model family."}}
  (workflow/bind-defers
   (workflow/workflow
    (fn [{:keys [feature]}] (str "Story: " feature))
    {:attributes {"workflow/family" "story"}}
    (workflow/step
     :identify-modules "Record the modules this feature touches" :self
     "Record the finite module inventory and feature intent on this step before implementation.")
    (workflow/step
     :overall-changes "Implement the feature behavior" :self :depends-on [:identify-modules]
     "Make the behavior changes before refactor waves. For a pure form conversion, record that no behavior change is needed.")
    (review/freeze-step :freeze-intent [:overall-changes] "intent")
    (review/review-defer :intent-review :freeze-intent)
    (resolve-review :resolve-intent :intent-review)
    (workflow/step
     :classify "Classify the finite module-wave collection" :self :depends-on [:resolve-intent]
     (format-alpha/prose
      "
        Read the module inventory and record the large-change modules as
        story/modules, a distinct vector. Small churn earns no wave. Pass this
        vector explicitly to the next defer, including an empty vector when none
        qualify. Do not launch extra Story runs: durable receipts name each
        serial child, and the join verifies their actual completion.
      "))
    (workflow/defer
     :waves "Supply the finite module waves" :depends-on [:classify]
     :attributes {"workflow/instruction"
                  (format-alpha/prose
                   "
                     Read story/modules on classify. Fill this defer with
                     story-waves and explicit modules, feature, branch, worktree,
                     reviewer-harness and optional card. No params are inherited.

                     Drive the child named by each launch slot's story/child
                     receipt. The next child is materialized only after that child
                     completes; do not launch waves separately. When waiting,
                     inspect these receipts in the parent subgraph and await the
                     recorded child run. The final join returns all receipts.
                   ")})
    (workflow/step
     :validate "Validate the complete Story" :self :depends-on [:waves]
     (format-alpha/prose
      "
        Read the module-wave children and joins from the run subgraph. Record their
        ids, final revisions and test receipts on the work task. Run the relevant
        checks under the shared lock, commit and push the complete branch.
      "))
    (handoff/prepare :prepare-review [:validate] "millstrand-review"
                     "Carry the exact branch and completed module-wave receipts into full review.")
    (handoff/launch-gate :accept-review :prepare-review "millstrand-review" "story-review"))
   {:intent-review #{:story-review} :waves #{:story-waves}}))
