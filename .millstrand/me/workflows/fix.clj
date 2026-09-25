(ns me.workflows.fix
  "The light bug-fix workflow (family `fix`)."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.workflow :as workflow]
            [me.workflows.support :as support]
            [me.workflows.handoff :as handoff]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::subject ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)

(s/def ::fix-params (s/keys :req-un [::subject ::branch ::worktree]
                            :opt-un [::card]))

(defn- fix-claim-instruction
  "Return the claim-trail instruction for a fix run's card mode."
  [{:keys [card branch worktree]}]
  (if card
    (format-alpha/reflow
     (format
      "|Claim card `%s` for this fix: `strand kanban claim %s --owner <name>
       |--branch %s --worktree %s`, with the worktree created for the branch.
       |Then stamp the card on this step before completing it: `strand update
       |<this-step-id> --attributes '{\"fix/card\":\"%s\"}'` — the stamp is the
       |run's durable link to the card the review handoff names."
      card card branch worktree card))
    (format-alpha/reflow
     (format
      "|Pour a kanban card for the bug (`strand kanban add`) and claim it with
       |owner, branch `%s`, and worktree `%s` created for the branch. Then
       |stamp the card on this step before completing it: `strand update
       |<this-step-id> --attributes '{\"fix/card\":\"<card-id>\"}'` — the stamp
       |is the run's durable link to the card the review handoff names."
      branch worktree))))

(workflow/defworkflow fix
  "Run one light bug fix through the full Millstrand review handoff."
  {:entrypoints #{:start}
   :param-spec ::fix-params
   :defaults {}
   :example {:subject "strand list drops --limit on named queries"
             :branch "fix-list-limit"
             :worktree "/abs/path/to/millstrand-src__fix-list-limit"
             :card "ab1cd"}
   :param-docs {:subject "One-line statement of the bug being fixed."
                :branch "Fix branch the claim step creates."
                :worktree
                (format-alpha/reflow
                 "|Absolute worktree path the claim step creates for the
                  |branch; the docs-check gate runs `make docs-check` there.")
                :card "Optional existing kanban card id to pick up."}}
  (workflow/workflow
   (fn [{:keys [subject]}] (str "Fix: " subject))
   {:attributes {"workflow/family" "fix"}}
   (workflow/step :claim-trail
                  (fn [{:keys [subject]}] (str "Card + worktree trail for: " subject))
                  :self
                  :attributes {"workflow/action-ref" "fix.claim-trail"}
                  fix-claim-instruction)
   (workflow/step :implement
                  (fn [{:keys [subject]}] (str "Fix: " subject))
                  :self
                  :depends-on [:claim-trail]
                  :attributes {"workflow/action-ref" "fix.implement"}
                  (fn [_]
                    (format-alpha/reflow
                     "|Create a kanban task under the card for the slice you choose;
                      |the agent doing the work owns that task's scope. Fix the bug
                      |in the worktree with a regression lock where feasible: a
                      |focused test that fails before the fix and passes after (cold
                      |run green). Note findings and decisions on that doing-task as
                      |you go — the notes are the handover. Commit the work to the
                      |branch before completing.")))
   (workflow/step :docs-sync
                  (fn [_] "Sync specs and CLAUDE.md with the changed behavior")
                  :self
                  :depends-on [:implement]
                  :attributes {"workflow/action-ref" "fix.docs-sync"}
                  (fn [_]
                    (format-alpha/reflow
                     "|The doc discipline, judged here and proven by the next
                      |gate: when shipped behavior changed, the relevant root
                      |spec in devflow/specs/ gets its delta, and CLAUDE.md stays
                      |in sync when the working surface moved. When nothing
                      |shipped changed, record that judgment explicitly in a note
                      |before completing — the judgment is recorded either way.")))
   (workflow/gate :docs-check
                  (fn [_] "Prove the docs gates green in the fix worktree")
                  :shell
                  :depends-on [:docs-sync]
                  :attributes {"workflow/action-ref" "fix.docs-check"
                               "shell/argv" ["flock" "-w" "180" "/tmp/millstrand-test.lock" "make" "docs-check"]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 1200}
                  (format-alpha/reflow
                   "|Machine gate: `make docs-check` runs in the fix worktree —
                    |the AGENTS.md budget, regenerated api docs with no diff,
                    |and a clean docs-site build. A failure stamps `gate/error`
                    |with captured output: fix the docs, commit, then remove the
                    |stamp (`strand update <gate-id> --attributes
                    |'{\"gate/error\":null}') to re-run."))
   (workflow/step :validate "Validate the committed fix" :self
                  :depends-on [:docs-check]
                  "Run focused cold tests. Commit and push the clean branch; record commands and HEAD on the work task.")
   (handoff/prepare :prepare-review [:validate] "millstrand-review"
                    "Read fix/card from claim-trail when card was created during this run. Use that actual id in all child params.")
   (handoff/launch-gate :accept-review :prepare-review "millstrand-review" "fix-review")))
