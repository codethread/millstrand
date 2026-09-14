(ns me.workflows.review
  "Millstrand's full repository review, before shared landing."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.spools.land.support :as land-support]
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
(s/def ::status ::non-blank-string)
(s/def ::base ::sha)
(s/def ::head ::sha)
(s/def ::name ::non-blank-string)
(s/def ::run-id ::non-blank-string)
(s/def ::successful-reviewer
  (s/and (s/keys :req-un [::name ::run-id])
         #(= #{:name :run-id} (set (keys %)))))
(s/def ::reviewers
  (s/coll-of ::successful-reviewer :kind vector? :min-count 1))
(s/def ::verdict #{"findings" "no-findings"})
(s/def ::reason (s/nilable ::non-blank-string))
(s/def ::repo-root ::non-blank-string)
(s/def ::source #{"branch"})
(s/def ::base-sha ::sha)
(s/def ::merge-base ::sha)
(s/def ::tip ::sha)
(s/def ::tip-sha ::sha)
(s/def ::paths (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::change
  (s/and (s/keys :req-un [::source ::repo-root ::base ::base-sha
                          ::merge-base ::tip ::tip-sha ::paths])
         #(= #{:source :repo-root :base :base-sha :merge-base
               :tip :tip-sha :paths}
             (set (keys %)))
         #(= (:base %) (:base-sha %) (:merge-base %))
         #(= (:tip %) (:tip-sha %))))
(s/def ::id ::non-blank-string)
(s/def ::seat ::non-blank-string)
(s/def ::reviewer ::non-blank-string)
(s/def ::selection-run
  (s/and (s/keys :req-un [::id ::reviewer ::seat])
         #(= #{:id :reviewer :seat} (set (keys %)))))
(s/def ::runs (s/coll-of ::selection-run :kind vector?))
(s/def ::skip
  (s/and (s/keys :req-un [::reviewer ::reason])
         #(= #{:reviewer :reason} (set (keys %)))
         #(= "glob-mismatch" (:reason %))))
(s/def ::skips (s/coll-of ::skip :kind vector?))
(s/def ::selection
  (s/and (s/keys :req-un [::status ::reason ::change ::runs ::skips])
         #(= #{:status :reason :change :runs :skips} (set (keys %)))))
(s/def ::scheduled-selection
  (s/and ::selection
         #(= "scheduled" (:status %))
         #(nil? (:reason %))
         #(seq (:runs %))))
(s/def ::no-applicable-selection
  (s/and ::selection
         #(= "skipped" (:status %))
         #(= "no-matching-reviewers" (:reason %))
         #(empty? (:runs %))
         #(seq (:skips %))))
(s/def ::success-evidence
  (s/and (s/keys :req-un [::status ::base ::head ::selection
                          ::reviewers ::verdict])
         #(= #{:status :base :head :selection :reviewers :verdict}
             (set (keys %)))
         #(= "success" (:status %))
         #(s/valid? ::scheduled-selection (:selection %))
         #(let [change (get-in % [:selection :change])]
            (and (= (:base %) (:base change))
                 (= (:head %) (:tip change))))))
(s/def ::no-applicable-reviewer-evidence
  (s/and (s/keys :req-un [::base ::head ::selection])
         #(= #{:base :head :selection} (set (keys %)))
         #(s/valid? ::no-applicable-selection (:selection %))
         #(let [change (get-in % [:selection :change])]
            (and (= (:base %) (:base change))
                 (= (:head %) (:tip change))))))

(def ^:private success-prefix "AUTOMATIC_REVIEW_SUCCESS ")
(def ^:private no-applicable-prefix
  "AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER ")

(defn- verification-key
  [{:keys [review-target review-id]}]
  (json/write-str [review-target review-id]))

(defn- require-single!
  [values message data]
  (when-not (= 1 (count values))
    (fail! message (assoc data :matches (mapv :id values))))
  (first values))

(defn- parse-evidence
  [gate first-line prefix description]
  (let [evidence (try
                   (json/read-str (subs first-line (count prefix))
                                  :key-fn keyword)
                   (catch Exception cause
                     (throw (ex-info (str "Automatic review " description
                                          " sentinel is not valid JSON")
                                     {:gate (:id gate)}
                                     cause))))]
    (require-valid! (case description
                      "success" ::success-evidence
                      "no-applicable-reviewer"
                      ::no-applicable-reviewer-evidence)
                    evidence
                    (str "Automatic review " description
                         " evidence is invalid"))))

(defn- completion-evidence
  [gate]
  (let [result (attr-get gate :harness/result)
        first-line (when (string? result) (first (str/split-lines result)))]
    (cond
      (and first-line (str/starts-with? first-line success-prefix))
      (assoc (parse-evidence gate first-line success-prefix "success")
             :outcome "reviewed")

      (and first-line (str/starts-with? first-line no-applicable-prefix))
      (assoc (parse-evidence gate first-line no-applicable-prefix
                             "no-applicable-reviewer")
             :outcome "no-applicable-reviewer")

      :else
      (fail! "Automatic review result is missing a valid completion sentinel"
             {:gate (:id gate)}))))

(defn- distinct-values?
  [values]
  (= (count values) (count (distinct values))))

(defn- require-complete-roster-selection!
  [runtime evidence]
  (let [active-reviewers (mapv :name (reviewers/reviewers runtime))
        selected-runs (get-in evidence [:selection :runs])
        selected-run-ids (mapv :id selected-runs)
        selected-reviewers (mapv :reviewer selected-runs)
        skipped-reviewers (mapv :reviewer (get-in evidence
                                                  [:selection :skips]))
        represented-reviewers (into selected-reviewers skipped-reviewers)]
    (when-not (distinct-values? selected-run-ids)
      (fail! "Scheduled review selection contains duplicate run IDs"
             {:selected-run-ids selected-run-ids}))
    (when-not (and (distinct-values? represented-reviewers)
                   (= (set active-reviewers) (set represented-reviewers)))
      (fail! "Review selection does not match the active roster"
             {:active-reviewers active-reviewers
              :selected-reviewers selected-reviewers
              :skipped-reviewers skipped-reviewers}))
    evidence))

(defn- require-successful-selected-runs!
  [evidence]
  (let [selected-runs (get-in evidence [:selection :runs])
        successful-runs (:reviewers evidence)
        successful-run-ids (mapv :run-id successful-runs)
        successful-reviewers (mapv :name successful-runs)
        selected-identities (mapv (fn [{:keys [id reviewer]}]
                                    {:name reviewer :run-id id})
                                  selected-runs)]
    (when-not (and (distinct-values? successful-run-ids)
                   (distinct-values? successful-reviewers))
      (fail! "Successful reviewer evidence contains duplicate run IDs or names"
             {:successful-run-ids successful-run-ids
              :successful-reviewers successful-reviewers}))
    (when-not (and (= (count selected-identities) (count successful-runs))
                   (= (set selected-identities) (set successful-runs)))
      (fail! "Successful reviewer runs do not match the scheduled selection"
             {:selected-runs selected-identities
              :successful-runs successful-runs}))
    evidence))

(defn verify-automatic-review!
  "Verify positive structured evidence from this run's automatic review gate.

  The code executor binds the originating runtime but passes only `code/params`.
  Resolve the correlated active verification gate in that runtime, then follow
  its declared dependency to avoid reading a similarly named gate from another
  workflow run. Return validated evidence for `code/result`, including the
  complete frozen selection. Accept either a completed reviewer set or a
  genuine glob-only no-applicable-reviewer outcome; fail loudly for every other
  result."
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
        evidence (completion-evidence review-gate)
        evidence (require-complete-roster-selection! runtime evidence)
        evidence (if (= "reviewed" (:outcome evidence))
                   (require-successful-selected-runs! evidence)
                   evidence)
        selection (:selection evidence)]
    (cond-> {"status" (:outcome evidence)
             "review-gate" (:id review-gate)
             "base" (:base evidence)
             "head" (:head evidence)
             "reviewers" (count (:reviewers evidence))}
      (:reviewers evidence) (assoc "successful-reviewers"
                                   (:reviewers evidence))
      (:verdict evidence) (assoc "verdict" (:verdict evidence))
      selection (assoc "selection" selection
                       "reason" (:reason selection)
                       "selected-runs" (mapv #(select-keys % [:id :reviewer])
                                             (:runs selection))
                       "skipped-reviewers" (mapv :reviewer
                                                 (:skips selection))))))

(defn handoff-instruction
  "Describe the full Millstrand review handoff with known work identity."
  [params]
  (format-alpha/prose
   "
     Commit and push the validated branch. Read
     `strand workflow show millstrand-review` for its parameter contract, then
     start a new run:

     ```sh
     strand workflow start <run-id> --workflow millstrand-review --params <json>
     ```

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
     The automatic review gate's harness result records either completed
     reviewer evidence or the exact no-applicable-reviewer selection outcome.
     The following outcome step records that result on the work task.

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

     Follow that line with useful failure details. Do not include either
     completion sentinel. After repairing the branch, recover by starting a new
     review run with a new unique `review-id`; never complete a failed gate by
     hand.

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
     strand --timeout 60s --workspace \"$MILLSTRAND_WORKSPACE\" agent review --cwd \"{worktree}\" --base \"$review_base\" --branch \"$review_head\" --label PR
     ```

     Capture and retain the command's complete structured JSON response. Do not
     substitute a mutable branch name for either SHA. Classify that response
     before awaiting anything:

     - `status=scheduled` requires at least one run. Await each returned run.
     - `status=skipped`, `reason=no-matching-reviewers`, zero runs, and one
       `reason=glob-mismatch` skip for every reviewer in the active roster is a
       valid no-applicable-reviewer outcome. Every active reviewer must appear
       exactly once. Emit the separate sentinel below and do not claim that a
       review succeeded.
     - `reason=no-changes` is invalid empty review input and fails this gate.
     - `seat-unavailable`, any other skip reason, a command error, an unknown or
       invalid selector, or any malformed result fails this gate. Never turn an
       unavailable or failed reviewer into no-applicable-reviewer evidence.

     For a scheduled result, await every returned run separately, repeating the
     bounded await until it reports settlement:

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

     After every scheduled reviewer succeeds, synthesize their results. Your
     final response must begin with one line in exactly this form. Embed the
     complete `agent review` response unchanged as `selection`, alongside one
     entry per successful reviewer:

     ```text
     AUTOMATIC_REVIEW_SUCCESS {success-example}
     ```

     Follow that line with the consolidated review. Include the frozen base and
     head SHAs, every reviewer name and run id, and one deduplicated P1/P2
     verdict. The successful reviewer identities must exactly match the
     selection's runs. The selection's runs and glob-mismatch skips must account
     for every active reviewer exactly once. Preserve concrete paths and lines.
     P1/P2 findings belong to the following outcome step; failed or missing
     reviewer evidence blocks this gate.

     For the valid no-applicable-reviewer result only, the final response must
     instead begin with this line. Embed the complete `agent review` response
     unchanged as `selection`; do not reconstruct it from individual skips or
     omit its change provenance:

     ```text
     AUTOMATIC_REVIEW_NO_APPLICABLE_REVIEWER {no-applicable-example}
     ```

     The verifier requires zero runs, a nonempty frozen change, matching base
     and head SHAs, and exactly one glob-mismatch skip for every reviewer in the
     active roster. Follow that line by stating that no reviewer ran. This is a
     selection outcome, not a successful review or a no-findings verdict.
   " {:branch branch
      :worktree worktree
      :review-target review-target
      :review-id review-id
      :head-ref "HEAD^{commit}"
      :upstream-ref "@{upstream}^{commit}"
      :success-example
      (json/write-str
       (array-map
        "status" "success"
        "base" "FULL_BASE_SHA"
        "head" "FULL_HEAD_SHA"
        "selection"
        (array-map
         "status" "scheduled"
         "reason" nil
         "change" (array-map "source" "branch"
                             "repo-root" worktree
                             "base" "FULL_BASE_SHA"
                             "base-sha" "FULL_BASE_SHA"
                             "merge-base" "FULL_BASE_SHA"
                             "tip" "FULL_HEAD_SHA"
                             "tip-sha" "FULL_HEAD_SHA"
                             "paths" ["CHANGED_PATH"])
         "runs" [(array-map "id" "RUN_ID"
                            "reviewer" "SELECTED_REVIEWER"
                            "seat" "SELECTED_SEAT")]
         "skips" [(array-map "reviewer" "SKIPPED_REVIEWER"
                             "reason" "glob-mismatch")])
        "reviewers" [(array-map "name" "SELECTED_REVIEWER"
                                "run-id" "RUN_ID")]
        "verdict" "findings|no-findings"))
      :no-applicable-example
      (json/write-str
       (array-map
        "base" "FULL_BASE_SHA"
        "head" "FULL_HEAD_SHA"
        "selection"
        (array-map
         "status" "skipped"
         "reason" "no-matching-reviewers"
         "change" (array-map "source" "branch"
                             "repo-root" worktree
                             "base" "FULL_BASE_SHA"
                             "base-sha" "FULL_BASE_SHA"
                             "merge-base" "FULL_BASE_SHA"
                             "tip" "FULL_HEAD_SHA"
                             "tip-sha" "FULL_HEAD_SHA"
                             "paths" ["CHANGED_PATH"])
         "runs" []
         "skips" [(array-map "reviewer" "EVERY_ACTIVE_REVIEWER"
                             "reason" "glob-mismatch")])))}))

(workflow/defworkflow millstrand-review
  "Run Millstrand's full review roster and validate the resulting branch."
  {:entrypoints #{:start}
   :param-spec ::review-params
   :defaults {}
   :param-docs {:feature "Work identity carried into landing."
                :branch "Branch containing the committed change."
                :worktree "Absolute path to the branch's worktree."
                :card "Optional kanban card to move into review."
                :review-target
                (format-alpha/reflow
                 "|External work/task identity for the handoff, not a same-runtime
                  |target. The review or no-applicable-reviewer outcome stays on
                  |gate/result and is recorded on the work task.")
                :review-id "Non-blank identifier unique to this review pass; use a new value for every retry."
                :change-context "Optional captured range and changed files."}}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Review: " branch))
   {:attributes {"workflow/family" "millstrand-review"}}
   (land-support/card-gate
    :review-card "Move the optional card into review" []
    "millhouse.spools.land.card-actions/review-card!")
   (land-support/shell-gate :ci-green "Validate the pushed branch before review" [:review-card]
                            (fn [{:keys [branch]}]
                              (land-support/sh-gate
                               land-support/land-quality-gate-script
                               "review-quality" branch))
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
                     Review orchestration is automatic. A valid glob-only empty
                     selection is recorded separately from successful review.
                     Failed, unavailable, invalid, or empty-input results use
                     AUTOMATIC_REVIEW_FAILURE and block verification. Repair, then
                     start a new run with a unique review-id; never complete a gate
                     by hand.
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
                     Automatic review evidence is checked in-process. A missing or
                     malformed completion sentinel leaves this gate failed and
                     blocks outcome recording.
                   " {}))

   (workflow/step :resolve-review "Record the automatic review outcome" :self
                  :depends-on [:verify-automatic-review]
                  (format-alpha/prose
                   "
                     Read the automatic-review gate's `harness/result` and the
                     verification gate's `code/result`.

                     For `status=reviewed`, record the complete structured
                     `selection` object, combined verdict, and each resolution on
                     the external work/task identity carried in `review-target`.
                     Resolve every P1/P2 finding, then commit and push repairs.
                     Obtain focused follow-up review for material code changes.

                     For `status=no-applicable-reviewer`, record that status and the
                     complete structured `selection` object. Do not record a review
                     pass, a no-findings verdict, or a successful reviewer.
                   " {}))
   (land-support/shell-gate :final-ci-green "Validate the resulting branch HEAD" [:resolve-review]
                            (fn [{:keys [branch]}]
                              (land-support/sh-gate
                               land-support/land-quality-gate-script
                               "land-quality" branch))
                            5400
                            "Validate the actual pushed HEAD after outcome resolution. Fix failures and clear gate/error to retry.")
   (workflow/step :handoff-land "Hand the validated work to landing" :self
                  :depends-on [:final-ci-green]
                  (fn [params]
                    (format-alpha/prose
                     "
                       Record selection, review, and validation evidence on the work
                       task. Carry the complete `selection` object from either
                       verified outcome into the landing handoff.

                       When verification reports
                       `status=no-applicable-reviewer`, state that no local roster
                       reviewer ran; do not describe the work as reviewed or as
                       having a no-findings verdict.

                       If the user authorized landing, read
                       `strand workflow show land` and start `strand workflow start
                       <run-id> --workflow land --params <json>`. Otherwise report
                       the validated work.

                       Carry forward this work identity:

                       {identity:json}
                     " {:identity (select-keys params [:feature :branch :worktree :card])})))))
