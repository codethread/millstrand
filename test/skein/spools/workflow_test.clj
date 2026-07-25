(ns skein.spools.workflow-test
  "Tests for the skein.spools.workflow userland workflow engine: contract
  explain, compile semantics (calls, conditions, loops, splicing), and the
  run-driving surface (start!/complete!/choose!, gates, checkpoints, bonds)."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.batch.alpha :as batch]
            [skein.api.graph.alpha :as graph]
            [skein.api.registry.alpha :as registry]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.spools.test-support :as test-support :refer [assert-state-shape with-runtime]]
            [skein.spools.workflow :as workflow]
            [skein.spools.workflow.internal.registry :as wf-registry]
            [skein.repl :as repl]
            [skein.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(deftest workflow-module-declares-workflow-attr-namespace
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [decl (some #(when (= [:attr-namespace "workflow"] [(:kind %) (:name %)]) %)
                       (vocab/declarations rt))]
        (is (= :attr-namespace (:kind decl)))
        (is (= :skein/spools-workflow (:owner decl))
            "workflow module reconcile owns the workflow/* namespace via its module key")))))

(deftest workflow-spool-explains-public-input-shapes
  (let [contract (workflow/explain)]
    (is (= :workflow (:topic contract)))
    (is (= 'skein.spools.workflow/checkpoint (get-in contract [:builders 'checkpoint])))
    (is (re-find #"skein.spools.workflow/workflow" (get-in contract [:contract :spec])))
    (is (= :step (get-in contract [:step :topic])))
    (is (= :checkpoint (get-in contract [:checkpoint :topic])))
    (is (= :definition (get-in contract [:definition :topic])))
    (is (= 'skein.spools.workflow/defworkflow
           (get-in contract [:builders 'defworkflow])))
    (is (re-find #"skein.spools.workflow/definition"
                 (get-in (workflow/explain :definition) [:contract :spec])))))

(deftest workflow-spool-compiles-and-materializes-molecules
  (with-runtime
    (fn [rt _]
      (let [with-feature (fn [prefix]
                           (fn [{:keys [feature]}]
                             (str prefix feature)))
            definition (workflow/workflow
                        (with-feature "Ship ")
                        {:params {:feature (workflow/param :required true)
                                  :owner (workflow/param :default "agent")
                                  :include-review (workflow/param :default true)}}
                        (workflow/step :design (with-feature "Design ") :self
                                       :attributes {:owner (fn [{:keys [owner]}] owner)})
                        (workflow/step :implement (with-feature "Implement ") :self
                                       :depends-on [:design])
                        (workflow/step :review (with-feature "Review ") :self
                                       :depends-on [:implement]
                                       :condition :include-review))
            result (workflow/pour! definition {:feature "workflow spool"})
            root-id (workflow/molecule-id result)
            root (repl/strand root-id)
            subgraph (graph/subgraph rt [root-id])]
        (is (= "Ship workflow spool" (:title root)))
        (is (= "root" (get-in root [:attributes :workflow/role])))
        (is (= #{"Design workflow spool" "Implement workflow spool" "Review workflow spool" "Ship workflow spool"}
               (set (map :title (:strands subgraph)))))
        (is (= 3 (count (filter #(= "parent-of" (:edge_type %)) (:edges subgraph)))))))))

(deftest workflow-spool-inlines-procedure-calls
  (let [review (fn [_]
                 (workflow/workflow
                  "Review"
                  (workflow/step :inspect
                                 (fn [{:keys [artifact]}] (str "Inspect " artifact)) :self)
                  (workflow/step :write-review
                                 (fn [{:keys [artifact]}] (str "Write review for " artifact)) :self
                                 :depends-on [:inspect])))
        definition (workflow/workflow
                    "Procedure demo"
                    (workflow/step :write-artifact "Write artifact" :self)
                    (workflow/call :review-artifact review {:artifact "proposal.md"}
                                   :depends-on [:write-artifact])
                    (workflow/step :continue "Continue" :self
                                   :depends-on [:review-artifact]))
        payload (workflow/compile definition)
        strands-by-ref (into {} (map (juxt :ref identity)) (:strands payload))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :write-artifact :review-artifact--inspect
             :review-artifact--write-review :review-artifact :continue}
           (set (keys strands-by-ref))))
    (is (= "Inspect proposal.md" (get-in strands-by-ref [:review-artifact--inspect :title])))
    (is (contains? edges [:review-artifact--inspect :write-artifact "depends-on"]))
    (is (contains? edges [:review-artifact--write-review :review-artifact--inspect "depends-on"]))
    (is (contains? edges [:review-artifact :review-artifact--write-review "depends-on"]))
    (is (contains? edges [:continue :review-artifact "depends-on"]))))

(defn- toastie-quality-workflow [_]
  (workflow/workflow
   "Toastie quality check"
   (workflow/step :inspect "Check toastie melt and crunch" :self)))

(defn- toastie-serve-workflow [{:keys [filling]}]
  (workflow/workflow
   (str "Serve " filling " toastie")
   (workflow/step :plate (fn [{:keys [filling]}] (str "Plate " filling " toastie")) :self)))

(deftest workflow-spool-runtime-drives-toastie-demo
  (with-runtime
    (fn [_rt _]
      (let [toastie (workflow/workflow
                     (fn [{:keys [filling]}] (str "Make " filling " toastie"))
                     (workflow/step :butter-bread "Butter bread" :self)
                     (workflow/call :quality toastie-quality-workflow {}
                                    :depends-on [:butter-bread])
                     (workflow/checkpoint :choose-finish "Choose toastie finish"
                                          :depends-on [:quality]
                                          :kind :agent
                                          :choices [{:key :serve
                                                     :label "Serve"
                                                     :description "Plate the toastie and serve it hot."
                                                     :next 'skein.spools.workflow-test/toastie-serve-workflow}
                                                    {:key :remake
                                                     :label "Remake"
                                                     :description "Start over with fresh bread."}]))]
        (is (= [{:title "Butter bread" :role "step"}]
               (mapv #(select-keys % [:title :role])
                     (:ready (workflow/start! "toastie-demo" toastie {:filling "cheese"})))))
        (is (= "Check toastie melt and crunch" (:title (first (:ready (workflow/complete! "toastie-demo"))))))
        ;; completing the inner quality step auto-closes the procedure join, so
        ;; the checkpoint is next with no manual "Complete quality" step to close
        (is (= "Choose toastie finish" (:title (first (:ready (workflow/complete! "toastie-demo"))))))
        (is (= ["serve" "remake"] (:choices (workflow/ready-step "toastie-demo"))))
        (is (not (contains? (workflow/ready-step "toastie-demo") :choice-details)))
        (is (= {"label" "Serve"
                "description" "Plate the toastie and serve it hot."
                "next" "skein.spools.workflow-test/toastie-serve-workflow"}
               (workflow/choice-detail "toastie-demo" :serve)))
        (is (= "Plate cheese toastie"
               (:title (first (:ready (workflow/choose! "toastie-demo" :serve {:filling "cheese"}))))))
        (is (= {:ready [] :done true} (workflow/complete! "toastie-demo")))
        (is (workflow/done? "toastie-demo"))))))

;; Pull-request flow modelled without conditional edges: every branch is a
;; checkpoint choice the driving agent makes after observing the world (CI
;; verdict, review outcome), and every external wait is a gate. The CI round
;; is one reusable workflow recomposed via `call` by each stage that pushes
;; commits, and its verdict checkpoint always routes green to review and red
;; to the fix loop.
;;
;; The definitions are forge-agnostic: steps carry only semantic
;; workflow/action-ref names, and the concrete forge commands arrive as a
;; pure-data bindings map (action-ref -> attribute map) through params — pure
;; data so bindings survive workflow/context round-trips across routed loop
;; rounds. github ships as the reference; a user rebinds any subset from the
;; outside without touching a definition (see PLAN.md).

;; binding keys ride workflow/context across routed loop rounds: map keys come
;; back keywordized and are written with their full ns/name form
;; (skein.core.db/json-key), so keyword keys round-trip faithfully. Simple keyword
;; keys stay the convention here; bind-attrs maps them onto the canonical
;; string attribute vocabulary.
(def ^:private github-pr-bindings
  {:pr.open           {:instruction "gh pr create --fill"}
   :pr.ci.wait        {:instruction "gh pr checks --watch --fail-fast"
                       :skills "ci-watch"}
   :pr.ci.fix         {:instruction "gh run view --log-failed to inspect the failing checks"}
   :pr.review.wait    {:instruction "gh pr view --comments"}
   :pr.review.address {:instruction "Reply with gh pr comment; push follow-up commits"}
   :pr.merge          {:instruction "gh pr merge --squash"}})

(def ^:private binding-attr-keys
  {:instruction "workflow/instruction"
   :skills "skills"})

(defn- bind-attrs
  "Merge the binding for action-ref into canonical step attributes, failing
  loudly (TEN-003) on an unbound action or a key outside the binding
  vocabulary — a typo in user bindings must not yield a silently bare step."
  [bindings action-ref]
  (let [bindings (or bindings github-pr-bindings)
        bound (or (get bindings action-ref)
                  (throw (ex-info "No binding for workflow action"
                                  {:action-ref action-ref :bound (vec (keys bindings))})))]
    (when-let [unknown (seq (remove binding-attr-keys (keys bound)))]
      (throw (ex-info "Unknown binding keys"
                      {:action-ref action-ref :unknown (vec unknown)
                       :allowed (vec (keys binding-attr-keys))})))
    (merge {"workflow/action-ref" (name action-ref)}
           (into {} (map (fn [[k v]] [(binding-attr-keys k) v])) bound))))

(defn- pr-ci-round-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "CI round for " feature))
   (workflow/gate :ci-wait (fn [{:keys [feature]}] (str "Wait for CI on " feature)) :ci
                  :attributes (bind-attrs bindings :pr.ci.wait))
   (workflow/checkpoint :ci-verdict "Judge CI result"
                        :depends-on [:ci-wait]
                        :kind :agent
                        :choices [{:key :green
                                   :label "CI green"
                                   :description "All checks passed; hand off to review."
                                   :next 'skein.spools.workflow-test/pr-review-round-workflow}
                                  {:key :red
                                   :label "CI red"
                                   :description "Checks failed; run the fix-CI loop."
                                   :next 'skein.spools.workflow-test/pr-fix-ci-workflow}])))

(defn- pr-fix-ci-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Fix CI for " feature))
   (workflow/step :diagnose "Diagnose CI failure" :self
                  :attributes (bind-attrs bindings :pr.ci.fix))
   (workflow/step :push-fix "Push CI fix" :self :depends-on [:diagnose])
   (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:push-fix])))

(defn- pr-review-round-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Review round for " feature))
   (workflow/gate :review-wait
                  (fn [{:keys [feature]}] (str "Wait for reviewer feedback on " feature))
                  :human
                  :attributes (bind-attrs bindings :pr.review.wait))
   (workflow/checkpoint :review-verdict "Judge review outcome"
                        :depends-on [:review-wait]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approved"
                                   :description "All green and approved; merge."
                                   :next 'skein.spools.workflow-test/pr-merge-workflow}
                                  {:key :changes-requested
                                   :label "Changes requested"
                                   :description "Address comments, push, and re-run CI."
                                   :next 'skein.spools.workflow-test/pr-fix-and-push-workflow}])))

(defn- pr-fix-and-push-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Address review feedback for " feature))
   (workflow/step :address-comments "Address review comments" :self
                  :attributes (bind-attrs bindings :pr.review.address))
   (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:address-comments])))

(defn- pr-merge-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Merge " feature))
   (workflow/step :merge (fn [{:keys [feature]}] (str "Merge " feature)) :self
                  :attributes (bind-attrs bindings :pr.merge))))

(defn- pr-dev-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Pull request: " feature))
   (workflow/step :dev (fn [{:keys [feature]}] (str "Implement " feature)) :self)
   (workflow/step :open "Open the change for review" :self :depends-on [:dev]
                  :attributes (bind-attrs bindings :pr.open))
   (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:open])))

(deftest workflow-models-pull-request-flow-without-conditional-edges
  (with-runtime
    (fn [_rt _]
      (workflow/start! "pr-flow" (pr-dev-workflow {}) {:feature "pr-42"}
                       {:family "pull-request"
                        :definition 'skein.spools.workflow-test/pr-dev-workflow
                        :context {:feature "pr-42"}})
      (is (= "Implement pr-42" (:title (workflow/ready-step "pr-flow"))))
      (is (= "Open the change for review" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      ;; the CI round is inlined by call; its gate tells the driver to wait
      ;; (e.g. run a blocking `gh pr checks --watch`), not to do work
      (let [gate (first (:ready (workflow/complete! "pr-flow")))]
        (is (= "Wait for CI on pr-42" (:title gate)))
        (is (= "ci" (:gate gate)))
        (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by "ci-bot"}))))))
        (is (= "ci-bot" (get-in (repl/strand (:id gate)) [:attributes :workflow/outcome-by]))))
      ;; red verdict routes into the fix-CI loop, which recomposes the CI round
      (is (= "Diagnose CI failure" (:title (first (:ready (workflow/choose! "pr-flow" :red))))))
      (is (= "Push CI fix" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Wait for CI on pr-42" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by "ci-bot"}))))))
      ;; green verdict hands off to the review round
      (let [review-gate (first (:ready (workflow/choose! "pr-flow" :green)))]
        (is (= "Wait for reviewer feedback on pr-42" (:title review-gate)))
        (is (= "human" (:gate review-gate))))
      (is (= "Judge review outcome" (:title (first (:ready (workflow/complete! "pr-flow" {:by "reviewer"}))))))
      ;; changes requested: fix-and-push recomposes the same CI round, whose
      ;; green verdict flows back into review — the nested loop the flow needs
      (is (= "Address review comments" (:title (first (:ready (workflow/choose! "pr-flow" :changes-requested))))))
      (is (= "Wait for CI on pr-42" (:title (first (:ready (workflow/complete! "pr-flow"))))))
      (is (= "Judge CI result" (:title (first (:ready (workflow/complete! "pr-flow" {:by "ci-bot"}))))))
      (is (= "Wait for reviewer feedback on pr-42" (:title (first (:ready (workflow/choose! "pr-flow" :green))))))
      (is (= "Judge review outcome" (:title (first (:ready (workflow/complete! "pr-flow" {:by "reviewer"}))))))
      ;; approval routes to merge; the run closes itself when merge completes
      (is (= "Merge pr-42" (:title (first (:ready (workflow/choose! "pr-flow" :approved {} {:by "agent-driver"}))))))
      (is (= {:ready [] :done true} (workflow/complete! "pr-flow")))
      (is (workflow/done? "pr-flow")))))

(def ^:private gitlab-pr-bindings
  ;; what a gitlab user writes in their own config: a partial override
  ;; deep-merged over the shipped reference — only the rebound fields of the
  ;; rebound actions change (:pr.ci.wait keeps its reference :skills)
  (merge-with merge github-pr-bindings
              {:pr.open    {:instruction "glab mr create --fill"}
               :pr.ci.wait {:instruction "glab ci status --live"}}))

(deftest workflow-pr-flow-rebinds-forge-without-spool-changes
  (with-runtime
    (fn [_rt _]
      ;; reference run: no bindings passed, the github reference applies
      (workflow/start! "pr-forge-ref" (pr-dev-workflow {}) {:feature "ref-feat"}
                       {:family "pull-request" :context {:feature "ref-feat"}})
      (workflow/complete! "pr-forge-ref")
      (let [open-step (workflow/ready-step "pr-forge-ref")]
        (is (= "pr.open" (:action-ref open-step)))
        (is (= "gh pr create --fill" (:instruction open-step))))
      (let [gate (first (:ready (workflow/complete! "pr-forge-ref")))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "gh pr checks --watch --fail-fast" (:instruction gate)))
        (is (= "ci-watch" (:skills gate))))
      ;; gitlab run: the same untouched definitions, driven by user-supplied
      ;; pure-data overrides passed through params and context
      (workflow/start! "pr-forge-gl" (pr-dev-workflow {:bindings gitlab-pr-bindings})
                       {:feature "gl-feat" :bindings gitlab-pr-bindings}
                       {:family "pull-request"
                        :context {:feature "gl-feat" :bindings gitlab-pr-bindings}})
      (workflow/complete! "pr-forge-gl")
      (is (= "glab mr create --fill" (:instruction (workflow/ready-step "pr-forge-gl"))))
      (let [gate (first (:ready (workflow/complete! "pr-forge-gl")))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "glab ci status --live" (:instruction gate)))
        ;; per-field override: only :instruction was rebound, the reference
        ;; :skills field on the same action survives
        (is (= "ci-watch" (:skills gate))))
      ;; red verdict routes into the fix loop: the non-overridden fix action
      ;; keeps the github reference (partial override at work)
      (workflow/complete! "pr-forge-gl" {:by "gitlab-ci"})
      (let [diagnose (first (:ready (workflow/choose! "pr-forge-gl" :red)))]
        (is (= "Diagnose CI failure" (:title diagnose)))
        (is (= "pr.ci.fix" (:action-ref diagnose)))
        (is (= "gh run view --log-failed to inspect the failing checks"
               (:instruction diagnose))))
      (workflow/complete! "pr-forge-gl")
      ;; the rebound CI gate survives the routed loop round: bindings rode
      ;; workflow/context into the recompiled continuation
      (let [gate (first (:ready (workflow/complete! "pr-forge-gl")))]
        (is (= "Wait for CI on gl-feat" (:title gate)))
        (is (= "glab ci status --live" (:instruction gate)))))))

(deftest workflow-runtime-closes-empty-runs-at-start
  (with-runtime
    (fn [_rt _]
      (let [empty-workflow (workflow/workflow "Nothing to do")]
        (is (= {:ready [] :done true} (workflow/start! "empty-run" empty-workflow {})))
        (is (workflow/done? "empty-run"))
        (is (nil? (workflow/current-root "empty-run")))
        (is (= {:ready [] :done true} (workflow/start! "empty-run" empty-workflow {})))))))

(deftest workflow-run-not-done-while-blocked-by-external-dependency
  (with-runtime
    (fn [_rt _]
      (let [blocker (repl/strand! "External blocker")
            definition (workflow/workflow
                        "Blocked run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            result (workflow/pour! definition {} {:run-id "blocked-run"})
            b-id (get-in result [:refs :b])]
        (repl/update! b-id {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {:title "Do A" :role "step"}
               (select-keys (workflow/ready-step "blocked-run") [:title :role])))
        (is (= {:ready [] :done false} (workflow/complete! "blocked-run")))
        (is (not (workflow/done? "blocked-run")))
        (is (some? (workflow/current-root "blocked-run")))
        (is (= "active" (:state (repl/strand b-id))))))))

(deftest workflow-done-fails-loudly-for-unknown-run
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (workflow/done? "no-such-run"))))))

(deftest workflow-run-auto-closes-root-when-last-step-completes
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Linear run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))]
        (workflow/start! "linear-run" definition {})
        (is (= [{:title "Do B" :role "step"}]
               (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "linear-run")))))
        (is (= {:ready [] :done true} (workflow/complete! "linear-run")))
        (is (workflow/done? "linear-run"))
        (is (nil? (workflow/current-root "linear-run")))))))

