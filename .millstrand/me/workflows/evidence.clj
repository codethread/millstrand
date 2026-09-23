(ns me.workflows.evidence
  "Durable step lookup and Git evidence for repository workflows."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn data
  "Read JSON-shaped stored evidence with keyword keys."
  [value]
  (walk/keywordize-keys value))

(defn single!
  "Require exactly one matching strand."
  [strands description]
  (when-not (= 1 (count strands))
    (fail! description {:matches (mapv :id strands)}))
  (first strands))

(defn gate!
  "Find the unique active code gate for an authored function and correlation key."
  [function key]
  (single!
   (weaver/list (current/runtime)
                [:and [:= :state "active"]
                 [:= [:attr "code/fn"] function]
                 [:= [:attr "delivery/key"] key]] {})
   "Code gate correlation is absent or ambiguous"))

(defn dependency!
  "Read the single direct prerequisite of a linear step."
  [step]
  (let [rt (current/runtime)]
    (single! (mapv #(weaver/show rt (:to_strand_id %))
                   (graph/outgoing-edges rt [(:id step)] "depends-on"))
             "Expected one durable prerequisite")))

(defn git!
  "Run Git in the specified worktree; return trimmed stdout or fail loudly."
  [worktree & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" (concat args [:dir worktree]))]
    (when-not (zero? exit)
      (fail! "Git evidence command failed" {:args args :worktree worktree :error err}))
    (str/trim out)))

(defn freeze!
  "Require a clean branch and capture its immutable merge-base and HEAD."
  [{:keys [worktree branch]}]
  (when-not (= branch (git! worktree "branch" "--show-current"))
    (fail! "Unexpected worktree branch" {:branch branch :worktree worktree}))
  (when-not (str/blank? (git! worktree "status" "--porcelain" "--untracked-files=all"))
    (fail! "Commit all changes before freezing review" {:worktree worktree}))
  (let [head (git! worktree "rev-parse" "HEAD^{commit}")
        base (git! worktree "merge-base" "origin/main" head)]
    {:base base :head head :worktree worktree :branch branch}))

(defn unchanged!
  "Require the frozen branch and HEAD still describe a clean worktree."
  [{:keys [head] :as frozen}]
  (let [actual (freeze! frozen)]
    (when-not (= head (:head actual))
      (fail! "Review HEAD changed after dispatch" {:expected head :actual (:head actual)})))
  frozen)
