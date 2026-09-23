(ns millstrand.ct.release-workflow-test
  "Tests for the repository release workflow declaration."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [me.workflows.release-evidence :as release]
            [millhouse.spools.workflow :as workflow]
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

(deftest release-params-require-semver-and-an-absolute-worktree
  (let [spec (:param-spec release-definition)]
    (is (s/valid? spec {:version "0.5.3" :worktree "/tmp/millstrand" :branch "release/0.5.3"}))
    (is (not (s/valid? spec {:version "0.5" :worktree "/tmp/millstrand" :branch "release/0.5.3"})))
    (is (not (s/valid? spec {:version "0.5.3" :worktree "relative" :branch "release/0.5.3"})))))

(deftest release-graph-orders-mutation-validation-and-publication
  (is (= [:preflight :bump-version :update-changelog :quality :pin-homebrew
          :build-identity :freeze-candidate :landing-policy :approve :publish :verify-remote]
         (mapv :id (:steps release-definition))))
  (is (= ["sh" ".millstrand/land-quality.sh"]
         (get-in (step :quality) [:attributes "shell/argv"])))
  (is (= "human"
         (get-in (step :approve) [:attributes "workflow/gate"])))
  (is (= [:approve] (:depends-on (step :publish)))))

(deftest workspace-config-selects-the-release-definition
  (let [selection (workspace-release-selection)
        resolved-selection
        (walk/postwalk-replace
         {'workflow/use-workflow! 'millhouse.spools.workflow/use-workflow!
          'release/release 'me.workflows.release/release}
         selection)
        collection
        (t/collect-module-forms
         :test/release 'millstrand.ct.release-workflow-test
         #(eval resolved-selection))
        entry (get-in collection
                      [:contribution
                       :millhouse.spools.workflow/definition
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
                  {:version "0.5.3" :branch "release/0.5.3"})))))

(deftest release-publication-requires-exact-approval-and-preserved-landing
  (let [root (test-support/temp-dir "release-candidate")
        origin (io/file root "origin.git")
        checkout (doto (io/file root "checkout") .mkdirs)
        params {:version "0.5.3" :branch "release/0.5.3" :worktree (.getPath checkout)}]
    (try
      (test-support/run-git! root "init" "--bare" (.getPath origin))
      (test-support/run-git! checkout "init" "-b" "release/0.5.3")
      (test-support/run-git! checkout "config" "user.name" "Fixture")
      (test-support/run-git! checkout "config" "user.email" "fixture@example.invalid")
      (spit (io/file checkout "file") "base")
      (test-support/run-git! checkout "add" ".")
      (test-support/run-git! checkout "commit" "-m" "base")
      (test-support/run-git! checkout "remote" "add" "origin" (.getPath origin))
      (test-support/run-git! checkout "push" "origin" "HEAD:main")
      (spit (io/file checkout "file") "release")
      (test-support/run-git! checkout "commit" "-am" "release fixture")
      (test-support/with-runtime
        (fn [rt _]
          (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
          (workflow/start! "release-fixture" release-definition params)
          ;; No release shell gates run: the disposable Git fixture supplies
          ;; candidate identities while this test drives authorization boundaries.
          (dotimes [_ 6] (workflow/complete! "release-fixture" {:by-identity "fixture"}))
          (let [candidate (release/candidate! params)
                candidate-gate (:id (first (workflow/ready "release-fixture")))]
            (workflow/complete! "release-fixture"
                                {:by-identity "fixture" :attributes {"code/result" candidate}})
            (is (= "Resolve candidate-preserving shared landing"
                   (:title (first (workflow/ready "release-fixture")))))
            (workflow/complete! "release-fixture" {:by-identity "fixture"})
            (let [approval-gate (:id (first (workflow/ready "release-fixture")))]
              (workflow/complete! "release-fixture" {:by-identity "fixture"})
              (let [publish-gate (weaver/show rt (:id (first (workflow/ready "release-fixture"))))
                    publish-params (attr-get publish-gate :code/params)]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"actual user approval"
                                      (release/publish! publish-params)))
                (weaver/update! rt approval-gate
                                {:attributes {:release/approval {:version "0.5.3" :head (:head candidate)
                                                                 :authorization "fixture-user-message"}}})
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has not preserved"
                                      (release/publish! publish-params)))
                ;; Simulate an authorized identity-preserving landing in a local
                ;; bare fixture only. Production's squash-policy question remains.
                (test-support/run-git! checkout "push" "origin" "HEAD:main")
                (let [receipt (release/publish! publish-params)]
                  (is (= (:head candidate) (:head receipt)))
                  (is (= receipt (release/publish! publish-params)))
                  (workflow/complete! "release-fixture"
                                      {:by-identity "fixture" :attributes {"code/result" receipt}})
                  (let [verify-params (attr-get (weaver/show rt (:id (first (workflow/ready "release-fixture"))))
                                                :code/params)]
                    (is (= receipt (release/verify-remote! verify-params)))
                    (test-support/run-git! checkout "push" "origin" ":refs/tags/v0.5.3")
                    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                                          (release/verify-remote! verify-params)))))
                (is (some? (attr-get (weaver/show rt candidate-gate) :code/result))))))))
      (finally (test-support/delete-tree! root)))))

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
