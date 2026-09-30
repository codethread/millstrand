(ns me.auto-run-workflows
  "Repository-owned delivery contract for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [me.workflows.evidence :as evidence]
            [millhouse.auto-run-land :as autonomous]
            [millhouse.land.support :as land-support]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

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
       "esac\n"
       "printf 'auto-run verified head: %s\\n' \"$head\"\n"))

(defn- start-key
  [{:keys [card]}]
  (str "auto-land-start/" card))

(defn start-land!
  "Start or verify the exact repository Land run for autonomous delivery."
  [{:keys [key card feature branch worktree]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.auto-run-workflows/start-land!" key)
        verification (evidence/dependency! gate)
        output (or (attr-get verification :shell/output) "")
        verified-head (second (re-find #"(?m)^auto-run verified head: ([0-9a-f]{40})$"
                                       output))
        {:keys [head] :as current}
        (evidence/quality-head! {:branch branch :worktree worktree})
        _ (when-not (= verified-head head)
            (fail! "Autonomous Land head differs from verified PR evidence"
                   {:verified verified-head :current current}))
        run-id (str "land-auto-" card "-" head)
        expected {:card card :feature feature :branch branch
                  :worktree worktree :head head}
        roots #(evidence/reusable-land-roots rt run-id)]
    (when (empty? (roots))
      (workflow/start! run-id :land expected))
    (let [root (evidence/single! (roots) "Autonomous Land root missing or ambiguous")
          actual (evidence/data (attr-get root :workflow/context))]
      (when-not (= expected (select-keys actual (keys expected)))
        (fail! "Autonomous Land run does not match the verified candidate"
               {:run-id run-id :expected expected :actual actual
                :definition (attr-get root :workflow/definition-name)}))
      (let [receipt {:run-id run-id :root (:id root) :head head}]
        (weaver/update! rt (:id gate) {:attributes {:auto-run/landing-start receipt}})
        receipt))))

(defn- repository-autonomous-land
  "Bind shared autonomous custody prose to this repository's start receipt."
  []
  (let [definition @#'autonomous/autonomous-land
        receipt-text "the parent `auto-run/landing-start` receipt's exact `:run-id`"
        rewrite
        (fn [step]
          (if-not (#{:review :authorize-land :observe-land} (:id step))
            step
            (update-in
             step [:attributes "workflow/instruction"]
             (fn [instruction]
               (fn [{:keys [card] :as params}]
                 (let [rendered (instruction params)
                       legacy-id (str "land-auto-" card)
                       revised
                       (case (:id step)
                         :review
                         (str/replace
                          rendered
                          (re-pattern
                           (str "(?s)Start or reuse the\\s+exact repository Land run "
                                "`land-auto-.*?never replace an existing run\\."))
                          (str "Read " receipt-text ". It already started or reused "
                               "the repository Land run for the verified candidate. "
                               "Drive that run; never reconstruct an ID from the card "
                               "or replace the recorded run."))

                         :authorize-land
                         (-> rendered
                             (str/replace legacy-id receipt-text)
                             (str/replace "squash message" "merge message"))

                         :observe-land
                         (str/replace rendered legacy-id receipt-text))]
                   (when (or (= rendered revised)
                             (str/includes? revised legacy-id)
                             (and (= :authorize-land (:id step))
                                  (str/includes? revised "squash message")))
                     (fail! "Shared autonomous instruction no longer exposes its repository policy seam"
                            {:step (:id step)}))
                   revised))))))]
    (update definition :steps #(mapv rewrite %))))

(def repository-land-handoff
  "Shared custody phases with Millstrand's receipt-bound Land instruction."
  (repository-autonomous-land))

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
       :start-land "Start repository Land at the verified HEAD" :code
       :depends-on [:verify-pr]
       :attributes {"code/fn" "me.auto-run-workflows/start-land!"
                    "delivery/key" start-key
                    "code/params" #(assoc (select-keys % [:card :feature :branch :worktree])
                                          :key (start-key %))}
       "Start or verify land-auto-<card>-<head> and persist auto-run/landing-start as the authoritative custody receipt.")
      (workflow/call :land #'repository-land-handoff {}
                     :depends-on [:start-land]
                     :title "Review and hand off autonomous landing")])))

(workflow/defworkflow! auto-full-land
  "Implement, validate, review, and hand local landing to an independent finisher."
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
