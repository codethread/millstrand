(ns me.workflows.release-evidence
  "Exact candidate approval, tag publication and remote release receipts."
  (:require [clojure.string :as str]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millhouse.land.support :as land-support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn candidate!
  "Capture the candidate commits and durable repository checkout."
  [{:keys [version worktree] :as params}]
  (assoc (evidence/freeze! params)
         :version version
         :release-commit (evidence/git! worktree "rev-parse" "HEAD^")
         :repository (land-support/canonical-worktree worktree)))

(defn- remote-tag [worktree tag]
  (into {} (map (fn [line]
                  (let [[sha ref] (str/split line #"\s+")] [ref sha])))
        (remove str/blank? (str/split-lines
                            (evidence/git! worktree "ls-remote" "origin"
                                           (str "refs/tags/" tag)
                                           (str "refs/tags/" tag "^{}"))))))

(defn- require-remote! [worktree {:keys [tag object head] :as receipt}]
  (let [remote (remote-tag worktree tag)]
    (when-not (and (= object (get remote (str "refs/tags/" tag)))
                   (= head (get remote (str "refs/tags/" tag "^{}"))))
      (fail! "Remote release receipt does not match the annotated candidate"
             {:expected receipt :remote remote})))
  receipt)

(defn publish!
  "Publish only an explicitly approved candidate already preserved on remote main.

  Persist push intent before the external effect. On an uncertain result, require
  the exact remote tag object and peeled candidate before accepting a retry. Do
  not replay a push or move an existing tag to repair ambiguous publication."
  [{:keys [key version]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.release-evidence/publish!" key)
        approval-step (evidence/dependency! gate)
        candidate-step (-> approval-step evidence/dependency! evidence/dependency!)
        candidate (evidence/data (attr-get candidate-step :code/result))
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
      (fail! "Shared landing has not preserved the exact two-commit candidate"
             {:head head :policy "Resolve squash versus candidate preservation before publication"}))
    (if-let [intent (attr-get gate :release/push-intent)]
      (require-remote! repository (evidence/data intent))
      (do
        (when (or (seq (remote-tag repository tag))
                  (seq (evidence/git! repository "tag" "--list" tag)))
          (fail! "Release tag already exists; never move it" {:tag tag}))
        (evidence/git! repository "tag" "-a" tag head "-m" (str "Millstrand " version))
        (let [receipt {:tag tag :head head :version version :repository repository
                       :object (evidence/git! repository "rev-parse" (str "refs/tags/" tag))}]
          (weaver/update! rt (:id gate) {:attributes {:release/push-intent receipt}})
          (evidence/git! repository "push" "--atomic" "origin" (str "refs/tags/" tag))
          receipt)))))

(defn verify-remote!
  "Verify the published annotated tag and exact peeled candidate independently."
  [{:keys [key]}]
  (let [gate (evidence/gate! "me.workflows.release-evidence/verify-remote!" key)
        publication (evidence/dependency! gate)
        receipt (evidence/data (attr-get publication :code/result))]
    (when-not (every? support/non-blank-string?
                      ((juxt :tag :head :object :repository) receipt))
      (fail! "Missing publication receipt" {:publication (:id publication)}))
    (require-remote! (:repository receipt) receipt)))