(deftest workflow-runtime-supports-parallel-ready-steps
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Parallel entry"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self))
            started (:ready (workflow/start! "parallel-run" definition {}))
            a-id (:id (first (filter #(= "Do A" (:title %)) started)))
            b-id (:id (first (filter #(= "Do B" (:title %)) started)))]
        (is (= #{"Do A" "Do B"} (set (map :title started))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/complete! "parallel-run")))
        (is (= "active" (:state (repl/strand a-id))))
        (is (= "active" (:state (repl/strand b-id))))
        (let [remaining (:ready (workflow/complete! "parallel-run" {:step a-id}))]
          (is (= "closed" (:state (repl/strand a-id))))
          (is (= "active" (:state (repl/strand b-id))))
          (is (= [{:title "Do B" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining))))))))

(deftest workflow-complete-merges-caller-attributes-onto-the-closed-step
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Attrs run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "attrs-run" definition {}))]
        (workflow/complete! "attrs-run" {:attributes {"acme/outcome" "ok"
                                                      "acme/exit-code" 7}})
        (let [strand (repl/strand (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "ok" (get-in strand [:attributes :acme/outcome])))
          ;; a typed value survives the merge as itself, not as its printed form
          (is (= 7 (get-in strand [:attributes :acme/exit-code]))))))))

(deftest workflow-complete-holds-direct-callers-to-the-attributes-spec
  ;; The worker CLI validates its request map; a direct Clojure caller reaches
  ;; the same mutation, so the same spec judges it rather than a looser local check.
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Bad attrs run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "bad-attrs-run" definition {}))]
        (doseq [[label attributes] [["a non-map" "acme/outcome=ok"]
                                    ["a keyword key" {:acme/outcome "ok"}]
                                    ["a blank key" {"" "ok"}]]]
          (testing label
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Invalid workflow complete attributes"
                                  (workflow/complete! "bad-attrs-run" {:attributes attributes})))))
        (is (= "active" (:state (repl/strand (:id step)))))
        (testing "an empty map is a stated no-op, not a rejection"
          (workflow/complete! "bad-attrs-run" {:attributes {}})
          (is (= "closed" (:state (repl/strand (:id step))))))))))

(deftest workflow-complete-and-advance-refuse-removed-notes-arg
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "No notes run"
                                          (workflow/step :a "Do A" :self)
                                          (workflow/step :b "Do B" :self))
            [step] (:ready (workflow/start! "no-notes-run" definition {}))]
        (doseq [[label f] [["complete!" #(workflow/complete! "no-notes-run" {:notes "prose"})]
                           ["advance!" #(workflow/advance! "no-notes-run" {:notes "prose"})]]]
          (testing label
            (try
              (f)
              (is false (str "expected " label " to refuse :notes"))
              (catch clojure.lang.ExceptionInfo e
                (is (re-find #"no longer accepts :notes" (ex-message e)))
                (is (= :workflow/notes-removed (:reason (ex-data e))))
                (is (= label (:op (ex-data e))))))))
        ;; the refusal happens before the guard, so nothing moved
        (is (= "active" (:state (repl/strand (:id step)))))))))

(deftest workflow-run-history-reads-legacy-outcome-notes-as-an-ordinary-attribute
  (with-runtime
    (fn [_rt _]
      ;; a step closed before the outcome cutover: run-history projects the
      ;; engine's own outcome keys and leaves the historical row on the strand,
      ;; where show and the query language read it like any other attribute.
      (let [definition (workflow/workflow "Legacy run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "legacy-notes-run" definition {}))]
        (workflow/complete! "legacy-notes-run" {:attributes {"workflow/outcome-notes" "closed in 2026"}})
        (let [event (first (:events (first (workflow/run-history "legacy-notes-run"))))]
          (is (= :step-closed (:type event)))
          (is (not (contains? event :notes))))
        (is (= "closed in 2026"
               (get-in (repl/strand (:id step)) [:attributes :workflow/outcome-notes])))))))

(deftest workflow-complete-fails-loudly-on-invalid-step-and-mutates-nothing
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Bad step run" (workflow/step :a "Do A" :self))]
        (workflow/start! "bad-step-run" definition {})
        (let [a-id (:id (workflow/ready-step "bad-step-run"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requested workflow step is not ready"
                                (workflow/complete! "bad-step-run" {:step "no-such-step"})))
          (is (= "active" (:state (repl/strand a-id)))))))))

(deftest workflow-gate-requires-by-and-records-who-closed-it
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Gated run"
                        (workflow/step :push "Push branch" :self)
                        (workflow/gate :ci "Wait for CI to go green" :ci :depends-on [:push])
                        (workflow/step :deploy "Deploy" :self :depends-on [:ci]))]
        (workflow/start! "gated-run" definition {})
        ;; the non-gate :push step closes without :by, reaching the gate
        (let [gate (first (:ready (workflow/complete! "gated-run")))
              gate-id (:id gate)]
          (is (= "ci" (:gate gate)))
          (is (= "step" (:role gate)))
          ;; the gate refuses to close without :by and stays active
          (try
            (workflow/complete! "gated-run")
            (is false "expected gate complete to fail without :by")
            (catch clojure.lang.ExceptionInfo e
              (is (re-find #"Gate steps require a non-blank :by" (ex-message e)))
              (is (= "ci" (:gate (ex-data e))))
              (is (= "ci" (get-in (ex-data e) [:step :gate])))))
          ;; a nil or blank :by is no better than a missing one
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate steps require a non-blank :by"
                                (workflow/complete! "gated-run" {:by nil})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate steps require a non-blank :by"
                                (workflow/complete! "gated-run" {:by "  "})))
          (is (= "active" (:state (repl/strand gate-id))))
          ;; an external actor closes the gate with :by; :deploy becomes ready
          (let [remaining (:ready (workflow/complete! "gated-run" {:by "ci"
                                                                   :attributes {"ci/result" "green"}}))
                closed (repl/strand gate-id)]
            (is (= "closed" (:state closed)))
            (is (= "ci" (get-in closed [:attributes :workflow/outcome-by])))
            (is (= "green" (get-in closed [:attributes :ci/result])))
            (is (= [{:title "Deploy" :role "step"}]
                   (mapv #(select-keys % [:title :role]) remaining)))))))))

(deftest workflow-non-gate-step-closes-without-by
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Plain run" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "plain-gate-run" definition {}))]
        (is (= {:ready [] :done true} (workflow/complete! "plain-gate-run")))
        (let [closed (repl/strand (:id step))]
          (is (= "closed" (:state closed)))
          (is (nil? (get-in closed [:attributes :workflow/outcome-by]))))))))

(deftest workflow-non-gate-step-records-by-when-supplied
  ;; :by is recorded on any step completion when supplied (provenance parity),
  ;; even though only gates require it
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Plain run with by" (workflow/step :a "Do A" :self))
            [step] (:ready (workflow/start! "plain-by-run" definition {}))]
        (is (= {:ready [] :done true} (workflow/complete! "plain-by-run" {:by "agent-driver"})))
        (let [closed (repl/strand (:id step))]
          (is (= "closed" (:state closed)))
          (is (= "agent-driver" (get-in closed [:attributes :workflow/outcome-by]))))))))

(defn- empty-continuation-workflow [_]
  (workflow/workflow "Empty continuation"))

(deftest workflow-routed-choice-closes-workless-continuation-run
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Route to empty"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :finish
                                                        :label "Finish"
                                                        :next 'skein.spools.workflow-test/empty-continuation-workflow}]))]
        (workflow/start! "route-to-empty" definition {})
        (is (= {:ready [] :done true} (workflow/choose! "route-to-empty" :finish)))
        (is (true? (workflow/done? "route-to-empty")))
        (is (nil? (workflow/current-root "route-to-empty")))))))

(defn- routed-continuation-workflow [_]
  (workflow/workflow
   "Continuation"
   (workflow/step :follow-up "Do follow up work" :self)))

(deftest workflow-routed-choice-swaps-to-single-active-continuation-root
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Route to work"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :continue
                                                        :label "Continue"
                                                        :next 'skein.spools.workflow-test/routed-continuation-workflow}]))]
        (workflow/start! "route-to-work" definition {})
        (let [old-root-id (:id (workflow/current-root "route-to-work"))
              remaining (:ready (workflow/choose! "route-to-work" :continue))]
          (is (= "closed" (:state (repl/strand old-root-id))))
          (is (= [{:title "Do follow up work" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining)))
          ;; current-root throws on more than one active root, so a non-nil
          ;; result asserts exactly one active root remains for the run-id
          (let [new-root (workflow/current-root "route-to-work")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root)))
            (is (= "active" (:state new-root)))))))))

(deftest workflow-choose-records-outcome-by
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Signoff run"
                        (workflow/checkpoint :approve "Approve it"
                                             :choices [{:key :approved :label "Approve"}]))
            [step] (:ready (workflow/start! "signoff-run" definition {}))]
        (workflow/choose! "signoff-run" :approved {} {:by "agent:reviewer"})
        (let [strand (repl/strand (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "approved" (get-in strand [:attributes :workflow/outcome])))
          (is (= "agent:reviewer" (get-in strand [:attributes :workflow/outcome-by]))))))))

(defn- loopy-workflow [{:keys [revision]}]
  (workflow/workflow
   "Loopy"
   {:params {:revision (workflow/param :default (boolean revision))}}
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/step :work "Do work" :self :depends-on [:orient])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:work]
                        :kind :agent
                        :choices [{:key :approved :label "Approve"}
                                  {:key :revise
                                   :label "Revise"
                                   :next 'skein.spools.workflow-test/loopy-revision-workflow}])))

(defn- loopy-revision-workflow [opts]
  (loopy-workflow (assoc opts :revision true)))

(deftest workflow-start-accepts-var-and-defaults-durable-context
  (with-runtime
    (fn [_rt _]
      (workflow/start! "var-start" #'loopy-workflow {:revision :yes})
      (let [root (workflow/current-root "var-start")]
        (is (= "skein.spools.workflow-test/loopy-workflow"
               (get-in root [:attributes :workflow/definition])))
        (is (= {:revision "yes"}
               (get-in root [:attributes :workflow/context]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pass :context explicitly"
                            (workflow/start! "bad-context" #'loopy-workflow {:f identity})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "nan-context" #'loopy-workflow {:n ##NaN})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "inf-context" #'loopy-workflow {:n ##Inf}))))))

(deftest workflow-start-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-test 'skein.spools.workflow-test/loopy-workflow)
      (workflow/start! "keyword-start" :loopy-test {})
      (is (= "skein.spools.workflow-test/loopy-workflow"
             (get-in (workflow/current-root "keyword-start") [:attributes :workflow/definition])))
      (is (= "Orient" (:title (workflow/ready-step "keyword-start")))))))

(deftest workflow-describe-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-describe 'skein.spools.workflow-test/loopy-workflow)
      (is (= "Loopy" (:name (workflow/describe :loopy-describe {})))))))

(deftest workflow-start-and-describe-reject-unknown-registered-keyword
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/start! "missing-keyword-start" :missing-workflow {})))))
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/describe :missing-workflow {}))))))

