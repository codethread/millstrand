(ns millstrand.release-workflow-test
  "Tests for the repository release workflow declaration."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.test.alpha :as t]))

(def ^:private release-definition
  "The workspace release workflow under test."
  @(requiring-resolve 'me.workflows.release/release))

(defn- step
  [id]
  (first (filter #(= id (:id %)) (:steps release-definition))))

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

(deftest release-definition-is-selectable-by-a-config-module
  (let [collection
        (t/collect-module-forms
         :test/release 'millstrand.release-workflow-test
         #(eval '(millhouse.spools.workflow/use-workflow!
                  me.workflows.release/release)))]
    (is (contains?
         (get-in collection
                 [:contribution :millhouse.spools.workflow/definition :entries])
         :release))))

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
