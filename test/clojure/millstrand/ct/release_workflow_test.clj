(ns millstrand.ct.release-workflow-test
  "Tests for the repository release workflow declaration."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
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
    (is (s/valid? spec {:version "0.5.3" :worktree "/tmp/millstrand"}))
    (is (not (s/valid? spec {:version "0.5" :worktree "/tmp/millstrand"})))
    (is (not (s/valid? spec {:version "0.5.3" :worktree "relative"})))))

(deftest release-graph-orders-mutation-validation-and-publication
  (is (= [:preflight :bump-version :update-changelog :quality :pin-homebrew
          :build-identity :publish]
         (mapv :id (:steps release-definition))))
  (is (= ["make" "land-quality"]
         (get-in (step :quality) [:attributes "shell/argv"])))
  (is (= "human"
         (get-in (step :publish) [:attributes "workflow/gate"])))
  (is (= [:build-identity] (:depends-on (step :publish)))))

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
        [shell flag script name version]
        ((get-in (step :build-identity) [:attributes "shell/argv"])
         {:version "0.5.3"})]
    (try
      (test-support/run-git! root "init" "-b" "main")
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
            (run-command root [shell flag script name version])]
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
                  {:version "0.5.3"})))
    (is (re-find #"CHANGELOG.md"
                 ((get-in (step :update-changelog)
                          [:attributes "workflow/instruction"])
                  {:version "0.5.3"}))))
  (is (re-find #"v0.5.3"
               ((get-in (step :publish) [:attributes "workflow/instruction"])
                {:version "0.5.3"}))))