(deftest workflow-revise-choice-loops-back-to-a-fresh-revision-round
  (with-runtime
    (fn [_rt _]
      (is (= [{:title "Orient" :role "step"}]
             (mapv #(select-keys % [:title :role])
                   (:ready (workflow/start! "loopy" (loopy-workflow {}) {})))))
      (is (= [{:title "Do work" :role "step"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
      (let [signoff (workflow/ready-step "loopy")
            signoff-id (:id signoff)
            old-root-id (:id (workflow/current-root "loopy"))]
        (is (= "checkpoint" (:role signoff)))
        ;; revise routes back to a fresh revision round under the same run-id
        (let [remaining (:ready (workflow/choose! "loopy" :revise))]
          (is (= "closed" (:state (repl/strand signoff-id))))
          (is (= "revise" (get-in (repl/strand signoff-id) [:attributes :workflow/outcome])))
          (is (= "closed" (:state (repl/strand old-root-id))))
          (let [new-root (workflow/current-root "loopy")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root))))
          ;; :orient is condition-skipped on the revision round, so :work is ready
          (is (= [{:title "Do work" :role "step"}]
                 (mapv #(select-keys % [:title :role]) remaining))))
        (is (= [{:title "Sign off" :role "checkpoint"}]
               (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "loopy")))))
        (is (= {:ready [] :done true} (workflow/choose! "loopy" :approved)))
        (is (workflow/done? "loopy"))))))

(deftest workflow-routed-choose-failure-keeps-run-resumable
  (with-runtime
    (fn [_rt _]
      (workflow/start! "loopy-fail" (loopy-workflow {}) {})
      (workflow/complete! "loopy-fail")
      (workflow/complete! "loopy-fail")
      (let [old-root-id (:id (workflow/current-root "loopy-fail"))
            signoff-id (:id (workflow/ready-step "loopy-fail"))]
        ;; a failed continuation apply must not leave the run in a false
        ;; terminal state; the checkpoint close and continuation pour are folded
        ;; into one batch/apply!, so a failing apply commits nothing
        (with-redefs [batch/apply! (fn [_ _] (throw (ex-info "batch boom" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"batch boom"
                                (workflow/choose! "loopy-fail" :revise))))
        (let [root (workflow/current-root "loopy-fail")]
          (is (some? root))
          (is (= old-root-id (:id root)))
          (is (= "active" (:state root))))
        (is (= "active" (:state (repl/strand signoff-id))))
        (is (false? (workflow/done? "loopy-fail")))
        ;; the run stays resumable: retrying the same choice now succeeds
        (is (= [{:title "Do work" :role "step"}]
               (mapv #(select-keys % [:title :role])
                     (:ready (workflow/choose! "loopy-fail" :revise)))))))))

(deftest workflow-runtime-selects-among-parallel-ready-checkpoints
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Parallel checkpoints"
                        (workflow/checkpoint :x "Pick X"
                                             :choices [{:key :go :label "Go X"}])
                        (workflow/checkpoint :y "Pick Y"
                                             :choices [{:key :go :label "Go Y"}]))
            started (:ready (workflow/start! "parallel-checkpoints" definition {}))
            x-id (:id (first (filter #(= "Pick X" (:title %)) started)))
            y-id (:id (first (filter #(= "Pick Y" (:title %)) started)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/choose! "parallel-checkpoints" :go)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/choice-details "parallel-checkpoints")))
        (is (= "active" (:state (repl/strand x-id))))
        (is (= "active" (:state (repl/strand y-id))))
        ;; choice-details string-keys choice names and detail maps, agreeing
        ;; with choice-detail's shape (archived workflow-engine review, R2)
        (is (= {"go" {"label" "Go X"}}
               (workflow/choice-details "parallel-checkpoints" {:step x-id})))
        (is (= {"label" "Go Y"}
               (workflow/choice-detail "parallel-checkpoints" :go {:step y-id})))
        (let [remaining (:ready (workflow/choose! "parallel-checkpoints" :go {} {:step x-id}))]
          (is (= "closed" (:state (repl/strand x-id))))
          (is (= "go" (get-in (repl/strand x-id) [:attributes :workflow/outcome])))
          (is (= "active" (:state (repl/strand y-id))))
          (is (= [y-id] (mapv :id remaining))))))))

(deftest workflow-spool-supports-wisps-bonds-and-squash
  (with-runtime
    (fn [_rt _]
      (let [left-result (workflow/wisp! {:name "Left" :steps [{:id :a :title "A"}]})
            right-result (workflow/wisp! {:name "Right" :steps [{:id :b :title "B"}]})
            left-id (workflow/molecule-id left-result)
            right-id (workflow/molecule-id right-result)]
        (is (= "wisp" (get-in (repl/strand left-id) [:attributes :workflow/form])))
        (workflow/bond! left-id right-id)
        (let [digest (workflow/squash! left-id "Left digest" {:summary "done"})]
          (is (= "closed" (:state digest)))
          (is (= "digest" (get-in digest [:attributes :workflow/role])))
          (is (nil? (repl/strand left-id))))))))

(deftest workflow-bond-parent-blocks-the-bonded-run
  (with-runtime
    (fn [_rt _]
      (workflow/start! "bond-left" {:name "Left" :steps [{:id :a :title "Do A"}]} {})
      (workflow/start! "bond-right" {:name "Right" :steps [{:id :b :title "Do B"}]} {})
      (let [left-root-id (:id (workflow/current-root "bond-left"))
            right-root-id (:id (workflow/current-root "bond-right"))]
        (workflow/bond! left-root-id right-root-id)
        ;; the right step has no deps of its own, but the dep-blocked root
        ;; hides the whole run until the left root closes
        (is (= [] (workflow/ready "bond-right")))
        (is (false? (workflow/done? "bond-right")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No ready workflow step"
                              (workflow/complete! "bond-right")))
        (is (= "Do A" (:title (workflow/ready-step "bond-left"))))
        (workflow/complete! "bond-left")
        (is (true? (workflow/done? "bond-left")))
        (is (= ["Do B"] (mapv :title (workflow/ready "bond-right"))))))))

(deftest workflow-checkpoint-rejects-duplicate-choice-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"choice keys must be unique"
                        (workflow/checkpoint :gate "Gate"
                                             :choices [{:key :abort :label "A"}
                                                       {:key :abort :label "B"}]))))

(deftest workflow-builders-reject-unknown-option-keys
  (testing "each builder and the choice map fail loudly on a mistyped option key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/param :requird true)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/step :a "A" :self :depend-on [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/gate :a "A" :ci :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A" :choicez [:x])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/call :a 'x {} :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/workflow "W" {:param {:x true}} (workflow/step :a "A" :self))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A"
                                               :choices [{:key :ok :labl "Bad"}]))))
  (testing "ex-data carries the offending and allowed keys"
    (try
      (workflow/step :a "A" :self :depend-on [:b])
      (is false "expected step to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= [:depend-on] (:unknown (ex-data e))))
        (is (contains? (:allowed (ex-data e)) :depends-on))))))

(deftest workflow-step-requires-self-waiter
  (testing "only :self is accepted; any other waiter fails loudly, directing to gate"
    (is (= {:id :a :title "A"} (workflow/step :a "A" :self)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Step waiter must be :self.*use gate"
                          (workflow/step :a "A" :ci)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Step waiter must be :self.*use gate"
                          (workflow/step :a "A" :subagent)))
    (try
      (workflow/step :a "A" :ci)
      (is false "expected step to throw on a non-:self waiter")
      (catch clojure.lang.ExceptionInfo e
        (is (= :ci (:waiter (ex-data e))))))))

(deftest workflow-gate-rejects-self-waiter
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate waiter must be.*other than :self"
                        (workflow/gate :handoff "Hand off" :self)))
  (try
    (workflow/gate :handoff "Hand off" :self)
    (is false "expected gate to reject :self")
    (catch clojure.lang.ExceptionInfo e
      (is (= :self (:waiter (ex-data e)))))))

(deftest workflow-gate-rejects-malformed-waiters
  (doseq [bad [42 nil "" "  " [:ci]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate waiter must be a keyword, symbol, or non-blank string"
                          (workflow/gate :handoff "Hand off" bad))
        (pr-str bad))))

