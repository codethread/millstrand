(ns me.workflows.story-review
  "Returning adversarial review of explicitly frozen Story revisions."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [fail!]]))

(s/def ::feature support/non-blank-string?)
(s/def ::module support/non-blank-string?)
(s/def ::branch support/non-blank-string?)
(s/def ::worktree support/non-blank-string?)
(s/def ::reviewer-harness support/non-blank-string?)
(s/def ::base #(and (string? %) (boolean (re-matches #"[0-9a-f]{40}" %))))
(s/def ::head ::base)
(s/def ::focus #{"intent" "split" "fold"})
(s/def ::params
  (s/keys :req-un [::feature ::module ::branch ::worktree ::reviewer-harness
                   ::base ::head ::focus]))

(defn verify-revision!
  "Reject a dirty, wrong-branch or stale revision before an adversarial review."
  [{:keys [base head worktree] :as params}]
  (evidence/unchanged! params)
  (when-not (= base (evidence/git! worktree "merge-base" base head))
    (fail! "Story base must be an ancestor of its frozen head" {:base base :head head}))
  (select-keys params [:base :head :branch :worktree]))

(workflow/defworkflow story-review
  "Review one committed Story revision and return the findings."
  {:entrypoints #{:call} :param-spec ::params :defaults {}}
  (workflow/workflow
   "Frozen Story review"
   (workflow/gate
    :verify-revision "Verify the exact review input" :code
    :attributes {"code/fn" "me.workflows.story-review/verify-revision!"
                 "code/params" identity}
    "Reject dirty or changed HEAD. Re-freeze and fill a fresh review with exact base/head after repair.")
   (workflow/gate
    :review "Adversarial review of the frozen revision" :agent
    :depends-on [:verify-revision]
    :attributes
    {"harness/alias" (fn [params] (:reviewer-harness params))
     "harness/cwd" (fn [params] (:worktree params))
     "harness/prompt"
     (fn [{:keys [feature module base head focus]}]
       (format-alpha/prose
        "
          Review only; do not modify files or workflow strands.
          Feature: {feature}. Module: {module}. Lens: {focus}.
          Inspect only `git diff {base} {head}`; both revisions are immutable.
          Do not fetch or substitute origin/main or current HEAD.

          For intent, challenge the approach against the feature's specs and
          requested behavior. For split/fold, challenge module boundaries,
          forwarding husks, hidden sequencing, name collisions and internal tests.
          Read the public-surface tests and report concrete findings with paths
          and lines. Your final response must name base/head, then the verdict
          and full findings. Successful execution does not mean no findings.
        " {:feature feature :module module :base base :head head :focus focus}))}
    "Wait for the reviewer. Its harness/result is the durable input to the parent's resolution step.")))

(defn freeze-step
  "Build a commit/freeze boundary before a runtime-filled review."
  [id dependencies focus]
  (workflow/step
   id (str "Commit and freeze " focus " review input") :self :depends-on dependencies
   (format-alpha/prose
    "
      Commit all intended changes; require a clean worktree. Record full HEAD
      and its merge-base with origin/main as story/frozen on this step. The next
      returning review takes explicit base/head, branch, worktree, feature,
      module, reviewer-harness and focus={focus}. It inherits no root context.
      Use a reviewer outside your own model family. Never pass mutable refs.
    " {:focus focus})))

(defn review-defer
  "Build the runtime input boundary for one returning frozen review."
  [id dependency]
  (workflow/defer
   id "Supply the frozen review parameters" :depends-on [dependency]
   :attributes {"workflow/instruction"
                (format-alpha/prose
                 "
                   Read story/frozen from the commit/freeze prerequisite. Fill
                   this defer with story-review and explicit feature, module,
                   branch, worktree, reviewer-harness, base, head and focus.
                   Missing or invalid params must leave the defer unfilled.
                   This returning child's gates and join remain in the parent
                   graph; do not independently launch another review workflow.
                 ")}))
