(ns me.workflows.release-evidence
  "Exact candidate approval, tag publication and remote release receipts."
  (:require [clojure.string :as str]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millhouse.land.support :as land-support]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn candidate!
  "Capture and persist the candidate commits and durable repository checkout."
  [{:keys [key version worktree] :as params}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.release-evidence/candidate!" key)
        repository (land-support/canonical-worktree worktree)]
    (when (or (= (.getCanonicalPath (java.io.File. worktree))
                 (.getCanonicalPath (java.io.File. repository)))
              (not= "main" (evidence/git! repository "branch" "--show-current")))
      (fail! "Release requires a distinct canonical main checkout"
             {:worktree worktree :repository repository}))
    (let [candidate (assoc (evidence/freeze! params)
                           :version version
                           :release-commit (evidence/git! worktree "rev-parse" "HEAD^")
                           :repository repository)]
      (weaver/update! rt (:id gate) {:attributes {:release/candidate candidate}})
      candidate)))

(defn start-land!
  "Start or reuse the one exact repository Land run for a release candidate."
  [{:keys [key version]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.release-evidence/start-land!" key)
        candidate-step (evidence/dependency! gate)
        candidate (evidence/data (attr-get candidate-step :release/candidate))
        expected {:feature (str "release/" version)
                  :branch (:branch candidate)
                  :worktree (:worktree candidate)
                  :head (:head candidate)}
        run-id (str "release-land-" version "-" (:head candidate))
        roots #(evidence/reusable-land-roots rt run-id)]
    (when-not (= version (:version candidate))
      (fail! "Release candidate does not match the requested Land run"
             {:version version :candidate candidate}))
    (when (empty? (roots))
      (workflow/start! run-id :land expected))
    (let [root (evidence/single! (roots) "Release Land root missing or ambiguous")
          context (evidence/data (attr-get root :workflow/context))]
      (when-not (= expected (select-keys context (keys expected)))
        (fail! "Release Land run does not match the frozen candidate"
               {:expected expected :actual context :root (:id root)}))
      (let [receipt (assoc expected :run-id run-id :root (:id root))]
        (weaver/update! rt (:id gate) {:attributes {:release/landing-start receipt}})
        receipt))))

(defn landed!
  "Require a completed repository Land run and its candidate-preserving merge."
  [{:keys [key version]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.release-evidence/landed!" key)
        landing-step (evidence/dependency! gate)
        start-step (evidence/dependency! landing-step)
        candidate-step (evidence/dependency! start-step)
        candidate (evidence/data (attr-get candidate-step :release/candidate))
        start-receipt (evidence/data (attr-get start-step :release/landing-start))
        completion (evidence/data (attr-get landing-step :release/land-receipt))
        {:keys [run-id root]} start-receipt
        merge-commit (:merge-commit completion)
        receipt (assoc start-receipt :merge-commit merge-commit)]
    (when-not (and (= version (:version candidate))
                   (every? support/non-blank-string? [run-id root merge-commit]))
      (fail! "Release landing receipt is incomplete"
             {:candidate candidate :receipt receipt}))
    (let [land-root
          (evidence/single!
           (weaver/list rt [:and [:= :id root]
                            [:= [:attr "workflow/run-id"] run-id]] {})
           "Release Land root missing or ambiguous")
          context (evidence/data (attr-get land-root :workflow/context))
          merge-root
          (evidence/single!
           (weaver/list rt [:and [:= [:attr "workflow/run-id"] run-id]
                            [:= [:attr "workflow/definition-name"] "land-merge"]] {})
           "Release Land merge continuation missing or ambiguous")
          merge-context (evidence/data (attr-get merge-root :workflow/context))
          expected {:feature (str "release/" version)
                    :branch (:branch candidate)
                    :worktree (:worktree candidate)
                    :head (:head candidate)}]
      (when-not (and (= "closed" (:state land-root))
                     (= "root" (attr-get land-root :workflow/role))
                     (= "land" (attr-get land-root :workflow/definition-name))
                     (= expected (select-keys context (keys expected)))
                     (= expected (select-keys merge-context (keys expected)))
                     (= "closed" (:state merge-root))
                     (= "root" (attr-get merge-root :workflow/role))
                     (= "merge" (attr-get merge-root :land/stage)))
        (fail! "Release requires a successfully completed exact-candidate Land run"
               {:candidate candidate :receipt receipt :land-root land-root
                :merge-root merge-root}))
      (let [repository (:repository candidate)]
        (when-not (support/non-blank-string? repository)
          (fail! "Frozen release evidence has no durable repository checkout"
                 {:candidate candidate}))
        (evidence/git! repository "fetch" "origin")
        (let [merge-commit (evidence/git! repository "rev-parse"
                                          (str merge-commit "^{commit}"))
              parents (str/split (evidence/git! repository "show" "-s" "--format=%P"
                                                merge-commit)
                                 #"\s+")]
          (when-not (and (= 2 (count parents))
                         (some #{(:head candidate)} parents)
                         (= merge-commit
                            (evidence/git! repository "merge-base"
                                           merge-commit "origin/main")))
            (fail! "Release Land did not preserve the candidate as a merge parent"
                   {:candidate candidate :receipt receipt :parents parents
                    :merge-commit merge-commit}))
          (let [landed (assoc candidate :landing
                              (assoc receipt :merge-root (:id merge-root)
                                     :merge-commit merge-commit))]
            (weaver/update! rt (:id gate) {:attributes {:release/landing landed}})
            landed))))))

(defn- remote-tag [worktree tag]
  (into {} (map (fn [line]
                  (let [[sha ref] (str/split line #"\s+")] [ref sha])))
        (remove str/blank? (str/split-lines
                            (evidence/git! worktree "ls-remote" "origin"
                                           (str "refs/tags/" tag)
                                           (str "refs/tags/" tag "^{}"))))))

(defn- require-matching-remote! [remote {:keys [tag object head] :as receipt}]
  (when-not (and (= object (get remote (str "refs/tags/" tag)))
                 (= head (get remote (str "refs/tags/" tag "^{}"))))
    (fail! "Remote release receipt does not match the annotated candidate"
           {:expected receipt :remote remote}))
  receipt)

(defn- require-remote! [worktree {:keys [tag] :as receipt}]
  (require-matching-remote! (remote-tag worktree tag) receipt))

(defn- complete-push! [{:keys [repository tag object head] :as receipt}]
  (when-not (and (= "tag" (evidence/git! repository "cat-file" "-t" object))
                 (= head (evidence/git! repository "rev-parse" (str object "^{commit}"))))
    (fail! "Push intent does not identify the annotated candidate" {:intent receipt}))
  (let [remote (remote-tag repository tag)
        local (evidence/git! repository "tag" "--list" "--format=%(objectname)" tag)
        ref (str "refs/tags/" tag)]
    (when (and (seq local) (not= object local))
      (fail! "Local release tag does not match push intent; never move it"
             {:intent receipt :local local}))
    (when (seq remote)
      (require-matching-remote! remote receipt))
    (when (str/blank? local)
      (evidence/git! repository "update-ref" ref object ""))
    (when (empty? remote)
      ;; Push the persisted object, not a ref that could change after inspection.
      ;; A competing remote tag is rejected by Git; never force publication.
      (evidence/git! repository "push" "--atomic" "origin" (str object ":" ref)))
    (require-remote! repository receipt)))

(defn publish!
  "Publish only an explicitly approved candidate already preserved on remote main.

  Persist push intent before the external effect. Retry only that exact annotated
  object: prepare a missing local ref and push without force if the remote tag is
  absent. Require an exact remote receipt before accepting publication; uncertain
  remote reads and mismatched existing tags fail without moving either tag."
  [{:keys [key version]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.release-evidence/publish!" key)
        approval-step (evidence/dependency! gate)
        candidate-step (evidence/dependency! approval-step)
        candidate (evidence/data (attr-get candidate-step :release/landing))
        approval (evidence/data (attr-get approval-step :release/approval))
        head (:head candidate)
        repository (:repository candidate)
        tag (str "v" version)]
    (when-not (and (= version (:version candidate) (:version approval))
                   (support/non-blank-string? head) (= head (:head approval))
                   (support/non-blank-string? (:authorization approval)))
      (fail! "Publication requires actual user approval of the exact candidate"
             {:candidate candidate :approval approval}))
    (when-not (support/non-blank-string? repository)
      (fail! "Frozen release evidence has no durable repository checkout"
             {:candidate candidate}))
    (evidence/git! repository "fetch" "origin")
    (when-not (= head (evidence/git! repository "merge-base" head "origin/main"))
      (fail! "Repository landing has not preserved the exact two-commit candidate"
             {:head head :policy "Land the candidate without rewriting its commits"}))
    (if-let [intent (attr-get gate :release/push-intent)]
      (let [intent (evidence/data intent)
            expected {:tag tag :head head :version version :repository repository}]
        (when-not (= expected (select-keys intent (keys expected)))
          (fail! "Push intent does not match the approved candidate"
                 {:expected expected :intent intent}))
        (complete-push! intent))
      (do
        (when (or (seq (remote-tag repository tag))
                  (seq (evidence/git! repository "tag" "--list" tag)))
          (fail! "Release tag already exists; never move it" {:tag tag}))
        (let [tag-object
              (evidence/git-input!
               repository
               (str "object " head "\n"
                    "type commit\n"
                    "tag " tag "\n"
                    "tagger " (evidence/git! repository "var" "GIT_COMMITTER_IDENT") "\n\n"
                    "Millstrand " version "\n")
               "mktag")
              receipt {:tag tag :head head :version version :repository repository
                       :object tag-object}]
          (weaver/update! rt (:id gate) {:attributes {:release/push-intent receipt}})
          (complete-push! receipt))))))

(defn verify-remote!
  "Verify the published annotated tag and exact peeled candidate independently."
  [{:keys [key]}]
  (let [gate (evidence/gate! "me.workflows.release-evidence/verify-remote!" key)
        publication (evidence/dependency! gate)
        receipt (evidence/data (attr-get publication :release/push-intent))]
    (when-not (every? support/non-blank-string?
                      ((juxt :tag :head :object :repository) receipt))
      (fail! "Missing publication receipt" {:publication (:id publication)}))
    (require-remote! (:repository receipt) receipt)))