(deftest workflow-self-step-carries-no-gate-attribute
  ;; :self steps compile identically to the old bare steps: zero graph churn
  (let [definition (workflow/workflow "Self step" (workflow/step :a "Do A" :self))
        payload (workflow/compile definition)
        strand (first (filter #(= :a (:ref %)) (:strands payload)))]
    (is (= "step" (get-in strand [:attributes "workflow/role"])))
    (is (not (contains? (:attributes strand) "workflow/gate")))))

(defn- cyclic-procedure-workflow [_params]
  (workflow/workflow "Cyclic procedure"
                     (workflow/step :work "Do work" :self)
                     ;; recursive edge by symbol while the entry call passes the
                     ;; fn value: both must canonicalize to one identity
                     (workflow/call :again 'skein.spools.workflow-test/cyclic-procedure-workflow {}
                                    :depends-on [:work])))

(deftest workflow-compile-fails-loudly-on-cyclic-procedure-call
  ;; conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate — compile must throw, not overflow
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Workflow procedure call is cyclic"
                        (workflow/compile
                         (workflow/workflow "Cyclic root"
                                            (workflow/call :outer cyclic-procedure-workflow {}))))))

(deftest workflow-compile-resolves-symbol-procedures
  (let [payload (workflow/compile
                 (workflow/workflow "Symbol procedure demo"
                                    (workflow/call :quality 'skein.spools.workflow-test/toastie-quality-workflow {})))]
    (is (= #{:molecule :quality :quality--inspect}
           (set (map :ref (:strands payload)))))))

(deftest workflow-step-view-reads-keyword-and-string-keyed-attributes
  ;; strand attributes arrive keyword-keyed in-memory but string-keyed after a
  ;; JSON round-trip through the weaver; step-view reads through the single attr
  ;; boundary so both key forms yield the same view (archived workflow-engine review, R2)
  (let [keyworded (workflow/step-view
                   {:id "s1" :title "Do it" :state "active"
                    :attributes {:workflow/role "checkpoint"
                                 :workflow/choices ["a" "b"]
                                 :skills "clojure"}})
        stringed (workflow/step-view
                  {:id "s1" :title "Do it" :state "active"
                   :attributes {"workflow/role" "checkpoint"
                                "workflow/choices" ["a" "b"]
                                "skills" "clojure"}})]
    (is (= {:id "s1" :title "Do it" :state "active" :role "checkpoint"
            :choices ["a" "b"] :skills "clojure"}
           keyworded))
    (is (= keyworded stringed))))

(deftest workflow-spool-fails-loudly-on-bad-definitions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"required params"
                        (workflow/compile {:name "Missing" :params {:x {:required true}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad params" :params {"x" {:required true}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad param def" :params {:x {:required "yes"}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow params"
                        (workflow/compile {:name "Bad params" :steps []} {"x" true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                        (workflow/compile {:name "Duplicate" :steps [{:id :a :title "A"}
                                                                     {:id :a :title "Again"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad condition" :steps [{:id :a :title "A" :condition '(bad)}]}))))

(deftest workflow-compile-splices-condition-excluded-step-deps
  (let [definition (workflow/workflow
                    "Splice"
                    (workflow/step :design "Design" :self)
                    (workflow/step :review "Review" :self :depends-on [:design] :condition :include-review)
                    (workflow/step :implement "Implement" :self :depends-on [:review]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (not (contains? refs :review)))
    (is (contains? edges [:implement :design "depends-on"]))
    (is (not (contains? edges [:implement :review "depends-on"])))))

(deftest workflow-compile-splices-transitively-through-two-excluded-steps
  (let [definition (workflow/workflow
                    "Transitive splice"
                    (workflow/step :base "Base" :self)
                    (workflow/step :mid1 "Mid 1" :self :depends-on [:base] :condition :skip)
                    (workflow/step :mid2 "Mid 2" :self :depends-on [:mid1] :condition :skip)
                    (workflow/step :consumer "Consumer" :self :depends-on [:mid2]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :base :consumer} refs))
    (is (contains? edges [:consumer :base "depends-on"]))
    (is (not (contains? edges [:consumer :mid1 "depends-on"])))
    (is (not (contains? edges [:consumer :mid2 "depends-on"])))))

(deftest workflow-compile-fails-loudly-on-unknown-depends-on-ref
  (let [definition (workflow/workflow
                    "Typo"
                    (workflow/step :design "Design" :self)
                    (workflow/step :implement "Implement" :self :depends-on [:desgin]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :implement (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-attributes-unknown-ref-to-the-excluded-step-that-names-it
  (let [definition (workflow/workflow
                    "Typo in excluded step"
                    (workflow/step :design "Design" :self)
                    (workflow/step :review "Review" :self :depends-on [:desgin] :condition :include-review)
                    (workflow/step :implement "Implement" :self :depends-on [:review]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :review (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-fails-loudly-on-root-ref-collision
  (let [definition (workflow/workflow
                    "Root collision"
                    (workflow/step :molecule "Steal the root ref" :self))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile definition)))))

(deftest workflow-loop-steps-render-item-index-and-params
  (let [definition (workflow/workflow
                    "Loop render"
                    (workflow/step :deploy
                                   (fn [{:keys [feature item i]}]
                                     (str "Deploy " feature " to " (name item) " #" i)) :self
                                   :loop {:each :envs}))
        titles (into {} (map (juxt :ref :title)) (:strands (workflow/compile definition {:feature "checkout"
                                                                                         :envs [:dev :prod]})))]
    (is (= "Deploy checkout to dev #0" (get titles :deploy-1)))
    (is (= "Deploy checkout to prod #1" (get titles :deploy-2)))))

(deftest workflow-loop-each-accepts-param-keyword-and-fn-of-params
  (let [from-keyword (workflow/workflow
                      "Each keyword"
                      (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item)) :self :loop {:each :regions}))
        from-fn (workflow/workflow
                 "Each fn"
                 (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item)) :self
                                :loop {:each (fn [{:keys [regions]}] (reverse regions))}))
        regions {:regions ["us" "eu"]}]
    (is (= #{:molecule :ship-1 :ship-2}
           (set (map :ref (:strands (workflow/compile from-keyword regions))))))
    (is (= ["Ship us" "Ship eu"]
           (mapv :title (rest (:strands (workflow/compile from-keyword regions))))))
    (is (= ["Ship eu" "Ship us"]
           (mapv :title (rest (:strands (workflow/compile from-fn regions))))))))

(deftest workflow-loop-each-fails-loudly-on-non-sequential-param
  (let [definition (workflow/workflow
                    "Bad each"
                    (workflow/step :s "S" :self :loop {:each :n}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":each must resolve to a sequential"
                          (workflow/compile definition {:n 5})))))

(deftest workflow-loop-suffix-rules
  (let [count-def (workflow/workflow "Count" (workflow/step :ping "Ping" :self :loop {:count 3}))
        map-def (workflow/workflow
                 "Map ids"
                 (workflow/step :run (fn [{:keys [item]}] (str "Run " (:id item))) :self :loop {:each :tasks}))
        position-def (workflow/workflow
                      "Positions"
                      (workflow/step :s (fn [{:keys [item]}] (str "S " item)) :self :loop {:each ["x" "y"]}))]
    (is (= [:molecule :ping-1 :ping-2 :ping-3] (map :ref (:strands (workflow/compile count-def)))))
    (is (= [:molecule :run-alpha :run-beta]
           (map :ref (:strands (workflow/compile map-def {:tasks [{:id "alpha"} {:id "beta"}]})))))
    (is (= [:molecule :s-1 :s-2] (map :ref (:strands (workflow/compile position-def)))))))

(deftest workflow-loop-fans-in-base-id-dependents
  (let [definition (workflow/workflow
                    "Fan in"
                    (workflow/step :migrate (fn [{:keys [item]}] (str "Migrate " item)) :self :loop {:each :shards})
                    (workflow/step :verify "Verify migrations" :self :depends-on [:migrate]))
        payload (workflow/compile definition {:shards ["a" "b" "c"]})
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :migrate-1 :migrate-2 :migrate-3 :verify} refs))
    (is (contains? edges [:verify :migrate-1 "depends-on"]))
    (is (contains? edges [:verify :migrate-2 "depends-on"]))
    (is (contains? edges [:verify :migrate-3 "depends-on"]))
    ;; the pre-expansion base id is not itself a strand, only its fan-in edges
    (is (not (contains? edges [:verify :migrate "depends-on"])))))

(deftest workflow-loop-does-not-mask-unknown-depends-on-refs
  (let [definition (workflow/workflow
                    "Loop plus typo"
                    (workflow/step :migrate "Migrate" :self :loop {:each :shards})
                    (workflow/step :verify "Verify" :self :depends-on [:migrate :migrat]))]
    (try
      (workflow/compile definition {:shards ["a" "b"]})
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :verify (:step (ex-data e))))
        (is (= :migrat (:missing (ex-data e))))))))

(deftest workflow-loop-base-id-collisions-fail-loudly
  ;; Fan-in keys deps on the pre-expansion base id, so a base-id collision must
  ;; be rejected before it can silently misroute a dependency.
  (let [dup-base (workflow/workflow
                  "Dup base"
                  (workflow/step :run "Run once" :self :loop {:each :xs})
                  (workflow/step :run "Run again" :self :loop {:count 3}))
        base-vs-plain (workflow/workflow
                       "Base vs plain"
                       (workflow/step :run "Loop" :self :loop {:each :xs})
                       (workflow/step :run "Plain" :self))
        base-vs-root (workflow/workflow
                      "Base vs root"
                      (workflow/step :molecule "Steal root" :self :loop {:each :xs}))
        xs {:xs ["a" "b"]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile dup-base xs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile base-vs-plain xs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile base-vs-root xs)))))

(deftest workflow-loop-chain-depends-through-expansions-and-keeps-base-fan-in
  (let [definition (workflow/workflow
                    "Chain"
                    (workflow/step :prep "Prep" :self)
                    (workflow/step :task (fn [{:keys [item]}] (str "Task " (:id item))) :self
                                   :depends-on [:prep]
                                   :loop {:each :tasks :chain true})
                    (workflow/step :accept "Accept" :self :depends-on [:task]))
        tasks {:tasks [{:id "a"} {:id "b"} {:id "c"}]}
        edges (set (map (juxt :from :to :type) (:edges (workflow/compile definition tasks))))
        described (into {} (map (juxt :id identity)) (:steps (workflow/describe definition tasks)))]
    (is (contains? edges [:task-a :prep "depends-on"]))
    (is (contains? edges [:task-b :task-a "depends-on"]))
    (is (contains? edges [:task-c :task-b "depends-on"]))
    (is (= [:task-a :task-b :task-c] (:depends-on (described :accept))))))

(deftest workflow-loop-chain-count-uses-previous-count-expansion
  (let [definition (workflow/workflow
                    "Count chain"
                    (workflow/step :round "Round" :self :loop {:count 3 :chain true}))
        edges (set (map (juxt :from :to :type) (:edges (workflow/compile definition))))]
    (is (contains? edges [:round-2 :round-1 "depends-on"]))
    (is (contains? edges [:round-3 :round-2 "depends-on"]))))

(deftest workflow-loop-condition-and-fan-in-splice-interact
  ;; A condition on a loop step is evaluated against workflow params for every
  ;; expanded copy; excluding all copies leaves a base-id dependent to splice
  ;; through the fanned-in (now excluded) ids onto their own deps.
  (let [definition (workflow/workflow
                    "Loop conditions"
                    (workflow/step :migrate "Migrate" :self :loop {:each :shards} :condition :do-migrate)
                    (workflow/step :verify "Verify" :self :depends-on [:migrate]))
        payload (workflow/compile definition {:shards ["a" "b"] :do-migrate false})
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :verify} refs))
    (is (not-any? (fn [[_ to _]] (= :migrate to)) edges))))

(deftest workflow-checkpoint-kind-carries-the-decision-owner
  ;; workflow/checkpoint-kind is the canonical HITL signal: :human is the default
  ;; kind, and an :agent checkpoint is distinguished by this one attribute.
  (let [human (workflow/checkpoint :signoff "Sign off" :kind :human :choices [:approved])
        default-kind (workflow/checkpoint :also "Also decide" :choices [:approved])
        agent (workflow/checkpoint :route "Route" :kind :agent :choices [:go])]
    (is (= "human" (get-in human [:attributes "workflow/checkpoint-kind"])))
    (is (= "human" (get-in default-kind [:attributes "workflow/checkpoint-kind"])))
    (is (= "agent" (get-in agent [:attributes "workflow/checkpoint-kind"])))))

(deftest workflow-run-scoped-views-carry-run-id-and-filter-frontier
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow "Runid demo"
                                          (workflow/step :a "Do A" :self)
                                          (workflow/gate :handoff "Hand off" :subagent)
                                          (workflow/checkpoint :decide "Decide" :kind :agent :choices [:ok]))
            started (workflow/start! "runid-run" definition {})]
        (is (= "runid-run" (:run-id (first (:ready started)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow steps are ready"
                              (workflow/ready-step "runid-run")))
        (is (= #{"Do A" "Hand off" "Decide"} (set (map :title (workflow/ready "runid-run")))))
        (is (= ["runid-run" "runid-run" "runid-run"] (mapv :run-id (workflow/ready "runid-run"))))
        (is (= ["Hand off"] (mapv :title (workflow/ready-gates "runid-run" "subagent"))))
        (is (= "Decide" (:title (workflow/ready-checkpoint "runid-run"))))
        (is (= ["Decide"] (mapv :title (workflow/ready "runid-run" {:role "checkpoint"}))))
        ;; a bare step-view (no run context) stays unchanged
        (is (not (contains? (workflow/step-view {:id "x" :title "T" :state "active"
                                                 :attributes {"workflow/role" "step"}})
                            :run-id)))))))

(defn- input-checkpoint-workflow [_]
  (workflow/workflow
   "Input gate"
   (workflow/checkpoint :gate "Decide"
                        :kind :agent
                        :choices [{:key :abort
                                   :label "Abort"
                                   :input [{:key :reason :required true :description "Why abort"}
                                           {:key :note :required false}]}])))

(deftest workflow-choice-input-surfaced-and-enforced
  (with-runtime
    (fn [_rt _]
      (workflow/start! "input-run" (input-checkpoint-workflow {}) {})
      ;; the declaration is surfaced with the choice details, string-keyed
      (is (= [{"key" "reason" "required" true "description" "Why abort"}
              {"key" "note" "required" false}]
             (get (workflow/choice-detail "input-run" :abort) "input")))
      (let [gate-id (:id (workflow/ready-step "input-run"))]
        ;; a missing required key fails loudly before any mutation, carrying the
        ;; declaration in ex-data, and the checkpoint stays active
        (try
          (workflow/choose! "input-run" :abort {})
          (is false "expected choose! to fail on missing required input")
          (catch clojure.lang.ExceptionInfo e
            (is (re-find #"missing required keys" (ex-message e)))
            (is (= ["reason"] (:missing (ex-data e))))
            (is (seq (:input-declaration (ex-data e))))))
        (is (= "active" (:state (repl/strand gate-id))))
        ;; supplying the required key succeeds and closes the run
        (is (= {:ready [] :done true}
               (workflow/choose! "input-run" :abort {:reason "cancelled"})))))))

(deftest workflow-choice-input-rejects-unknown-declaration-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input [{:key :reason :requird true}]}]))))

(deftest workflow-choice-input-declaration-validates-required-and-description
  ;; the public ::choices spec is the grammar the builder enforces, so a
  ;; malformed field type fails there with explain data (TEN-003)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input [{:key :reason :required "yes"}]}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input [{:key :reason :description :nope}]}])))
  (let [thrown (try (workflow/checkpoint :gate "Decide"
                                         :choices [{:key :abort
                                                    :input [{:key :reason :required "yes"}]}])
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some #(= :required (last (:path %)))
              (::s/problems (:explain (ex-data thrown))))
        "the explain payload names the field that failed")))

(deftest workflow-choice-input-accepts-string-or-keyword-keys
  ;; the surfaced declaration uses string key names, so a caller feeding those
  ;; names straight back (string-keyed) satisfies the requirement just as a
  ;; keyword-keyed map does
  (with-runtime
    (fn [_rt _]
      (workflow/start! "input-str-run" (input-checkpoint-workflow {}) {})
      (is (= {:ready [] :done true}
             (workflow/choose! "input-str-run" :abort {"reason" "cancelled"}))))))

(defn- join-inner-workflow [_]
  (workflow/workflow
   "Inner"
   (workflow/step :do-inner "Do inner work" :self)))

(deftest workflow-procedure-join-auto-closes-and-never-surfaces-as-ready
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Join demo"
                        (workflow/step :prep "Prep" :self)
                        (workflow/call :inner join-inner-workflow {} :depends-on [:prep])
                        (workflow/step :after "After" :self :depends-on [:inner]))]
        (workflow/start! "join-run" definition {})
        (is (= "Prep" (:title (workflow/ready-step "join-run"))))
        ;; completing prep reveals the inner step, not the join
        (is (= "Do inner work" (:title (first (:ready (workflow/complete! "join-run"))))))
        ;; completing the last inner step auto-closes the join in the same
        ;; transaction: the join never appears as ready work and :after is next
        (let [after-inner (:ready (workflow/complete! "join-run"))]
          (is (= ["After"] (mapv :title after-inner)))
          (is (not-any? #(= "procedure" (:role %)) after-inner)))
        ;; the join strand is closed with engine provenance, though it was never
        ;; returned as a ready step nor manually completed
        (let [join (first (repl/query [:and
                                       [:= [:attr "workflow/role"] "procedure"]
                                       [:= [:attr "workflow/procedure"] "inner"]]))]
          (is (= "closed" (:state join)))
          (is (= "engine" (get-in join [:attributes :workflow/outcome-by]))))
        (is (= {:ready [] :done true} (workflow/complete! "join-run")))
        (is (workflow/done? "join-run"))))))

(deftest workflow-advance-drives-steps-and-checkpoints
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Advance demo"
                        (workflow/step :work "Do work" :self)
                        (workflow/checkpoint :sign "Sign off"
                                             :depends-on [:work]
                                             :kind :agent
                                             :choices [{:key :approved :label "Approve"}]))]
        (workflow/start! "advance-run" definition {})
        ;; a ready step advanced with a :choice fails loudly and mutates nothing
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not supply a :choice"
                              (workflow/advance! "advance-run" {:choice :approved})))
        ;; advance! completes the ready step, returning the D1.1 result shape
        (let [after (workflow/advance! "advance-run")]
          (is (= ["Sign off"] (mapv :title (:ready after))))
          (is (false? (:done after))))
        ;; a ready checkpoint advanced without a :choice fails loudly
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a :choice"
                              (workflow/advance! "advance-run")))
        ;; advance! dispatches the checkpoint choice and closes the run
        (is (= {:ready [] :done true} (workflow/advance! "advance-run" {:choice :approved})))
        (is (workflow/done? "advance-run"))))))

(defn- registry-router-stage [{:keys [target]}]
  (workflow/workflow
   "Registry router"
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(defn- registry-second-stage [_]
  (workflow/workflow "Registry second" (workflow/step :do-second "Do second" :self)))

(defn- registry-alt-second-stage [_]
  (workflow/workflow "Registry alt" (workflow/step :do-alt "Do alt" :self)))

(deftest workflow-named-next-resolves-and-fails-loudly-on-unknown-name
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-second 'skein.spools.workflow-test/registry-second-stage)
      (is (= 'skein.spools.workflow-test/registry-second-stage
             (workflow/workflow-definition :wt-second)))
      (workflow/start! "named-run" (registry-router-stage {:target :wt-second}) {})
      ;; a registered keyword name routes just like a symbol :next target
      (is (= [{:title "Do second" :role "step"}]
             (mapv #(select-keys % [:title :role])
                   (:ready (workflow/choose! "named-run" :advance)))))
      ;; an unregistered name fails loudly at choose! time, before any mutation,
      ;; so the checkpoint stays active and resumable
      (workflow/start! "unknown-run" (registry-router-stage {:target :wt-never}) {})
      (let [go-id (:id (workflow/ready-step "unknown-run"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                              (workflow/choose! "unknown-run" :advance)))
        (is (= "active" (:state (repl/strand go-id))))))))

(deftest workflow-registry-rename-repoints-in-flight-run
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-rename 'skein.spools.workflow-test/registry-second-stage)
      (workflow/start! "rename-run" (registry-router-stage {:target :wt-rename}) {})
      ;; re-registering the name (a reloaded workflow) points the in-flight run's
      ;; not-yet-chosen route at the new constructor
      (workflow/register-workflow! :wt-rename 'skein.spools.workflow-test/registry-alt-second-stage)
      (is (= ["Do alt"]
             (mapv :title (:ready (workflow/choose! "rename-run" :advance))))))))

(defn- revise-stage-workflow [{:keys [revision]}]
  (workflow/workflow
   "Revise stage"
   {:params {:revision (workflow/param :default (boolean revision))}}
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:orient]
                        :kind :agent
                        :choices [{:key :revise :label "Revise" :revise {:params {:revision true}}}
                                  {:key :approved :label "Approve" :next :wt-downstream}])))

(defn- downstream-stage-workflow [_]
  (workflow/workflow "Downstream stage" (workflow/step :do-downstream "Do downstream" :self)))

(deftest workflow-revise-repours-definition-skipping-condition-gated-steps
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-downstream 'skein.spools.workflow-test/downstream-stage-workflow)
      (workflow/start! "revise-run" (revise-stage-workflow {}) {}
                       {:definition 'skein.spools.workflow-test/revise-stage-workflow
                        :context {}})
      (is (= "Orient" (:title (workflow/ready-step "revise-run"))))
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/complete! "revise-run")))))
      ;; :revise re-pours the run's own workflow/definition with :revision true;
      ;; the condition-gated :orient drops out, so signoff is immediately ready
      (is (= [{:title "Sign off" :role "checkpoint"}]
             (mapv #(select-keys % [:title :role]) (:ready (workflow/choose! "revise-run" :revise)))))
      (let [revised-root (workflow/current-root "revise-run")]
        (is (true? (get-in revised-root [:attributes :workflow/context :revision])))
        ;; the override key is recorded stage-local so it can be shed on exit
        (is (= ["revision"] (get-in revised-root [:attributes :workflow/stage-params]))))
      ;; approving routes forward: the stage-local :revision must not leak into
      ;; the downstream stage's persisted context
      (let [remaining (:ready (workflow/choose! "revise-run" :approved))]
        (is (= ["Do downstream"] (mapv :title remaining)))
        (is (not (contains? (get-in (workflow/current-root "revise-run")
                                    [:attributes :workflow/context])
                            :revision)))))))

(deftest workflow-revise-fails-loudly-without-resolvable-definition
  (with-runtime
    (fn [_rt _]
      ;; no :definition seeded, so the run's root cannot resolve a workflow to
      ;; re-pour and :revise fails loudly (TEN-003) rather than guessing
      (workflow/start! "revise-nodef" (revise-stage-workflow {}) {})
      (workflow/complete! "revise-nodef")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no workflow/definition"
                            (workflow/choose! "revise-nodef" :revise))))))

(deftest workflow-checkpoint-rejects-next-and-revise-together
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":next and :revise are mutually exclusive"
                        (workflow/checkpoint :c "C"
                                             :choices [{:key :x :next :foo :revise {:params {}}}]))))

