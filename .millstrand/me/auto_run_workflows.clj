(ns me.auto-run-workflows
  "Repository-owned delivery contract for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.codethread.auto-run-land :as autonomous]
            [millhouse.spools.land.support :as land-support]
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

(def ^:private verify-pr-script
  "Verify the ready PR and its immutable quality-marked head."
  (str "set -eu\n"
       "branch=\"$1\"\n"
       "if [ \"$(git branch --show-current)\" != \"$branch\" ]; then\n"
       "  echo \"expected branch $branch\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "if [ -n \"$(git status --porcelain --untracked-files=all)\" ]; then\n"
       "  echo \"worktree is dirty\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "head=$(git rev-parse --verify HEAD^{commit})\n"
       "upstream=$(git rev-parse --verify @{upstream}^{commit})\n"
       "marker=$(cat \"$(git rev-parse --git-path millstrand-land-quality-head)\")\n"
       "if [ \"$head\" != \"$upstream\" ] || [ \"$head\" != \"$marker\" ]; then\n"
       "  echo \"quality-marked HEAD is not the pushed branch head\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "pr=$(gh pr view \"$branch\" --json isDraft,state,baseRefName,headRefName,headRefOid --jq '\n"
       "  [.isDraft, .state, .baseRefName, .headRefName, .headRefOid] | @tsv')\n"
       "IFS=\"$(printf '\\t')\" read -r draft state base pr_branch pr_head <<EOF\n"
       "$pr\n"
       "EOF\n"
       "if [ \"$draft\" != false ] || [ \"$state\" != OPEN ] || [ \"$base\" != main ] || [ \"$pr_branch\" != \"$branch\" ] || [ \"$pr_head\" != \"$head\" ]; then\n"
       "  echo \"PR is not an open ready main PR at the quality-marked branch head\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "body=$(gh pr view \"$branch\" --json body --jq .body)\n"
       "case \"$body\" in\n"
       "  *'## Summary'*'## Walkthrough'*'## Verification'*) ;;\n"
       "  *) echo \"PR is missing its required review package\" >&2; exit 1 ;;\n"
       "esac\n"))

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
      (workflow/step
       :prepare-pr "Publish the exact change with its review package" :self
       :depends-on [:implement]
       (fn [{:keys [card branch]}]
         (format-alpha/prose
          "
            Publish the committed {branch} and establish its upstream before
            quality runs:

            ```sh
            git push -u origin {branch}
            ```

            Create or update its ready-for-review PR against main. Its nonempty
            Markdown body must contain `## Summary`, `## Walkthrough`, and
            `## Verification`. Put the PR URL and concise handoff on card {card};
            retain detailed verification evidence on its task. Complete this step
            only after publishing the committed revision and review package.
          " {:card card :branch branch})))
      (land-support/shell-gate
       :quality "Pass repository quality checks" [:prepare-pr]
       (fn [{:keys [branch]}]
         (land-support/sh-gate land-support/land-quality-gate-script
                               "auto-run-quality" branch))
       5400 failure-instruction)
      (land-support/shell-gate :ci "Wait for the PR checks" [:quality]
                               (fn [{:keys [branch]}]
                                 (land-support/pr-checks-argv "required" branch))
                               2100 failure-instruction)
      (land-support/shell-gate
       :verify-pr "Verify the ready PR and review package" [:ci]
       (fn [{:keys [branch]}]
         (land-support/sh-gate verify-pr-script "auto-run-verify-pr" branch))
       300 failure-instruction)
      (workflow/gate
       :review-card "Move the verified feature into review" :code
       :depends-on [:verify-pr]
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
