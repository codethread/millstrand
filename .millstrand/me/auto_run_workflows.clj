(ns me.auto-run-workflows
  "Repository-owned delivery contract for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.land.autonomous :as autonomous]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]))

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::card ::text)
(s/def ::feature ::text)
(s/def ::branch ::text)
(s/def ::worktree ::text)
(s/def ::seat ::text)
(s/def ::effort ::text)
(s/def ::params (s/keys :req-un [::card ::feature ::branch ::worktree ::seat ::effort]))

(defn- shell-gate
  "Return one bounded shell gate running in the assigned worktree."
  [id title dependencies argv timeout failure-instruction]
  (workflow/gate id title :shell
                 :depends-on dependencies
                 :attributes {"shell/argv" argv
                              "shell/cwd" (fn [{:keys [worktree]}] worktree)
                              "shell/timeout-secs" timeout}
                 failure-instruction))

(defn- delivery
  "Return the one repository-approved autonomous delivery workflow."
  []
  (let [failure-instruction
        (fn [{:keys [card]}] (autonomous/failure-policy card))]
    (apply
     workflow/workflow
     "Deliver automatically"
     [(workflow/step
       :implement "Implement and verify the assigned feature" :self
       (fn [{:keys [card]}]
         (format-alpha/prose
          "
            Read card {card}, its epic and tasks, and AGENTS.md. Claim the card
            with your provided identity, branch, worktree and Harnesses run ID.
            Work in the provided worktree; do not create a second one. Implement
            the scoped outcome yourself and record evidence on the card's tasks.

            Use disposable fixtures for mutations. Add focused regression tests
            when behavior or ownership boundaries warrant them. Commit the work
            and complete this step only after focused verification passes. The
            following gates own quality, PR publication, CI, review, and landing.

            {failure-policy}
          " {:card card :failure-policy (autonomous/failure-policy card)})))
      (shell-gate :quality "Pass repository quality checks" [:implement]
                  ["make" "land-quality"] 7200 failure-instruction)
      (workflow/step
       :prepare-pr "Publish the exact change with its review package" :self
       :depends-on [:quality]
       (fn [{:keys [card branch]}]
         (format-alpha/prose
          "
            Push {branch} and create or update its ready-for-review PR against
            main. Put the PR URL and concise handoff on card {card}; retain
            detailed verification evidence on its task. Complete this step only
            after publishing the committed revision and review package. The next
            gate verifies the exact PR head before shared landing reviews it.
          " {:card card :branch branch})))
      (shell-gate :ci "Wait for the PR checks" [:prepare-pr]
                  (fn [{:keys [branch]}]
                    ["gh" "pr" "checks" branch "--watch" "--fail-fast"])
                  2100 failure-instruction)
      (workflow/gate
       :review-card "Move the verified feature into review" :code
       :depends-on [:ci]
       :attributes {"code/fn" "millhouse.spools.land.card-actions/review-card!"
                    "code/params" (fn [{:keys [card]}] {:card card})}
       failure-instruction)
      (workflow/call :land #'autonomous/autonomous-land {}
                     :depends-on [:review-card]
                     :title "Review and hand off autonomous landing")])))

(workflow/defworkflow! auto-full-land
  "Implement, validate, review, and hand landing to an independent finisher."
  {:entrypoints #{:start}
   :param-spec ::params
   :defaults {}
   :param-docs {:card "Kanban card receiving this automatic delivery."
                :feature "Feature title captured at dispatcher admission."
                :branch "Prepared feature branch."
                :worktree "Absolute prepared worktree path."
                :seat "Resolved Harnesses seat receipt."
                :effort "Resolved Harnesses effort receipt."}}
  (delivery))