(deftest workflow-checkpoint-rejects-malformed-revise
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :c "C"
                                             :choices [{:key :x :revise {:no-params true}}]))))

(defn- introspect-stage-b-workflow [_]
  (workflow/workflow
   "Introspect stage B"
   (workflow/step :finish "Finish B" :self)))

(defn- introspect-stage-a-workflow [{:keys [revision]}]
  (workflow/workflow
   "Introspect stage A"
   {:params {:feature (workflow/param :required true)
             :revision (workflow/param :default (boolean revision))}}
   (workflow/step :draft (fn [{:keys [feature]}] (str "Draft " feature)) :self
                  :condition [:!= :revision true])
   (workflow/step :refine "Refine draft" :self :depends-on [:draft])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:refine]
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :description "Ship it."
                                   :next 'skein.spools.workflow-test/introspect-stage-b-workflow}
                                  {:key :revise
                                   :label "Revise"
                                   :description "Send it back."
                                   :revise {:params {:revision true}}
                                   :input [{:key :reason :required true :description "Why revise"}]}])))

(deftest workflow-describe-projects-choices-input-and-condition-filtering
  ;; describe is a compile-time projection: no strands are written, so it needs no
  ;; runtime. On the base pass the conditioned :draft is present with its
  ;; :condition; the checkpoint's choices carry declared :input and routing.
  (let [desc (workflow/describe (introspect-stage-a-workflow {}) {:feature "widgets"})
        by-id (into {} (map (juxt :id identity)) (:steps desc))
        signoff (:signoff by-id)
        choices (into {} (map (juxt :key identity)) (:choices signoff))]
    (is (= "Introspect stage A" (:name desc)))
    (is (= #{:draft :refine :signoff} (set (keys by-id))))
    (is (= "Draft widgets" (:title (:draft by-id))))
    (is (= [:!= :revision true] (:condition (:draft by-id))))
    (is (= "checkpoint" (:role signoff)))
    (is (= "step" (:role (:refine by-id))))
    (is (= "skein.spools.workflow-test/introspect-stage-b-workflow"
           (:next (get choices "approve"))))
    (is (= {:revision true} (:revise (get choices "revise"))))
    (is (= [{"key" "reason" "required" true "description" "Why revise"}]
           (:input (get choices "revise")))))
  ;; a revision round condition-excludes :draft; its dependent :refine splices to
  ;; become the entry step, so the description matches what would pour
  (is (= #{:refine :signoff}
         (set (map :id (:steps (workflow/describe (introspect-stage-a-workflow {:revision true})
                                                  {:feature "widgets"})))))))

(deftest workflow-describe-fails-loudly-on-missing-required-params
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"required params are missing"
                        (workflow/describe (introspect-stage-a-workflow {})))))

(deftest workflow-run-history-projects-ordered-molecules-and-events
  (with-runtime
    (fn [_rt _]
      (workflow/start! "hist" (introspect-stage-a-workflow {}) {:feature "widgets"}
                       {:definition 'skein.spools.workflow-test/introspect-stage-a-workflow
                        :context {:feature "widgets"}})
      (workflow/complete! "hist")                            ; :draft
      (workflow/complete! "hist" {:attributes {"acme/round" "one"}}) ; :refine
      (workflow/choose! "hist" :revise {:reason "needs work"}) ; loop → round 2
      (workflow/complete! "hist" {:attributes {"acme/round" "two"}}) ; :refine (draft skipped)
      (workflow/choose! "hist" :approve {})                  ; hand off → stage B
      (workflow/complete! "hist")                            ; :finish → done
      (is (workflow/done? "hist"))
      (let [history (workflow/run-history "hist")
            created (map #(get-in % [:root :created_at]) history)
            choice-outcome (fn [mol] (some #(when (= :choice (:type %)) (:outcome %)) (:events mol)))
            revise-mol (first (filter #(= "revise" (choice-outcome %)) history))
            approve-mol (first (filter #(= "approve" (choice-outcome %)) history))
            stage-b-mol (first (filter #(= "Introspect stage B" (get-in % [:root :title])) history))
            ;; the engine projects its own outcome keys only, so a caller's
            ;; vocabulary is read back off the closed strands the events name
            round-set (fn [mol]
                        (set (keep #(get-in (repl/strand (:id %)) [:attributes :acme/round])
                                   (:events mol))))]
        (is (= 3 (count history)))
        ;; molecules are ordered by creation; events within a molecule by :at
        (is (= created (sort created)))
        (is (every? (fn [{:keys [events]}] (= (map :at events) (sort (map :at events)))) history))
        ;; the revise round recorded the choice input and the first round's attrs
        (is (= "Introspect stage A" (get-in revise-mol [:root :title])))
        (is (= {:reason "needs work"}
               (:input (first (filter #(= :choice (:type %)) (:events revise-mol))))))
        (is (contains? (round-set revise-mol) "one"))
        (is (contains? (round-set approve-mol) "two"))
        ;; the conditioned :draft ran only in the first round
        (is (some #(= "Draft widgets" (:title %)) (:events revise-mol)))
        (is (not-any? #(= "Draft widgets" (:title %)) (:events approve-mol)))
        (is (= [:step-closed] (mapv :type (:events stage-b-mol))))))))

(deftest workflow-run-history-fails-loudly-for-unknown-run
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (workflow/run-history "no-such-run"))))))

(deftest workflow-squash-run-refuses-active-then-squashes-to-one-digest
  (with-runtime
    (fn [_rt _]
      (workflow/start! "arch" (introspect-stage-a-workflow {}) {:feature "widgets"}
                       {:definition 'skein.spools.workflow-test/introspect-stage-a-workflow
                        :context {:feature "widgets"}})
      (workflow/complete! "arch")             ; :draft
      (workflow/complete! "arch")             ; :refine
      ;; an active root cannot be archived
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active root"
                            (workflow/squash-run! "arch")))
      (workflow/choose! "arch" :approve {})   ; hand off → stage B
      (workflow/complete! "arch")             ; :finish → done
      (is (workflow/done? "arch"))
      (let [digest (workflow/squash-run! "arch")
            summary (get-in digest [:attributes :workflow/summary])
            molecules (repl/query [:and [:= [:attr "workflow/run-id"] "arch"]
                                   [:= [:attr "workflow/role"] "root"]])
            digests (repl/query [:and [:= [:attr "workflow/run-id"] "arch"]
                                 [:= [:attr "workflow/role"] "digest"]])]
        (is (= "closed" (:state digest)))
        (is (= "digest" (get-in digest [:attributes :workflow/role])))
        (is (= "arch" (get-in digest [:attributes :workflow/run-id])))
        ;; the summary carries stage titles + checkpoint outcomes
        (is (= 2 (count summary)))
        (is (contains? (set (map :title summary)) "Introspect stage A"))
        (is (contains? (set (mapcat :outcomes summary)) "approve"))
        ;; exactly one digest remains for the run and every molecule is burned
        (is (empty? molecules))
        (is (= 1 (count digests)))))))

(deftest await-returns-checkpoint-for-a-ready-checkpoint
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-checkpoint"
                       (workflow/workflow "Await checkpoint"
                                          (workflow/checkpoint :decide "Decide" :kind :human
                                                               :choices [:go]))
                       {})
      (is (= :checkpoint (:reason (workflow/await! "await-checkpoint" {:timeout-secs 1})))))))

(deftest await-returns-step-for-a-ready-self-step
  ;; a bare :self step used to bury itself under :waiting; it must now surface
  ;; immediately as :step so the driving agent never sits idle on its own work
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-self-step"
                       (workflow/workflow "Await step" (workflow/step :do-it "Do it" :self))
                       {})
      (is (= :step (:reason (workflow/await! "await-self-step" {:timeout-secs 1})))))))

(deftest await-returns-gate-for-a-waiter-with-no-registered-executor
  (with-runtime
    (fn [_rt _]
      (workflow/start! "await-unowned-gate"
                       (workflow/workflow "Await gate"
                                          (workflow/gate :delegate "Delegate" :await-test-unowned))
                       {})
      (is (= :gate (:reason (workflow/await! "await-unowned-gate" {:timeout-secs 1})))))))

(deftest await-stays-silent-on-a-healthy-executor-owned-gate-then-reports-stalled
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow "Await executor gate"
                                          (workflow/gate :delegate "Delegate" :await-test-executor))]
        (workflow/start! "await-executor-gate" definition {})
        (test-alpha/set-clock! rt (test-alpha/manual-clock Instant/EPOCH))
        (let [gate-id (:id (first (workflow/ready "await-executor-gate")))]
          (is (= :await-test-executor
                 (workflow/register-executor! :await-test-executor (constantly nil))))
          ;; a healthy executor-owned gate stays silent: the run just times out
          (is (= :timeout (:reason (workflow/await! rt "await-executor-gate"
                                                    {:timeout-secs 1}))))
          (workflow/register-executor! :await-test-executor
                                       (fn [step]
                                         (when (= gate-id (:id step))
                                           {:why "test"})))
          (let [result (workflow/await! rt "await-executor-gate" {:timeout-secs 1})]
            (is (= :stalled (:reason result)))
            (is (= {:why "test"} (get-in result [:detail :stall])))))))))

(deftest await-explicit-runtime-arity-matches-ambient-result-for-a-completed-run
  (with-runtime
    (fn [rt _]
      (workflow/start! "await-explicit-runtime"
                       (workflow/workflow "Await explicit runtime" (workflow/step :do-it "Do it" :self))
                       {})
      (workflow/complete! "await-explicit-runtime")
      (let [ambient (workflow/await! "await-explicit-runtime" {:timeout-secs 1})
            explicit (workflow/await! rt "await-explicit-runtime" {:timeout-secs 1})]
        (is (= :done (:reason explicit)))
        (is (= ambient explicit))))))

(deftest await!-fails-loudly-for-malformed-timeout-secs-or-poll-ms
  (with-runtime
    (fn [rt _]
      (doseq [bad [-1 1.5 "1"]]
        (testing (str "timeout-secs " (pr-str bad))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout-secs must be a non-negative integer"
                                (workflow/await! rt "await-malformed-opts" {:timeout-secs bad}))))
        (testing (str "poll-ms " (pr-str bad))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a positive integer"
                                (workflow/await! rt "await-malformed-opts" {:poll-ms bad})))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":poll-ms must be a positive integer"
                            (workflow/await! rt "await-malformed-opts" {:poll-ms 0}))))))

(deftest register-executor-rejects-invalid-waiters
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Executor waiter must be.*other than :self"
                        (workflow/register-executor! :self (constantly nil))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Executor waiter must be.*keyword, symbol, or non-blank string"
                        (workflow/register-executor! 42 (constantly nil))))
  (try
    (workflow/register-executor! :self (constantly nil))
    (is false "expected executor registration to reject :self")
    (catch clojure.lang.ExceptionInfo e
      (is (= :self (:waiter (ex-data e)))))))

(deftest executors-reflects-registrations
  (with-runtime
    (fn [_rt _]
      (workflow/register-executor! :registry-test-executor (constantly nil))
      (is (contains? (workflow/executors) :registry-test-executor)))))

;; --- owner-partitioned constructor/executor conversion (TASK-Olr-007) --------

(defn exec-detail-a [_step] {:by :a})
(defn exec-detail-b [_step] {:by :b})

(deftest executor-fn-value-registration-lives-in-resource-state
  ;; A bare function value has no symbol, so it is held as runtime-owned resource
  ;; state (DELTA-OlrDrt-001.CC8), not as owner-partition declaration data.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [pred (constantly {:raw true})]
        (workflow/register-executor! :raw-exec pred)
        (is (identical? pred (get @(wf-registry/executor-fns rt) "raw-exec")))
        (is (identical? pred (wf-registry/executor-for rt "raw-exec")))
        (is (empty? (registry/effective (wf-registry/registry-handle rt)
                                        workflow/executor-kind))
            "no function value reaches the declarative executor kind")))))

(deftest executor-symbol-resolves-to-a-function-value-per-gate-evaluation
  ;; DW1: an executor symbol is resolved to a function value at each gate
  ;; evaluation, so a re-pointed executor is observed on the next lookup while a
  ;; value already captured for an in-flight call keeps its snapshot (CC10).
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-executor! :exec-snap 'skein.spools.workflow-test/exec-detail-a)
      (let [snapshot (wf-registry/executor-for rt "exec-snap")]
        (is (= {:by :a} (snapshot {})))
        (workflow/register-executor! :exec-snap 'skein.spools.workflow-test/exec-detail-b)
        (is (= {:by :b} ((wf-registry/executor-for rt "exec-snap") {}))
            "the next gate evaluation resolves the re-pointed executor")
        (is (= {:by :a} (snapshot {}))
            "a value captured for an in-flight call keeps its snapshot")))))

