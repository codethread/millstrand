(ns millstrand.ct.auto-run-test
  "Prove repository auto-run activation in a disposable Weaver world."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.codethread.auto-run :as auto-run]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private workspace-root
  (.getCanonicalFile (io/file ".millstrand")))

(defn- workspace-files
  "Return the repository-owned workspace source needed by its real init file."
  []
  (let [source-root (io/file workspace-root "me")]
    (into {}
          (for [file (file-seq source-root)
                :when (.isFile file)]
            [(str "me/" (.relativize (.toPath source-root)
                                     (.toPath file)))
             (slurp file)]))))

(defn- world-options
  "Build an isolated world with the repository init and its pinned dependencies."
  []
  (let [deps (:deps (edn/read-string (slurp (io/file workspace-root "deps.edn"))))]
    {:storage :sqlite-memory
     :deps-edn
     (pr-str
      {:deps (update-vals deps
                          #(if-let [root (:local/root %)]
                             (assoc % :local/root
                                    (.getCanonicalPath (io/file workspace-root root)))
                             %))})
     :init-clj (slurp (io/file workspace-root "init.clj"))
     :files (workspace-files)}))

(defn- role-step
  "Return the materialized autonomous-delivery step with `role`."
  [strands role]
  (first (filter #(= role (attr-get % :auto-run/role)) strands)))

(deftest repository-auto-run-activates-only-auto-full-land
  (test-alpha/with-weaver-world
    [ctx (world-options)]
    (let [runtime (:runtime ctx)
          status (auto-run/status runtime)]
      (testing "the real init registers bounded repository policy"
        (is (:enabled status))
        (is (= "sol-high" (get-in status [:config :seat])))
        (is (= "high" (get-in status [:config :effort])))
        (is (= "auto-full-land" (get-in status [:config :workflow])))
        (is (= ["auto-full-land"] (get-in status [:config :workflows])))
        (is (= 2 (get-in status [:config :max-running])))
        (is (empty? (:cards status)))
        (is (empty? (:dispatched (auto-run/scan! runtime)))))
      (current/with-runtime runtime
        (let [run-id "auto-run-disposable"
              result (workflow/start! run-id :auto-full-land
                                      {:card "fixture-card"
                                       :feature "Disposable feature"
                                       :branch "auto/fixture-card"
                                       :worktree (:config-dir ctx)
                                       :seat "sol-high"
                                       :effort "high"})
              root (workflow/current-root run-id)
              strands (:strands (graph/subgraph runtime [(:id root)]))
              views (map workflow/step-view strands)
              handoff (workflow/step-view (role-step strands "handoff-worker"))
              finisher (workflow/step-view (role-step strands "finisher"))]
          (testing "delivery sequences implementation, quality, PR CI, review, and landing"
            (is (= ["Implement and verify the assigned feature"]
                   (mapv :title (:ready result))))
            (is (some #(= "Pass repository quality checks" (:title %)) views))
            (is (some #(= "Publish the exact change with its review package" (:title %)) views))
            (is (some #(= "Wait for the PR checks" (:title %)) views))
            (is (some #(= "Move the verified feature into review" (:title %)) views))
            (is (some #(= "Review and hand off autonomous landing" (:title %)) views)))
          (testing "the landing finisher is a separate, initially blocked target"
            (is (not= (:id handoff) (:id finisher)))
            (is (= "step" (:role handoff) (:role finisher)))
            (is (str/includes? (:instruction handoff) "FINISHER STEP ID"))
            (is (str/includes? (:instruction handoff) "auto-land-finisher/FINISHER_STEP_ID"))
            (is (str/includes? (:instruction handoff) "auto-run/worker-run-id"))
            (is (str/includes? (:instruction finisher) "This step is finisher-only"))
            (is (str/includes? (:instruction finisher) "Do not finish the card early"))))))))
