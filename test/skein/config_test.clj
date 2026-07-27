(ns skein.config-test
  "Tests for the repo-local .skein config modules.

  Covers registration, the delegate-pipeline weave pattern, the land workflow,
  generic workflow integration with ct.spools.devflow, and report scripts."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core.db-test :as db-test]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :as spool-api]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.core.weaver.module-publication :as publication]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.spools.test-support :as test-support]))

(defn- delete-directory!
  "Delete a directory tree rooted at `path` if it exists."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (io/delete-file child true)))))

(defn- test-world
  "Return an isolated test world rooted in a temporary directory."
  [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- write-embedded-spools!
  "Write repo spool approvals into target for an embedded runtime."
  [target]
  (spit (io/file target "spools.edn")
        (pr-str (test-support/embedded-spools-edn ".skein/spools.edn"))))

(defn- with-runtime-loader
  "Run f with runtime's ambient binding and synced spool classloader."
  [rt f]
  (weaver-runtime/with-runtime-and-spool-classloader
    rt
    (fn []
      (spool-sync/sync-approved-spools rt)
      (f))))

(defn- publish-contribution!
  "Replace one fixture module owner's partitions with `contribution`."
  [rt module-key contribution]
  (let [backends (publication/backends rt)
        candidates (publication/stage-owner backends (publication/candidates backends)
                                            module-key contribution)]
    (publication/publish! backends candidates)))

(defn- load-module-source!
  "Load one workspace authoring file and publish its complete contribution."
  [rt module-key file]
  (let [path (.getCanonicalPath (io/file file))
        ns-sym (symbol (str/replace (str/replace file #"^\.skein/" "") #"\.clj$" ""))]
    (publish-contribution!
     rt module-key
     (:contribution
      (module-graph/with-contribution-collection
        {:module/key module-key :source/file path :source/namespace ns-sym}
        #(load-file file))))))

(defn- load-module-namespace!
  "Load one synced spool namespace under module-key and publish the authoring
  forms its load collects.

  The peer-spool twin of `load-module-source!`, for a spool whose whole
  contribution is top-level authoring forms and which therefore declares no
  `contribute` entry point: its declarations exist only when the load itself
  happens inside that module's collection scope, exactly as the real module
  loader runs it."
  [rt module-key ns-sym]
  (publish-contribution!
   rt module-key
   (:contribution
    (module-graph/with-contribution-collection
      {:module/key module-key
       :source/file (spool-sync/synced-namespace-file rt ns-sym)
       :source/namespace ns-sym}
      #(spool-sync/load-synced-namespace! rt ns-sym module-key)))))

(defn- publish-module-contribution!
  "Replace one fixture module owner from its data-first contribution function."
  [rt module-key contribute]
  (publish-contribution!
   rt module-key
   (update-vals (contribute {:runtime rt :module/key module-key})
                (fn [partition]
                  (if (contains? partition :entries)
                    partition
                    {:entries partition :overrides #{}})))))

(defn- with-config-runtime
  "Run f with an isolated runtime and the repo-local .skein config loaded.

  Loads the split config modules the way init.clj orders them: config.clj
  first (workflows.clj references its public CLI-tail helpers at load time),
  then harnesses.clj and workflows.clj. attention.clj and nvd_scan.clj are
  deliberately not loaded here — chime rules are asserted through the full
  startup fixture, and the NVD job must never register from a direct load."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-test-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (write-embedded-spools! config-dir)
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)
                                             :publish? false})]
      (try
        (with-runtime-loader
          rt
          (fn []
            (spool-sync/load-synced-namespace!
             rt 'ct.spools.agent-run :skein/spools-shuttle)
            (publish-module-contribution!
             rt :skein/spools-shuttle
             (requiring-resolve 'ct.spools.agent-run/contribute))
            ((requiring-resolve 'skein.spools.workflow/contribute)
             {:runtime rt :module/key :skein/spools-workflow})
            ((requiring-resolve 'skein.spools.workflow/reconcile)
             {:runtime rt :module/key :skein/spools-workflow
              :module/contribution {:status :applied}})
            (load-module-namespace! rt :skein/spools-workflow-cli
                                    'skein.spools.workflow.cli)
            (publish-module-contribution!
             rt :skein/spools-workflow-cli
             (requiring-resolve 'skein.spools.workflow.cli/contribute))
            ((requiring-resolve 'skein.spools.workflow.cli/reconcile)
             {:runtime rt :module/key :skein/spools-workflow-cli
              :module/contribution {:status :applied}})
            (load-module-namespace! rt :skein/spools-devflow 'ct.spools.devflow)
            (load-module-source! rt :config ".skein/config.clj")
            (load-file ".skein/harnesses.clj")
            (publish-module-contribution!
             rt :harnesses (requiring-resolve 'harnesses/contribute))
            ((requiring-resolve 'harnesses/reconcile) {:runtime rt})
            (load-module-source! rt :workflows ".skein/workflows.clj")
            (f rt)))
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- copy-config-dir!
  "Copy the repo-local config files into a temporary config dir."
  [target]
  (.mkdirs (io/file target))
  (doseq [name ["init.clj" "config.clj" "workflows.clj" "harnesses.clj"
                "attention.clj" "nvd_scan.clj" "reviewers.clj"
                "kanban_tracker.clj" "module_adapters.clj" "spools.edn"]]
    (io/copy (io/file ".skein" name) (io/file target name)))
  (let [scripts-target (io/file target "scripts")]
    (.mkdirs scripts-target)
    (doseq [name ["feature-ci-watch.sh" "land-cleanup.sh" "land-merge.sh"]]
      (io/copy (io/file ".skein/scripts" name) (io/file scripts-target name))))
  ;; The copied config dir would reinterpret repo-relative local roots. Git
  ;; families remain byte-for-byte sourced from the checked-in approvals.
  (write-embedded-spools! target)
  ;; The shipped config leaves chime's notifier to each developer's personal
  ;; init.local.clj. Bind an inert command through that same overlay hook
  ;; (loaded after init.clj on startup and on every reload) so the test also
  ;; exercises the overlay path, a developer's real init.local.clj is never
  ;; read, and test-created HITL checkpoints record no notifier-missing noise.
  (spit (io/file target "init.local.clj")
        (pr-str '(do (require '[skein.spools.chime :as chime])
                     (chime/set-notifier! {:argv ["true"]})))))

(defn- with-startup-config-runtime
  "Run f with an isolated runtime started through copied .skein/init.clj."
  [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-config-startup-" (java.util.UUID/randomUUID))]
    (copy-config-dir! config-dir)
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)
                                             :publish? false})]
      (try
        (with-runtime-loader rt #(f rt))
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- write-f16-probe!
  "Write the F16 regression probe file into config-dir.

  When present? is true it contributes one harness seat and one workflow
  definition entry; otherwise it contributes empty partitions — the file-edit a
  developer makes to remove an entry."
  [config-dir present?]
  (spit (io/file config-dir "f16_probe.clj")
        (str "(ns f16-probe\n"
             "  \"F16 regression probe: contributes an alias and a workflow definition.\"\n"
             "  (:require [ct.spools.agent-run :as shuttle]\n"
             "            [skein.spools.workflow :as workflow]))\n"
             "(defn contribute [_]\n"
             "  {shuttle/alias-kind "
             (if present? "{:f16-probe-seat {:alias-of :codex}}" "{}") "\n"
             "   workflow/definition-kind "
             (if present? "{:f16-probe-flow 'workflows/story}" "{}") "})\n"
             "(def spool {:contribute 'contribute})\n")))

(deftest f16-workspace-partition-refresh-deletes-omitted-seats-and-constructors
  ;; F16 regression: the .skein policy files publish their harness seats, reviewer
  ;; rosters, and workflow definitions as :workspace-layer partitions, so removing
  ;; an entry from the file and refreshing must DELETE it from the live registry.
  ;; The reconcile->install! path this replaced upserted into a shared REPL owner,
  ;; where a deleted entry stayed silently effective. A probe module contributes an
  ;; alias-kind seat and a definition-kind entry, then drops both and refreshes.
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-f16-probe-" (java.util.UUID/randomUUID))]
    (copy-config-dir! config-dir)
    ;; Layer the probe module onto the same overlay hook as the chime notifier,
    ;; so init.clj stays untouched and refresh re-reads the probe every cycle.
    (spit (io/file config-dir "init.local.clj")
          (pr-str '(do (require '[skein.api.current.alpha :as current]
                                '[skein.api.runtime.alpha :as runtime]
                                '[skein.spools.chime :as chime])
                       (chime/set-notifier! {:argv ["true"]})
                       (runtime/module! (current/runtime) :f16-probe
                                        {:file "f16_probe.clj"
                                         :spools ['ct.spools/agent-run 'skein.spools/workflow]
                                         :after [:skein/spools-shuttle :skein/spools-workflow]}))))
    (write-f16-probe! config-dir true)
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)
                                             :publish? false})]
      (try
        (with-runtime-loader
          rt
          (fn []
            (let [resolve-harness (requiring-resolve 'ct.spools.agent-run/resolve-harness)
                  workflow-definition (requiring-resolve 'skein.spools.workflow/workflow-definition)]
              (is (= :codex (:name (resolve-harness :f16-probe-seat)))
                  "the probe seat resolves through its :alias-of tool after startup")
              (is (= 'workflows/story (workflow-definition :f16-probe-flow))
                  "the probe workflow definition is registered after startup")
              (write-f16-probe! config-dir false)
              (is (contains? #{:applied :unchanged} (:status (runtime/refresh! rt))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Harness not found"
                                    (resolve-harness :f16-probe-seat))
                  "omitting the seat and refreshing deletes it from the alias registry")
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                                    (workflow-definition :f16-probe-flow))
                  "omitting the definition and refreshing deletes it from the registry"))))
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- op!
  "Invoke a repo-local registered op by name with a CLI-shaped argv."
  [op-name argv]
  (weaver/op! (current/runtime) (symbol op-name) argv))

(defn- start-land!
  "Start the registered land workflow through the generic CLI."
  [run-id branch worktree & [card]]
  (op! "workflow"
       ["start" run-id
        "--workflow" "land"
        "--params" (json/write-str (cond-> {:feature run-id
                                            :branch branch
                                            :worktree worktree}
                                     card (assoc :card card)))]))

(defn- active-merge-locks
  "Return active merge locks from the same query shape as the named query."
  []
  (weaver/list (current/runtime)
               [:and [:= :state "active"] [:= [:attr "kind"] "merge-lock"]]
               {}))

(def ^:private config-op-names
  "The config-owned CLI ops whose generated `help <op>` the refactor must preserve.

  Every op authored as a `defop` in .skein/config.clj; the surrounding spool ops
  (kanban/agent/bench) and workflow ops (land) are untouched by the refactor and
  are covered by their own tests, so this holistic guard scopes to config.clj."
  ["hitl"])

(def ^:private named-query-names
  "The config-owned named queries whose registered definitions the refactor must
  preserve, authored as `defquery` blocks in .skein/config.clj."
  ["feature-active" "feature-work" "feature-owner-work" "feature-run"
   "workflow-runs" "devflow-runs" "merge-lock" "work"])

(defn- portable-source
  "Rewrite an op-help envelope's absolute `:source` file to a repo-relative path.

  The runtime resolves each op's source to an absolute on-disk path — the most
  useful form for the live API — so freezing it verbatim would bind the surface
  baseline to one checkout. Strip the checkout-root prefix here so the frozen
  surface reads e.g. `.skein/config.clj` and stays portable across CI and other
  worktrees; `:line` is kept as-is."
  [detail]
  (let [root (str (System/getProperty "user.dir") "/")]
    (cond-> detail
      (get-in detail [:source :file])
      (update-in [:source :file]
                 (fn [file]
                   (if (str/starts-with? file root)
                     (subs file (count root))
                     file))))))

(defn- capture-config-surface
  "Load the config module at config-path into an isolated runtime and return its
  registered config-owned surface as plain, EDN-round-trippable data.

  The surface is `{:op-help {op -> help-detail} :queries {name -> definition}}`.
  Only config.clj is loaded (no harnesses/workflows), so this captures exactly the
  op/query surface the defquery/defop refactor could have perturbed. Used both
  to snapshot the pre-refactor baseline and to capture the current converted
  config for a byte-identical comparison.

  devflow is loaded first, the way init.clj's `:after` ordering loads it before
  `:config`. Its stages are top-level `defworkflow` forms, so letting
  config.clj's require pull it in for the first time inside the `:config`
  collector would file devflow's declarations under `:config` — which the
  module-graph collection-source guard rightly refuses. It loads outside any
  collection scope: this world declares only the op and query backends the
  captured surface reads, so devflow's own declarations have nowhere to publish."
  [config-path]
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/skein-surface-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (write-embedded-spools! config-dir)
    (let [rt (weaver-runtime/start! db-file {:world (test-world config-dir)
                                             :publish? false})]
      (try
        (with-runtime-loader
          rt
          (fn []
            (spool-sync/load-synced-namespace!
             rt 'ct.spools.devflow :skein/spools-devflow)
            (load-module-source! rt :config config-path)
            {:op-help (into {} (map (fn [op] [op (portable-source (op! "help" [op]))])) config-op-names)
             :queries (into {} (map (fn [q] [q (get (graph/queries rt) q)])) named-query-names)}))
        (finally
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file)
          (delete-directory! config-dir))))))

(defn- assert-config-registrations
  "Assert the repo-local query/op/pattern registrations are present."
  [rt]
  (doseq [query-name ["kanban-cards" "kanban-pending" "feature-active" "feature-work"
                      "feature-owner-work" "feature-run" "workflow-runs" "devflow-runs"
                      "merge-lock" "work"]]
    (is (contains? (graph/queries rt) query-name)))
  (is (contains? (graph/queries rt) "bench-runs"))
  (doseq [op-name ["kanban" "hitl" "land" "workflow"
                   "agent" "bench"]]
    (is (some #(= op-name (:name %)) (weaver/ops rt)) op-name))
  (is (some #(= "delegate-pipeline" (:name %)) (patterns/patterns rt)))
  ;; agent-plan is spool-owned now; a real startup wires the agents spool in
  ;; via init.clj, so it must still be registered end to end
  (is (some #(= "agent-plan" (:name %)) (patterns/patterns rt)))
  ;; agent review must consume the one authoritative policy text by default;
  ;; the text ships from ct.spools.delegation, the accessor stays on agent-run
  (is (= (var-get (requiring-resolve 'ct.spools.delegation/review-contract))
         ((requiring-resolve 'ct.spools.agent-run/default-review-contract-text))))
  ;; this repo runs the agent-plan task workflow, which no spool registers for
  ;; it: harnesses.clj opts its serving runs into the exported fragment
  (is (= (var-get (requiring-resolve 'ct.spools.delegation/worker-contract))
         ((requiring-resolve 'ct.spools.agent-run/default-task-contract-text))))
  ;; the repo owns chime's attention rules; the chime engine ships none
  (is (= [:agent-failure :gate-error :hitl-checkpoint-ready :kanban-blocked :kanban-completed
          :kanban-started :parked-run]
         (mapv :key ((requiring-resolve 'skein.spools.chime/rules)))))
  ;; the declarative reviewer rosters register from .skein/reviewers.clj
  (let [rosters ((requiring-resolve 'ct.spools.delegation/rosters))]
    (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
    (is (some #(= "test-sleeps" (:name %)) (:seats (first rosters))))))

(deftest config-surface-matches-intentional-baseline
  ;; The baseline freezes the config-owned op help and named-query definitions.
  ;; Regenerate it only when a feature deliberately changes that public surface.
  (let [golden (edn/read-string (slurp "test/skein/surface_baseline.edn"))
        current (capture-config-surface ".skein/config.clj")]
    (is (= (:queries golden) (:queries current))
        "every named query definition matches the pre-refactor baseline")
    (doseq [op config-op-names]
      (is (= (get-in golden [:op-help op]) (get-in current [:op-help op]))
          (str "generated help for " op " must match the pre-refactor baseline")))))

(deftest generic-workflow-surface-drives-devflow-intake
  (with-startup-config-runtime
    (fn [_rt]
      (let [description (op! "workflow" ["show" "intake"])
            params (json/write-str {:feature "generic-feature"
                                    :worktree-check "already-in-worktree-ok"})
            started (op! "workflow" ["start" "generic-feature"
                                     "--workflow" "intake"
                                     "--params" params])]
        (is (= "ct.spools.devflow/intake" (:definition description)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready started)))))
        (is (= "brief"
               (:artifact
                (first (:ready (op! "workflow"
                                    ["next" "generic-feature"
                                     "--choice" "already-in-worktree"]))))))
        (is (= "discuss-scope"
               (:checkpoint
                (first (:ready (op! "workflow" ["next" "generic-feature"]))))))
        (is (= "devflow.proposal.orient"
               (:action-ref
                (first (:ready (op! "workflow"
                                    ["next" "generic-feature"
                                     "--choice" "proposal-ready"]))))))
        (is (false? (:done (op! "workflow" ["ready" "generic-feature"]))))
        (is (= ["generic-feature"]
               (mapv #(get-in % [:attributes :workflow/run-id])
                     (weaver/list (current/runtime)
                                  (var-get (requiring-resolve
                                            'config/devflow-runs-query))
                                  {}))))))))

(deftest spool-bump-workflow-publishes-authority-exclusive-cutover-paths
  (with-startup-config-runtime
    (fn [_rt]
      (let [description (op! "workflow" ["show" "spool-bump"])
            definition (var-get (requiring-resolve 'workflows/spool-bump))
            compile-workflow (requiring-resolve 'skein.spools.workflow/compile)
            params (fn [direct?]
                     (json/write-str
                      {:bumps [{:family "codethread/kanban"
                                :version "latest"}
                               {:family "codethread/devflow"
                                :version "v12"}]
                       :branch "bump-demo"
                       :worktree "/tmp/bump-demo"
                       :direct-user-request direct?}))
            action-refs
            (fn [direct?]
              (->> (:strands
                    (compile-workflow
                     definition
                     {:bumps [{:family "codethread/kanban"
                               :version "latest"}
                              {:family "codethread/devflow"
                               :version "v12"}]
                      :branch "bump-demo"
                      :worktree "/tmp/bump-demo"
                      :direct-user-request direct?}))
                   (keep #(get-in % [:attributes "workflow/action-ref"]))
                   set))]
        (is (= "workflows/spool-bump" (:definition description)))
        (is (= ["create-branch"] (get-in description [:declared :entry])))
        (is (= "workflows/spool-bump-params" (get-in description [:params :spec])))
        (is (= "spool-bump.branch.create"
               (:action-ref
                (first (:ready
                        (op! "workflow" ["start" "direct-bump"
                                         "--workflow" "spool-bump"
                                         "--params" (params true)]))))))
        (let [compiled (:strands
                        (compile-workflow
                         definition
                         {:bumps [{:family "codethread/kanban"
                                   :version "latest"}
                                  {:family "codethread/devflow"
                                   :version "v12"}]
                          :branch "bump-demo"
                          :worktree "/tmp/bump-demo"
                          :direct-user-request true}))
              bump-steps (filterv #(= "spool-bump.coordinate.bump"
                                      (get-in % [:attributes "workflow/action-ref"]))
                                  compiled)
              by-action-ref
              (into {}
                    (keep (fn [strand]
                            (when-let [action-ref
                                       (get-in strand
                                               [:attributes "workflow/action-ref"])]
                              [action-ref strand])))
                    compiled)]
          (is (= ["Bump third-party spool codethread/kanban to latest"
                  "Bump third-party spool codethread/devflow to v12"]
                 (mapv :title bump-steps)))
          (is (str/includes?
               (get-in by-action-ref
                       ["spool-bump.bump-world.start"
                        :attributes
                        "workflow/instruction"])
               "mill weaver start --workspace /tmp/bump-demo/.skein"))
          (is (str/includes?
               (get-in (first bump-steps) [:attributes "workflow/instruction"])
               "spool bump codethread/kanban`"))
          (is (str/includes?
               (get-in (second bump-steps) [:attributes "workflow/instruction"])
               "spool bump codethread/devflow --to v12`"))
          (is (str/includes?
               (get-in by-action-ref
                       ["spool-bump.bump-world.stop"
                        :attributes
                        "workflow/instruction"])
               "mill weaver stop --workspace /tmp/bump-demo/.skein"))
          (is (str/includes?
               (get-in by-action-ref
                       ["spool-bump.world.create"
                        :attributes
                        "workflow/instruction"])
               "validation_ws=$(mktemp -d)"))
          (is (str/includes?
               (get-in by-action-ref
                       ["spool-bump.world.smoke"
                        :attributes
                        "workflow/instruction"])
               "spool status"))
          (is (contains? by-action-ref "spool-bump.resources.cleanup")))
        (op! "workflow" ["start" "indirect-bump"
                         "--workflow" "spool-bump"
                         "--params" (params false)])
        (is (contains? (action-refs true) "spool-bump.runtime.cutover"))
        (is (not (contains? (action-refs true)
                            "spool-bump.runtime.handover")))
        (is (contains? (action-refs false)
                       "spool-bump.runtime.handover"))
        (is (not (contains? (action-refs false)
                            "spool-bump.runtime.cutover")))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Value does not satisfy the named spec"
             (op! "workflow" ["start" "unstated-authority"
                              "--workflow" "spool-bump"
                              "--params"
                              (json/write-str
                               {:bumps [{:family "codethread/kanban"
                                         :version "latest"}]
                                :branch "bump-demo"
                                :worktree "/tmp/bump-demo"})])))))))

(deftest repo-config-publishes-no-devflow-alias-ops
  (with-startup-config-runtime
    (fn [rt]
      (is (empty? (filter #(str/starts-with? (:name %) "devflow-")
                          (weaver/ops rt)))))))

(deftest named-queries-return-expected-rows-against-seeded-strands
  ;; TASK-Srm-009.MI1: exercise each registered named query's rows against one
  ;; deterministic seed, so a defquery `:where`/`:params` regression surfaces as a
  ;; wrong row set rather than only a definition diff.
  (with-config-runtime
    (fn [rt]
      (doseq [[title attrs] [["A1" {:feature "alpha" :kind "task" :owner "amy"}]
                             ["A2" {:feature "alpha" :kind "review" :owner "bob"}]
                             ["A3" {:feature "alpha" :kind "note"}]
                             ["B1" {:feature "beta" :kind "task" :owner "amy"}]
                             ["R1" {:workflow/run-id "alpha"}]
                             ["M1" {:workflow/role "root"}]
                             ["D1" {:workflow/role "root" :workflow/family "devflow"}]
                             ["S1" {:agent-run/run "true"}]
                             ["K1" {:kanban/card "true" :kanban/lane "refinement"}]]]
        (weaver/add! rt {:title title :state "active" :attributes attrs}))
      (let [rows (fn [query-name params]
                   (set (map :title (weaver/list rt (get (graph/queries rt) query-name) params))))]
        (is (= #{"A1" "A2" "A3"} (rows "feature-active" {:feature "alpha"})))
        (is (= #{"A1" "A2"} (rows "feature-work" {:feature "alpha"})))
        (is (= #{"A1"} (rows "feature-owner-work" {:feature "alpha" :owner "amy"})))
        (is (= #{"R1"} (rows "feature-run" {:feature "alpha"})))
        (is (= #{"M1" "D1"} (rows "workflow-runs" {})))
        (is (= #{"D1"} (rows "devflow-runs" {})))
        (is (= #{"A1" "A2" "A3" "B1" "R1"} (rows "work" {})))))))

(defn- feature-cost-report
  "Run the report-side reducer against `subgraph`; return the shell result."
  [subgraph]
  (sh/sh "jq" "-f" "scripts/reports/feature-costs.jq"
         :in (json/write-str subgraph)))

(deftest feature-cost-report-reduces-generic-subgraph-json
  (let [root {:id "root" :title "Feature card" :state "active" :attributes {}}
        run-a {:id "run-a" :title "Delegate: implement" :state "closed"
               :attributes {"agent-run/run" "true"
                            "agent-run/harness" "build"
                            "agent-run/cost-usd" "1.25"
                            "agent-run/tokens-total" "1000"
                            "agent-run/tokens" (json/write-str {:input 800 :output 200})
                            "agent-run/usage-source" "session"
                            "agent-run/exit-code" 0
                            "agent-run/started-at" "2026-07-10T11:00:00.250+01:00"
                            "agent-run/finished-at" "2026-07-10T10:05:00.750Z"}}
        run-b {:id "run-b" :title "Review: skeptic" :state "closed"
               :attributes {"agent-run/run" "true"
                            "agent-run/harness" "hard-gpt"
                            "agent-run/started-at" "2026-07-10T10:06:00Z"
                            "agent-run/finished-at" "2026-07-10T05:08:30-05:00"}}
        note {:id "note" :title "Not a run" :state "closed" :attributes {}}
        result (feature-cost-report {:root_ids ["root"]
                                     :strands [run-b note root run-a]
                                     :edges []})
        report (json/read-str (:out result) :key-fn keyword)]
    (is (zero? (:exit result)) (:err result))
    (is (= {:id "root" :title "Feature card" :state "active"} (:root report)))
    (is (= ["run-a" "run-b"] (mapv :id (:runs report))))
    (is (= {:runs 2 :runs-with-usage 1 :cost-usd 1.25 :tokens-total 1000
            :wall-clock {:started-at "2026-07-10T10:00:00.25Z"
                         :finished-at "2026-07-10T10:08:30Z"
                         :duration-secs 509.75}}
           (:totals report)))
    (is (= [{:runs 1 :runs-with-usage 1 :cost-usd 1.25 :tokens-total 1000
             :harness "build"}
            {:runs 1 :runs-with-usage 0 :cost-usd 0 :tokens-total 0
             :harness "hard-gpt"}]
           (:by-harness report)))
    (is (= ["run-b"] (:missing-usage report)))
    (is (= 300.5 (get-in report [:runs 0 :duration-secs])))
    (is (= "2026-07-10T10:05:00.75Z"
           (get-in report [:runs 0 :finished-at])))
    (is (= {:input 800 :output 200} (get-in report [:runs 0 :tokens])))))

(deftest feature-cost-report-fails-loudly-on-malformed-present-values
  (doseq [[attribute value] [["agent-run/cost-usd" "not-a-number"]
                             ["agent-run/cost-usd" "NaN"]
                             ["agent-run/tokens-total" 1.5]
                             ["agent-run/tokens" "[]"]
                             ["agent-run/started-at" "yesterday"]
                             ["agent-run/run" false]]]
    (let [result (feature-cost-report
                  {:root_ids ["root"]
                   :strands [{:id "root" :title "Root" :state "active" :attributes {}}
                             {:id "bad" :title "Bad run" :state "closed"
                              :attributes (assoc {"agent-run/run" "true"}
                                                 attribute value)}]
                   :edges []})]
      (is (not (zero? (:exit result))) attribute)
      (is (empty? (:out result)) attribute)
      (is (str/includes? (:err result) "strand=bad") attribute)
      (is (str/includes? (:err result) (str "attribute=" attribute)) attribute)
      (is (str/includes? (:err result) (json/write-str value)) attribute))))

(deftest feature-cost-report-requires-one-matching-root
  (doseq [[root-ids strands message]
          [[[] [] "requires exactly one root id: count=0 root_ids=[]"]
           [["missing"] [] "root not found in subgraph"]]]
    (let [result (feature-cost-report {:root_ids root-ids
                                       :strands strands
                                       :edges []})]
      (is (not (zero? (:exit result))))
      (is (empty? (:out result)))
      (is (str/includes? (:err result) message)))))

(deftest chime-attention-rules-register-and-fire
  ;; TASK-Srm-009.MI1: through the full startup fixture (which loads attention.clj
  ;; via init.clj), assert the registered chime rule keys and that the registered
  ;; handlers actually fire — resolving each rule's registered fn symbol and
  ;; invoking it, so a defrule handler/registration regression is caught behavior,
  ;; not just key, deep.
  (with-startup-config-runtime
    (fn [_rt]
      (let [rules ((requiring-resolve 'skein.spools.chime/rules))
            by-key (into {} (map (juxt :key identity)) rules)
            fire (fn [rule-key strand]
                   (@(requiring-resolve (:fn (get by-key rule-key)))
                    {:strand strand :ready-ids #{}}))]
        (is (= [:agent-failure :gate-error :hitl-checkpoint-ready :kanban-blocked
                :kanban-completed :kanban-started :parked-run]
               (mapv :key rules)))
        ;; gate-error fires on any strand stamped with a gate error
        (let [note (fire :gate-error {:id "g1" :state "active" :title "Gate A"
                                      :attributes {:gate/error "spawn failed"}})]
          (is (= "Gate error: Gate A" (:title note)))
          (is (str/includes? (:body note) "spawn failed")))
        ;; and stays silent (no false positive) when the condition is absent
        (is (nil? (fire :gate-error {:id "g2" :state "active" :title "Clean gate"
                                     :attributes {}})))
        ;; agent-failure fires on a failed agent run and carries its error
        (let [note (fire :agent-failure {:id "r1" :state "active" :title "Run"
                                         :attributes {:agent-run/phase "failed" :agent-run/error "boom"}})]
          (is (= "Agent run failed: Run" (:title note)))
          (is (str/includes? (:body note) "boom")))))))

(deftest kanban-tree-op-projects-epic-feature-task-hierarchy
  ;; The kanban-tree projection joins the parent-of tiers (epic -> feature ->
  ;; task) the flat query surface can't, and derives task status. Uses the full
  ;; startup fixture because the op resolves the kanban spool's `kanban-cards`
  ;; query. Asserts epic linkage, top-level vs nested features, derived statuses
  ;; (done/blocked/doing/ready), and that closed tasks appear only under --all.
  (with-startup-config-runtime
    (fn [rt]
      (let [blocker (weaver/add! rt {:title "Blocker" :state "active" :attributes {:kind "task"}})
            epic (weaver/add! rt {:title "Epic E" :state "active"
                                  :attributes {:kanban/card "true" :kanban/type "epic"}})
            f1 (weaver/add! rt {:title "Feature under epic" :state "active"
                                :attributes {:kanban/card "true" :kanban/type "feature"}})
            f2 (weaver/add! rt {:title "Top-level feature" :state "active"
                                :attributes {:kanban/card "true" :kanban/type "feature"}})
            t-doing (weaver/add! rt {:title "Doing task" :state "active"
                                     :attributes {:kanban/task "true" :owner "amy"}})
            t-ready (weaver/add! rt {:title "Ready task" :state "active"
                                     :attributes {:kanban/task "true"}})
            t-blocked (weaver/add! rt {:title "Blocked task" :state "active"
                                       :attributes {:kanban/task "true" :owner "bob"}})
            t-done (weaver/add! rt {:title "Done task" :state "closed"
                                    :attributes {:kanban/task "true" :owner "amy"}})]
        (weaver/update! rt (:id epic) {:edges [{:type "parent-of" :to (:id f1)}]})
        (weaver/update! rt (:id f1) {:edges [{:type "parent-of" :to (:id t-doing)}
                                             {:type "parent-of" :to (:id t-ready)}
                                             {:type "parent-of" :to (:id t-blocked)}
                                             {:type "parent-of" :to (:id t-done)}]})
        (weaver/update! rt (:id t-blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (let [by-id (fn [result] (into {} (map (juxt :id identity)) (:cards result)))
              active (by-id (op! "kanban-tree" []))
              full (by-id (op! "kanban-tree" ["--all" "true"]))
              task-status (fn [card] (into {} (map (juxt :title :status)) (:tasks card)))]
          ;; active view: only kanban cards (epic + features); the plain-task
          ;; blocker carries no kanban/card and never surfaces as a card
          (is (= #{(:id epic) (:id f1) (:id f2)} (set (keys active))))
          (is (= "epic" (:type (active (:id epic)))))
          (is (= "feature" (:type (active (:id f1)))))
          ;; epic linkage: a feature under an epic carries its epic id; others nil
          (is (= (:id epic) (:epic (active (:id f1)))))
          (is (nil? (:epic (active (:id f2)))))
          (is (nil? (:epic (active (:id epic)))))
          ;; derived task status; the closed task is filtered from the active view
          (is (= {"Doing task" "doing" "Ready task" "ready" "Blocked task" "blocked"}
                 (task-status (active (:id f1)))))
          (is (= [] (:tasks (active (:id f2)))))
          ;; --all surfaces the closed (done) task alongside the active ones
          (is (= {"Doing task" "doing" "Ready task" "ready" "Blocked task" "blocked" "Done task" "done"}
                 (task-status (full (:id f1))))))))))

(deftest delegate-pipeline-weave-creates-chain-loop-gates
  (with-config-runtime
    (fn [rt]
      (patterns/weave! rt :delegate-pipeline
                       {:run_id "pipe-test"
                        :harness "worker"
                        :accept true
                        :tasks [{:id "a" :title "Do A" :body "A body"}
                                {:id "b" :title "Do B"}]})
      (let [strands (weaver/list rt)
            by-task (into {} (keep (fn [s]
                                     (when-let [task (or (get-in s [:attributes :delegate-pipeline/task])
                                                         (get-in s [:attributes "delegate-pipeline/task"]))]
                                       [task s])) strands))
            attr (fn [s k]
                   (or (get-in s [:attributes k])
                       (get-in s [:attributes (name k)])))]
        (is (= #{"a" "b"} (set (keys by-task))))
        (is (str/includes? (attr (by-task "a") :agent-run/prompt) "A body"))
        ;; a gate's run serves the gate, so the agent-run preamble injects the
        ;; contract this repo registers; prepending it here would double it
        (is (not (str/includes? (attr (by-task "a") :agent-run/prompt) "[worker contract]")))
        (is (not (str/includes? (attr (by-task "a") :agent-run/prompt) "[task workflow]")))
        (is (= "worker" (attr (by-task "b") :agent-run/harness))))))
  (testing "acceptance checkpoint is optional and task max-attempts pass through"
    (with-config-runtime
      (fn [rt]
        (patterns/weave! rt :delegate-pipeline
                         {:run_id "pipe-no-accept"
                          :tasks [{:id "a" :title "Do A" :harness "worker" :max-attempts 4}]})
        (let [strands (weaver/list rt)
              task (first (filter #(= "a" (or (get-in % [:attributes :delegate-pipeline/task])
                                              (get-in % [:attributes "delegate-pipeline/task"])))
                                  strands))]
          (is (some? task))
          (is (= 4 (or (get-in task [:attributes :agent-run/max-attempts])
                       (get-in task [:attributes "agent-run/max-attempts"]))))
          (is (not-any? #(= "checkpoint" (or (get-in % [:attributes :workflow/role])
                                             (get-in % [:attributes "workflow/role"])))
                        strands)))))))

(deftest work-query-excludes-workflow-plumbing-but-keeps-steps
  (with-config-runtime
    (fn [rt]
      (doseq [[title role] [["Root" "root"]
                            ["Procedure" "procedure"]
                            ["Digest" "digest"]
                            ["Step" "step"]
                            ["Checkpoint" "checkpoint"]
                            ["Run record" nil]
                            ["Plain task" nil]
                            ["Pending card" nil]
                            ["Refinement card" nil]]]
        (weaver/add! rt {:title title
                         :state "active"
                         :attributes (cond-> {:feature "work-query"}
                                       role (assoc :workflow/role role)
                                       (= title "Run record") (assoc :agent-run/run "true")
                                       (= title "Pending card") (assoc :kanban/card "true"
                                                                       :kanban/lane "pending")
                                       (= title "Refinement card") (assoc :kanban/card "true"
                                                                          :kanban/lane "refinement"))}))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (weaver/list rt (var-get (requiring-resolve 'config/work-query)) {})))))
      (is (= #{"Step" "Checkpoint" "Plain task" "Pending card"}
             (set (map :title (weaver/ready rt (var-get (requiring-resolve 'config/work-query)) {}))))))))

(deftest reviewers-file-registers-declarative-roster
  ;; exercises the same contribution path init.clj's reviewers module runs
  (with-config-runtime
    (fn [rt]
      ;; materialize delegation's registry handle so its roster kind is a declared
      ;; publication backend before reviewers.clj contributes its roster partition
      ((requiring-resolve 'ct.spools.delegation/contribute)
       {:runtime rt :module/key :skein/spools-delegation})
      (load-file ".skein/reviewers.clj")
      (publish-module-contribution! rt :reviewers (requiring-resolve 'reviewers/contribute))
      (let [rosters ((requiring-resolve 'ct.spools.delegation/rosters))
            roster (first (filter #(= :change-review (:name %)) rosters))
            complex-roster (first (filter #(= :complex-patch-review (:name %)) rosters))
            docs-roster (first (filter #(= :docs-review (:name %)) rosters))]
        (is (= [:change-review :complex-patch-review :docs-review] (mapv :name rosters)))
        (let [sleeps (first (filter #(= "test-sleeps" (:name %)) (:seats roster)))]
          (is (some? sleeps) "owner-required test-sleeps seat is declared")
          (is (str/includes? (:brief sleeps) "time itself is a genuine component")))
        (is (= :sol-med (get-in roster [:synthesis :harness]))
            "sign-off synthesis stays on the cross-vendor GPT seat")
        (is (= :terra-med (get-in complex-roster [:synthesis :harness]))
            "complex patch review is synthesized outside its reviewer seats")
        (let [fact-check (first (filter #(= "docs-fact-check" (:name %)) (:seats docs-roster)))]
          (is (some? fact-check) "docs roster leads with the accuracy seat")
          (is (str/includes? (:brief fact-check) "NEVER the canonical .skein")))
        (is (= :sol-med (get-in docs-roster [:synthesis :harness]))
            "docs sign-off synthesis stays on the cross-vendor GPT seat")))))

(deftest codex-harness-persists-sessions-and-declares-resume
  ;; PLAN-Pnl-001.A2/PH2: the repo :codex harness drops --ephemeral (sessions
  ;; persist) and declares the verified `codex exec resume <session-id>` splice.
  (with-config-runtime
    (fn [_rt]
      (let [codex ((requiring-resolve 'ct.spools.agent-run/resolve-harness) :codex)]
        (is (not-any? #{"--ephemeral"} (:argv codex))
            "sessions persist so codex exec resume can continue them")
        (is (= ["resume" :agent-run/session-id] (:resume codex))
            "codex declares its verified resume subcommand splice")))))

(defn- shell-gate-complete!
  "Close the ready :shell land gate for feature the way the shell executor
  does — `complete!` with `:by \"shell\"` and the executor's own `shell/*`
  outcome attributes. The config fixture loads workflows.clj without installing
  the shell executor, so tests stand in for its pass path."
  [feature output]
  ((requiring-resolve 'skein.spools.workflow/complete!)
   feature {:by "shell" :attributes {"shell/exit-code" 0 "shell/output" output}}))

(defn- code-gate-complete!
  "Close the ready :code land gate with the executor's success attributes."
  [feature result]
  ((requiring-resolve 'skein.spools.workflow/complete!)
   feature {:by "code" :attributes {"code/result" result}}))

(defn- write-fake-gh!
  "Write a deterministic `gh` executable for feature-CI watch script tests."
  [dir]
  (let [file (io/file dir "gh")]
    (spit file
          (str "#!/bin/sh\n"
               "set -eu\n"
               "case \"$1 $2\" in\n"
               "  'pr view')\n"
               "    case \"$FAKE_GH_MODE\" in\n"
               "      delayed)\n"
               "        n=0\n"
               "        if [ -f \"$FAKE_GH_COUNTER\" ]; then n=$(cat \"$FAKE_GH_COUNTER\"); fi\n"
               "        n=$((n + 1))\n"
               "        printf '%s\\n' \"$n\" > \"$FAKE_GH_COUNTER\"\n"
               "        case \"$n\" in\n"
               "          1) printf '%s\\t0\\n' \"$FAKE_GH_STALE_SHA\" ;;\n"
               "          2) printf '%s\\t0\\n' \"$FAKE_GH_EXPECTED_SHA\" ;;\n"
               "          *) printf '%s\\t3\\n' \"$FAKE_GH_EXPECTED_SHA\" ;;\n"
               "        esac ;;\n"
               "      absent) printf '%s\\t0\\n' \"$FAKE_GH_EXPECTED_SHA\" ;;\n"
               "      stale-absent) printf '%s\\t0\\n' \"$FAKE_GH_STALE_SHA\" ;;\n"
               "      malformed-shape) printf 'not-a-pair\\n' ;;\n"
               "      malformed-head) printf 'not-a-sha\\t3\\n' ;;\n"
               "      short-head) printf 'deadbeef\\t3\\n' ;;\n"
               "      malformed-count) printf '%s\\tnot-a-count\\n' \"$FAKE_GH_EXPECTED_SHA\" ;;\n"
               "      lookup-fail) echo 'lookup failed' >&2; exit 42 ;;\n"
               "      watch-fail) printf '%s\\t3\\n' \"$FAKE_GH_EXPECTED_SHA\" ;;\n"
               "    esac ;;\n"
               "  'pr checks')\n"
               "    printf 'watch:%s\\n' \"$*\"\n"
               "    if [ \"$FAKE_GH_MODE\" = watch-fail ]; then exit 17; fi ;;\n"
               "  *) echo \"unexpected gh argv: $*\" >&2; exit 64 ;;\n"
               "esac\n"))
    (is (.setExecutable file true))
    file))

(defn- run-feature-ci-watch
  "Run executable against fake-gh-dir with mode and startup timeout, without sleeping."
  [executable fake-gh-dir mode expected-sha timeout]
  (let [env (merge (into {} (System/getenv))
                   {"PATH" (str (.getAbsolutePath fake-gh-dir)
                                java.io.File/pathSeparator
                                (System/getenv "PATH"))
                    "FAKE_GH_MODE" mode
                    "FAKE_GH_COUNTER" (str (io/file fake-gh-dir (str "counter-" mode)))
                    "FAKE_GH_EXPECTED_SHA" expected-sha
                    "FAKE_GH_STALE_SHA" (str/join (repeat 40 "0"))})]
    (sh/sh (.getAbsolutePath executable) "land-x" (str timeout) "0"
           :dir (System/getProperty "user.dir")
           :env env)))

(defn- write-fake-merge-gh!
  "Write a fake `gh` that records the merge script's command sequence."
  [dir]
  (let [file (io/file dir "gh")]
    (spit file
          (str "#!/bin/sh\n"
               "set -eu\n"
               "printf '%s\\n' \"$*\" >> \"$FAKE_GH_LOG\"\n"
               "case \"$1 $2\" in\n"
               "  'pr view')\n"
               "    case \"$*\" in\n"
               "      *'--json state'*)\n"
               "        case \"$FAKE_GH_MODE\" in\n"
               "          merged) printf 'MERGED\\n' ;;\n"
               "          invalid-state) printf 'CLOSED\\n' ;;\n"
               "          *) printf 'OPEN\\n' ;;\n"
               "        esac ;;\n"
               "      *'--json isDraft'*)\n"
               "        case \"$FAKE_GH_MODE\" in\n"
               "          ready-failure) printf 'true\\n' ;;\n"
               "          *) printf 'false\\n' ;;\n"
               "        esac ;;\n"
               "    esac ;;\n"
               "  'pr ready')\n"
               "    case \"$FAKE_GH_MODE\" in draft-recovery|ready-failure) exit 1 ;; esac ;;\n"
               "  'pr merge')\n"
               "    if [ \"$FAKE_GH_MODE\" = merge-failure ]; then exit 17; fi ;;\n"
               "  *) echo \"unexpected gh argv: $*\" >&2; exit 64 ;;\n"
               "esac\n"))
    (is (.setExecutable file true))
    file))

(defn- run-land-merge
  "Run executable against fake-gh-dir and return its process result."
  [executable fake-gh-dir mode subject body]
  (sh/sh (.getAbsolutePath executable) "412" subject body
         :dir (System/getProperty "user.dir")
         :env (merge (into {} (System/getenv))
                     {"PATH" (str (.getAbsolutePath fake-gh-dir)
                                  java.io.File/pathSeparator
                                  (System/getenv "PATH"))
                      "FAKE_GH_MODE" mode
                      "FAKE_GH_LOG" (.getAbsolutePath (io/file fake-gh-dir "merge.log"))})))

(defn- write-main-ci-fakes!
  "Write deterministic git and gh executables for main-CI code-gate tests."
  [bin-dir]
  (.mkdirs (io/file bin-dir))
  (let [git-file (io/file bin-dir "git")
        gh-file (io/file bin-dir "gh")]
    (spit git-file
          (str "#!/bin/sh\n"
               "set -eu\n"
               "pwd >> \"$FAKE_MAIN_CI_CWD_LOG\"\n"
               "if [ \"$*\" != 'rev-parse origin/main' ]; then\n"
               "  echo \"unexpected git argv: $*\" >&2\n"
               "  exit 64\n"
               "fi\n"
               "printf '%s\\n' \"$FAKE_MAIN_CI_SHA\"\n"))
    (spit gh-file
          (str "#!/bin/sh\n"
               "set -eu\n"
               "pwd >> \"$FAKE_MAIN_CI_CWD_LOG\"\n"
               "case \"$*\" in\n"
               "  *'--json status,conclusion')\n"
               "    case \"$FAKE_MAIN_CI_MODE\" in\n"
               "      polling)\n"
               "        n=0\n"
               "        if [ -f \"$FAKE_MAIN_CI_COUNTER\" ]; then n=$(cat \"$FAKE_MAIN_CI_COUNTER\"); fi\n"
               "        n=$((n + 1))\n"
               "        printf '%s\\n' \"$n\" > \"$FAKE_MAIN_CI_COUNTER\"\n"
               "        case \"$n\" in\n"
               "          1) printf '[{\"status\":\"in_progress\",\"conclusion\":null}]\\n' ;;\n"
               "          *) printf '[{\"status\":\"completed\",\"conclusion\":\"success\"},"
               "{\"status\":\"completed\",\"conclusion\":\"skipped\"}]\\n' ;;\n"
               "        esac ;;\n"
               "      failing)\n"
               "        printf '[{\"status\":\"completed\",\"conclusion\":\"failure\"}]\\n' ;;\n"
               "      malformed-json) printf '{not-json\\n' ;;\n"
               "      unknown-status)\n"
               "        printf '[{\"status\":\"mysterious\",\"conclusion\":null}]\\n' ;;\n"
               "      blocking)\n"
               "        printf '%s\\n' \"$$\" > \"$FAKE_MAIN_CI_PID.tmp\"\n"
               "        mv \"$FAKE_MAIN_CI_PID.tmp\" \"$FAKE_MAIN_CI_PID\"\n"
               "        read _ < \"$FAKE_MAIN_CI_RELEASE\" ;;\n"
               "    esac ;;\n"
               "  'run list --commit '*) printf 'failing workflow listing\\n' ;;\n"
               "  *) echo \"unexpected gh argv: $*\" >&2; exit 64 ;;\n"
               "esac\n"))
    (is (.setExecutable git-file true))
    (is (.setExecutable gh-file true))
    {:git git-file :gh gh-file}))

(defn- main-ci-env
  "Return a fake-command environment for mode under worktree."
  [worktree bin-dir mode]
  {"PATH" (str (.getAbsolutePath (io/file bin-dir))
               java.io.File/pathSeparator
               (System/getenv "PATH"))
   "FAKE_MAIN_CI_MODE" mode
   "FAKE_MAIN_CI_SHA" (str/join (repeat 40 "a"))
   "FAKE_MAIN_CI_COUNTER" (.getAbsolutePath (io/file worktree "counter"))
   "FAKE_MAIN_CI_CWD_LOG" (.getAbsolutePath (io/file worktree "cwd.log"))
   "FAKE_MAIN_CI_PID" (.getAbsolutePath (io/file worktree "gh.pid"))
   "FAKE_MAIN_CI_RELEASE" (.getAbsolutePath (io/file worktree "release"))})

(deftest main-ci-watch-polls-to-two-stable-green-snapshots-from-explicit-cwd
  (with-config-runtime
    (fn [_rt]
      (let [worktree (.toFile
                      (java.nio.file.Files/createTempDirectory
                       "skein-main-ci-worktree"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
            unrelated (.toFile
                       (java.nio.file.Files/createTempDirectory
                        "skein-main-ci-unrelated"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
            bin-dir (io/file worktree "bin")
            watch (requiring-resolve 'workflows/main-ci-watch)]
        (try
          (write-main-ci-fakes! bin-dir)
          (let [original-user-dir (System/getProperty "user.dir")]
            (try
              (System/setProperty "user.dir" (.getAbsolutePath unrelated))
              (is (= (str "all 2 workflow runs at " (str/join (repeat 40 "a"))
                          " completed successfully")
                     (watch {:worktree (.getAbsolutePath worktree)
                             :poll-interval-ms 0
                             :env (main-ci-env worktree bin-dir "polling")})))
              (finally
                (System/setProperty "user.dir" original-user-dir))))
          (is (= "3" (str/trim (slurp (io/file worktree "counter"))))
              "one pending poll plus two consecutive green polls are required")
          (is (every? #{(.getCanonicalPath worktree)}
                      (str/split-lines (slurp (io/file worktree "cwd.log")))))
          (finally
            (delete-directory! unrelated)
            (delete-directory! worktree)))))))

(deftest main-ci-watch-preserves-failing-run-listing
  (with-config-runtime
    (fn [_rt]
      (let [worktree (.toFile
                      (java.nio.file.Files/createTempDirectory
                       "skein-main-ci-failing"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
            bin-dir (io/file worktree "bin")
            watch (requiring-resolve 'workflows/main-ci-watch)]
        (try
          (write-main-ci-fakes! bin-dir)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"(?s)unsuccessful workflow runs at .*failing workflow listing"
               (watch {:worktree (.getAbsolutePath worktree)
                       :poll-interval-ms 0
                       :env (main-ci-env worktree bin-dir "failing")})))
          (finally
            (delete-directory! worktree)))))))

(deftest main-ci-watch-fails-loudly-on-malformed-params-and-gh-output
  (with-config-runtime
    (fn [_rt]
      (let [worktree (.toFile
                      (java.nio.file.Files/createTempDirectory
                       "skein-main-ci-malformed"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
            bin-dir (io/file worktree "bin")
            watch (requiring-resolve 'workflows/main-ci-watch)]
        (try
          (write-main-ci-fakes! bin-dir)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"params must satisfy the declared spec"
                                (watch {:worktree ""})))
          (try
            (watch {:worktree (.getAbsolutePath worktree)
                    :poll-interval-ms 0
                    :env (main-ci-env worktree bin-dir "malformed-json")})
            (is false "malformed JSON must throw")
            (catch clojure.lang.ExceptionInfo error
              (is (= "gh run list returned malformed JSON" (ex-message error)))
              (is (= "{not-json\n" (:out (ex-data error))))))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"unknown workflow statuses"
               (watch {:worktree (.getAbsolutePath worktree)
                       :poll-interval-ms 0
                       :env (main-ci-env worktree bin-dir "unknown-status")})))
          (finally
            (delete-directory! worktree)))))))

(deftest main-ci-watch-timeout-interrupt-destroys-the-active-child
  (with-config-runtime
    (fn [_rt]
      (let [worktree (.toFile
                      (java.nio.file.Files/createTempDirectory
                       "skein-main-ci-blocking"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
            bin-dir (io/file worktree "bin")
            pid-file (io/file worktree "gh.pid")
            watcher (.newWatchService (java.nio.file.FileSystems/getDefault))
            watch (requiring-resolve 'workflows/main-ci-watch)]
        (try
          (write-main-ci-fakes! bin-dir)
          (is (zero? (:exit (sh/sh "mkfifo" (.getAbsolutePath
                                             (io/file worktree "release"))))))
          (.register (.toPath worktree)
                     watcher
                     (into-array java.nio.file.WatchEvent$Kind
                                 [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))
          (let [call (future
                       (watch {:worktree (.getAbsolutePath worktree)
                               :poll-interval-ms 0
                               :env (main-ci-env worktree bin-dir "blocking")}))
                pid (loop []
                      (if-let [pid (when (.exists pid-file)
                                     (parse-long (str/trim (slurp pid-file))))]
                        pid
                        (let [key (.take watcher)]
                          (.reset key)
                          (recur))))]
            (is (pos-int? pid) "the fake gh child published its pid")
            (let [handle (.orElseThrow (java.lang.ProcessHandle/of pid))]
              (future-cancel call)
              (is (thrown? java.util.concurrent.CancellationException @call))
              (.get (.onExit handle))
              (is (not (.isAlive handle))
                  "interruption destroys and joins the active gh child")))
          (finally
            (.close watcher)
            (delete-directory! worktree)))))))

(deftest land-feature-ci-watch-waits-for-check-registration-and-preserves-failures
  (with-config-runtime
    (fn [rt]
      (let [_ (start-land! "land-ci-script" "land-x" (System/getProperty "user.dir"))
            completed (op! "land" ["complete" "land-ci-script" "--pr-number" "411"])
            gate-attrs (:attributes (weaver/show rt (get-in completed [:ready 0 :id])))
            [shell-command shell-flag script script-name branch startup-timeout poll-interval]
            (:shell/argv gate-attrs)
            executable (io/file ".skein/scripts/feature-ci-watch.sh")
            expected-sha (str/trim (:out (sh/sh "git" "rev-parse" "HEAD")))
            fake-gh-dir (.toFile
                         (java.nio.file.Files/createTempDirectory
                          "skein-land-fake-gh"
                          (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (write-fake-gh! fake-gh-dir)
          (is (.canExecute executable))
          (is (= script (slurp executable)))
          (is (= ["sh" "-c" "land-ci-watch" "land-x" "180" "5"]
                 [shell-command shell-flag script-name branch startup-timeout poll-interval]))
          (let [{:keys [exit out err]} (run-feature-ci-watch executable fake-gh-dir "delayed" expected-sha 10)]
            (is (zero? exit))
            (is (= "watch:pr checks land-x --watch --fail-fast\n" out))
            (is (= "" err))
            (is (= "3" (str/trim (slurp (io/file fake-gh-dir "counter-delayed"))))))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "absent" expected-sha 0)]
            (is (= 1 exit))
            (is (str/includes? err "timed out after 0s waiting for CI checks on land-x"))
            (is (str/includes? err (str "expected HEAD: " expected-sha)))
            (is (str/includes? err (str "last PR HEAD: " expected-sha "; checks: 0"))))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "stale-absent" expected-sha 0)]
            (is (= 1 exit))
            (is (str/includes? err (str "expected HEAD: " expected-sha)))
            (is (str/includes? err (str "last PR HEAD: " (str/join (repeat 40 "0"))))))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "malformed-shape" expected-sha 10)]
            (is (= 1 exit))
            (is (str/includes? err "malformed PR check metadata")))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "malformed-head" expected-sha 10)]
            (is (= 1 exit))
            (is (str/includes? err "malformed PR head")))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "short-head" expected-sha 10)]
            (is (= 1 exit))
            (is (str/includes? err "malformed PR head for land-x: deadbeef")))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "malformed-count" expected-sha 10)]
            (is (= 1 exit))
            (is (str/includes? err "malformed PR check count")))
          (let [{:keys [exit err]} (run-feature-ci-watch executable fake-gh-dir "lookup-fail" expected-sha 10)]
            (is (= 42 exit))
            (is (str/includes? err "lookup failed")))
          (let [{:keys [exit out]} (run-feature-ci-watch executable fake-gh-dir "watch-fail" expected-sha 10)]
            (is (= 17 exit))
            (is (= "watch:pr checks land-x --watch --fail-fast\n" out)))
          (finally
            (delete-directory! fake-gh-dir)))))))

(deftest land-merge-script-runs-standalone-with-argv-values
  (let [executable (io/file ".skein/scripts/land-merge.sh")
        fake-gh-dir (.toFile
                     (java.nio.file.Files/createTempDirectory
                      "skein-land-merge-fake-gh"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        log-file (io/file fake-gh-dir "merge.log")
        subject "feat: keep argv intact"
        body "Squashed commits: abc123; echo not-interpolated"]
    (try
      (write-fake-merge-gh! fake-gh-dir)
      (is (.canExecute executable))
      (let [{:keys [exit out err]} (run-land-merge executable fake-gh-dir "open" subject body)]
        (is (zero? exit))
        (is (= "" out))
        (is (= "" err))
        (is (= ["pr view 412 --json state --jq .state"
                "pr ready 412"
                (str "pr merge 412 --squash --subject " subject " --body " body)]
               (str/split-lines (slurp log-file)))))
      (doseq [[mode expected-exit expected-out expected-err]
              [["merged" 0 "already merged: 412\n" ""]
               ["invalid-state" 1 "" "cannot merge PR 412: state is CLOSED; expected OPEN or MERGED\n"]
               ["ready-failure" 1 "" "failed to mark PR ready: 412\n"]
               ["merge-failure" 17 "" ""]
               ["draft-recovery" 0 "" ""]]]
        (io/delete-file log-file true)
        (let [{:keys [exit out err]} (run-land-merge executable fake-gh-dir mode subject body)]
          (is (= expected-exit exit) mode)
          (is (= expected-out out) mode)
          (is (= expected-err err) mode)))
      (is (= ["pr view 412 --json state --jq .state"
              "pr ready 412"
              "pr view 412 --json isDraft --jq .isDraft"
              (str "pr merge 412 --squash --subject " subject " --body " body)]
             (str/split-lines (slurp log-file))))
      (finally
        (delete-directory! fake-gh-dir)))))

(deftest land-ops-drive-a-poured-run-end-to-end
  (with-config-runtime
    (fn [rt]
      (let [started (start-land! "land-x" "land-x" "/tmp/land-x")]
        (is (= "workflow start" (:operation started)))
        (is (false? (:done started)))
        (is (= "land.pr.open" (:action-ref (first (:ready started)))))
        (is (not (contains? (first (:ready started)) :choice-details))))
      ;; completing push-draft-pr leaves the machine ci-green shell gate ready,
      ;; carrying the interpolated watch command for the shell executor
      (let [completed (op! "land" ["complete" "land-x" "--pr-number" "412"])
            gate (first (:ready completed))
            gate-attrs (:attributes (weaver/show rt (:id gate)))
            context (get-in ((requiring-resolve 'skein.spools.workflow/current-root) "land-x")
                            [:attributes :workflow/context])]
        (is (= "land complete" (:operation completed)))
        (is (= 412 (or (:pr-number context) (get context "pr-number"))))
        (is (= "land.ci.green" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (not (contains? gate :choice-details)))
        (is (= ["sh" "-c" "land-ci-watch" "land-x" "180" "5"]
               (let [[command flag _script & args] (:shell/argv gate-attrs)]
                 (into [command flag] args))))
        (is (= "/tmp/land-x" (:shell/cwd gate-attrs))))
      ;; a coordinator cannot hand-close a CI gate; the shell executor owns it
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No ready workflow step"
                            (op! "workflow" ["complete" "land-x"])))
      (shell-gate-complete! "land-x" "checks green")
      (is (= "land.signoff.review"
             (:action-ref (first (:ready (op! "workflow" ["ready" "land-x"]))))))
      (let [at-checkpoint (op! "workflow" ["complete" "land-x"])
            checkpoint (first (:ready at-checkpoint))
            next-checkpoint (first (:ready (op! "workflow" ["ready" "land-x"])))
            status-checkpoint (first (:ready (op! "workflow" ["ready" "land-x"])))]
        (is (= "checkpoint" (:role checkpoint)))
        (is (= "signoff" (:checkpoint checkpoint)))
        (is (= ["approved" "revise" "abort"] (:choices checkpoint)))
        (is (= checkpoint next-checkpoint status-checkpoint))
        (is (not (contains? checkpoint :choice-details))))
      ;; approved and abort each name one whole-map input spec; revise takes no
      ;; input and re-pours the current definition from saved context
      (let [choices ((requiring-resolve 'skein.spools.workflow/choice-details) "land-x")
            approved-input (get-in choices ["approved" "input-spec"])
            abort-input (get-in choices ["abort" "input-spec"])]
        (is (= #{"approved" "revise" "abort"} (set (keys choices))))
        (is (= "workflows/land-merge-input" (get approved-input "spec")))
        (is (= "workflows/land-abort-input" (get abort-input "spec")))
        (is (= "(clojure.spec.alpha/and (clojure.spec.alpha/keys :req-un [:workflows/subject :workflows/body]) (clojure.core/fn [%] (clojure.core/every? #{:body :subject} (clojure.core/keys %))))"
               (get (first (get approved-input "spec-forms")) "form")))
        (is (= "(clojure.spec.alpha/and (clojure.spec.alpha/keys :req-un [:workflows/reason]) (clojure.core/fn [%] (clojure.core/every? #{:reason} (clojure.core/keys %))))"
               (get (first (get abort-input "spec-forms")) "form"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Value does not satisfy the named spec"
                            (op! "land" ["choose" "land-x" "approved" "--input" "{}"])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"input contains unsupported keys"
           (op! "land" ["choose" "land-x" "approved" "--input"
                        (json/write-str {:subject "feat: land x"
                                         :body "Squashed commits: abc123"
                                         :subjet "typo"})])))
      ;; approval routes to the mechanical merge continuation. Subject and body
      ;; remain argv elements rather than being interpolated into shell source.
      (let [subject "feat: land x"
            body "Squashed commits: abc123"
            approved (op! "land" ["choose" "land-x" "approved" "--input"
                                  (json/write-str {:subject subject :body body})])
            gate (first (:ready approved))
            gate-attrs (:attributes (weaver/show rt (:id gate)))
            script (nth (:shell/argv gate-attrs) 2)]
        (is (= "land choose" (:operation approved)))
        (is (= "land.pr.merge" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (not (contains? gate :choice-details)))
        (is (str/includes? script "gh pr merge"))
        (is (= ["sh" "-c" script "land-merge" "412" subject body]
               (:shell/argv gate-attrs)))
        (is (= ["merge-lock"] (mapv #(get-in % [:attributes :kind]) (active-merge-locks)))))
      (start-land! "land-z" "land-z" "/tmp/land-z")
      (op! "land" ["complete" "land-z" "--pr-number" "413"])
      (shell-gate-complete! "land-z" "checks green")
      (op! "workflow" ["complete" "land-z"])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another land run holds the merge lock"
                            (op! "land" ["choose" "land-z" "approved" "--input"
                                         (json/write-str {:subject "feat: land z"
                                                          :body "Squashed commits: def456"})])))
      (shell-gate-complete! "land-x" "PR merged")
      (let [gate (first (:ready (op! "workflow" ["ready" "land-x"])))
            gate-attrs (:attributes (weaver/show rt (:id gate)))]
        (is (= "land.main.pull" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (str/includes? (last (:shell/argv gate-attrs)) "--ff-only")))
      (shell-gate-complete! "land-x" "main fast-forwarded")
      (let [gate (first (:ready (op! "workflow" ["ready" "land-x"])))
            gate-attrs (:attributes (weaver/show rt (:id gate)))]
        (is (= "land.main.ci-green" (:action-ref gate)))
        (is (= "code" (:gate gate)))
        (is (= "workflows/main-ci-watch" (:code/fn gate-attrs)))
        (is (= {:worktree "/tmp/land-x"} (:code/params gate-attrs)))
        (is (= 5400 (:code/timeout-secs gate-attrs))))
      (code-gate-complete! "land-x" "main runs green")
      (let [gate (first (:ready (op! "workflow" ["ready" "land-x"])))
            gate-attrs (:attributes (weaver/show rt (:id gate)))]
        (is (= "land.branch-worktree.cleanup" (:action-ref gate)))
        (is (= "shell" (:gate gate)))
        (is (= ["land-cleanup" "land-x" "/tmp/land-x"]
               (drop 3 (:shell/argv gate-attrs))))
        (is (str/includes? (nth (:shell/argv gate-attrs) 2) "fetch origin --prune"))
        (is (str/includes? (nth (:shell/argv gate-attrs) 2) "pwd -P"))
        (is (str/includes? (nth (:shell/argv gate-attrs) 2) "failed to inspect remote branch"))
        (is (str/includes? (nth (:shell/argv gate-attrs) 2) "git -C \"$canonical\" worktree remove"))
        (is (str/includes? (nth (:shell/argv gate-attrs) 2) "git -C \"$canonical\" branch -D"))
        (is (not (str/includes? (nth (:shell/argv gate-attrs) 2) "wktree"))))
      (shell-gate-complete! "land-x" "branch and worktree removed")
      (let [ready-tidy (op! "workflow" ["ready" "land-x"])
            tidy-step (first (:ready ready-tidy))]
        (is (= "land.resources.tidy" (:action-ref tidy-step)))
        (is (str/includes? (:instruction tidy-step) "tmux sessions"))
        (is (str/includes? (:instruction tidy-step) "recorded PID")))
      (op! "workflow" ["complete" "land-x"])
      (let [ready-cleanup (op! "workflow" ["ready" "land-x"])
            cleanup-step (first (:ready ready-cleanup))]
        (is (= "workflow ready" (:operation ready-cleanup)))
        (is (= "land.cleanup" (:action-ref cleanup-step)))
        ;; cardless run: the cleanup instruction must omit kanban-finish
        ;; entirely rather than render a literal "<card>" placeholder
        (is (not (str/includes? (:instruction cleanup-step) "kanban finish")))
        (is (not (str/includes? (:instruction cleanup-step) "<card>"))))
      (let [owned (first (active-merge-locks))]
        (weaver/update! rt (:id owned) {:state "closed"})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cleanup requires its active merge lock"
                              (op! "land" ["complete" "land-x"])))
        (let [foreign (weaver/add! rt {:title "Foreign merge lock"
                                       :attributes {:kind "merge-lock"
                                                    :land/run-id "other-land-run"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"another land run holds the merge lock"
                                (op! "land" ["complete" "land-x"])))
          (weaver/update! rt (:id foreign) {:state "closed"}))
        (weaver/update! rt (:id owned) {:state "active"}))
      (let [duplicate (weaver/add! rt {:title "Corrupt duplicate merge lock"
                                       :attributes {:kind "merge-lock"
                                                    :land/run-id "other-land-run"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"multiple active merge locks found"
                              (op! "land" ["complete" "land-x"])))
        (is (= 2 (count (active-merge-locks)))
            "terminal cleanup must not normalize a corrupt lock set")
        (weaver/update! rt (:id duplicate) {:state "closed"})
        (let [done (op! "land" ["complete" "land-x"])]
          (is (true? (:done done)))
          (is (empty? (:ready done)))))
      (let [status (op! "workflow" ["ready" "land-x"])]
        (is (= "workflow ready" (:operation status)))
        (is (true? (:done status)))
        (is (empty? (:ready status)))
        (is (empty? (active-merge-locks)))
        ;; history is not a land concern: read a run's past through trusted Clojure
        (is (not (contains? status :history)))))))

(deftest land-push-draft-pr-requires-context-and-rolls-back-card-lane
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (weaver/add! rt {:title "PR context card"
                                          :attributes {:kanban/card "true"
                                                       :kanban/lane "claimed"
                                                       :kanban/type "feature"}}))
            _ (start-land! "land-pr-context" "land-pr-context"
                           "/tmp/land-pr-context" card-id)
            root-before ((requiring-resolve 'skein.spools.workflow/current-root)
                         "land-pr-context")
            context-before (get-in root-before [:attributes :workflow/context])]
        (doseq [[argv message] [[["complete" "land-pr-context"]
                                 #"push-draft-pr completion requires a positive --pr-number"]
                                [["complete" "land-pr-context" "--pr-number" "0"]
                                 #"push-draft-pr completion requires a positive --pr-number"]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                message
                                (op! "land" argv)))
          (is (= "active" (:state (weaver/show rt (:id root-before)))))
          (is (= context-before
                 (get-in ((requiring-resolve 'skein.spools.workflow/current-root)
                          "land-pr-context")
                         [:attributes :workflow/context])))
          (is (= "claimed"
                 (get-in (weaver/show rt card-id) [:attributes :kanban/lane]))))
        (op! "land" ["complete" "land-pr-context" "--pr-number" "417"])
        (shell-gate-complete! "land-pr-context" "checks green")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"requires a PR-open or terminal policy frontier"
                              (op! "land" ["complete" "land-pr-context"
                                           "--pr-number" "418"])))))))

(deftest land-signoff-invalid-pr-number-leaves-no-durable-mutation
  (with-config-runtime
    (fn [rt]
      (start-land! "land-invalid-pr" "land-invalid-pr" "/tmp/land-invalid-pr")
      ;; Bypass the land wrapper to model a pre-feature run whose draft-PR step
      ;; closed without producing :pr-number.
      ((requiring-resolve 'skein.spools.workflow/complete!) "land-invalid-pr")
      (shell-gate-complete! "land-invalid-pr" "checks green")
      (op! "workflow" ["complete" "land-invalid-pr"])
      (let [old-root ((requiring-resolve 'skein.spools.workflow/current-root)
                      "land-invalid-pr")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Value does not satisfy the named spec"
                              (op! "land" ["choose" "land-invalid-pr" "approved" "--input"
                                           (json/write-str {:subject "feat: invalid"
                                                            :body "body"})])))
        (is (= "active" (:state (weaver/show rt (:id old-root))))
            "invalid sign-off leaves the old workflow root active")
        (is (empty? (active-merge-locks))
            "invalid sign-off leaves no durable merge lock")))))

(deftest land-policy-rollbacks-preserve-idempotent-pre-call-state
  (with-config-runtime
    (fn [rt]
      (let [review-card (:id (weaver/add! rt {:title "Already reviewed card"
                                              :attributes {:kanban/card "true"
                                                           :kanban/lane "in_review"
                                                           :kanban/type "feature"}}))]
        (start-land! "land-review-idempotent" "land-review-idempotent"
                     "/tmp/land-review-idempotent" review-card)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"push-draft-pr completion requires a positive --pr-number"
             (op! "land" ["complete" "land-review-idempotent"
                          "--pr-number" "0"])))
        (is (= "in_review"
               (get-in (weaver/show rt review-card) [:attributes :kanban/lane]))))
      (let [claimed-card (:id (weaver/add! rt {:title "Already claimed abort card"
                                               :attributes {:kanban/card "true"
                                                            :kanban/lane "claimed"
                                                            :kanban/type "feature"}}))]
        (start-land! "land-abort-idempotent" "land-abort-idempotent"
                     "/tmp/land-abort-idempotent" claimed-card)
        (op! "land" ["complete" "land-abort-idempotent" "--pr-number" "420"])
        (shell-gate-complete! "land-abort-idempotent" "checks green")
        (op! "workflow" ["complete" "land-abort-idempotent"])
        ((requiring-resolve 'ct.spools.kanban/rework!) rt claimed-card)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Value does not satisfy the named spec"
                              (op! "land" ["choose" "land-abort-idempotent" "abort"
                                           "--input" "{}"])))
        (is (= "claimed"
               (get-in (weaver/show rt claimed-card) [:attributes :kanban/lane])))))))

(deftest land-failed-approval-retains-a-reused-same-run-lock
  (with-config-runtime
    (fn [rt]
      (start-land! "land-reused-lock" "land-reused-lock" "/tmp/land-reused-lock")
      (op! "land" ["complete" "land-reused-lock" "--pr-number" "421"])
      (shell-gate-complete! "land-reused-lock" "checks green")
      (op! "workflow" ["complete" "land-reused-lock"])
      (let [root ((requiring-resolve 'skein.spools.workflow/current-root)
                  "land-reused-lock")
            lock (weaver/add! rt {:title "Merge lock: land-reused-lock"
                                  :attributes {:kind "merge-lock"
                                               :owner (:id root)
                                               :land/run-id "land-reused-lock"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Value does not satisfy the named spec"
                              (op! "land" ["choose" "land-reused-lock" "approved"
                                           "--input" "{}"])))
        (is (= "active" (:state (weaver/show rt (:id lock)))))
        (is (= [(:id lock)] (mapv :id (active-merge-locks))))
        (weaver/add! rt {:title "Corrupt second merge lock"
                         :attributes {:kind "merge-lock"
                                      :land/run-id "other-land-run"}})
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"multiple active merge locks found"
             (op! "land" ["choose" "land-reused-lock" "approved"
                          "--input"
                          (json/write-str {:subject "feat: reused"
                                           :body "Squashed commits: abc123"})])))))))

(deftest land-signoff-revise-repours-with-context-and-no-merge-lock
  (with-config-runtime
    (fn [rt]
      (start-land! "land-revise" "land-revise" "/tmp/land-revise")
      (op! "land" ["complete" "land-revise" "--pr-number" "419"])
      (shell-gate-complete! "land-revise" "checks green")
      (op! "workflow" ["complete" "land-revise"])
      (let [old-root ((requiring-resolve 'skein.spools.workflow/current-root) "land-revise")
            old-context (get-in old-root [:attributes :workflow/context])
            revised (op! "workflow" ["choose" "land-revise" "revise"])
            new-root ((requiring-resolve 'skein.spools.workflow/current-root) "land-revise")]
        (is (= "land.pr.open" (:action-ref (first (:ready revised)))))
        (is (not= (:id old-root) (:id new-root)))
        (is (= "closed" (:state (weaver/show rt (:id old-root)))))
        (is (= old-context (get-in new-root [:attributes :workflow/context])))
        (is (= 419 (get-in new-root [:attributes :workflow/context :pr-number])))
        (is (empty? (active-merge-locks)))))))

(deftest land-signoff-abort-routes-to-record-step
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (weaver/add! rt {:title "Abort card"
                                          :attributes {:kanban/card "true"
                                                       :kanban/lane "claimed"
                                                       :kanban/type "feature"}}))]
        (start-land! "land-y" "land-y" "/tmp/land-y" card-id))
      ;; completing push-draft-pr starts the automated CI watch and review
      ;; pipeline, so it is the completion that moves the card to in_review
      (op! "land" ["complete" "land-y" "--pr-number" "414"]) ; push-draft-pr
      (let [root ((requiring-resolve 'skein.spools.workflow/current-root) "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "in_review" (get-in (weaver/show rt card-id) [:attributes :kanban/lane]))))
      (shell-gate-complete! "land-y" "checks green") ; ci-green
      (op! "workflow" ["complete" "land-y"])           ; signoff-review
      (let [aborted (op! "land" ["choose" "land-y" "abort" "--input"
                                 (json/write-str {:reason "scope changed"})])]
        (is (= "land choose" (:operation aborted)))
        ;; routing is a hard cutover to the reason-recording continuation
        (is (= "land.abort.record" (:action-ref (first (:ready aborted))))))
      (let [root ((requiring-resolve 'skein.spools.workflow/current-root) "land-y")
            context (get-in root [:attributes :workflow/context])
            card-id (or (:card context) (get context "card"))]
        (is (= "claimed" (get-in (weaver/show rt card-id) [:attributes :kanban/lane]))))
      (let [done (op! "land" ["complete" "land-y"])]
        (is (true? (:done done)))
        (is (empty? (:ready done)))))))

(deftest land-cleanup-instruction-interpolates-the-real-card-id
  (with-config-runtime
    (fn [rt]
      (let [card-id (:id (weaver/add! rt {:title "Cleanup card"
                                          :attributes {:kanban/card "true"
                                                       :kanban/lane "claimed"
                                                       :kanban/type "feature"}}))]
        (start-land! "land-w" "land-w" "/tmp/land-w" card-id)
        (op! "land" ["complete" "land-w" "--pr-number" "415"])      ; push-draft-pr
        (shell-gate-complete! "land-w" "checks green")              ; ci-green
        (op! "workflow" ["complete" "land-w"])                          ; signoff-review
        (op! "land" ["choose" "land-w" "approved" "--input"
                     (json/write-str {:subject "feat: land w"
                                      :body "Squashed commits: abc123"})])
        (shell-gate-complete! "land-w" "PR merged")                  ; merge-pr
        (shell-gate-complete! "land-w" "main fast-forwarded")       ; pull-main
        (code-gate-complete! "land-w" "main runs green")            ; main-ci-green
        (shell-gate-complete! "land-w" "branch and worktree removed") ; remove-branch-worktree
        (op! "workflow" ["complete" "land-w"])                           ; tidy-created-resources
        (let [ready-cleanup (op! "workflow" ["ready" "land-w"])
              cleanup-step (first (:ready ready-cleanup))]
          (is (= "land.cleanup" (:action-ref cleanup-step)))
          (is (str/includes? (:instruction cleanup-step)
                             (str "strand kanban finish " card-id " --outcome done")))
          (is (not (str/includes? (:instruction cleanup-step) "<card>"))))))))

(deftest land-break-lock-closes-active-sentinel-with-reason
  (with-config-runtime
    (fn [_rt]
      (start-land! "land-lock-x" "land-lock-x" "/tmp/land-lock-x")
      (op! "land" ["complete" "land-lock-x" "--pr-number" "416"])
      (shell-gate-complete! "land-lock-x" "checks green")
      (op! "workflow" ["complete" "land-lock-x"])
      (op! "land" ["choose" "land-lock-x" "approved" "--input"
                   (json/write-str {:subject "feat: land lock x"
                                    :body "Squashed commits: abc123"})])
      (is (= ["merge-lock"] (mapv #(get-in % [:attributes :kind]) (active-merge-locks))))
      ;; a blank reason fails at the handler; a missing reason fails at the arg-spec
      ;; parse layer — both are loud rejections rather than a silent break
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reason must be a non-blank string"
                            (op! "land" ["break-lock" "--reason" ""])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --reason"
                            (op! "land" ["break-lock"])))
      (let [broken (op! "land" ["break-lock" "--reason" "coordinator confirmed stale lock"])]
        (is (= "land break-lock" (:operation broken)))
        (is (= "closed" (get-in broken [:broken :state])))
        (is (= "coordinator confirmed stale lock"
               (get-in broken [:broken :attributes :land/broken-reason])))
        (is (empty? (active-merge-locks))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no active merge lock to break"
                            (op! "land" ["break-lock" "--reason"
                                         "must not report a nonexistent intervention"]))))))

(deftest land-break-lock-refuses-to-break-when-multiple-locks-are-active
  (with-config-runtime
    (fn [rt]
      ;; a healthy world holds one lock; two active merge-lock strands is a
      ;; corrupt state break-lock must refuse rather than pick one arbitrarily.
      (weaver/add! rt {:title "Merge lock: land-dup-a"
                       :attributes {:kind "merge-lock" :land/run-id "land-dup-a"}})
      (weaver/add! rt {:title "Merge lock: land-dup-b"
                       :attributes {:kind "merge-lock" :land/run-id "land-dup-b"}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"multiple active merge locks found"
                            (op! "land" ["break-lock" "--reason"
                                         "trying to clear a corrupt state"]))))))

(deftest generic-land-start-fails-loudly-on-a-blank-card
  (with-config-runtime
    (fn [_rt]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Value does not satisfy the named spec"
           (op! "workflow"
                ["start" "land-blank-card"
                 "--workflow" "land"
                 "--params" "{\"feature\":\"land-blank-card\",\"branch\":\"land-blank-card\",\"worktree\":\"/tmp/land-blank-card\",\"card\":\"\"}"])))
      (is (= "workflow start"
             (:operation (start-land! "land-no-card" "land-no-card"
                                      "/tmp/land-no-card")))))))

(deftest land-op-renders-arg-spec-subcommand-help-and-fails-loudly
  (with-config-runtime
    (fn [_rt]
      (let [help (op! "help" ["land"])
            subs (get-in help [:node :children])
            by-name (into {} (map (juxt :name identity)) subs)]
        (is (= #{"complete" "choose" "break-lock"}
               (set (map :name subs))))
        (is (str/starts-with? (get-in help [:node :doc])
                              "Enforce the cross-domain policy boundaries"))
        (is (= [["pr-number" false "int"]]
               (mapv (juxt :name :required :type)
                     (get-in by-name ["complete" :invocation :flags]))))
        (is (= [["input" true "string" "json"]]
               (mapv (juxt :name :required :type :parse)
                     (get-in by-name ["choose" :invocation :flags]))))
        (is (= [["reason" true "string"]]
               (mapv (juxt :name :required :type)
                     (get-in by-name ["break-lock" :invocation :flags]))))
        (is (= [["run-id" true false]]
               (mapv (juxt :name :required :variadic)
                     (get-in by-name ["complete" :invocation :positionals]))))
        (is (= [["run-id" true false] ["choice" true false]]
               (mapv (juxt :name :required :variadic)
                     (get-in by-name ["choose" :invocation :positionals])))))
      ;; required flags and positionals fail loudly at parse
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --input"
                            (op! "land" ["choose" "no-input" "approved"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accepts only approved or abort"
                            (op! "land" ["choose" "unused-run" "revise"
                                         "--input" "{}"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument run-id"
                            (op! "land" ["complete"]))))))

(defn- assert-treadle-installed-after-runtime-dependencies
  "Assert the subagent executor module orders after the modules it consumes.

  A green startup fixture already proves every required module applied (start!
  throws otherwise), so the load-order guarantee lives in the declared `:after`
  edges, read from the module graph."
  [rt]
  (let [decl (get-in (runtime/status rt) [:modules :skein/spools-treadle])]
    (is (some? decl) ":skein/spools-treadle is a declared module")
    (is (every? (set (:after decl)) [:harnesses :workflows])
        "treadle depends on :harnesses and :workflows")))

(defn- assert-workflow-spool-consent-edges
  "Assert repo startup guards every module that relies on the workflow coordinate."
  [rt]
  (let [modules (:modules (runtime/status rt))]
    (doseq [id [:skein/spools-workflow :skein/spools-workflow-cli
                :skein/spools-shell :skein/spools-code]]
      (is (= ['skein.spools/workflow] (:spools (get modules id)))
          (str id " must opt into skein.spools/workflow")))
    (is (= [:skein/spools-workflow] (:after (get modules :skein/spools-workflow-cli)))
        "the opt-in worker CLI orders after the engine module it contributes beside")
    (is (= ['skein.spools/workflow 'ct.spools/agent-run
            'codethread/devflow 'skein.macros/macros]
           (:spools (get modules :config)))
        ":config must guard every spool coordinate its config.clj ns requires")
    (is (true? (:required? (get modules :config)))
        ":config is required — a guarded but non-required module skips silently, dropping the op/query surface")
    (is (= ['skein.spools/workflow 'ct.spools/delegation 'skein.macros/macros]
           (:spools (get modules :workflows)))
        ":workflows must opt into skein.spools/workflow, ct.spools/delegation, and the authoring macros")
    (is (= [:skein/spools-workflow :workflows]
           (:after (get modules :skein/spools-code)))
        "the code executor scans only after workflows/main-ci-watch is loaded")
    (is (= (requiring-resolve 'workflows/main-ci-watch)
           (runtime/resolve-var rt 'workflows/main-ci-watch))
        "cold startup resolves the exact persisted code/fn symbol")))

(defn- assert-kanban-tracker-installed
  "Assert startup declared the required devflow tracker binding and it is live."
  [rt]
  (let [decl (get-in (runtime/status rt) [:modules :kanban/tracker])]
    (is (some? decl) ":kanban/tracker is a declared module")
    (is (true? (:required? decl)))
    (is (nil? (ns-resolve 'kanban-tracker 'install!))
        "the workspace module exposes no legacy installer")
    (is (re-find #"Bound tracker: devflow" (:tracker (op! "kanban" ["about"]))))))

(deftest kanban-tracker-devflow-projection-contract
  (with-config-runtime
    (fn [rt]
      (load-file ".skein/kanban_tracker.clj")
      (let [project (requiring-resolve 'kanban-tracker/devflow-projection)
            current-root (requiring-resolve 'ct.spools.devflow/current-root)
            ready (requiring-resolve 'ct.spools.devflow/ready)]
        (testing "an active root projects its stage and ready steps"
          (with-redefs-fn {current-root (constantly {:attributes {:devflow/stage "tasks"}})
                           ready (constantly [{:id "next" :title "Do next" :role "step"}])}
            #(is (= {:status "tasks"
                     :ready [{:id "next" :title "Do next" :role "step"}]}
                    (project rt "active-run")))))
        (testing "no active root is the accepted nil-status projection"
          (with-redefs-fn {current-root (constantly nil)
                           ready (fn [_] (throw (ex-info "must not read steps" {})))}
            #(is (= {:status nil :ready []}
                    (project rt "inactive-run")))))
        (testing "a malformed run id fails at the adapter boundary"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                                (project rt ""))))
        (testing "an active root without a stage fails loudly"
          (with-redefs-fn {current-root (constantly {:attributes {}})}
            #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank devflow/stage"
                                   (project rt "missing-stage")))))
        (testing "malformed ready steps fail the owning kanban projection spec"
          (with-redefs-fn {current-root (constantly {:attributes {:devflow/stage "tasks"}})
                           ready (constantly [{}])}
            #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"projection must match"
                                   (project rt "malformed-step")))))))))

(deftest repo-local-startup-and-refresh-preserve-registrations
  (with-startup-config-runtime
    (fn [rt]
      (assert-config-registrations rt)
      (assert-treadle-installed-after-runtime-dependencies rt)
      (assert-workflow-spool-consent-edges rt)
      (assert-kanban-tracker-installed rt)
      (is (map? (op! "help" ["agent"])))
      (is (seq (op! "agent" ["harnesses"])))
      (is (= "bench about" (:operation (op! "bench" ["about"]))))
      (is (str/includes? (:tracker (op! "kanban" ["about"]))
                         "Bound tracker: devflow"))
      (op! "workflow" ["start" "startup-feature"
                       "--workflow" "intake"
                       "--params" (json/write-str {:feature "startup-feature"
                                                   :worktree-check "already-in-worktree-ok"})])
      (let [refresh-result (runtime/refresh! rt)]
        (is (contains? #{:applied :unchanged} (:status refresh-result))))
      (let [refresh-result (runtime/refresh! rt {:only #{:config}})]
        (is (contains? #{:applied :unchanged} (:status refresh-result))))
      (is (every? #(= :applied (:status %))
                  (vals (:resource/outcomes (runtime/status rt)))))
      (assert-config-registrations rt)
      (assert-workflow-spool-consent-edges rt)
      (assert-kanban-tracker-installed rt)
      ;; Module-owned registrations refresh; the strand graph and run state persist.
      (let [status (op! "workflow" ["ready" "startup-feature"])]
        (is (false? (:done status)))
        (is (= "create-or-confirm-worktree" (:checkpoint (first (:ready status)))))))))

;; ---------------------------------------------------------------------------
;; Guard-wiring assertion gate (PROP-usc-001.R1/.V, PLAN-usc-001.V4)
;; ---------------------------------------------------------------------------

(defn- read-first-form
  "Read the first top-level form of a Clojure source file without evaluating it."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (read {:eof ::eof} r))))

(defn- read-all-forms
  "Read every top-level form of a Clojure source file without evaluating them."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (into [] (take-while #(not= ::eof %))
            (repeatedly #(read {:eof ::eof} r))))))

(defn- unquote-form
  "Unwrap a reader `(quote x)` list to x, leaving other forms untouched."
  [form]
  (if (and (seq? form) (= 'quote (first form))) (second form) form))

(defn- module-form?
  "True when form is a `runtime/module!` declaration call."
  [form]
  (and (seq? form) (= 'runtime/module! (first form))))

(defn- parse-module-form
  "Project a `(runtime/module! runtime <key> <opts>)` form into its guard- and
  convention-relevant data."
  [form]
  (let [opts (nth form 3)]
    {:key (nth form 2)
     :ns (some-> (:ns opts) unquote-form)
     :file (:file opts)
     :contribute (some-> (:contribute opts) unquote-form)
     :reconcile (some-> (:reconcile opts) unquote-form)
     :spools (into #{} (map unquote-form) (:spools opts))}))

(defn- ns-require-libs
  "Return the required namespace symbols from a parsed `ns` form's :require clauses."
  [ns-form]
  (->> (rest ns-form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))))

(defn- spool-or-macros-ns?
  "True when sym names a skein.spools.* or skein.macros.* namespace."
  [sym]
  (let [n (name sym)]
    (or (str/starts-with? n "skein.spools.")
        (str/starts-with? n "skein.macros."))))

(defn- coordinate-source-roots
  "Map each loaded/available synced coordinate to its deps.edn :paths source dirs.

  This is the approved-manifest resolution surface: the spools.edn coordinate,
  the root the runtime synced it to, and that root's deps.edn `:paths` — the only
  thing that maps a namespace to a coordinate without a name heuristic."
  [rt]
  (into {}
        (keep (fn [[coord {:keys [root status]}]]
                (when (#{:loaded :already-available} status)
                  (let [deps (edn/read-string (slurp (io/file root "deps.edn")))
                        paths (or (:paths deps) ["src"])]
                    [coord (mapv #(io/file root %) paths)]))))
        (:spools (spool-sync/approved-spool-syncs rt))))

(defn- ns->source-relative-path
  "Return the classpath-relative source path for a namespace symbol."
  [ns-sym]
  (str (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- resolve-spool-coordinate
  "Resolve ns-sym to the coordinate whose synced root holds its source file, or nil."
  [coordinate-roots ns-sym]
  (let [relative (ns->source-relative-path ns-sym)]
    (some (fn [[coord source-dirs]]
            (when (some #(.isFile (io/file % relative)) source-dirs)
              coord))
          coordinate-roots)))

(deftest init-use-guards-declare-required-spool-coordinates
  ;; PROP-usc-001.R1/.V, PLAN-usc-001.V4/.TC2: the guard-wiring acceptance gate.
  ;; A synced root resolves through the spool classloader whether or not a
  ;; module! declares :spools, so a green world load never proves consent is
  ;; wired. This asserts it directly: every init.clj module! that pulls a
  ;; skein.spools.*/skein.macros.* namespace onto the classpath — a :ns module
  ;; (its own coordinate) or a :file module's ns :require (each required
  ;; coordinate) — must declare that coordinate in :spools. Coordinates resolve
  ;; through the synced root manifests, never a name heuristic: batteries and
  ;; workflow are source-root spools, ct.spools.devflow lives in the
  ;; codethread/devflow root, and skein.spools.executors.shell lives in the
  ;; skein.spools/workflow root, so a prefix rule would false-pass real misses.
  (with-startup-config-runtime
    (fn [rt]
      (let [coordinate-roots (coordinate-source-roots rt)
            modules (map parse-module-form (filter module-form? (read-all-forms ".skein/init.clj")))]
        (is (seq modules) "parsed at least one init.clj module! form")
        (doseq [{:keys [key file spools] use-ns :ns} modules]
          (let [required-nss (if file
                               (->> (ns-require-libs (read-first-form (io/file ".skein" file)))
                                    (filter spool-or-macros-ns?))
                               [use-ns])]
            (doseq [required-ns required-nss]
              (let [coord (resolve-spool-coordinate coordinate-roots required-ns)]
                (is (some? coord)
                    (str key " requires " required-ns
                         " but no synced spool root supplies its source"))
                (is (contains? spools coord)
                    (str key " requires " required-ns " (coordinate " coord
                         ") but its :spools guard " spools " does not declare it"))))))))))

(def ^:private in-tree-spool-vars
  "The in-tree spool modules `.skein/init.clj` activates, keyed as init.clj
  keys them, each mapped to the namespace's public `def spool` declaration var.
  Guild ships in-tree but is not activated in this workspace."
  {:skein/spools-batteries 'skein.spools.batteries/spool
   :skein/spools-workflow 'skein.spools.workflow/spool
   :skein/spools-workflow-cli 'skein.spools.workflow.cli/spool
   :skein/spools-shell 'skein.spools.executors.shell/spool
   :skein/spools-code 'skein.spools.executors.code/spool
   :skein/spools-unsafe-text-search 'skein.spools.unsafe-text-search/spool
   :skein/spools-chime 'skein.spools.chime/spool
   :skein/spools-cron 'skein.spools.cron/spool})

(def ^:private sibling-spool-vars
  "The pinned sibling modules `.skein/init.clj` activates, keyed as init.clj keys
  them, each mapped to the released namespace's public `def spool` var. These are
  the pinned namespaces that contribute through an entry point: `codethread/kanban`
  and the three `ct.spools/agent-run` roots, whose subagent executor carries its
  own `spool` var."
  {:skein/spools-kanban 'ct.spools.kanban/spool
   :skein/spools-shuttle 'ct.spools.agent-run/spool
   :skein/spools-delegation 'ct.spools.delegation/spool
   :skein/spools-bench 'ct.spools.bench/spool
   :skein/spools-treadle 'ct.spools.executors.subagent/spool})

(def ^:private forms-only-ns-modules
  "The init.clj `:ns` modules that legally declare no `spool` var: the macro
  namespaces, whose contribution is empty, the defp demo, whose contribution is
  the patterns its source collects, and pinned `codethread/devflow`, whose whole
  contribution is the stage `defworkflow` entries its load collects."
  #{:macros/patterns :macros/ops :macros/queries :macros/rules :macros/demo
    :skein/spools-devflow})

(defn- public-spool-var
  "Resolve a namespace's `spool` var, or nil when it is missing or private."
  [spool-sym]
  (when-let [spool-var (requiring-resolve spool-sym)]
    (when-not (:private (meta spool-var)) spool-var)))

(deftest init-modules-resolve-entry-points-by-convention
  ;; PROP-Dsp-001.G7/P7.1: the literal-mirror triples are retired outright —
  ;; init.clj names only a source target and world policy, and the coordinator
  ;; resolves every module's entry points from its namespace's public `def spool`
  ;; var. This guards that conversion from both sides. First, NO init.clj module
  ;; — in-tree spool, pinned sibling, or workspace file — declares an explicit
  ;; `:contribute`/`:reconcile`; that is the invariant the retired sibling parity
  ;; test used to police from the other direction. Second, every `:ns` module
  ;; expected to contribute — in-tree spool AND pinned sibling — resolves a public
  ;; `spool` var that is a valid `::spool-api/spool` carrying at least a
  ;; `:contribute` entry point. Without that second half a pin bump to a sibling
  ;; that renamed, privatised, or malformed its var lands as a silently empty
  ;; contribution and then a startup failure of the coordination world, not a test
  ;; failure. It runs inside a started world so the pinned sibling roots are on
  ;; the classpath and their vars resolve. Cardinality is asserted first, so a
  ;; deleted, duplicated, or re-keyed declaration fails before any per-module
  ;; check, and an added `:ns` module must be classified as contributing or as
  ;; forms-only before it can pass. Workspace `:file` modules are deliberately
  ;; outside the var requirement: a file's whole contribution may be the authoring
  ;; forms its load collects.
  (with-startup-config-runtime
    (fn [_rt]
      (let [declarations (->> (read-all-forms ".skein/init.clj")
                              (filter module-form?)
                              (map parse-module-form))
            expected-vars (merge in-tree-spool-vars sibling-spool-vars)
            ns-keys (->> declarations (filter :ns) (map :key))]
        (is (seq declarations) "parsed at least one init.clj module! form")
        (is (every? #(= 1 (count %)) (vals (group-by :key declarations)))
            "a module key is declared more than once in init.clj")
        (is (= (into (set (keys expected-vars)) forms-only-ns-modules)
               (set ns-keys))
            "init.clj's :ns module keys drifted from the expected set")
        (doseq [{:keys [key contribute reconcile]} declarations]
          (is (and (nil? contribute) (nil? reconcile))
              (str key " still declares an explicit entry-point key in init.clj")))
        (doseq [[key spool-sym] expected-vars]
          (if-let [spool-var (public-spool-var spool-sym)]
            (do
              (is (s/valid? ::spool-api/spool @spool-var)
                  (str key " backing " spool-sym " is not a valid ::spool: "
                       (s/explain-str ::spool-api/spool @spool-var)))
              (is (contains? @spool-var :contribute)
                  (str key " backing " spool-sym
                       " declares no :contribute entry point")))
            (is false
                (str key " resolves no public " spool-sym
                     " var, so it contributes nothing by convention"))))))))
