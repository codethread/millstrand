(ns me.workflows.evidence
  "Durable step lookup and Git evidence for repository workflows."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
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

(defn git-input!
  "Run Git with `input` on stdin; return trimmed stdout or fail loudly."
  [worktree input & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" (concat args [:dir worktree :in input]))]
    (when-not (zero? exit)
      (fail! "Git evidence command failed" {:args args :worktree worktree :error err}))
    (str/trim out)))

(defn reusable-land-roots
  "Return the current Land root, or a successfully merged historical root.

  Closed and aborted attempts do not suppress a fresh start. A closed Land root
  is reusable only when its merge continuation also completed successfully."
  [rt run-id]
  (let [roots (weaver/list rt [:and
                               [:= [:attr "workflow/run-id"] run-id]
                               [:= [:attr "workflow/role"] "root"]] {})
        land-roots (filter #(= "land" (attr-get % :workflow/definition-name)) roots)
        current (filter #(= "active" (:state %)) land-roots)
        latest (last (sort-by (juxt :created_at :id) land-roots))
        merged-roots (set (map #(attr-get % :queue/root)
                               (weaver/list rt [:and
                                                [:= [:attr "kind"] "merge-queue-entry"]
                                                [:= [:attr "land/run-id"] run-id]
                                                [:= :state "closed"]
                                                [:= [:attr "queue/outcome"] "merged"]] {})))
        successful-merge?
        (some (fn [candidate]
                (and latest
                     (not (neg? (compare (:created_at candidate)
                                         (:created_at latest))))
                     (= "closed" (:state candidate))
                     (= "land-merge" (attr-get candidate :workflow/definition-name))
                     (= "merge" (attr-get candidate :land/stage))
                     (contains? merged-roots (:id candidate))))
              roots)]
    (if (seq current)
      (vec current)
      (if (and latest (= "closed" (:state latest)) successful-merge?)
        [latest]
        []))))

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

(defn quality-head!
  "Require clean HEAD, its quality marker and the named origin branch to agree."
  [{:keys [worktree branch] :as params}]
  (let [{:keys [head] :as frozen} (freeze! params)
        marker-path (git! worktree "rev-parse" "--git-path" "millstrand-land-quality-head")
        marker (io/file marker-path)
        marker (if (.isAbsolute marker) marker (io/file worktree marker-path))
        remote-ref (str "refs/heads/" branch)
        remote (git! worktree "ls-remote" "--exit-code" "origin" remote-ref)]
    (when-not (and (= head (str/trim (slurp marker)))
                   (= (str head "\t" remote-ref) remote))
      (fail! "Quality-marked HEAD must match the named origin branch"
             {:frozen frozen :remote-ref remote-ref :remote remote}))
    frozen))

(defn unchanged!
  "Require the frozen branch and HEAD still describe a clean worktree."
  [{:keys [head] :as frozen}]
  (let [actual (freeze! frozen)]
    (when-not (= head (:head actual))
      (fail! "Review HEAD changed after dispatch" {:expected head :actual (:head actual)})))
  frozen)
