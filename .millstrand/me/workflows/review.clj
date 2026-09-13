(ns me.workflows.review
  "The repository's shared final code review, before landing."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
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
  (s/keys :req-un [::commit-range ::files]))
(s/def ::review-params
  (s/keys :req-un [::feature ::branch ::worktree
                   ::review-target ::review-id]
          :opt-un [::card ::change-context]))
(s/def ::verification-key ::non-blank-string)
(s/def ::verification-params (s/keys :req-un [::verification-key]))
(s/def ::sha
  (s/and ::non-blank-string
         #(boolean (re-matches #"(?i)[0-9a-f]{40}" %))))
(s/def ::status #{"success"})
(s/def ::base ::sha)
(s/def ::head ::sha)
(s/def ::name ::non-blank-string)
(s/def ::run-id ::non-blank-string)
(s/def ::reviewer (s/keys :req-un [::name ::run-id]))
(s/def ::reviewers
  (s/coll-of ::reviewer :kind vector? :min-count 1))
(s/def ::verdict #{"findings" "no-findings"})
(s/def ::success-evidence
  (s/keys :req-un [::status ::base ::head ::reviewers ::verdict]))

(def ^:private success-prefix "AUTOMATIC_REVIEW_SUCCESS ")

(defn- verification-key
  [{:keys [review-target review-id]}]
  (json/write-str [review-target review-id]))

(defn- require-single!
  [values message data]
  (when-not (= 1 (count values))
    (fail! message (assoc data :matches (mapv :id values))))
  (first values))

(defn- success-evidence
  [gate]
  (let [result (attr-get gate :harness/result)
        first-line (when (string? result) (first (str/split-lines result)))]
    (when-not (and first-line (str/starts-with? first-line success-prefix))
      (fail! "Automatic review result is missing its success sentinel"
             {:gate (:id gate)}))
    (let [evidence (try
                     (json/read-str (subs first-line (count success-prefix))
                                    :key-fn keyword)
                     (catch Exception cause
                       (throw (ex-info "Automatic review success sentinel is not valid JSON"
                                       {:gate (:id gate)}
                                       cause))))]
      (require-valid! ::success-evidence evidence
                      "Automatic review success evidence is invalid"))))

(defn verify-automatic-review!
  "Verify positive structured evidence from this run's automatic review gate.

  The code executor binds the originating runtime but passes only `code/params`.
  Resolve the correlated active verification gate in that runtime, then follow
  its declared dependency to avoid reading a similarly named gate from another
  workflow run. Return compact evidence for `code/result`; fail loudly unless
  the review result begins with the complete success sentinel."
  [params]
  (require-valid! ::verification-params params
                  "Invalid automatic review verification params")
  (let [runtime (current/runtime)
        key (:verification-key params)
        verification-gate
        (require-single!
         (weaver/list runtime
                      [:and
                       [:= :state "active"]
                       [:= [:attr "workflow/gate"] "code"]
                       [:= [:attr "review/role"] "automatic-review-verification"]
                       [:= [:attr "review/verification-key"] key]]
                      {})
         "Automatic review verification gate is not unique"
         {:verification-key key})
        review-gate
        (require-single!
         (->> (graph/outgoing-edges runtime [(:id verification-gate)] "depends-on")
              (keep #(weaver/show runtime (:to_strand_id %)))
              (filter #(= "automatic-review" (attr-get % :review/role)))
              vec)
         "Automatic review dependency is not unique"
         {:verification-gate (:id verification-gate)})
        evidence (success-evidence review-gate)]
    {"status" "verified"
     "review-gate" (:id review-gate)
     "base" (:base evidence)
     "head" (:head evidence)
     "reviewers" (count (:reviewers evidence))
     "verdict" (:verdict evidence)}))

(defn handoff-instruction
  "Describe the repository review handoff with the caller's known work identity."
  [params]
  (format-alpha/prose
   "
     Commit and push the validated branch. Read `strand workflow show review`
     for its parameter contract, then start a new run with
     `strand workflow start <run-id> --workflow review --params <json>`.
     Complete this handoff after starting review.

     Carry forward this work identity:

     {identity:json}
   " {:identity (select-keys params [:feature :branch :worktree :card])}))

(defn- automatic-review-prompt
  "Return the coordinator contract for one frozen Harnesses review pass."
  [{:keys [branch worktree review-target review-id]}]
  (format-alpha/prose
   "
     Orchestrate the repository's automatic review pass for branch `{branch}`
     in `{worktree}`. The review handoff target is the external work/task
     identity `{review-target}` carried into this handoff (pass `{review-id}`).
     It is not a target receiving child writes in this originating runtime.
     Findings remain on the automatic review gate's harness result; the
     following resolution step records them on the work task.

     Before running anything, require both `MILLSTRAND_WORKSPACE` and
     `MILLSTRAND_RUN_ID` to be non-empty. An empty binding is an orchestration
     failure. Every CLI call must use the originating workspace explicitly;
     never derive it from the current directory or rely on CLI defaults.

     On any Git preflight, review setup, reviewer await, reviewer evidence, or
     synthesis failure, do not stop or mutate the serving run or its gates.
     Instead, the final response must begin with one line in exactly this form,
     using a concrete reason:

     ```text
     AUTOMATIC_REVIEW_FAILURE <concrete reason>
     ```

     Follow that line with useful failure details. Do not include the
     `AUTOMATIC_REVIEW_SUCCESS` sentinel. After repairing the branch, recover by
     starting a new review run with a new unique `review-id`; never complete a
     failed gate by hand.

     First verify that the checked-out branch is `{branch}`, the worktree is
     clean, and these three full commit SHAs are identical:

     - `git rev-parse --verify \"{head-ref}\"`
     - `git rev-parse --verify \"{upstream-ref}\"`
     - the content of
       `$(git rev-parse --git-path millstrand-land-quality-head)`

     Report any mismatch as an automatic review failure. The preceding quality
     gate applies only to that pushed HEAD; reviewing another commit would
     invalidate its evidence.

     Resolve the immutable review range:

     ```sh
     review_head=$(git rev-parse --verify \"{head-ref}\")
     review_base=$(git merge-base origin/main \"$review_head\")
     strand --workspace \"$MILLSTRAND_WORKSPACE\" agent review --cwd \"{worktree}\" --base \"$review_base\" --branch \"$review_head\" --label PR
     ```

     Do not substitute a mutable branch name for either SHA. Require the
     review command to return `status=scheduled` and at least one run. Await
     every returned run separately, repeating the bounded await until it
     reports settlement:

     ```sh
     strand --timeout 60s --workspace \"$MILLSTRAND_WORKSPACE\" await --query agent-run-settled --param run-id=RUN_ID --min-count 1 --timeout-secs 40
     ```

     A normal await/query timeout, including one while `agent show` still
     reports a running child, is pending rather than failure. Continue bounded
     awaits for that same run and do not stop or kill it. Once settlement is
     reported, inspect the run:

     ```sh
     strand --timeout 60s --workspace \"$MILLSTRAND_WORKSPACE\" agent show RUN_ID
     ```

     Settlement alone is not success. Every shown run must have
     `status=stopped`, `substatus=completed`, `settled=true`, and a non-blank
     `result`. Only an actually failed child or invalid evidence fails this
     review; a stopped child without `substatus=completed` is a failed review,
     never a pass.

     After every reviewer succeeds, synthesize their results. Your final
     response must begin with one line in exactly this form, using JSON string
     values and one entry per successful reviewer:

     ```text
     AUTOMATIC_REVIEW_SUCCESS {success-example}
     ```

     Follow that line with the consolidated review. Include the frozen base and
     head SHAs, every reviewer name and run id, and one deduplicated P1/P2
     verdict. Preserve concrete paths and lines. P1/P2 findings belong to the
     following resolve-review step; failed or missing reviewer evidence blocks
     this gate.
   " {:branch branch
      :worktree worktree
      :review-target review-target
      :review-id review-id
      :head-ref "HEAD^{commit}"
      :upstream-ref "@{upstream}^{commit}"
      :success-example
      (json/write-str
       (array-map "status" "success"
                  "base" "FULL_SHA"
                  "head" "FULL_SHA"
                  "reviewers" [(array-map "name" "REVIEWER"
                                          "run-id" "RUN_ID")]
                  "verdict" "findings|no-findings"))}))

(workflow/defworkflow review
  "Review and validate implemented work without authorizing a merge."
  {:entrypoints #{:start}
   :param-spec ::review-params
   :defaults {}
   :param-docs {:feature "Work identity carried into landing."
                :branch "Branch containing the committed change."
                :worktree "Absolute path to the branch's worktree."
                :card "Optional kanban card to move into review."
                :review-target "External work/task identity carried into handoff, not a same-runtime child-write target. Findings stay on gate/result; resolution records them on the work task."
                :review-id "Non-blank identifier unique to this review pass; use a new value for every retry."
                :change-context "Optional captured range and changed files."}}
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
   (workflow/gate :automatic-review "Run and synthesize the frozen Harnesses review"
                  :agent
                  :depends-on [:ci-green]
                  :attributes {"harness/alias" "coordinator"
                               "harness/cwd" (fn [{:keys [worktree]}] worktree)
                               "harness/prompt" automatic-review-prompt
                               "review/role" "automatic-review"
                               "review/verification-key" verification-key}
                  (format-alpha/prose
                   "
                     Review orchestration is automatic. A failed child review or
                     invalid result is reported with AUTOMATIC_REVIEW_FAILURE and
                     blocks verification. Repair, then start a new review run with a
                     unique review-id; never complete a gate by hand.
                   " {}))

   (workflow/gate :verify-automatic-review
                  "Verify automatic review completion evidence"
                  :code
                  :depends-on [:automatic-review]
                  :attributes {"code/fn" "me.workflows.review/verify-automatic-review!"
                               "code/params" (fn [params]
                                               {:verification-key
                                                (verification-key params)})
                               "review/role" "automatic-review-verification"
                               "review/verification-key" verification-key}
                  (format-alpha/prose
                   "
                     Automatic review evidence is checked in-process. A missing
                     or malformed success sentinel leaves this gate failed and
                     blocks review resolution.
                   " {}))

   (workflow/step :resolve-review "Resolve the review findings" :self
                  :depends-on [:verify-automatic-review]
                  (format-alpha/prose
                   "
                     Read the automatic-review gate's `harness/result`. Record its
                     combined verdict and each resolution on the external work/task
                     identity carried in `review-target`. Findings are durably stored
                     on the automatic gate/result; this step records them on the work
                     task. Resolve every P1/P2 finding, then commit and push repairs.
                     Obtain focused follow-up review for material code changes.
                   " {}))
   (support/shell-gate :final-ci-green "Validate the reviewed branch HEAD" [:resolve-review]
                       (fn [{:keys [branch]}]
                         (support/sh-gate support/land-quality-gate-script "land-quality" branch))
                       5400
                       "Validate the actual pushed HEAD after review repairs. Fix failures and clear gate/error to retry.")
   (workflow/step :handoff-land "Hand the reviewed work to landing" :self
                  :depends-on [:final-ci-green]
                  (fn [params]
                    (format-alpha/prose
                     "
                       Record review and validation evidence on the work task.
                       If the user authorized landing, read `strand workflow show land`
                       and start `strand workflow start <run-id> --workflow land
                       --params <json>`. Otherwise report the reviewed work.

                       Carry forward this work identity:

                       {identity:json}
                     " {:identity (select-keys params [:feature :branch :worktree :card])})))))
