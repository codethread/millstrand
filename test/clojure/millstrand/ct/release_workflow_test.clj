(ns millstrand.ct.release-workflow-test
  "Tests for the repository release workflow declaration."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [me.workflows.evidence :as evidence]
            [me.workflows.release-evidence :as release]
            [millhouse.workflow :as workflow]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as t])
  (:import [java.io PushbackReader]))

(def ^:private release-definition
  "The workspace release workflow under test."
  @(requiring-resolve 'me.workflows.release/release))

(defn- step
  [id]
  (first (filter #(= id (:id %)) (:steps release-definition))))

(defn- read-forms
  [path]
  (with-open [reader (PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- workspace-release-selection
  []
  (some #(when (= 'release/release (second %)) %)
        (read-forms ".millstrand/me/config.clj")))

(defn- run-command
  [dir argv]
  (let [process (-> (ProcessBuilder. ^java.util.List argv)
                    (.directory (io/file dir))
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))]
    {:exit (.waitFor process)
     :output output}))

(defn- run-command-with-env
  [dir argv env]
  (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory (io/file dir))
                  (.redirectErrorStream true))
        environment (.environment builder)]
    (doseq [[key value] env]
      (.put environment key value))
    (let [process (.start builder)
          output (slurp (.getInputStream process))]
      {:exit (.waitFor process)
       :output output})))

(deftest release-params-require-semver-and-an-absolute-worktree
  (let [spec (:param-spec release-definition)]
    (is (s/valid? spec {:version "0.5.3" :worktree "/tmp/millstrand" :branch "release/0.5.3"}))
    (is (not (s/valid? spec {:version "0.5" :worktree "/tmp/millstrand" :branch "release/0.5.3"})))
    (is (not (s/valid? spec {:version "0.5.3" :worktree "relative" :branch "release/0.5.3"})))))

(deftest release-graph-orders-mutation-validation-and-publication
  (is (= [:preflight :bump-version :update-changelog :quality :pin-homebrew
          :build-identity :freeze-candidate :start-landing :landing
          :verify-landing :approve :publish :verify-remote]
         (mapv :id (:steps release-definition))))
  (is (= ["sh" ".millstrand/land-quality.sh"]
         (get-in (step :quality) [:attributes "shell/argv"])))
  (is (= "human"
         (get-in (step :approve) [:attributes "workflow/gate"])))
  (is (= [:freeze-candidate] (:depends-on (step :start-landing))))
  (is (= [:start-landing] (:depends-on (step :landing))))
  (is (= [:verify-landing] (:depends-on (step :approve))))
  (is (= [:approve] (:depends-on (step :publish)))))

(deftest repository-land-preserves-the-reviewed-candidate-commits
  (let [head "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        params {:branch "release/0.5.3" :head head}
        definition @(requiring-resolve 'me.workflows.land/land-merge)
        steps (into {} (map (juxt :id identity)) (:steps definition))
        review-definition @(requiring-resolve 'me.workflows.land/review)
        review-steps (into {} (map (juxt :id identity)) (:steps review-definition))
        review-argv ((get-in review-steps [:review-quality :attributes "shell/argv"])
                     params)
        prepare-argv ((get-in steps [:prepare-merge :attributes "shell/argv"])
                      params)
        merge-argv ((get-in steps [:merge-pr :attributes "shell/argv"])
                    {:pr-number 42 :subject "Subject" :body "Body"
                     :branch "release/0.5.3" :head head})]
    (is (= ["release/0.5.3" head] (subvec review-argv 4 6)))
    (is (= ["release/0.5.3" head "preserve"] (subvec prepare-argv 4 7)))
    (is (every? #(str/includes? (nth % 2) "reviewed HEAD changed")
                [review-argv prepare-argv]))
    (is (= ["42" head "Subject" "Body" "release/0.5.3"]
           (conj (subvec merge-argv 4 8) (last merge-argv))))
    (is (= [:canonical-main-clean]
           (:depends-on (steps :merge-pr))))
    (is (str/includes? (nth merge-argv 2)
                       "merge commit does not preserve reviewed HEAD"))
    (is (= "me.workflows.land/land-abort"
           (get-in definition [:attributes "land/abort-definition"])))
    (is (= #{:start :call}
           (:entrypoints @(requiring-resolve 'me.workflows.land/land))))
    (is (not (s/valid? (:param-spec @(requiring-resolve 'me.workflows.land/land))
                       {:feature "release" :branch "release/0.5.3"
                        :worktree "/tmp/release"
                        :head "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})))
    (let [merge-params {:feature "release" :branch "release/0.5.3"
                        :worktree "/tmp/release" :head head :pr-number 42
                        :subject "Release" :body "Preserve candidate"}
          merge-spec (:param-spec @(requiring-resolve 'me.workflows.land/land-merge))]
      (is (not (s/valid? merge-spec merge-params)))
      (is (s/valid? merge-spec
                    (assoc merge-params :authorization "user-message-42"))))))

(deftest landing-head-wrapper-refuses-review-drift
  (let [root (test-support/temp-dir "landing-reviewed-head")]
    (try
      (test-support/run-git! root "init" "-b" "feature/reviewed-head")
      (test-support/run-git! root "config" "user.name" "Fixture")
      (test-support/run-git! root "config" "user.email" "fixture@example.invalid")
      (spit (io/file root "file") "reviewed")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "reviewed")
      (let [head (str/trim (:output (run-command root ["git" "rev-parse" "HEAD"])))
            review-definition @(requiring-resolve 'me.workflows.land/review)
            review-step (some #(when (= :review-quality (:id %)) %)
                              (:steps review-definition))
            argv ((get-in review-step [:attributes "shell/argv"])
                  {:branch "feature/reviewed-head" :head head})
            argv (assoc argv (dec (count argv)) "exit 0")]
        (is (zero? (:exit (run-command root argv))))
        (let [mutating-argv (assoc argv (dec (count argv))
                                   "git commit --allow-empty -m drift >/dev/null")
              post-check (run-command root mutating-argv)]
          (is (not (zero? (:exit post-check))))
          (is (str/includes? (:output post-check) "reviewed HEAD changed")))
        (let [pre-check (run-command root argv)]
          (is (not (zero? (:exit pre-check))))
          (is (str/includes? (:output pre-check) "reviewed HEAD changed"))))
      (finally (test-support/delete-tree! root)))))

(deftest candidate-prepare-wrapper-preserves-the-reviewed-head
  (let [root (test-support/temp-dir "landing-candidate-prepare")
        record (io/file root "prepare-arguments")]
    (try
      (test-support/run-git! root "init" "-b" "feature/candidate-prepare")
      (test-support/run-git! root "config" "user.name" "Fixture")
      (test-support/run-git! root "config" "user.email" "fixture@example.invalid")
      (spit (io/file root "file") "candidate")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "candidate")
      (let [head (str/trim (test-support/run-git! root "rev-parse" "HEAD"))
            prepare-step (some #(when (= :prepare-merge (:id %)) %)
                               (:steps @(requiring-resolve 'me.workflows.land/land-merge)))
            argv ((get-in prepare-step [:attributes "shell/argv"])
                  {:branch "feature/candidate-prepare" :head head})
            recorder (str "printf '%s|%s|%s\\n' \"$1\" \"$2\" \"$3\" >"
                          " " (pr-str (.getPath record)))
            argv (-> argv
                     (assoc 7 recorder)
                     (assoc 8 "quality-script-fixture"))]
        (is (zero? (:exit (run-command root argv))))
        (is (= "feature/candidate-prepare|preserve|quality-script-fixture\n"
               (slurp record)))
        (test-support/run-git! root "commit" "--allow-empty" "-m" "drift")
        (let [drift (run-command root argv)]
          (is (not (zero? (:exit drift))))
          (is (str/includes? (:output drift) "reviewed HEAD changed"))))
      (finally (test-support/delete-tree! root)))))

(deftest canonical-main-clean-gate-refuses-dirty-main
  (let [root (test-support/temp-dir "landing-canonical-clean")
        origin (io/file root "origin.git")
        canonical (doto (io/file root "canonical") .mkdirs)
        feature (io/file root "feature")]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! canonical "init" "-b" "main")
      (test-support/run-git! canonical "config" "user.name" "Fixture")
      (test-support/run-git! canonical "config" "user.email" "fixture@example.invalid")
      (spit (io/file canonical "file") "base")
      (test-support/run-git! canonical "add" ".")
      (test-support/run-git! canonical "commit" "-m" "base")
      (test-support/run-git! canonical "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! canonical "push" "origin" "HEAD:main")
      (test-support/run-git! canonical "worktree" "add" "-b" "feature/canonical-clean"
                             (.getPath feature) "main")
      (let [clean-step (some #(when (= :canonical-main-clean (:id %)) %)
                             (:steps @(requiring-resolve 'me.workflows.land/land-merge)))
            argv ((get-in clean-step [:attributes "shell/argv"]) {})]
        (is (zero? (:exit (run-command feature argv))))
        (spit (io/file canonical "file") "local edit")
        (let [{:keys [exit output]} (run-command feature argv)]
          (is (not (zero? exit)))
          (is (str/includes? output "canonical main checkout is dirty")))
        (test-support/run-git! canonical "checkout" "--" "file")
        (spit (io/file canonical "untracked") "local file")
        (let [{:keys [exit output]} (run-command feature argv)]
          (is (not (zero? exit)))
          (is (str/includes? output "canonical main checkout is dirty")))
        ;; The standalone evidence gate may have passed before this local edit.
        ;; The irreversible shell must not trust that stale result.
        (let [merge-step (some #(when (= :merge-pr (:id %)) %)
                               (:steps @(requiring-resolve 'me.workflows.land/land-merge)))
              head (str/trim (test-support/run-git! feature "rev-parse" "HEAD"))
              merge-argv ((get-in merge-step [:attributes "shell/argv"])
                          {:pr-number 42 :subject "Subject" :body "Body"
                           :branch "feature/canonical-clean" :head head})
              merge-result (run-command feature merge-argv)]
          (is (not (zero? (:exit merge-result))))
          (is (str/includes? (:output merge-result)
                             "canonical main checkout is dirty"))
          (is (.delete (io/file canonical "untracked")))
          (test-support/run-git! canonical "commit" "--allow-empty" "-m" "local-only")
          (is (str/blank? (test-support/run-git! canonical "status" "--porcelain")))
          (doseq [check [argv (assoc merge-argv 8
                                     "touch ../merge-was-invoked; exit 99")]]
            (let [{:keys [exit output]} (run-command feature check)]
              (is (not (zero? exit)))
              (is (str/includes? output "canonical main must match origin/main"))))
          (is (not (.exists (io/file root "merge-was-invoked"))))))
      (finally (test-support/delete-tree! root)))))

(deftest pull-main-wrapper-rechecks-canonical-cleanliness
  (let [root (test-support/temp-dir "landing-pull-clean")
        origin (io/file root "origin.git")
        canonical (doto (io/file root "canonical") .mkdirs)
        feature (io/file root "feature")
        seed (io/file root "seed")
        fake-bin (doto (io/file root "fake-bin") .mkdirs)]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! canonical "init" "-b" "main")
      (test-support/run-git! canonical "config" "user.name" "Fixture")
      (test-support/run-git! canonical "config" "user.email" "fixture@example.invalid")
      (spit (io/file canonical "file") "base")
      (test-support/run-git! canonical "add" ".")
      (test-support/run-git! canonical "commit" "-m" "base")
      (test-support/run-git! canonical "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! canonical "push" "origin" "HEAD:main")
      (test-support/run-git! canonical "worktree" "add" "-b" "feature/pull-clean"
                             (.getPath feature) "main")
      (let [pull-step (some #(when (= :pull-main (:id %)) %)
                            (:steps @(requiring-resolve 'me.workflows.land/land-merge)))
            pull-argv ((get-in pull-step [:attributes "shell/argv"]) {})
            blocked (io/file canonical "blocked")]
        (spit blocked "before pull")
        (let [{:keys [exit output]} (run-command feature pull-argv)]
          (is (not (zero? exit)))
          (is (str/includes? output "canonical main checkout is dirty")))
        (is (.delete blocked))
        (test-support/run-git! root "clone" "-b" "main" (.getPath origin) (.getPath seed))
        (test-support/run-git! seed "config" "user.name" "Fixture")
        (test-support/run-git! seed "config" "user.email" "fixture@example.invalid")
        (spit (io/file seed "upstream") "landed")
        (test-support/run-git! seed "add" ".")
        (test-support/run-git! seed "commit" "-m" "landed")
        (test-support/run-git! seed "push" "origin" "HEAD:main")
        (let [real-git (str/trim (:output (run-command root
                                                       ["sh" "-c" "command -v git"])))
              fake-git (io/file fake-bin "git")]
          (spit fake-git
                (str "#!/bin/sh\nset -eu\n"
                     "\"$REAL_GIT\" \"$@\"\n"
                     "if [ \"${1-}\" = -C ] && [ \"${3-}\" = pull ]; then\n"
                     "  : > \"$2/raced-after-pull\"\n"
                     "fi\n"))
          (is (.setExecutable fake-git true))
          (let [{:keys [exit output]} (run-command-with-env
                                       feature pull-argv
                                       {"REAL_GIT" real-git
                                        "PATH" (str (.getPath fake-bin)
                                                    java.io.File/pathSeparator
                                                    (System/getenv "PATH"))})]
            (is (not (zero? exit)))
            (is (str/includes? output "canonical main checkout is dirty"))
            (is (.exists (io/file canonical "raced-after-pull"))))))
      (finally (test-support/delete-tree! root)))))

(deftest candidate-merge-wrapper-verifies-the-reviewed-parent
  (let [root (test-support/temp-dir "landing-merge-parent")
        origin (io/file root "origin.git")
        canonical (doto (io/file root "canonical") .mkdirs)
        feature (io/file root "feature")
        fake-gh (io/file feature "gh")]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! canonical "init" "-b" "main")
      (test-support/run-git! canonical "config" "user.name" "Fixture")
      (test-support/run-git! canonical "config" "user.email" "fixture@example.invalid")
      (spit (io/file canonical "file") "base")
      (test-support/run-git! canonical "add" ".")
      (test-support/run-git! canonical "commit" "-m" "base")
      (let [base (str/trim (test-support/run-git! canonical "rev-parse" "HEAD"))]
        (test-support/run-git! canonical "remote" "add" "origin" (.getPath origin))
        (test-support/run-git! canonical "push" "origin" "HEAD:main")
        (test-support/run-git! canonical "worktree" "add" "-b" "feature/merge-parent"
                               (.getPath feature) "main")
        (spit (io/file feature "file") "candidate")
        (test-support/run-git! feature "commit" "-am" "candidate")
        (test-support/run-git! feature "push" "-u" "origin" "HEAD")
        (let [head (str/trim (test-support/run-git! feature "rev-parse" "HEAD"))
              merge-step (some #(when (= :merge-pr (:id %)) %)
                               (:steps @(requiring-resolve 'me.workflows.land/land-merge)))
              argv ((get-in merge-step [:attributes "shell/argv"])
                    {:pr-number 42 :subject "Subject" :body "Body"
                     :branch "feature/merge-parent" :head head})
              argv (-> argv
                       (assoc 2 (str "PATH=\"$PWD:$PATH\"\n" (nth argv 2)))
                       (assoc 8 "exit 0"))]
          (test-support/run-git! canonical "merge" "--no-ff" "feature/merge-parent"
                                 "-m" "merge candidate")
          (let [merge-commit (str/trim (test-support/run-git! canonical "rev-parse" "HEAD"))]
            (test-support/run-git! canonical "reset" "--hard" base)
            (spit fake-gh (str "#!/bin/sh\nprintf '%s\\n' '" merge-commit "'\n"))
            (is (.setExecutable fake-gh true))
            (let [merge-argv (assoc argv 8 (str "git push origin " merge-commit ":refs/heads/main"))
                  result (run-command feature merge-argv)]
              (is (zero? (:exit result)) (:output result)))
            (is (= base (str/trim (test-support/run-git! canonical "rev-parse" "HEAD"))))
            (test-support/run-git! canonical "merge" "--ff-only" "origin/main")
            (spit fake-gh (str "#!/bin/sh\nprintf '%s\\n' '" base "'\n"))
            (let [wrong-parent (run-command feature argv)]
              (is (not (zero? (:exit wrong-parent))))
              (is (str/includes? (:output wrong-parent)
                                 "expected a two-parent merge commit"))))))
      (finally (test-support/delete-tree! root)))))

(deftest basic-review-resolution-is-bound-to-the-frozen-head
  (let [root (test-support/temp-dir "landing-review-evidence")
        origin (io/file root "origin.git")
        checkout (doto (io/file root "checkout") .mkdirs)]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! checkout "init" "-b" "main")
      (test-support/run-git! checkout "config" "user.name" "Fixture")
      (test-support/run-git! checkout "config" "user.email" "fixture@example.invalid")
      (spit (io/file checkout "file") "base")
      (test-support/run-git! checkout "add" ".")
      (test-support/run-git! checkout "commit" "-m" "base")
      (test-support/run-git! checkout "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! checkout "push" "origin" "HEAD:main")
      (test-support/run-git! checkout "checkout" "-b" "feature/review-evidence")
      (spit (io/file checkout "file") "candidate")
      (test-support/run-git! checkout "commit" "-am" "candidate")
      (test-support/run-git! checkout "push" "-u" "origin" "HEAD")
      (let [head (str/trim (test-support/run-git! checkout "rev-parse" "HEAD"))
            base (str/trim (test-support/run-git! checkout "merge-base" "origin/main" head))
            marker (str/trim (test-support/run-git! checkout "rev-parse" "--git-path"
                                                    "millstrand-land-quality-head"))
            definition (requiring-resolve 'me.workflows.land/review)
            verify! (requiring-resolve 'me.workflows.land/verify-review-resolution!)
            params {:branch "feature/review-evidence" :head head
                    :worktree (.getPath checkout)}
            resolution {:base base :head head :reviewer "reviewer"
                        :p1-p2 "none" :summary "No findings"}]
        (spit (io/file checkout marker) (str head "\n"))
        (test-support/with-runtime
          (fn [rt _]
            (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
            (letfn [(review-params [run-id feature choice outcome]
                      (workflow/start! run-id definition (assoc params :feature feature))
                      (dotimes [_ 2]
                        (let [gate (first (workflow/ready run-id))]
                          (workflow/complete! run-id {:step (:id gate) :executor "fixture"})))
                      (let [gate (first (workflow/ready run-id))
                            agent-run (weaver/add!
                                       rt {:title "Review agent proof" :state "closed"
                                           :attributes {:harness/run "true"
                                                        :harness/alias "reviewer"
                                                        :harness/status "stopped"
                                                        :harness/substatus "completed"
                                                        :harness/settled "true"
                                                        :harness/result "No findings"}
                                           :edges [{:type "serves" :to (:id gate)}]})]
                        (workflow/complete! run-id
                                            {:step (:id gate) :executor "agent"
                                             :executor-run-id (:id agent-run)
                                             :attributes {"harness/result" "No findings"}}))
                      (workflow/choose! run-id choice outcome)
                      (attr-get (weaver/show rt (:id (first (workflow/ready run-id))))
                                :code/params))]
              (let [mismatch (review-params
                              "review-mismatch" "mismatch" :accepted
                              (assoc resolution :head "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                                      (verify! mismatch))))
              (let [reviewer-mismatch
                    (review-params "reviewer-mismatch" "reviewer-mismatch" :accepted
                                   (assoc resolution :reviewer "other-reviewer"))]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                                      (verify! reviewer-mismatch))))
              (let [matching (review-params "review-match" "match" :accepted resolution)]
                (is (= resolution (verify! matching))))
              (workflow/start! "review-unverified" definition
                               (assoc params :feature "unverified"))
              (dotimes [_ 2]
                (let [gate (first (workflow/ready "review-unverified"))]
                  (workflow/complete! "review-unverified"
                                      {:step (:id gate) :executor "fixture"})))
              (let [gate (first (workflow/ready "review-unverified"))
                    unrelated (weaver/add!
                               rt {:title "Unrelated review proof" :state "closed"
                                   :attributes {:harness/run "true"
                                                :harness/alias "reviewer"
                                                :harness/status "stopped"
                                                :harness/substatus "completed"
                                                :harness/settled "true"
                                                :harness/result "No findings"}})]
                (workflow/complete! "review-unverified"
                                    {:step (:id gate) :executor "agent"
                                     :executor-run-id (:id unrelated)
                                     :attributes {"harness/result" "No findings"}}))
              (workflow/choose! "review-unverified" :accepted resolution)
              (let [unverified
                    (attr-get (weaver/show rt (:id (first (workflow/ready
                                                           "review-unverified"))))
                              :code/params)]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"lacks successful configured agent evidence"
                                      (verify! unverified))))))))
      (finally (test-support/delete-tree! root)))))

(deftest invalidated-land-review-routes-directly-to-abort
  (test-support/with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :land-abort 'me.workflows.land/land-abort)
      (workflow/register-workflow! :land-merge 'me.workflows.land/land-merge)
      (workflow/start! "invalidated-land"
                       @(requiring-resolve 'me.workflows.land/land)
                       {:feature "invalidated" :branch "feature/invalidated"
                        :worktree "/tmp/invalidated"
                        :head "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
      (dotimes [_ 4]
        (let [ready (first (workflow/ready "invalidated-land"))]
          (workflow/complete! "invalidated-land"
                              {:step (:id ready) :by-identity "fixture"})))
      (workflow/choose! "invalidated-land" :invalidated
                        {:reason "Candidate HEAD changed"})
      (let [ready (workflow/ready "invalidated-land")]
        (is (= ["Pause unfinished work"] (mapv :title ready)))
        (is (not-any? #(= "Authorize this work to land" (:title %))
                      (:strands (graph/subgraph rt
                                                [(:id (workflow/current-root
                                                       "invalidated-land"))]))))))))

(deftest workspace-config-selects-the-release-definition
  (let [selection (workspace-release-selection)
        resolved-selection
        (walk/postwalk-replace
         {'workflow/use-workflow! 'millhouse.workflow/use-workflow!
          'release/release 'me.workflows.release/release}
         selection)
        collection
        (t/collect-module-forms
         :test/release 'millstrand.ct.release-workflow-test
         #(eval resolved-selection))
        entry (get-in collection
                      [:contribution
                       :millhouse.workflow/definition
                       :entries
                       :release])]
    (is (= '(workflow/use-workflow! release/release) selection))
    (is (= 'me.workflows.release/release entry))
    (is (= #{:start} (:entrypoints release-definition)))))

(deftest release-identity-gate-rejects-an-extra-release-commit-path
  (let [root (test-support/temp-dir "millstrand-release-workflow")
        [shell flag script name version branch]
        ((get-in (step :build-identity) [:attributes "shell/argv"])
         {:version "0.5.3" :branch "release/0.5.3"})]
    (try
      (test-support/run-git! root "init" "-b" "release/0.5.3")
      (test-support/run-git! root "config" "user.name" "Millstrand Test")
      (test-support/run-git! root "config" "user.email" "test@millstrand.invalid")
      (doto (io/file root "Formula") .mkdirs)
      (spit (io/file root "VERSION") "0.5.2\n")
      (spit (io/file root "CHANGELOG.md") "# Changelog\n")
      (spit (io/file root "Formula/millstrand.rb") "baseline\n")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "baseline")
      (spit (io/file root "VERSION") "0.5.3\n")
      (spit (io/file root "CHANGELOG.md") "## 0.5.3\n")
      (spit (io/file root "unrelated.txt") "must not ship\n")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "chore: release 0.5.3")
      (spit (io/file root "Formula/millstrand.rb") "formula update\n")
      (test-support/run-git! root "add" "Formula/millstrand.rb")
      (test-support/run-git! root "commit" "-m" "chore: pin Homebrew to 0.5.3")
      (let [{:keys [exit output]}
            (run-command root [shell flag script name version branch])]
        (is (not (zero? exit)))
        (is (re-find #"release commit paths mismatch" output))
        (is (re-find #"unrelated.txt" output))
        (is (re-find #"expected \[CHANGELOG.md" output)))
      (finally
        (test-support/delete-tree! root)))))

(deftest release-instructions-render-the-requested-version
  (testing "version and changelog are separate obligations"
    (is (re-find #"VERSION"
                 ((get-in (step :bump-version)
                          [:attributes "workflow/instruction"])
                  {:version "0.5.3" :branch "release/0.5.3"})))
    (is (re-find #"CHANGELOG.md"
                 ((get-in (step :update-changelog)
                          [:attributes "workflow/instruction"])
                  {:version "0.5.3" :branch "release/0.5.3"})))
    (is (str/includes? ((get-in (step :landing)
                                [:attributes "workflow/instruction"])
                        {:version "0.5.3"})
                       "`release-land-0.5.3-<candidate-head>`"))))

(deftest release-candidate-requires-a-distinct-canonical-main-checkout
  (let [root (test-support/temp-dir "release-canonical")]
    (try
      (test-support/run-git! root "init" "-b" "release/0.5.3")
      (test-support/run-git! root "config" "user.name" "Fixture")
      (test-support/run-git! root "config" "user.email" "fixture@example.invalid")
      (spit (io/file root "file") "candidate")
      (test-support/run-git! root "add" ".")
      (test-support/run-git! root "commit" "-m" "candidate")
      (test-support/with-runtime
        (fn [rt _]
          (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
          (workflow/start!
           "candidate-canonical"
           (workflow/workflow
            "Candidate canonical proof"
            (workflow/gate :candidate "Freeze candidate" :code
                           :attributes {"code/fn" "me.workflows.release-evidence/candidate!"
                                        "delivery/key" "canonical-proof"}))
           {})
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"distinct canonical main checkout"
               (release/candidate! {:key "canonical-proof" :version "0.5.3"
                                    :branch "release/0.5.3"
                                    :worktree (.getPath root)})))))
      (finally (test-support/delete-tree! root)))))

(deftest historical-land-reuse-requires-its-successful-queue-reservation
  (test-support/with-runtime
    (fn [rt _]
      (doseq [[run-id outcome entry-state same-root? reusable?]
              [["missing" nil "closed" true false]
               ["withdrawn" "withdrawn" "closed" true false]
               ["unfinished" "merged" "active" true false]
               ["other-root" "merged" "closed" false false]
               ["merged" "merged" "closed" true true]]]
        (testing run-id
          (let [root (weaver/add! rt {:title "Historical Land" :state "closed"
                                      :attributes {:workflow/run-id run-id
                                                   :workflow/role "root"
                                                   :workflow/definition-name "land"}})
                merge-root (weaver/add! rt {:title "Merge continuation" :state "closed"
                                            :attributes {:workflow/run-id run-id
                                                         :workflow/role "root"
                                                         :workflow/definition-name "land-merge"
                                                         :land/stage "merge"}})]
            (when outcome
              (weaver/add! rt {:title "Queue reservation" :state entry-state
                               :attributes {:kind "merge-queue-entry"
                                            :land/run-id run-id
                                            :queue/root (if same-root? (:id merge-root) "other")
                                            :queue/outcome outcome}}))
            (is (= (if reusable? [(:id root)] [])
                   (mapv :id (evidence/reusable-land-roots rt run-id))))))))))

(deftest release-publication-requires-exact-approval-and-preserved-landing
  (let [root (test-support/temp-dir "release-candidate")
        origin (io/file root "origin.git")
        canonical (doto (io/file root "canonical") .mkdirs)
        checkout (io/file root "checkout")
        params {:version "0.5.3" :branch "release/0.5.3" :worktree (.getPath checkout)}]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! canonical "init" "-b" "main")
      (test-support/run-git! canonical "config" "user.name" "Fixture")
      (test-support/run-git! canonical "config" "user.email" "fixture@example.invalid")
      (spit (io/file canonical "file") "base")
      (test-support/run-git! canonical "add" ".")
      (test-support/run-git! canonical "commit" "-m" "base")
      (test-support/run-git! canonical "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! canonical "push" "origin" "HEAD:main")
      (test-support/run-git! canonical "worktree" "add" "-b" "release/0.5.3"
                             (.getPath checkout) "main")
      (spit (io/file checkout "file") "release")
      (test-support/run-git! checkout "commit" "-am" "release fixture")
      (test-support/with-runtime
        (fn [rt _]
          (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
          (workflow/register-workflow! :land-abort 'me.workflows.land/land-abort)
          (workflow/register-workflow! :land-merge 'me.workflows.land/land-merge)
          (workflow/register-workflow! :land 'me.workflows.land/land)
          (workflow/start! "release-fixture" release-definition params)
          ;; No release shell gates run: the disposable Git fixture supplies
          ;; candidate identities while this test drives authorization boundaries.
          (dotimes [_ 6] (workflow/complete! "release-fixture" {:by-identity "fixture"}))
          (let [candidate-step (weaver/show rt (:id (first (workflow/ready
                                                            "release-fixture"))))
                candidate-gate (:id candidate-step)
                candidate (release/candidate! (attr-get candidate-step :code/params))]
            (workflow/complete! "release-fixture" {:by-identity "fixture"})
            (let [start-step (weaver/show rt (:id (first (workflow/ready
                                                          "release-fixture"))))
                  run-id (str "release-land-0.5.3-" (:head candidate))
                  historical-root
                  (weaver/add! rt {:title "Aborted historical Land"
                                   :state "closed"
                                   :attributes
                                   {"workflow/run-id" run-id
                                    "workflow/role" "root"
                                    "workflow/definition-name" "land"
                                    "workflow/context"
                                    {:feature "release/0.5.3"
                                     :branch (:branch candidate)
                                     :worktree (:worktree candidate)
                                     :head (:head candidate)}}})
                  start-receipt (release/start-land! (attr-get start-step :code/params))
                  land-context (select-keys start-receipt
                                            [:feature :branch :worktree :head])]
              (is (not= (:id historical-root) (:root start-receipt)))
              (is (= "active" (:state (weaver/show rt (:root start-receipt)))))
              (is (= start-receipt
                     (release/start-land! (attr-get start-step :code/params))))
              (let [repaired-head "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
                (weaver/update! rt candidate-gate
                                {:attributes {:release/candidate
                                              (assoc candidate :head repaired-head)}})
                (is (= (str "release-land-0.5.3-" repaired-head)
                       (:run-id (release/start-land!
                                 (attr-get start-step :code/params)))))
                (weaver/update! rt candidate-gate
                                {:attributes {:release/candidate candidate}})
                (is (= start-receipt
                       (release/start-land! (attr-get start-step :code/params)))))
              (weaver/update! rt (:root start-receipt)
                              {:attributes {:workflow/context
                                            (assoc land-context :worktree "/tmp/wrong")}})
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"frozen candidate"
                                    (release/start-land! (attr-get start-step :code/params))))
              (weaver/update! rt (:root start-receipt)
                              {:attributes {:workflow/context land-context}})
              (workflow/complete! "release-fixture" {:by-identity "fixture"})
              (is (= "Land the exact candidate without rewriting its commits"
                     (:title (first (workflow/ready "release-fixture")))))
              (weaver/update! rt (:root start-receipt) {:state "closed"})
              (let [merge-root
                    (weaver/add! rt {:title "Merge Land proof"
                                     :state "closed"
                                     :attributes
                                     {"workflow/run-id" (:run-id start-receipt)
                                      "workflow/definition-name" "land-merge"
                                      "workflow/role" "root"
                                      "workflow/context" land-context
                                      "land/stage" "merge"}})]
                (workflow/complete!
                 "release-fixture"
                 {:by-identity "fixture"
                  :attributes {"release/land-receipt"
                               {:merge-commit (:head candidate)}}})
                (let [landing-step (:id (evidence/dependency!
                                         (weaver/show rt
                                                      (:id (first (workflow/ready
                                                                   "release-fixture"))))))
                      landed-params (attr-get (weaver/show rt
                                                           (:id (first (workflow/ready
                                                                        "release-fixture"))))
                                              :code/params)]
                  (weaver/update! rt (:id merge-root)
                                  {:attributes {:workflow/context
                                                (assoc land-context :worktree "/tmp/wrong")}})
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact-candidate"
                                        (release/landed! landed-params)))
                  (weaver/update! rt (:id merge-root)
                                  {:attributes {:workflow/context land-context}})
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"merge parent"
                                        (release/landed! landed-params)))
                  (test-support/run-git! canonical "merge" "--no-ff" (:head candidate)
                                         "-m" "Merge release candidate")
                  (let [merge-commit (str/trim (test-support/run-git! canonical
                                                                      "rev-parse" "HEAD"))]
                    (test-support/run-git! canonical "push" "origin" "HEAD:main")
                    (test-support/run-git! canonical "worktree" "remove" "--force"
                                           (.getPath checkout))
                    (is (not (.exists checkout)))
                    (weaver/update! rt landing-step
                                    {:attributes {:release/land-receipt
                                                  {:merge-commit merge-commit}}})
                    (let [landed (release/landed! landed-params)]
                      (is (= (:head candidate) (:head landed)))
                      (workflow/complete! "release-fixture" {:by-identity "fixture"}))))))
            (let [approval-gate (:id (first (workflow/ready "release-fixture")))]
              (workflow/complete! "release-fixture" {:by-identity "fixture"})
              (let [publish-gate (weaver/show rt (:id (first (workflow/ready "release-fixture"))))
                    publish-params (attr-get publish-gate :code/params)]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"actual user approval"
                                      (release/publish! publish-params)))
                (weaver/update! rt approval-gate
                                {:attributes {:release/approval
                                              {:version "0.5.3" :head (:head candidate)
                                               :authorization "fixture-user-message"}}})
                (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo #"persist intent fixture"
                     (with-redefs [weaver/update!
                                   (fn [& _]
                                     (throw (ex-info "persist intent fixture" {})))]
                       (release/publish! publish-params))))
                (is (str/blank? (test-support/run-git! canonical "tag" "--list"
                                                       "v0.5.3")))
                (let [receipt (release/publish! publish-params)]
                  (is (= (:head candidate) (:head receipt)))
                  (is (= receipt (release/publish! publish-params)))
                  (workflow/complete! "release-fixture" {:by-identity "fixture"})
                  (let [verify-params (attr-get (weaver/show rt (:id (first (workflow/ready "release-fixture"))))
                                                :code/params)]
                    (is (= receipt (release/verify-remote! verify-params)))
                    (test-support/run-git! canonical "push" "origin" ":refs/tags/v0.5.3")
                    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                                          (release/verify-remote! verify-params)))))
                (is (= candidate
                       (evidence/data (attr-get (weaver/show rt candidate-gate)
                                                :release/candidate)))))))))
      (finally (test-support/delete-tree! root)))))

(defn- with-publication-fixture [f]
  (let [root (test-support/temp-dir "release-push-retry")
        origin (io/file root "origin.git")
        repository (doto (io/file root "repository") .mkdirs)]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! repository "init" "-b" "main")
      (test-support/run-git! repository "config" "user.name" "Fixture")
      (test-support/run-git! repository "config" "user.email" "fixture@example.invalid")
      (test-support/run-git! repository "commit" "--allow-empty" "-m" "candidate")
      (test-support/run-git! repository "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! repository "config" "remote.origin.tagOpt" "--no-tags")
      (test-support/run-git! repository "push" "origin" "HEAD:main")
      (test-support/with-runtime
        (fn [rt _]
          (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
          (let [head (evidence/git! (.getPath repository) "rev-parse" "HEAD")
                candidate {:version "0.5.3" :head head :repository (.getPath repository)}
                landed (weaver/add! rt {:title "Landed candidate" :state "closed"
                                        :attributes {:release/landing candidate}})
                approval (weaver/add! rt {:title "Approval" :state "closed"
                                          :attributes {:release/approval
                                                       (assoc candidate :authorization "user-message")}
                                          :edges [{:type "depends-on" :to (:id landed)}]})
                gate (weaver/add! rt {:title "Publish"
                                      :attributes {:code/fn "me.workflows.release-evidence/publish!"
                                                   :delivery/key "retry"}
                                      :edges [{:type "depends-on" :to (:id approval)}]})]
            (f {:rt rt :gate (:id gate) :repository (.getPath repository)
                :origin origin :head head :params {:version "0.5.3" :key "retry"}}))))
      (finally (test-support/delete-tree! root)))))

(defn- interrupt-publication! [params command after?]
  (let [git! evidence/git!]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"publication interrupted"
         (with-redefs [evidence/git!
                       (fn [repository & args]
                         (if (= command (first args))
                           (do
                             (when after? (apply git! repository args))
                             (throw (ex-info "publication interrupted" {})))
                           (apply git! repository args)))]
           (release/publish! params))))))

(deftest publication-retries-complete-exact-intent-across-failure-windows
  (doseq [[command after?] [["update-ref" false] ["push" false] ["push" true]]]
    (testing (str command " after=" after?)
      (with-publication-fixture
        (fn [{:keys [rt gate repository params]}]
          (interrupt-publication! params command after?)
          (let [intent (evidence/data (attr-get (weaver/show rt gate) :release/push-intent))
                git! evidence/git!
                pushes (atom [])]
            (is (some? (:object intent)))
            (is (= (if (= command "update-ref") "" (:object intent))
                   (git! repository "tag" "--list" "--format=%(objectname)" "v0.5.3")))
            (with-redefs [evidence/git!
                          (fn [repository & args]
                            (when (= "push" (first args)) (swap! pushes conj (vec args)))
                            (apply git! repository args))]
              (is (= intent (release/publish! params)))
              (is (= intent (release/publish! params))))
            (is (= (if after? []
                       [["push" "--atomic" "origin" (str (:object intent) ":refs/tags/v0.5.3")]])
                   @pushes))
            (is (= (:object intent) (git! repository "rev-parse" "refs/tags/v0.5.3")))
            (is (= (str (:object intent) "\trefs/tags/v0.5.3\n"
                        (:head intent) "\trefs/tags/v0.5.3^{}")
                   (git! repository "ls-remote" "origin" "refs/tags/v0.5.3*")))))))))

(deftest publication-retries-refuse-mismatches-and-uncertain-remote-reads
  (doseq [failure [:local :local-after-push :remote :intent :unreachable]]
    (testing (name failure)
      (with-publication-fixture
        (fn [{:keys [rt gate repository origin head params]}]
          (if (= failure :local-after-push)
            (interrupt-publication! params "push" true)
            (interrupt-publication! params "update-ref" false))
          (let [intent (evidence/data (attr-get (weaver/show rt gate) :release/push-intent))
                git! evidence/git!
                effects (atom [])]
            (case failure
              :local (git! repository "tag" "v0.5.3" head)
              :local-after-push (git! repository "update-ref" "refs/tags/v0.5.3" head)
              :remote (test-support/run-git! origin "update-ref" "refs/tags/v0.5.3" head)
              :intent (weaver/update! rt gate
                                      {:attributes {:release/push-intent
                                                    (assoc intent :head "wrong-candidate")}})
              :unreachable nil)
            (with-redefs [evidence/git!
                          (fn [repository & args]
                            (when (#{"push" "update-ref"} (first args))
                              (swap! effects conj args))
                            (when (and (= failure :unreachable) (= "ls-remote" (first args)))
                              (throw (ex-info "remote unavailable" {})))
                            (apply git! repository args))]
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo #"does not match|remote unavailable"
                   (release/publish! params))))
            (is (empty? @effects))
            (is (= (if (#{:local :local-after-push} failure) head "")
                   (git! repository "tag" "--list" "--format=%(objectname)" "v0.5.3")))
            (is (= (case failure
                     :remote (str head "\trefs/tags/v0.5.3")
                     :local-after-push (str (:object intent) "\trefs/tags/v0.5.3\n"
                                            head "\trefs/tags/v0.5.3^{}")
                     "")
                   (git! repository "ls-remote" "origin" "refs/tags/v0.5.3*")))))))))

(deftest publication-retry-never-overwrites-a-racing-remote-tag
  (with-publication-fixture
    (fn [{:keys [rt gate repository origin head params]}]
      (interrupt-publication! params "push" false)
      (let [intent (evidence/data (attr-get (weaver/show rt gate) :release/push-intent))
            git! evidence/git!]
        (with-redefs [evidence/git!
                      (fn [repository & args]
                        (when (= "push" (first args))
                          (test-support/run-git! origin "update-ref" "refs/tags/v0.5.3" head))
                        (apply git! repository args))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Git evidence command failed"
                                (release/publish! params))))
        (is (= (:object intent) (git! repository "rev-parse" "refs/tags/v0.5.3")))
        (is (= (str head "\trefs/tags/v0.5.3")
               (git! repository "ls-remote" "origin" "refs/tags/v0.5.3*")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Remote release receipt does not match"
                              (release/publish! params)))))))

(deftest repository-quality-entry-delegates-through-one-shared-lock
  (let [root (test-support/temp-dir "quality-entry")
        fake-flock (io/file root "flock")]
    (try
      (spit fake-flock "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
      (is (.setExecutable fake-flock true))
      (let [result (run-command
                    "." ["/bin/sh" "-c"
                         (str "PATH=\"" (.getPath root) ":$PATH\" exec /bin/sh .millstrand/land-quality.sh")])]
        (is (zero? (:exit result)))
        (is (= "-w\n180\n/tmp/millstrand-test.lock\nmake\nland-quality\n" (:output result))))
      (finally (test-support/delete-tree! root)))))