(deftest workflow-owner-refresh-removes-omitted-constructors-and-executors
  ;; DW2 / kxhd4 R4 per-domain deletion completeness: an owner-complete
  ;; replacement removes any route or executor the new partition omits, and
  ;; removing the owner clears the rest — no global reload.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [handle (wf-registry/registry-handle rt)
            spools-constructors (fn [entries]
                                  (registry/replace-owner!
                                   handle workflow/constructor-kind :spools/pkg
                                   {:layer :spools :entries entries :overrides #{}}))
            spools-executors (fn [entries]
                               (registry/replace-owner!
                                handle workflow/executor-kind :spools/pkg
                                {:layer :spools :entries entries :overrides #{}}))]
        (spools-constructors {:route-a 'skein.spools.workflow-test/registry-second-stage
                              :route-b 'skein.spools.workflow-test/registry-alt-second-stage})
        (spools-executors {"exec-a" 'skein.spools.workflow-test/exec-detail-a
                           "exec-b" 'skein.spools.workflow-test/exec-detail-b})
        (is (= #{:route-a :route-b} (set (keys (workflow/workflows)))))
        (is (= #{"exec-a" "exec-b"} (set (keys (wf-registry/executor-map rt)))))
        ;; a complete replacement omitting one of each removes only those
        (spools-constructors {:route-a 'skein.spools.workflow-test/registry-second-stage})
        (spools-executors {"exec-a" 'skein.spools.workflow-test/exec-detail-a})
        (is (= #{:route-a} (set (keys (workflow/workflows)))) "omitted route removed")
        (is (= #{"exec-a"} (set (keys (wf-registry/executor-map rt)))) "omitted executor removed")
        ;; removing the owner clears the rest
        (registry/remove-owner! handle workflow/constructor-kind :spools/pkg)
        (registry/remove-owner! handle workflow/executor-kind :spools/pkg)
        (is (empty? (workflow/workflows)))
        (is (empty? (wf-registry/executor-map rt)))))))

(deftest workflow-constructor-override-restores-shadowed-entry-on-removal
  ;; DW2 / DELTA-OlrDrt-001.CC3: a higher-layer entry shadows a lower one with
  ;; explicit override intent; removing the overriding owner re-exposes the
  ;; shadowed entry.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [handle (wf-registry/registry-handle rt)]
        (registry/replace-owner! handle workflow/constructor-kind :spools/pkg
                                 {:layer :spools
                                  :entries {:route-x 'skein.spools.workflow-test/registry-second-stage}
                                  :overrides #{}})
        (is (= 'skein.spools.workflow-test/registry-second-stage
               (workflow/workflow-definition :route-x)))
        ;; a direct/REPL registration shadows the lower spools layer
        (workflow/register-workflow! :route-x 'skein.spools.workflow-test/registry-alt-second-stage)
        (is (= 'skein.spools.workflow-test/registry-alt-second-stage
               (workflow/workflow-definition :route-x))
            "the direct layer wins while its override stands")
        ;; removing the direct owner restores the shadowed spools entry
        (registry/remove-owner! handle workflow/constructor-kind :skein.owner/repl)
        (is (= 'skein.spools.workflow-test/registry-second-stage
               (workflow/workflow-definition :route-x))
            "the shadowed entry becomes effective again")))))

(deftest executor-fns-state-shape-matches-declared-version
  (assert-state-shape #'wf-registry/new-executor-fns #{:executor-fns}))

;; --- static definitions and the live definition registry --------------------

(s/def ::scope string?)
(s/def ::static-build-params (s/keys :req-un [::scope]))

(workflow/defworkflow static-build
  "Build an agreed scope."
  {:entrypoints #{:start :continue}
   :param-spec ::static-build-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(workflow/defworkflow static-review
  "Review a completed implementation."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Review"
   (workflow/step :inspect "Inspect the change" :self)))

(workflow/defworkflow static-spike
  "Reduce uncertainty and recommend the next routine."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Spike"
   (workflow/checkpoint :recommendation "Choose what follows the spike"
                        :kind :agent
                        :choices [{:key :recommend-build :label "Build" :next :wt-build}
                                  {:key :stop :label "Stop"}])))

(def ^:private bad-defaults-definition
  (workflow/workflow
   "Bad defaults"
   {:entrypoints #{:start} :defaults {:at (Instant/parse "2026-01-01T00:00:00Z")}}
   (workflow/step :a "A" :self)))

(def ^:private bad-spec-definition
  (workflow/workflow
   "Bad spec"
   {:entrypoints #{:start} :param-spec ::never-registered}
   (workflow/step :a "A" :self)))

(defn- revisable [title]
  (workflow/workflow
   title
   {:entrypoints #{:start}}
   (workflow/checkpoint :again "Revise again"
                        :kind :agent
                        :choices [{:key :again :label "Again" :revise {:params {}}}])))

(def ^:private revisable-definition (revisable "Revisable v1"))
(def ^:private revisable-definition-v2 (revisable "Revisable v2"))

(def ^:private live-var-definition
  (workflow/workflow
   "Live v1"
   {:entrypoints #{:start}}
   (workflow/step :a "A" :self)))

(defn- exploding-constructor [_]
  (throw (ex-info "constructor blew up" {:cause :test})))

(defn- malformed-constructor [_]
  {:not-a "workflow"})

(deftest defworkflow-defines-a-self-describing-var-and-stays-passive
  ;; PROP-Wcd-001.S5: ordinary def semantics first — loading the namespace
  ;; defines the Var and publishes nothing.
  (is (= "Build an agreed scope." (:doc static-build)))
  (is (= "Build an agreed scope." (:doc (meta #'static-build))))
  (is (= #{:start :continue} (:entrypoints static-build)))
  (is (= ::static-build-params (:param-spec static-build)))
  (is (= {:reviewer "agent"} (:defaults static-build)))
  (is (= [:implement] (mapv :id (:steps static-build))))
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (is (empty? (workflow/workflows))
          "this namespace's defworkflow forms were evaluated outside contribution collection"))))

(deftest defworkflow-rejects-an-invalid-declaration
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/static-definition
                         "doc" {:entrypoints #{:teleport}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/static-definition
                         "doc" {:entrypoints #{}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/static-definition
                         "doc" {:entrypoint #{:start}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":doc must be a non-blank string"
                        (workflow/static-definition
                         "  " {:entrypoints #{:start}}
                         (workflow/workflow "W" (workflow/step :a "A" :self))))))

(deftest workflow-builder-validates-the-complete-definition
  ;; The builder owns the whole assembled shape, so a malformed nested step or
  ;; choice fails at authoring time rather than at the pour.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/workflow "W" {:id :untitled}))
      "a step map carrying :id is a step, and a step needs a title")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/workflow "W" {:title "no id"}))
      "a leading map without :id is read as the options map")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/workflow "W" {:defaults [:not :a :map]}
                                           (workflow/step :a "A" :self)))))

(deftest static-definition-start-merges-defaults-and-records-identity
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-build 'skein.spools.workflow-test/static-build)
      (workflow/start! "static-run" :wt-build {:scope "compact queue"})
      (let [root (workflow/current-root "static-run")]
        (is (= "Build compact queue" (:title root)))
        (is (= "wt-build" (get-in root [:attributes :workflow/definition-name]))
            "the registered name is what a later revision resolves against")
        (is (= "skein.spools.workflow-test/static-build"
               (get-in root [:attributes :workflow/definition]))
            "the resolved symbol records which definition this root was built from"))
      (is (= "Implement compact queue for agent" (:title (workflow/ready-step "static-run")))
          "declared :defaults merge under the caller's params"))))

(deftest static-definition-start-requires-the-start-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-review 'skein.spools.workflow-test/static-review)
      (let [thrown (try (workflow/start! "no-start" :wt-review {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
        (is (= :start (:entrypoint (ex-data thrown))))
        (is (= [:call] (:entrypoints (ex-data thrown)))))
      (is (nil? (workflow/current-root "no-start"))
          "the run is refused before anything is poured")
      ;; the registry is the capability boundary; trusted Clojure holding the
      ;; Var is already past it
      (workflow/start! "direct-var" #'static-review {})
      (is (= "Inspect the change" (:title (workflow/ready-step "direct-var")))))))

(deftest registered-name-routing-requires-the-continue-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-build 'skein.spools.workflow-test/static-build)
      (workflow/register-workflow! :wt-review 'skein.spools.workflow-test/static-review)
      ;; :wt-build declares :continue, so the authored route pours it — the
      ;; choice input carries the scope its :param-spec requires
      (workflow/start! "route-ok" (registry-router-stage {:target :wt-build}) {})
      (is (= ["Implement compact queue for agent"]
             (mapv :title (:ready (workflow/choose! "route-ok" :advance
                                                    {:scope "compact queue"})))))
      ;; :wt-review is call-only, so the same route is refused before mutation
      (workflow/start! "route-bad" (registry-router-stage {:target :wt-review}) {})
      (let [go-id (:id (workflow/ready-step "route-bad"))
            thrown (try (workflow/choose! "route-bad" :advance)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
        (is (= :continue (:entrypoint (ex-data thrown))))
        (is (= "active" (:state (repl/strand go-id))))))))

(deftest registered-call-target-requires-the-call-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-review 'skein.spools.workflow-test/static-review)
      (workflow/register-workflow! :wt-build 'skein.spools.workflow-test/static-build)
      (let [caller (fn [target]
                     (workflow/workflow
                      "Caller"
                      (workflow/step :prepare "Prepare" :self)
                      (workflow/call :sub target {} :depends-on [:prepare])))]
        (is (= ["Caller" "Prepare" "Inspect the change" "Complete sub"]
               (mapv :title (:strands (workflow/compile (caller :wt-review))))))
        (let [thrown (try (workflow/compile (caller :wt-build))
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown)))))))))

(deftest unregister-workflow-removes-the-direct-registration
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-build 'skein.spools.workflow-test/static-build)
      (workflow/register-workflow! :wt-review 'skein.spools.workflow-test/static-review)
      (is (= {:wt-review 'skein.spools.workflow-test/static-review}
             (workflow/unregister-workflow! :wt-build))
          "removal returns what the direct layer still declares")
      (is (= [:wt-review] (vec (keys (workflow/workflows)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/start! "gone" :wt-build {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no direct registration to remove"
                            (workflow/unregister-workflow! :wt-build))))))

(deftest register-workflow-rejects-an-unresolvable-symbol-with-repair-context
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-missing 'skein.spools.workflow-test/no-such-definition)
                        (catch clojure.lang.ExceptionInfo e e))
            data (ex-data thrown)]
        (is (= :workflow/definition-unresolvable (:reason data)))
        (is (= :wt-missing (:name data)))
        (is (= 'skein.spools.workflow-test/no-such-definition (:definition data)))
        (is (= 'skein.spools.workflow-test (:namespace data)))
        (is (seq (:repair data))))
      (is (empty? (workflow/workflows))
          "a rejected registration leaves the live registry untouched"))))

(deftest register-workflow-rejects-a-route-to-a-missing-target
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-spike 'skein.spools.workflow-test/static-spike)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/reference-unregistered (:reason (ex-data thrown))))
        (is (= :wt-build (:target (ex-data thrown))))
        (is (= :continue (:entrypoint (ex-data thrown)))))
      ;; with the target registered first, the same registration is publishable
      (workflow/register-workflow! :wt-build 'skein.spools.workflow-test/static-build)
      (is (= :wt-spike (workflow/register-workflow!
                        :wt-spike 'skein.spools.workflow-test/static-spike))))))

(deftest register-workflow-rejects-non-json-defaults-and-unknown-param-specs
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-defaults 'skein.spools.workflow-test/bad-defaults-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defaults-invalid (:reason (ex-data thrown))))
        (is (= [:at] (:path (ex-data thrown)))))
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-spec 'skein.spools.workflow-test/bad-spec-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/param-spec-missing (:reason (ex-data thrown))))
        (is (= ::never-registered (:param-spec (ex-data thrown))))))))

(deftest legacy-constructors-stay-trusted-only-and-fail-loudly
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-boom 'skein.spools.workflow-test/exploding-constructor)
      (workflow/register-workflow! :wt-malformed 'skein.spools.workflow-test/malformed-constructor)
      (is (= #{} (:entrypoints (workflow/resolve-workflow :wt-boom)))
          "an opaque constructor declares no capability")
      (is (= :legacy (:kind (workflow/resolve-workflow :wt-boom))))
      (let [thrown (try (workflow/start! "boom-run" :wt-boom {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/legacy-constructor-failed (:reason (ex-data thrown))))
        (is (= "constructor blew up" (ex-message (ex-cause thrown)))))
      (let [thrown (try (workflow/start! "malformed-run" :wt-malformed {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/legacy-definition-invalid (:reason (ex-data thrown))))
        (is (string? (:explain (ex-data thrown)))))
      (is (nil? (workflow/current-root "boom-run")) "both fail before any pour")
      (is (nil? (workflow/current-root "malformed-run"))))))

(deftest revision-resolves-the-live-registered-name
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-revisable 'skein.spools.workflow-test/revisable-definition)
      (workflow/start! "revise-name-run" :wt-revisable {})
      (is (= "Revisable v1" (:title (workflow/current-root "revise-name-run"))))
      ;; repointing the name changes what the next revision pours; the strands
      ;; already poured are untouched
      (workflow/register-workflow! :wt-revisable 'skein.spools.workflow-test/revisable-definition-v2)
      (workflow/choose! "revise-name-run" :again)
      (is (= "Revisable v2" (:title (workflow/current-root "revise-name-run"))))
      (is (= "skein.spools.workflow-test/revisable-definition-v2"
             (get-in (workflow/current-root "revise-name-run")
                     [:attributes :workflow/definition])))
      ;; removing the name fails the next revision before any mutation
      (workflow/unregister-workflow! :wt-revisable)
      (let [checkpoint (:id (workflow/ready-step "revise-name-run"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                              (workflow/choose! "revise-name-run" :again)))
        (is (= "active" (:state (repl/strand checkpoint))))))))

(deftest redefining-a-var-changes-the-next-transition-not-the-current-run
  ;; PROP-Wcd-001.S8: source load and code reload redefine Vars under a live
  ;; registry. Resolution is live, so the change lands at the next transition.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [original @#'live-var-definition]
        (try
          (workflow/register-workflow! :wt-live 'skein.spools.workflow-test/live-var-definition)
          (workflow/start! "live-run" :wt-live {})
          (is (= "Live v1" (:title (workflow/current-root "live-run"))))
          (alter-var-root #'live-var-definition assoc :name "Live v2")
          (is (= "Live v1" (:title (workflow/current-root "live-run")))
              "strands already poured keep the definition they were built from")
          (is (= "Live v2" (:name (:value (workflow/resolve-workflow :wt-live))))
              "the registered name resolves the redefined Var")
          (finally
            (alter-var-root #'live-var-definition (constantly original))))))))

;; --- owner-complete publication of definitions ------------------------------

(defn- definition-module-source
  "Write a module source file declaring `forms` and return its workspace path."
  [config-dir label forms]
  (let [source (str "modules/" label ".clj")
        file (io/file config-dir source)]
    (io/make-parents file)
    (spit file (str "(ns test.module." label "\n"
                    "  \"Definition module fixture for the workflow candidate tests.\"\n"
                    "  (:require [skein.spools.workflow :as workflow]))\n"
                    forms))
    source))

(def ^:private alpha-definition-form
  (str "(workflow/defworkflow alpha\n"
       "  \"Alpha routine.\"\n"
       "  {:entrypoints #{:start :continue}}\n"
       "  (workflow/workflow \"Alpha\" (workflow/step :a \"A\" :self)))\n"))

(def ^:private beta-routing-form
  (str "(workflow/defworkflow beta\n"
       "  \"Beta routine routing to alpha.\"\n"
       "  {:entrypoints #{:start}}\n"
       "  (workflow/workflow \"Beta\"\n"
       "    (workflow/checkpoint :go \"Go\" :kind :agent\n"
       "      :choices [{:key :on :label \"On\" :next :alpha}])))\n"))

(deftest module-refresh-publishes-collected-definitions-across-owners
  ;; PROP-Wcd-001.S5/S6: defworkflow contributes its symbol only under a
  ;; collector, and a cross-owner route is judged against the complete candidate.
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [alpha-source (definition-module-source config-dir "wf-alpha" alpha-definition-form)
            beta-source (definition-module-source config-dir "wf-beta" beta-routing-form)]
        (is (= :applied (:status (runtime/module! rt :wf-alpha {:file alpha-source}))))
        (is (= :applied (:status (runtime/module! rt :wf-beta {:file beta-source}))))
        (is (= #{:alpha :beta} (set (keys (workflow/workflows)))))
        (is (= 'test.module.wf-alpha/alpha (workflow/workflow-definition :alpha)))
        (is (= :static (:kind (workflow/resolve-workflow :beta))))))))

(deftest deleting-a-referenced-definition-by-omission-is-refused-atomically
  ;; The alpha owner's next contribution drops the definition beta routes to.
  ;; Publication is rejected whole, so both owners keep their live partitions.
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [alpha-source (definition-module-source config-dir "wf-alpha2" alpha-definition-form)
            beta-source (definition-module-source config-dir "wf-beta2" beta-routing-form)]
        (runtime/module! rt :wf-alpha2 {:file alpha-source})
        (runtime/module! rt :wf-beta2 {:file beta-source})
        (is (= #{:alpha :beta} (set (keys (workflow/workflows)))))
        (definition-module-source config-dir "wf-alpha2" "")
        (let [result (runtime/module! rt :wf-alpha2 {:file alpha-source})]
          (is (= :refused (:status result)))
          (is (= :workflow/reference-unregistered
                 (get-in result [:conflicts 0 :data :reason]))))
        (is (= #{:alpha :beta} (set (keys (workflow/workflows))))
            "every affected owner keeps its previous live partition")))))

(deftest an-unresolvable-contributed-definition-is-refused-with-owner-context
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [source (definition-module-source
                     config-dir "wf-gone"
                     (str "(skein.api.runtime.alpha/collect-entry!\n"
                          "  workflow/definition-kind :gone 'test.module.wf-gone/absent)\n"))
            result (runtime/module! rt :wf-gone {:file source})]
        (is (= :refused (:status result)))
        (is (= :workflow/definition-unresolvable
               (get-in result [:conflicts 0 :data :reason])))
        (is (= :wf-gone (get-in result [:conflicts 0 :data :owner])))
        (is (empty? (workflow/workflows)))))))

;; --- spec-first params, live checkpoint input, and the JSON boundary --------

(s/def ::reviewer string?)
(s/def ::spec-first-params (s/keys :req-un [::scope ::reviewer]))

(workflow/defworkflow spec-first-build
  "Build a scope under a whole-map param contract."
  {:entrypoints #{:start :continue}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(s/def ::approval-note string?)
(s/def ::approval-input (s/keys :req-un [::approval-note]))

(workflow/defworkflow spec-first-signoff
  "Approve or reject under a live checkpoint input spec."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Sign off"
   (workflow/checkpoint :signoff "Approve the change"
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :input {:spec ::approval-input
                                           :doc "Record why this was approved."}}
                                  {:key :reject :label "Reject"}])))

(workflow/defworkflow spec-first-revisable
  "Revise its own params under a whole-map contract."
  {:entrypoints #{:start}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Revise " scope))
   (workflow/checkpoint :again "Revise or stop"
                        :kind :agent
                        :choices [{:key :bad :label "Bad" :revise {:params {:scope 42}}}
                                  {:key :good :label "Good"
                                   :revise {:params {:scope "second pass"}}}
                                  {:key :stop :label "Stop"}])))

(def ^:private unknown-input-definition
  (workflow/workflow
   "Unknown input"
   {:entrypoints #{:start}}
   (workflow/checkpoint :go "Go" :kind :agent
                        :choices [{:key :approve :input ::never-registered-input}])))

(def ^:private legacy-input-definition
  (workflow/workflow
   "Legacy input"
   {:entrypoints #{:start}}
   (workflow/checkpoint :approve-step "Approve" :kind :agent
                        :choices [{:key :approve
                                   :input [{:key :note :required true}]}])))

(defn- spec-first-router [{:keys [target]}]
  (workflow/workflow
   "Router"
   {:entrypoints #{:start}}
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(deftest spec-first-params-merge-defaults-before-whole-map-validation
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-spec-build 'skein.spools.workflow-test/spec-first-build)
      ;; :reviewer is required by the spec and supplied only by :defaults, so a
      ;; start that omits it proves defaults merge before validation
      (workflow/start! "spec-ok" :wt-spec-build {:scope "compact queue"})
      (is (= "Implement compact queue for agent"
             (:title (workflow/ready-step "spec-ok"))))
      ;; the caller's own map compiles: validation never substitutes conform output
      (is (= {:scope "compact queue" :reviewer "agent"}
             (get-in (workflow/current-root "spec-ok") [:attributes :workflow/context]))))))

(deftest spec-first-params-fail-before-any-mutation
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-spec-build 'skein.spools.workflow-test/spec-first-build)
      (testing "a missing required key fails with the contract and the violation"
        (let [thrown (try (workflow/start! "spec-missing" :wt-spec-build {})
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/params-invalid (:reason data)))
          (is (= ::spec-first-params (:spec data)))
          (is (= :wt-spec-build (:name data)))
          (is (re-find #"scope" (:explain data)))
          (is (= "root" (get-in data [:spec-forms 0 "relation"])))
          (is (nil? (workflow/current-root "spec-missing"))
              "nothing pours when params are rejected")))
      (testing "a wrong-typed value fails the same way"
        (let [thrown (try (workflow/start! "spec-typed" :wt-spec-build {:scope 42})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/params-invalid (:reason (ex-data thrown))))
          (is (nil? (workflow/current-root "spec-typed"))))))))

(deftest spec-first-params-guard-named-routes-and-revisions
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-spec-build 'skein.spools.workflow-test/spec-first-build)
      (testing "a named route validates the target's merged params"
        (workflow/start! "route-invalid" (spec-first-router {:target :wt-spec-build}) {})
        (let [go-id (:id (workflow/ready-step "route-invalid"))
              thrown (try (workflow/choose! "route-invalid" :advance {:scope 42})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/params-invalid (:reason (ex-data thrown))))
          (is (= "active" (:state (repl/strand go-id)))
              "the checkpoint stays ready, so the run is resumable"))
        (is (= ["Implement compact queue for agent"]
               (mapv :title (:ready (workflow/choose! "route-invalid" :advance
                                                      {:scope "compact queue"})))))))))

(deftest spec-first-revision-validates-its-override-params
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-revisable 'skein.spools.workflow-test/spec-first-revisable)
      (workflow/start! "revise-run" :wt-revisable {:scope "first pass"})
      (let [checkpoint-id (:id (workflow/ready-checkpoint "revise-run"))
            thrown (try (workflow/choose! "revise-run" :bad)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/params-invalid (:reason (ex-data thrown))))
        (is (= "active" (:state (repl/strand checkpoint-id)))
            "the run keeps its stage when the revision is rejected"))
      (workflow/choose! "revise-run" :good)
      (is (= "Revise second pass" (:title (workflow/current-root "revise-run"))))
      (is (:done (workflow/choose! "revise-run" :stop))))))

(deftest checkpoint-input-spec-is-recorded-at-pour-and-validated-live
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/start! "input-run" #'spec-first-signoff {})
      (testing "the poured checkpoint records identity, doc, and the form graph"
        (let [detail (workflow/choice-detail "input-run" :approve)
              declared (get detail "input-spec")]
          (is (= "skein.spools.workflow-test/approval-input" (get declared "spec")))
          (is (= "Record why this was approved." (get declared "doc")))
          (is (= [{"spec" "skein.spools.workflow-test/approval-input"
                   "relation" "root"
                   "form" (pr-str (s/form ::approval-input))}
                  {"spec" "skein.spools.workflow-test/approval-note"
                   "relation" "keyword-reference"
                   "form" (pr-str (s/form ::approval-note))}]
                 (get declared "spec-forms")))))
      (testing "invalid input fails before mutation with the current contract"
        (let [step-id (:id (workflow/ready-checkpoint "input-run"))
              thrown (try (workflow/choose! "input-run" :approve {})
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/input-invalid (:reason data)))
          (is (= ::approval-input (:spec data)))
          (is (re-find #"approval-note" (:explain data)))
          (is (= "active" (:state (repl/strand step-id))))))
      (testing "a choice declaring no input contract takes any map"
        (is (:done (workflow/choose! "input-run" :reject {:anything "goes"}))))
      (testing "valid input records the choice"
        (workflow/start! "input-ok" #'spec-first-signoff {})
        (is (:done (workflow/choose! "input-ok" :approve {:approval-note "scope agreed"})))))))

(deftest checkpoint-input-spec-resolves-the-live-spec-not-the-recorded-form
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/start! "live-input" #'spec-first-signoff {})
      (try
        ;; redefining a nested spec changes validation while the outer form the
        ;; worker was shown is unchanged
        (s/def ::approval-note (s/and string? #(< 10 (count %))))
        (let [thrown (try (workflow/choose! "live-input" :approve {:approval-note "short"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/input-invalid (:reason (ex-data thrown)))))
        (is (:done (workflow/choose! "live-input" :approve
                                     {:approval-note "long enough to pass"})))
        (finally (s/def ::approval-note string?))))))

(deftest checkpoint-input-spec-removal-fails-loudly
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/start! "gone-input" #'spec-first-signoff {})
      (try
        (s/def ::approval-input nil)
        (let [step-id (:id (workflow/ready-checkpoint "gone-input"))
              thrown (try (workflow/choose! "gone-input" :approve {:approval-note "x"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/input-spec-missing (:reason (ex-data thrown))))
          (is (= "active" (:state (repl/strand step-id)))))
        (finally (s/def ::approval-input (s/keys :req-un [::approval-note])))))))

(deftest registering-a-definition-with-an-unknown-input-spec-is-refused
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-input 'skein.spools.workflow-test/unknown-input-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/input-spec-missing (:reason (ex-data thrown))))
        (is (= ::never-registered-input (:spec (ex-data thrown))))
        (is (empty? (workflow/workflows)))))))

(deftest deprecated-per-key-choice-input-still-enforces-required-keys
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/start! "legacy-input" #'legacy-input-definition {})
      (let [detail (workflow/choice-detail "legacy-input" :approve)]
        (is (= [{"key" "note" "required" true}] (get detail "input")))
        (is (nil? (get detail "input-spec"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                            (workflow/choose! "legacy-input" :approve {})))
      (is (:done (workflow/choose! "legacy-input" :approve {:note "fine"}))))))

;; --- s/form documentation graph --------------------------------------------

(s/def ::label string?)
(s/def ::children (s/coll-of ::node))
(s/def ::node (s/keys :req-un [::label] :opt-un [::children]))
(s/def ::node-alias ::node)
(s/def ::draft string?)
(s/def ::stage-enum #{::draft :final})

(def ^:private predicate-calls (atom 0))

(defn- counting-string?
  "A predicate that records every call, so a discovery walk that stays out of
  validation is provable rather than asserted."
  [value]
  (swap! predicate-calls inc)
  (string? value))

(s/def ::counted counting-string?)
(s/def ::counting-params (s/keys :req-un [::counted]))

(defn- form-graph [spec-name]
  (mapv (juxt #(get % "spec") #(get % "relation")) (workflow/spec-forms spec-name)))

(deftest spec-forms-walks-nested-keys-collections-and-aliases
  (is (= [["skein.spools.workflow-test/node" "root"]
          ["skein.spools.workflow-test/children" "keyword-reference"]
          ["skein.spools.workflow-test/label" "keyword-reference"]]
         (form-graph ::node))
      "references are visited in qualified-name order and emitted once")
  (is (= (pr-str (s/form ::node)) (get (first (workflow/spec-forms ::node)) "form")))
  ;; `(s/def ::node-alias ::node)` registers the target spec itself, so the
  ;; alias prints the target's form and its graph is the target's graph — ::node
  ;; then reappears one level down, through ::children.
  (is (= [["skein.spools.workflow-test/node-alias" "root"]
          ["skein.spools.workflow-test/children" "keyword-reference"]
          ["skein.spools.workflow-test/label" "keyword-reference"]
          ["skein.spools.workflow-test/node" "keyword-reference"]]
         (form-graph ::node-alias)))
  (is (= (pr-str (s/form ::node)) (get (first (workflow/spec-forms ::node-alias)) "form"))))

(deftest spec-forms-is-cycle-safe-and-deterministic
  ;; ::node -> ::children -> ::node closes the cycle
  (is (= (form-graph ::node) (form-graph ::node)))
  (is (= 1 (count (filter #(= "skein.spools.workflow-test/node" (first %))
                          (form-graph ::node))))))

(deftest spec-forms-reports-keyword-literals-without-claiming-dependency
  (is (= [["skein.spools.workflow-test/stage-enum" "root"]
          ["skein.spools.workflow-test/draft" "keyword-reference"]]
         (form-graph ::stage-enum))
      "a set member that also names a registered spec is supplementary documentation")
  (let [thrown (try (workflow/spec-forms ::never-registered-anywhere)
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/spec-missing (:reason (ex-data thrown)))
        "a stale or mistyped identity is never mistaken for a spec with no references")))

(deftest spec-forms-executes-no-predicate
  (reset! predicate-calls 0)
  (is (= [["skein.spools.workflow-test/counting-params" "root"]
          ["skein.spools.workflow-test/counted" "keyword-reference"]]
         (form-graph ::counting-params)))
  (is (zero? @predicate-calls) "discovery reads forms and the registry only")
  (is (s/valid? ::counting-params {:counted "x"}))
  (is (pos? @predicate-calls) "validation is what runs predicates"))

;; --- the JSON param boundary ------------------------------------------------

(s/def :acme.workflows/feature string?)
(s/def ::json-params (s/keys :req-un [::scope] :req [:acme.workflows/feature]))

(deftest json-params-keywordize-object-keys-recursively
  (is (= {:scope "queue"
          :acme.workflows/feature "cli"
          :options {:reviewer "agent" :tags ["a" "b"]}
          :steps [{:id "one"} {:id "two"}]}
         (workflow/json->params
          {"scope" "queue"
           "acme.workflows/feature" "cli"
           "options" {"reviewer" "agent" "tags" ["a" "b"]}
           "steps" [{"id" "one"} {"id" "two"}]})))
  (is (s/valid? ::json-params (workflow/json->params
                               {"scope" "queue" "acme.workflows/feature" "cli"}))
      "an unqualified JSON key satisfies :req-un and a qualified one addresses :req"))

(deftest json-params-fail-loudly-outside-the-object-contract
  (let [thrown (try (workflow/json->params [1 2 3])
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/params-not-json (:reason (ex-data thrown)))))
  (let [thrown (try (workflow/json->params {"" "blank"})
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/params-not-json (:reason (ex-data thrown))))))

(deftest json-params-cannot-express-string-keyed-maps
  ;; PROP-Wcd-001.NG8: conversion is total, so a spec that requires string keys
  ;; is reachable only from trusted Clojure in v1.
  (is (= {:a 1} (workflow/json->params {"a" 1})))
  (is (not (s/valid? (s/map-of string? any?) (workflow/json->params {"a" 1})))))

(deftest param-spec-removed-after-registration-fails-live
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-spec-build 'skein.spools.workflow-test/spec-first-build)
      (try
        (s/def ::spec-first-params nil)
        (let [thrown (try (workflow/start! "spec-gone" :wt-spec-build {:scope "queue"})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/param-spec-missing (:reason (ex-data thrown))))
          (is (nil? (workflow/current-root "spec-gone"))))
        (finally (s/def ::spec-first-params (s/keys :req-un [::scope ::reviewer])))))))

(deftest describe-surfaces-the-declared-input-contract-without-its-form-graph
  (let [choices (-> (workflow/describe #'spec-first-signoff) :steps first :choices)
        approve (first (filter #(= "approve" (:key %)) choices))]
    (is (= {"spec" "skein.spools.workflow-test/approval-input"
            "doc" "Record why this was approved."}
           (:input-spec approve))
        "description stays cheap; the form graph is recorded when the checkpoint pours")))

(def ^:private spec-first-caller
  (workflow/workflow
   "Caller"
   {:entrypoints #{:start}}
   (workflow/call :build :wt-callable {:scope "called scope"})))

(def ^:private spec-first-bad-caller
  (workflow/workflow
   "Bad caller"
   {:entrypoints #{:start}}
   (workflow/call :build :wt-callable {:scope 42})))

(workflow/defworkflow spec-first-callable
  "A call target under a whole-map param contract."
  {:entrypoints #{:call}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   "Callable"
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(deftest registered-call-targets-validate-their-params
  ;; a call target reached by registered name meets the same contract boundary
  ;; that requires its :call entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-callable 'skein.spools.workflow-test/spec-first-callable)
      (workflow/start! "call-ok" #'spec-first-caller {})
      (is (= "Implement called scope for agent"
             (:title (workflow/ready-step "call-ok"))))
      (let [thrown (try (workflow/start! "call-bad" #'spec-first-bad-caller {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/params-invalid (:reason (ex-data thrown))))
        (is (nil? (workflow/current-root "call-bad"))
            "the caller's own run pours nothing when the target rejects its params")))))

;; --- named deferred continuation (PROP-Wcd-001.S7) --------------------------

(s/def ::feature string?)
(s/def ::devflow-params (s/keys :req-un [::feature ::reviewer]))

(workflow/defworkflow defer-devflow
  "Plan and build a feature."
  {:entrypoints #{:start :continue}
   :param-spec ::devflow-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Plan and build " feature))
   (workflow/step :inspect
                  (fn [{:keys [feature reviewer]}]
                    (str "Inspect " feature " for " reviewer))
                  :self)))

(workflow/defworkflow defer-spike
  "Reduce uncertainty before committing."
  {:entrypoints #{:start :continue}}
  (workflow/workflow
   "Spike"
   (workflow/step :probe "Probe the unknown" :self)))

(workflow/defworkflow defer-call-only
  "A call-only routine that cannot be continued into."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Call only"
   (workflow/step :inner "Inner" :self)))

(def ^:private card-template
  "The unregistered template a spool publishes: it names its exit point without
  naming anyone else's workflow."
  (workflow/workflow
   "Track a card"
   (workflow/step :prepare "Prepare the card" :self)
   (workflow/defer :perform-work "Choose how this work will be performed"
                   :depends-on [:prepare])))

(defn- bound-card
  "Return the template bound to `targets`, as user code with both spools would."
  [targets]
  (workflow/bind-defers card-template {:perform-work targets}))

(def ^:private tracked-card (bound-card #{:wt-devflow :wt-spike}))
(def ^:private call-only-bound-card (bound-card #{:wt-callonly}))
(def ^:private legacy-bound-card (bound-card #{:wt-legacy}))

(def ^:private defer-caller
  (workflow/workflow
   "Caller"
   {:entrypoints #{:start}}
   (workflow/call :sub :wt-card {})))

(defn- register-defer-targets! []
  (workflow/register-workflow! :wt-devflow 'skein.spools.workflow-test/defer-devflow)
  (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-spike))

(defn- start-at-defer!
  "Start `run-id` on the bound card and complete its first step, leaving the
  defer exit as the whole ready frontier."
  [run-id]
  (workflow/start! run-id tracked-card {})
  (workflow/complete! run-id)
  (workflow/ready-step run-id))

(deftest defer-is-a-terminal-exit-across-the-topology-matrix
  ;; PROP-Wcd-001.S7: every way a definition continues past a step says so with
  ;; :depends-on, so one rule covers successors, conditions, loops, and calls —
  ;; and it reads the declaration, so params cannot change the answer.
  (testing "a terminal defer is the whole point and builds fine"
    (is (= [:prepare :perform-work] (mapv :id (:steps card-template)))))
  (doseq [[label successor]
          [[:successor (workflow/step :after "After" :self :depends-on [:perform-work])]
           [:condition (workflow/step :maybe "Maybe" :self
                                      :depends-on [:perform-work] :condition :flag)]
           [:loop (workflow/step :each "Each" :self
                                 :depends-on [:perform-work] :loop {:count 2})]
           [:call (workflow/call :sub :wt-spike {} :depends-on [:perform-work])]
           [:checkpoint (workflow/checkpoint :pick "Pick" :depends-on [:perform-work]
                                             :choices [:a])]]]
    (testing (str "nothing may continue past a defer: " (name label))
      (let [thrown (try (workflow/workflow "Bad"
                                           (workflow/defer :perform-work "Choose")
                                           successor)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-not-terminal (:reason (ex-data thrown))))
        (is (= :perform-work (:defer (ex-data thrown)))))))
  (testing "the exit itself is unconditional: the builder rejects the opts outright"
    (doseq [key [:condition :loop]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                            (workflow/defer :perform-work "Choose" key :flag)))))
  (testing "and a raw map that skipped the builder is caught at the definition"
    (doseq [[key value] [[:condition :flag] [:loop {:count 2}]]]
      (let [thrown (try (workflow/workflow
                         "Bad"
                         (assoc (workflow/defer :perform-work "Choose") key value))
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-not-static (:reason (ex-data thrown))))
        (is (= key (:key (ex-data thrown)))))))
  (testing "an enclosing procedure would continue past the exit, so a call refuses it"
    (let [thrown (try (workflow/compile
                       (workflow/workflow "Caller" (workflow/call :sub tracked-card {})))
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-in-procedure (:reason (ex-data thrown))))
      (is (= :perform-work (:defer (ex-data thrown)))))))

(deftest bind-defers-owns-the-user-authority-boundary
  (testing "targets materialize in registered-name order whatever the author wrote"
    (is (= ["wt-devflow" "wt-spike"]
           (get-in (second (:steps (bound-card #{:wt-spike :wt-devflow})))
                   [:attributes "workflow/defer-workflows"]))))
  (testing "binding a name the definition never declared is a defect, not a new exit"
    (let [thrown (try (workflow/bind-defers card-template {:nope #{:wt-spike}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-unknown (:reason (ex-data thrown))))
      (is (= [:perform-work] (:declared (ex-data thrown))))))
  (testing "an empty target set is an exit no worker can fill"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow defer bindings"
                          (workflow/bind-defers card-template {:perform-work #{}}))))
  (testing "an unbound template describes itself but cannot pour"
    (is (= [nil ["wt-devflow" "wt-spike"]]
           [(:workflows (second (:steps (workflow/describe card-template))))
            (:workflows (second (:steps (workflow/describe tracked-card))))]))
    (let [thrown (try (workflow/compile card-template)
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-unbound (:reason (ex-data thrown))))
      (is (= :perform-work (:defer (ex-data thrown)))))))

(deftest registering-a-definition-with-an-unbound-defer-is-refused
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-template 'skein.spools.workflow-test/card-template)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-unbound (:reason (ex-data thrown)))))
      (is (empty? (workflow/workflows)) "the live registry is untouched"))))

(deftest defer-targets-are-validated-against-the-complete-candidate
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (testing "a target that is not registered at all"
        (let [thrown (try (workflow/register-workflow!
                           :wt-card 'skein.spools.workflow-test/tracked-card)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-unregistered (:reason (ex-data thrown))))
          (is (= :continue (:entrypoint (ex-data thrown))))))
      (register-defer-targets!)
      (is (= :wt-card (workflow/register-workflow!
                       :wt-card 'skein.spools.workflow-test/tracked-card)))
      (testing "a target that does not declare :continue"
        (workflow/register-workflow! :wt-callonly 'skein.spools.workflow-test/defer-call-only)
        (let [thrown (try (workflow/register-workflow!
                           :wt-bad-card
                           'skein.spools.workflow-test/call-only-bound-card)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-entrypoint-unsupported (:reason (ex-data thrown))))
          (is (= :continue (:entrypoint (ex-data thrown))))))
      (testing "an opaque legacy constructor: a defer is new surface with no migration to protect"
        (workflow/register-workflow! :wt-legacy 'skein.spools.workflow-test/exploding-constructor)
        (let [thrown (try (workflow/register-workflow!
                           :wt-legacy-card
                           'skein.spools.workflow-test/legacy-bound-card)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/legacy-opaque (:reason (ex-data thrown))))
          (is (= :wt-legacy (:target (ex-data thrown)))))))))

(deftest a-registered-call-target-may-not-hide-a-defer
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (workflow/register-workflow! :wt-card 'skein.spools.workflow-test/tracked-card)
      (let [thrown (try (workflow/register-workflow!
                         :wt-caller 'skein.spools.workflow-test/defer-caller)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-in-procedure (:reason (ex-data thrown))))
        (is (= :wt-card (:target (ex-data thrown))))))))

(deftest continue-transfers-the-root-and-records-the-cutover
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (let [defer (start-at-defer! "card-1")
            old-root (workflow/current-root "card-1")
            defer-id (:id defer)]
        (is (= "defer" (:role defer)))
        (is (= "perform-work" (:defer defer)))
        (is (= ["wt-devflow" "wt-spike"] (:workflows defer))
            "the poured allowlist is the user's authority for this run")
        (let [result (workflow/continue! "card-1" :wt-devflow {:feature "kanban-web-ui"}
                                         {:by "worker-1"})
              new-root (workflow/current-root "card-1")
              closed (repl/strand defer-id)]
          (is (= ["Inspect kanban-web-ui for agent"] (mapv :title (:ready result))))
          (is (false? (:done result)))
          (is (= "Plan and build kanban-web-ui" (:title new-root)))
          (is (not= (:id old-root) (:id new-root)) "a defer transfers the root")
          (is (= "closed" (:state (repl/strand (:id old-root))))
              "the old root closes in the same cutover")
          (testing "the exit records what it continued into"
            (let [attrs (:attributes closed)]
              (is (= "closed" (:state closed)))
              (is (= "wt-devflow" (:workflow/outcome attrs)))
              (is (= "wt-devflow" (:workflow/continued-workflow attrs)))
              (is (= "skein.spools.workflow-test/defer-devflow"
                     (:workflow/continued-definition attrs)))
              (is (re-matches #"[0-9a-f]{16}" (:workflow/continued-fingerprint attrs)))
              (is (= {:feature "kanban-web-ui" :reviewer "agent"}
                     (:workflow/continued-params attrs))
                  "the exact params it poured with, defaults folded in")
              (is (= "worker-1" (:workflow/outcome-by attrs)))))
          (testing "history reads a filled exit as a continuation"
            (let [events (mapcat :events (workflow/run-history "card-1"))
                  event (first (filter #(= :continuation (:type %)) events))]
              (is (= "wt-devflow" (:outcome event)))
              (is (= "worker-1" (:by event))))))))))

(deftest continue-isolates-the-target-from-the-parent-context
  ;; PROP-Wcd-001.S7/S9: a defer is the cross-spool boundary, so the target sees
  ;; only its own defaults plus what was explicitly supplied for it.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (testing "parent-only params never reach the target"
        (workflow/start! "iso-1" tracked-card {:feature "from-parent" :reviewer "parent"})
        (workflow/complete! "iso-1")
        (let [thrown (try (workflow/continue! "iso-1" :wt-devflow {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/params-invalid (:reason (ex-data thrown)))
              "the parent's :feature is not the target's")
          (is (= "active" (:state (repl/strand (:id (workflow/ready-step "iso-1")))))
              "the defer stays ready")))
      (testing "a conflicting parent value loses to the target's own"
        (workflow/continue! "iso-1" :wt-devflow {:feature "target-owned"})
        (is (= "Plan and build target-owned" (:title (workflow/current-root "iso-1"))))
        (is (= {:feature "target-owned" :reviewer "agent"}
               (get-in (workflow/current-root "iso-1") [:attributes :workflow/context]))
            "the new root's context is the target's params alone"))
      (testing "omitted params and an explicit empty map are the same request"
        (start-at-defer! "iso-2")
        (workflow/continue! "iso-2" :wt-spike)
        (start-at-defer! "iso-3")
        (workflow/continue! "iso-3" :wt-spike {})
        (is (= ["Probe the unknown"] (mapv :title (workflow/ready "iso-2"))))
        (is (= (mapv :title (workflow/ready "iso-2"))
               (mapv :title (workflow/ready "iso-3"))))))))

(deftest continue-resolves-its-target-live-and-fails-with-the-defer-still-ready
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (testing "a name outside the poured allowlist is refused before resolution"
        (let [defer-id (:id (start-at-defer! "live-1"))
              thrown (try (workflow/continue! "live-1" :wt-elsewhere {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-target-not-allowed (:reason (ex-data thrown))))
          (is (= ["wt-devflow" "wt-spike"] (:allowed (ex-data thrown))))
          (is (= "active" (:state (repl/strand defer-id))))))
      (testing "a compatible repoint continues into the replacement"
        (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-devflow)
        (workflow/continue! "live-1" :wt-spike {:feature "repointed"})
        (is (= "Plan and build repointed" (:title (workflow/current-root "live-1"))))
        (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-spike))
      (testing "removal, a lost :continue, and rejected params all leave the defer ready"
        (let [defer-id (:id (start-at-defer! "live-2"))
              check (fn [thrown reason]
                      (is (= reason (:reason (ex-data thrown))))
                      (is (= "active" (:state (repl/strand defer-id)))
                          "nothing closed, so the worker can retry")
                      (is (= 1 (count (workflow/run-history "live-2")))
                          "and nothing was poured"))]
          (check (try (workflow/continue! "live-2" :wt-devflow {:feature 42})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/params-invalid)
          (workflow/register-workflow! :wt-devflow 'skein.spools.workflow-test/defer-call-only)
          (check (try (workflow/continue! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/entrypoint-unsupported)
          (workflow/register-workflow! :wt-devflow 'skein.spools.workflow-test/exploding-constructor)
          (check (try (workflow/continue! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/legacy-opaque)
          (workflow/unregister-workflow! :wt-devflow)
          (check (try (workflow/continue! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/definition-unregistered))))))

(deftest continue-validates-its-request-shape-before-touching-the-run
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (let [defer-id (:id (start-at-defer! "req-1"))]
        (doseq [[label call] [[:blank-run-id #(workflow/continue! "" :wt-spike {})]
                              [:string-workflow #(workflow/continue! "req-1" "wt-spike" {})]
                              [:non-map-params #(workflow/continue! "req-1" :wt-spike [1 2])]
                              [:blank-by #(workflow/continue! "req-1" :wt-spike {} {:by ""})]]]
          (testing (name label)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Invalid workflow continue request"
                                  (call)))))
        (is (= "active" (:state (repl/strand defer-id))))))))

(deftest a-ready-defer-is-neither-completed-nor-advanced
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (let [defer-id (:id (start-at-defer! "role-1"))]
        (doseq [call [#(workflow/complete! "role-1") #(workflow/advance! "role-1")]]
          (let [thrown (try (call) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/step-is-defer (:reason (ex-data thrown))))
            (is (re-find #"continue!" (ex-message thrown)))))
        (is (= "active" (:state (repl/strand defer-id))))
        (testing "and choose! refuses it as a non-checkpoint"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"not a checkpoint"
                                (workflow/choose! "role-1" :whatever))))))))

(deftest a-ready-defer-asks-for-attention-as-a-defer-not-as-work
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (start-at-defer! "await-1")
      (let [state (workflow/await! "await-1" {:timeout-secs 5 :poll-ms 10})]
        (is (= :defer (:reason state))
            "a defer is a decision, so it must not surface as a :self step to do")
        (is (= "perform-work" (:defer (:detail state))))
        (is (= ["wt-devflow" "wt-spike"] (:workflows (:detail state))))))))

(deftest concurrent-choose-and-continue-serialize-under-the-run-guard
  ;; Both verbs resolve their frontier inside the guard, so one wins the cutover
  ;; and the other re-resolves against the frontier it left rather than writing
  ;; a second root under the same run id.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (start-at-defer! "race-1")
      (let [attempts (mapv (fn [target]
                             (future
                               (try {:ok (workflow/continue! "race-1" target {:feature "raced"})}
                                    (catch clojure.lang.ExceptionInfo e {:err e}))))
                           [:wt-devflow :wt-spike])
            ;; bounded: a regression that deadlocks the guard must fail this test
            ;; rather than hang the suite, so the deref gives up and cancels
            results (mapv (fn [attempt]
                            (let [result (deref attempt (test-support/await-budget-ms) ::timeout)]
                              (when (= ::timeout result)
                                (future-cancel attempt))
                              result))
                          attempts)
            winners (filter :ok results)
            losers (filter :err results)]
        (is (not-any? #(= ::timeout %) results)
            "both continuations returned; a timeout here means the guard deadlocked")
        (is (= 1 (count winners)) "exactly one continuation pours")
        (is (= 1 (count losers)))
        (is (= :workflow/step-not-defer (:reason (ex-data (:err (first losers)))))
            "the loser re-resolved and found the winner's frontier, not its own exit")
        (is (some? (workflow/current-root "race-1"))
            "one active root, never two under one run id")))))
