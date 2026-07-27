(ns skein.spools.workflow-test
  "Tests for the skein.spools.workflow userland workflow engine: contract
  explain, compile semantics (calls, conditions, loops, splicing), and the
  run-driving surface (start!/complete!/choose!, gates, checkpoints, bonds)."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.batch.alpha :as batch]
            [skein.api.graph.alpha :as graph]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.registry.alpha :as registry]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.test-support :as test-support :refer [assert-state-shape with-runtime]]
            [skein.spools.workflow :as workflow]
            [skein.spools.workflow.internal.registry :as wf-registry]
            [skein.repl :as repl]
            [skein.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(defn- failure-reason [f]
  (:reason (ex-data (try (f) (catch clojure.lang.ExceptionInfo e e)))))

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
    (is (= :defer (:topic (workflow/explain :defer))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow explain topic"
                          (workflow/explain :dispatch)))
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
                        (workflow/step :design (with-feature "Design ") :self
                                       :attributes {:owner (fn [{:keys [owner]}] owner)})
                        (workflow/step :implement (with-feature "Implement ") :self
                                       :depends-on [:design])
                        (workflow/step :review (with-feature "Review ") :self
                                       :depends-on [:implement]
                                       :condition :include-review))
            result (workflow/pour! definition {:feature "workflow spool"
                                               :owner "agent"
                                               :include-review true})
            root-id (workflow/molecule-id result)
            root (repl/strand root-id)
            subgraph (graph/subgraph rt [root-id])]
        (is (= "Ship workflow spool" (:title root)))
        (is (= "root" (get-in root [:attributes :workflow/role])))
        (is (= #{"Design workflow spool" "Implement workflow spool" "Review workflow spool" "Ship workflow spool"}
               (set (map :title (:strands subgraph)))))
        (is (= 3 (count (filter #(= "parent-of" (:edge_type %)) (:edges subgraph)))))))))

(deftest workflow-spool-inlines-procedure-calls
  (let [review (workflow/workflow
                "Review"
                (workflow/step :inspect
                               (fn [{:keys [artifact]}] (str "Inspect " artifact)) :self)
                (workflow/step :write-review
                               (fn [{:keys [artifact]}] (str "Write review for " artifact)) :self
                               :depends-on [:inspect]))
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

(deftest workflow-call-compilation-preserves-expanded-payload-shape
  (let [procedure (workflow/workflow
                   "Multi entry"
                   (workflow/step :first "First" :self)
                   (workflow/step :second "Second" :self)
                   (workflow/step :finish-first "Finish first" :self :depends-on [:first])
                   (workflow/step :finish-second "Finish second" :self :depends-on [:second]))
        payload (workflow/compile
                 (workflow/workflow
                  "Caller"
                  (workflow/step :before "Before" :self)
                  (workflow/call :procedure procedure {} :depends-on [:before])))
        strands (into {} (map (juxt :ref identity)) (:strands payload))
        edges (set (map (juxt :from :to :type) (:edges payload)))
        procedure-deps (->> (:edges payload)
                            (filter #(= "depends-on" (:type %)))
                            (filter #(= :procedure (:from %)))
                            (map :to)
                            set)]
    (is (= #{:molecule :before :procedure--first :procedure--second
             :procedure--finish-first :procedure--finish-second :procedure}
           (set (keys strands))))
    (is (contains? edges [:procedure--first :before "depends-on"]))
    (is (contains? edges [:procedure--second :before "depends-on"]))
    (is (contains? edges [:procedure--finish-first :procedure--first "depends-on"]))
    (is (contains? edges [:procedure--finish-second :procedure--second "depends-on"]))
    (is (= #{:procedure--finish-first :procedure--finish-second} procedure-deps))))

(deftest workflow-call-compilation-prefixes-nested-procedures-twice
  (let [inner (workflow/workflow "Inner" (workflow/step :work "Work" :self))
        outer (workflow/workflow "Outer" (workflow/call :inner inner {}))
        payload (workflow/compile
                 (workflow/workflow "Caller" (workflow/call :outer outer {})))
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :outer--inner--work :outer--inner :outer} refs))
    (is (contains? edges [:outer--inner :outer--inner--work "depends-on"]))
    (is (contains? edges [:outer :outer--inner "depends-on"]))))

(deftest workflow-call-compilation-preserves-call-title-and-attributes
  (let [payload (workflow/compile
                 (workflow/workflow
                  "Caller"
                  (workflow/call :procedure
                                 (workflow/workflow "Callee" (workflow/step :work "Work" :self))
                                 {}
                                 :title "Review procedure"
                                 :attributes {:owner "reviewer" :priority "high"})))
        join (some #(when (= :procedure (:ref %)) %) (:strands payload))]
    (is (= "Review procedure" (:title join)))
    (is (= "procedure" (get-in join [:attributes "workflow/role"])))
    (is (= "procedure" (get-in join [:attributes "workflow/procedure"])))
    (is (= "reviewer" (get-in join [:attributes :owner])))
    (is (= "high" (get-in join [:attributes :priority])))))

(workflow/defworkflow toastie-quality-workflow
  "Check toastie quality."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Toastie quality check"
   (workflow/step :inspect "Check toastie melt and crunch" :self)))

(workflow/defworkflow toastie-serve-workflow
  "Serve a toastie."
  {:entrypoints #{:continue}}
  (workflow/workflow
   (fn [{:keys [filling]}] (str "Serve " filling " toastie"))
   (workflow/step :plate (fn [{:keys [filling]}] (str "Plate " filling " toastie")) :self)))

(deftest workflow-spool-runtime-drives-toastie-demo
  (with-runtime
    (fn [_rt _]
      (let [toastie (workflow/workflow
                     (fn [{:keys [filling]}] (str "Make " filling " toastie"))
                     (workflow/step :butter-bread "Butter bread" :self)
                     (workflow/call :quality #'toastie-quality-workflow {}
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

(defn- action-binding
  "Return the binding for `action-ref`, failing loudly (TEN-003) on an unbound
  action or a key outside the binding vocabulary — a typo in user bindings must
  not yield a silently bare step."
  [bindings action-ref]
  (let [bindings (or bindings github-pr-bindings)
        bound (or (get bindings action-ref)
                  (throw (ex-info "No binding for workflow action"
                                  {:action-ref action-ref :bound (vec (keys bindings))})))]
    (when-let [unknown (seq (remove binding-attr-keys (keys bound)))]
      (throw (ex-info "Unknown binding keys"
                      {:action-ref action-ref :unknown (vec unknown)
                       :allowed (vec (keys binding-attr-keys))})))
    bound))

(defn- bound
  "Return `action-ref`'s step attributes: its semantic name, plus one render fn
  per binding field.

  The attribute keys are fixed by the vocabulary and the values arrive from the
  `:bindings` param at render time, which is what lets one static definition
  serve every forge — nothing about the binding set is decided when the
  definition is written."
  [action-ref]
  (into {"workflow/action-ref" (name action-ref)}
        (map (fn [[field attr]]
               [attr (fn [{:keys [bindings]}]
                       (get (action-binding bindings action-ref) field))]))
        binding-attr-keys))

(workflow/defworkflow pr-ci-round
  "Wait for CI, then judge the result."
  {:entrypoints #{:continue :call} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "CI round for " feature))
   (workflow/gate :ci-wait (fn [{:keys [feature]}] (str "Wait for CI on " feature)) :ci
                  :attributes (bound :pr.ci.wait))
   (workflow/checkpoint :ci-verdict "Judge CI result"
                        :depends-on [:ci-wait]
                        :kind :agent
                        :choices [{:key :green
                                   :label "CI green"
                                   :description "All checks passed; hand off to review."
                                   :next 'skein.spools.workflow-test/pr-review-round}
                                  {:key :red
                                   :label "CI red"
                                   :description "Checks failed; run the fix-CI loop."
                                   :next 'skein.spools.workflow-test/pr-fix-ci}])))

(workflow/defworkflow pr-fix-ci
  "Diagnose and push a CI fix, then re-run the CI round."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Fix CI for " feature))
   (workflow/step :diagnose "Diagnose CI failure" :self
                  :attributes (bound :pr.ci.fix))
   (workflow/step :push-fix "Push CI fix" :self :depends-on [:diagnose])
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:push-fix])))

(workflow/defworkflow pr-review-round
  "Wait for reviewer feedback, then judge the review outcome."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Review round for " feature))
   (workflow/gate :review-wait
                  (fn [{:keys [feature]}] (str "Wait for reviewer feedback on " feature))
                  :human
                  :attributes (bound :pr.review.wait))
   (workflow/checkpoint :review-verdict "Judge review outcome"
                        :depends-on [:review-wait]
                        :kind :agent
                        :choices [{:key :approved
                                   :label "Approved"
                                   :description "All green and approved; merge."
                                   :next 'skein.spools.workflow-test/pr-merge}
                                  {:key :changes-requested
                                   :label "Changes requested"
                                   :description "Address comments, push, and re-run CI."
                                   :next 'skein.spools.workflow-test/pr-fix-and-push}])))

(workflow/defworkflow pr-fix-and-push
  "Address review comments, then re-run the CI round."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Address review feedback for " feature))
   (workflow/step :address-comments "Address review comments" :self
                  :attributes (bound :pr.review.address))
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:address-comments])))

(workflow/defworkflow pr-merge
  "Merge the approved change."
  {:entrypoints #{:continue} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Merge " feature))
   (workflow/step :merge (fn [{:keys [feature]}] (str "Merge " feature)) :self
                  :attributes (bound :pr.merge))))

(workflow/defworkflow pr-dev
  "Implement a change, open it for review, and enter the CI round."
  {:entrypoints #{:start} :defaults {}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Pull request: " feature))
   (workflow/step :dev (fn [{:keys [feature]}] (str "Implement " feature)) :self)
   (workflow/step :open "Open the change for review" :self :depends-on [:dev]
                  :attributes (bound :pr.open))
   (workflow/call :ci-round #'pr-ci-round {} :depends-on [:open])))

(deftest workflow-models-pull-request-flow-without-conditional-edges
  (with-runtime
    (fn [_rt _]
      (workflow/start! "pr-flow" #'pr-dev {:feature "pr-42"}
                       {:family "pull-request"
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
      (workflow/start! "pr-forge-ref" #'pr-dev {:feature "ref-feat"}
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
      (workflow/start! "pr-forge-gl" #'pr-dev
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

(deftest workflow-complete-context-is-shallow-last-write-wins
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Context run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "context-run" definition
                                            {:owner "old"
                                             :nested {:keep true}}))]
        (workflow/complete! "context-run"
                            {:context {:owner "new"
                                       :nested {:replacement true}
                                       :result :passed}})
        (is (= "closed" (:state (repl/strand (:id step)))))
        (is (= {:owner "new"
                :nested {:replacement true}
                :result "passed"}
               (get-in (workflow/current-root "context-run")
                       [:attributes :workflow/context]))
            "last write wins shallowly, including replacing a nested value")))))

(defn reject-complete-batch-hook [ctx]
  (throw (ex-info "complete batch rejected" {:code "policy/rejected" :ctx ctx})))

(deftest workflow-complete-context-and-step-close-rollback-together
  (with-runtime
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Rejected context run"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "rejected-context-run" definition
                                            {:owner "before"}))
            root-id (:id (workflow/current-root "rejected-context-run"))]
        (hooks/register-hook! rt :reject-complete #{:batch/apply-before-commit}
                              'skein.spools.workflow-test/reject-complete-batch-hook {})
        (let [thrown (try
                       (workflow/complete! "rejected-context-run"
                                           {:context {:owner "after"}})
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (= "Lifecycle hook failed" (ex-message thrown)))
          (is (= "policy/rejected" (:hook/cause-code (ex-data thrown)))))
        (is (= "active" (:state (repl/strand (:id step)))))
        (is (= {:owner "before"}
               (get-in (repl/strand root-id) [:attributes :workflow/context])))))))

(deftest workflow-complete-requires-keyword-context-keys
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Context keys"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self))
            [step] (:ready (workflow/start! "context-keys-run" definition {}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid workflow complete context"
                              (workflow/complete! "context-keys-run"
                                                  {:context {"owner" "agent"}})))
        (is (= "active" (:state (repl/strand (:id step)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cannot be defaulted into workflow/context"
                              (workflow/complete! "context-keys-run"
                                                  {:context {:opaque (Object.)}})))
        (is (= "active" (:state (repl/strand (:id step)))))
        (is (not (s/valid? :skein.spools.workflow.request/context
                           {:opaque (Object.)})))))))

(deftest workflow-complete-refuses-malformed-persisted-context-before-mutating
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Malformed context"
                        (workflow/step :a "Do A" :self)
                        (workflow/step :b "Do B" :self :depends-on [:a]))
            [step] (:ready (workflow/start! "malformed-context-run" definition {}))
            root-id (:id (workflow/current-root "malformed-context-run"))]
        (repl/update! root-id {:attributes {"workflow/context" "not-a-map"}})
        (let [thrown (try
                       (workflow/complete! "malformed-context-run"
                                           {:context {:owner "agent"}})
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/context-invalid (:reason (ex-data thrown))))
          (is (= "malformed-context-run" (:run-id (ex-data thrown))))
          (is (= root-id (:root (ex-data thrown))))
          (is (= "not-a-map" (:context (ex-data thrown)))))
        (is (= "active" (:state (repl/strand (:id step)))))
        (is (= "not-a-map"
               (get-in (repl/strand root-id) [:attributes :workflow/context])))))))

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

(workflow/defworkflow empty-continuation-workflow
  "Finish a routed run without new work."
  {:entrypoints #{:continue}}
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

(workflow/defworkflow routed-continuation-workflow
  "Continue a routed run."
  {:entrypoints #{:continue}}
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

(defn- loopy-body
  "The shared body of the loopy stage and its revision round.

  Both are the same steps under different defaults, which is what a revision
  round IS now that a definition carries its own defaults."
  []
  (workflow/workflow
   "Loopy"
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/step :work "Do work" :self :depends-on [:orient])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:work]
                        :kind :agent
                        :choices [{:key :approved :label "Approve"}
                                  {:key :revise
                                   :label "Revise"
                                   :next 'skein.spools.workflow-test/loopy-revision}])))

(workflow/defworkflow loopy
  "A stage whose sign-off can route into a revision round."
  {:entrypoints #{:start :continue} :defaults {}}
  (loopy-body))

(workflow/defworkflow loopy-revision
  "The revision round of `loopy`: the same steps with :revision already true."
  {:entrypoints #{:continue} :defaults {:revision true}}
  (loopy-body))

(deftest workflow-start-accepts-var-and-defaults-durable-context
  (with-runtime
    (fn [_rt _]
      (workflow/start! "var-start" #'loopy {:revision :yes})
      (let [root (workflow/current-root "var-start")]
        (is (= "skein.spools.workflow-test/loopy"
               (get-in root [:attributes :workflow/definition])))
        (is (= {:revision "yes"}
               (get-in root [:attributes :workflow/context]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pass :context explicitly"
                            (workflow/start! "bad-context" #'loopy {:f identity})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "nan-context" #'loopy {:n ##NaN})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite numbers are not JSON-safe"
                            (workflow/start! "inf-context" #'loopy {:n ##Inf}))))))

(deftest workflow-start-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-test 'skein.spools.workflow-test/loopy)
      (workflow/start! "keyword-start" :loopy-test {})
      (is (= "skein.spools.workflow-test/loopy"
             (get-in (workflow/current-root "keyword-start") [:attributes :workflow/definition])))
      (is (= "Orient" (:title (workflow/ready-step "keyword-start")))))))

(deftest workflow-describe-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-describe 'skein.spools.workflow-test/loopy)
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
                   (:ready (workflow/start! "loopy" #'loopy {})))))
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
      (workflow/start! "loopy-fail" #'loopy {})
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
                          (workflow/workflow "W" {:param {:x true}} (workflow/step :a "A" :self))))
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

(workflow/defworkflow cyclic-procedure
  "A procedure that calls itself, to prove expansion refuses a cycle."
  {:entrypoints #{:call}}
  (workflow/workflow "Cyclic procedure"
                     (workflow/step :work "Do work" :self)
                     ;; recursive edge by symbol while the entry call passes the
                     ;; Var: both must canonicalize to one identity
                     (workflow/call :again 'skein.spools.workflow-test/cyclic-procedure {}
                                    :depends-on [:work])))

(deftest workflow-compile-fails-loudly-on-cyclic-procedure-call
  ;; conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate — compile must throw, not overflow
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Workflow procedure call is cyclic"
                        (workflow/compile
                         (workflow/workflow "Cyclic root"
                                            (workflow/call :outer #'cyclic-procedure {}))))))

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
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad steps" :steps {:not "a vector"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad attributes" :attributes [] :steps []})))
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

(deftest workflow-choice-input-rejects-the-removed-vector-declaration
  ;; A choice input names one whole-map spec. The per-key vector is gone, so the
  ;; builder refuses it through the public ::choices grammar with explain data
  ;; naming both spec-first shapes it would have accepted (TEN-003).
  (let [data (ex-data (try
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input [{:key :reason :required true}]}])
                        (catch clojure.lang.ExceptionInfo e e)))
        paths (set (map :path (::s/problems (:explain data))))]
    (is (= :skein.spools.workflow/choices (::s/spec (:explain data))))
    (is (contains? paths [:declaration :input :spec])
        "the qualified-keyword spec form is offered")
    (is (contains? paths [:declaration :input :declaration])
        "the {:spec :doc} declaration form is offered")))

(deftest workflow-choice-input-accepts-both-spec-first-shapes
  (doseq [input [::approval-input {:spec ::approval-input :doc "Why"}]]
    (is (some? (workflow/checkpoint :gate "Decide"
                                    :choices [{:key :approve :input input}]))
        (pr-str input))))

(deftest workflow-choice-input-rejects-a-spec-declaration-with-unknown-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input {:spec ::approval-input
                                                                :doccc "typo"}}]))))

(deftest workflow-choice-input-rejects-an-unqualified-spec-name
  ;; a spec identity must be resolvable, so a bare keyword is not one
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort :input :reason}]))))

(workflow/defworkflow join-inner-workflow
  "Perform the inner joined procedure."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Inner"
   (workflow/step :do-inner "Do inner work" :self)))

(deftest workflow-procedure-join-auto-closes-and-never-surfaces-as-ready
  (with-runtime
    (fn [_rt _]
      (let [definition (workflow/workflow
                        "Join demo"
                        (workflow/step :prep "Prep" :self)
                        (workflow/call :inner #'join-inner-workflow {} :depends-on [:prep])
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
    (fn [rt _]
      (let [definition (workflow/workflow
                        "Advance demo"
                        (workflow/step :work "Do work" :self)
                        (workflow/checkpoint :sign "Sign off"
                                             :depends-on [:work]
                                             :kind :agent
                                             :choices [{:key :approved :label "Approve"}]))]
        (workflow/start! "advance-run" definition {})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow advance opts"
                              (workflow/advance! "advance-run" {:step 42})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                              (workflow/advance! "advance-run" {:bogus true})))
        (is (= :workflow/advance-input-without-checkpoint
               (failure-reason #(workflow/advance! "advance-run"
                                                   {:input {:verdict "pass"}}))))
        (let [step-id (:id (workflow/ready-step "advance-run"))]
          (weaver/update! rt step-id {:attributes {"workflow/role" "improvised"}})
          (is (= :workflow/ready-next-incompatible
                 (failure-reason #(workflow/advance! "advance-run"
                                                     {:step step-id}))))
          (is (= "active" (:state (weaver/show rt step-id))))
          (weaver/update! rt step-id {:attributes {"workflow/role" "step"}}))
        ;; a ready step advanced with a :choice fails loudly and mutates nothing
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not supply a :choice"
                              (workflow/advance! "advance-run" {:choice :approved})))
        ;; advance! completes the ready step, returning the D1.1 result shape
        (let [after (workflow/advance! "advance-run")]
          (is (= ["Sign off"] (mapv :title (:ready after))))
          (is (false? (:done after))))
        ;; a ready checkpoint advanced without a :choice fails loudly
        (let [thrown (try (workflow/advance! "advance-run")
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (re-find #"requires a :choice" (ex-message thrown)))
          (is (= ["approved"] (:choices (ex-data thrown)))))
        (is (= :workflow/advance-attributes-on-checkpoint
               (failure-reason
                #(workflow/advance! "advance-run"
                                    {:choice :approved
                                     :attributes {"verdict" "pass"}}))))
        ;; advance! dispatches the checkpoint choice and closes the run
        (is (= {:ready [] :done true} (workflow/advance! "advance-run" {:choice :approved})))
        (is (workflow/done? "advance-run"))
        (testing "a non-inferable sibling does not make one advanceable item ambiguous"
          (workflow/start! "advance-with-gate"
                           (workflow/workflow
                            "Advance beside gate"
                            (workflow/step :work "Do work" :self)
                            (workflow/gate :wait "Wait" :external))
                           {})
          (is (= ["Wait"]
                 (mapv :title (:ready (workflow/advance! "advance-with-gate")))))
          (let [thrown (try (workflow/advance! "advance-with-gate" {:by "ci-bot"})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/ready-next-absent (:reason (ex-data thrown))))
            (is (= ["Wait"] (mapv :title (:ready (ex-data thrown)))))
            (is (re-find #"--step" (:guidance (ex-data thrown))))))))))

(defn- registry-router-stage [{:keys [target]}]
  (workflow/workflow
   "Registry router"
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(workflow/defworkflow registry-second-stage
  "Provide the second registry stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Registry second" (workflow/step :do-second "Do second" :self)))

(workflow/defworkflow registry-alt-second-stage
  "Provide the alternate second registry stage."
  {:entrypoints #{:continue}}
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

(workflow/defworkflow revise-stage-workflow
  "A stage whose sign-off can re-pour itself with :revision true."
  {:entrypoints #{:start :continue} :defaults {}}
  (workflow/workflow
   "Revise stage"
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:orient]
                        :kind :agent
                        :choices [{:key :revise :label "Revise" :revise {:params {:revision true}}}
                                  {:key :approved :label "Approve" :next :wt-downstream}])))

(workflow/defworkflow downstream-stage-workflow
  "Provide the downstream stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Downstream stage" (workflow/step :do-downstream "Do downstream" :self)))

(deftest workflow-routing-refuses-malformed-persisted-context-before-mutating
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-second 'skein.spools.workflow-test/registry-second-stage)
      (workflow/register-workflow! :wt-downstream
                                   'skein.spools.workflow-test/downstream-stage-workflow)
      (doseq [[run-id definition choice]
              [["malformed-next" (registry-router-stage {:target :wt-second}) :advance]
               ["malformed-revise" #'revise-stage-workflow :revise]]]
        (workflow/start! run-id definition {} {:context {}})
        (when (= choice :revise)
          (workflow/complete! run-id))
        (let [root-id (:id (workflow/current-root run-id))
              checkpoint-id (:id (workflow/ready-step run-id))]
          (repl/update! root-id {:attributes {"workflow/context" "not-a-map"}})
          (let [thrown (try
                         (workflow/choose! run-id choice)
                         (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/context-invalid (:reason (ex-data thrown))))
            (is (= run-id (:run-id (ex-data thrown))))
            (is (= root-id (:root (ex-data thrown))))
            (is (= "not-a-map" (:context (ex-data thrown)))))
          (is (= "active" (:state (repl/strand checkpoint-id))))
          (is (= "not-a-map"
                 (get-in (repl/strand root-id)
                         [:attributes :workflow/context]))))))))

(deftest workflow-revise-repours-definition-skipping-condition-gated-steps
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-downstream 'skein.spools.workflow-test/downstream-stage-workflow)
      (workflow/start! "revise-run" #'revise-stage-workflow {} {:context {}})
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
      ;; started from a raw value, so the root records no workflow/definition
      (workflow/start! "revise-nodef" @#'revise-stage-workflow {})
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

(workflow/defworkflow introspect-stage-b
  "The stage an approved introspection round hands off to."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Introspect stage B"
   (workflow/step :finish "Finish B" :self)))

(s/def ::reason string?)
(s/def ::revise-reason-input (s/keys :req-un [::reason]))
(s/def ::introspect-params (s/keys :req-un [::feature]))

(workflow/defworkflow introspect-stage-a
  "A stage carrying a conditioned step, a routed choice, and a revision round."
  {:entrypoints #{:start :continue}
   :param-spec ::introspect-params
   :defaults {}}
  (workflow/workflow
   "Introspect stage A"
   (workflow/step :draft (fn [{:keys [feature]}] (str "Draft " feature)) :self
                  :condition [:!= :revision true])
   (workflow/step :refine "Refine draft" :self :depends-on [:draft])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:refine]
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :description "Ship it."
                                   :next 'skein.spools.workflow-test/introspect-stage-b}
                                  {:key :revise
                                   :label "Revise"
                                   :description "Send it back."
                                   :revise {:params {:revision true}}
                                   :input {:spec ::revise-reason-input
                                           :doc "Why revise"}}])))

(deftest workflow-describe-projects-choices-input-and-condition-filtering
  ;; describe is a compile-time projection: no strands are written, so it needs no
  ;; runtime. On the base pass the conditioned :draft is present with its
  ;; :condition; the checkpoint's choices carry declared :input and routing.
  (let [desc (workflow/describe #'introspect-stage-a {:feature "widgets"})
        by-id (into {} (map (juxt :id identity)) (:steps desc))
        signoff (:signoff by-id)
        choices (into {} (map (juxt :key identity)) (:choices signoff))]
    (is (= "Introspect stage A" (:name desc)))
    (is (= #{:draft :refine :signoff} (set (keys by-id))))
    (is (= "Draft widgets" (:title (:draft by-id))))
    (is (= [:!= :revision true] (:condition (:draft by-id))))
    (is (= "checkpoint" (:role signoff)))
    (is (= "step" (:role (:refine by-id))))
    (is (= "skein.spools.workflow-test/introspect-stage-b"
           (:next (get choices "approve"))))
    (is (= {:revision true} (:revise (get choices "revise"))))
    (is (= {"spec" "skein.spools.workflow-test/revise-reason-input"
            "doc" "Why revise"}
           (select-keys (:input-spec (get choices "revise")) ["spec" "doc"]))))
  ;; a revision round condition-excludes :draft; its dependent :refine splices to
  ;; become the entry step, so the description matches what would pour
  (is (= #{:refine :signoff}
         (set (map :id (:steps (workflow/describe #'introspect-stage-a
                                                  {:feature "widgets" :revision true})))))))

(deftest workflow-describe-fails-loudly-on-params-its-spec-rejects
  ;; A missing required param is now the definition's own :param-spec refusing
  ;; the whole map, so describe fails with the spec's identity and explanation
  ;; rather than a hand-rolled required-key check.
  (let [data (try (workflow/describe #'introspect-stage-a {})
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :workflow/params-invalid (:reason data)))
    (is (= ::introspect-params (:spec data)))))

(deftest workflow-run-history-projects-ordered-molecules-and-events
  (with-runtime
    (fn [_rt _]
      (workflow/start! "hist" #'introspect-stage-a {:feature "widgets"}
                       {:context {:feature "widgets"}})
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
      (workflow/start! "arch" #'introspect-stage-a {:feature "widgets"}
                       {:context {:feature "widgets"}})
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

(deftest workflow-owner-refresh-removes-omitted-definitions-and-executors
  ;; DW2 / kxhd4 R4 per-domain deletion completeness: an owner-complete
  ;; replacement removes any route or executor the new partition omits, and
  ;; removing the owner clears the rest — no global reload.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (let [handle (wf-registry/registry-handle rt)
            spools-definitions (fn [entries]
                                 (registry/replace-owner!
                                  handle workflow/definition-kind :spools/pkg
                                  {:layer :spools :entries entries :overrides #{}}))
            spools-executors (fn [entries]
                               (registry/replace-owner!
                                handle workflow/executor-kind :spools/pkg
                                {:layer :spools :entries entries :overrides #{}}))]
        (spools-definitions {:route-a 'skein.spools.workflow-test/registry-second-stage
                             :route-b 'skein.spools.workflow-test/registry-alt-second-stage})
        (spools-executors {"exec-a" 'skein.spools.workflow-test/exec-detail-a
                           "exec-b" 'skein.spools.workflow-test/exec-detail-b})
        (is (= #{:route-a :route-b} (set (keys (workflow/workflows)))))
        (is (= #{"exec-a" "exec-b"} (set (keys (wf-registry/executor-map rt)))))
        ;; a complete replacement omitting one of each removes only those
        (spools-definitions {:route-a 'skein.spools.workflow-test/registry-second-stage})
        (spools-executors {"exec-a" 'skein.spools.workflow-test/exec-detail-a})
        (is (= #{:route-a} (set (keys (workflow/workflows)))) "omitted route removed")
        (is (= #{"exec-a"} (set (keys (wf-registry/executor-map rt)))) "omitted executor removed")
        ;; removing the owner clears the rest
        (registry/remove-owner! handle workflow/definition-kind :spools/pkg)
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
        (registry/replace-owner! handle workflow/definition-kind :spools/pkg
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
        (registry/remove-owner! handle workflow/definition-kind :skein.owner/repl)
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

(deftest a-registered-symbol-must-resolve-to-a-definition-map
  ;; Constructors are gone: a symbol that resolves to a function, or to any
  ;; other value, is refused at registration — before it can reach a run.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (doseq [[label sym] [["a function" 'skein.spools.workflow-test/exploding-constructor]
                           ["a non-workflow value" 'skein.spools.workflow-test/malformed-constructor]]]
        (let [thrown (try (workflow/register-workflow! :wt-boom sym)
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/definition-invalid (:reason data)) label)
          (is (= sym (:definition data)) label)
          (is (string? (:resolved-class data))
              (str label ": the failure names what it did resolve to"))))
      (is (empty? (workflow/workflows))
          "a refused registration leaves the live registry untouched")
      (is (nil? (workflow/current-root "boom-run")) "nothing poured"))))

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
        (is (= 'test.module.wf-beta/beta (:definition (workflow/resolve-workflow :beta))))
        (is (map? (:value (workflow/resolve-workflow :beta)))
            "a registered name resolves to the definition map itself")))))

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

(def ^:private static-input-definition
  (workflow/workflow
   "Static input"
   {:entrypoints #{:start}}
   (workflow/checkpoint :approve-step "Approve" :kind :agent
                        :choices [{:key :approve
                                   :input ::approval-input}])))

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

(deftest static-choice-input-requires-its-whole-map-spec
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/start! "static-input" static-input-definition {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Value does not satisfy the named spec"
                            (workflow/choose! "static-input" :approve {})))
      (is (:done (workflow/choose! "static-input" :approve {:approval-note "fine"}))))))

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
;; --- runtime-selected returning composition (PROP-Dfr-001) ------------------

(s/def ::feature string?)
(s/def ::devflow-params (s/keys :req-un [::feature ::reviewer]))

(workflow/defworkflow defer-devflow
  "Plan and build a feature."
  {:entrypoints #{:start :call}
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
  {:entrypoints #{:start :call}}
  (workflow/workflow
   "Spike"
   (workflow/step :probe "Probe the unknown" :self)))

(workflow/defworkflow defer-continue-only
  "A route-only routine that no defer may select."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Continue only"
   (workflow/step :inner "Inner" :self)))

(s/def ::defer-scope string?)
(s/def ::defer-target-params (s/keys :req-un [::defer-scope]))

(workflow/defworkflow defer-two-step-target
  "A call-capable routine with an entry and an exit."
  {:entrypoints #{:call}
   :param-spec ::defer-target-params
   :defaults {:defer-scope "default"}}
  (workflow/workflow
   (fn [{:keys [defer-scope]}] (str "Deliver " defer-scope))
   (workflow/step :plan (fn [{:keys [defer-scope]}] (str "Plan " defer-scope)) :self)
   (workflow/step :ship "Ship it" :self :depends-on [:plan])))

(workflow/defworkflow defer-fanout-target
  "A call-capable routine with a fan-out and colliding step ids."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Fanout"
   (workflow/step :a "Target a" :self)
   (workflow/step :left "Target left" :self :depends-on [:a])
   (workflow/step :right "Target right" :self :depends-on [:a])
   (workflow/step :c "Target c" :self :depends-on [:left :right])))

(workflow/defworkflow defer-empty-target
  "A call-capable routine that materializes no steps."
  {:entrypoints #{:call}}
  (workflow/workflow "Empty"))

(def ^:private card-template
  "The unregistered template a spool publishes: it names its selection point
  without naming anyone else's workflow, and carries on afterwards."
  (workflow/workflow
   "Track a card"
   {:entrypoints #{:start :call}}
   (workflow/step :prepare "Prepare the card" :self)
   (workflow/defer :perform-work "Choose how this work will be performed"
                   :depends-on [:prepare])
   (workflow/step :record "Record the result" :self :depends-on [:perform-work])))

(defn- bound-card
  "Return the template bound to `targets`, as user code with both spools would."
  [targets]
  (workflow/bind-defers card-template {:perform-work targets}))

(def ^:private tracked-card (bound-card #{:wt-devflow :wt-spike}))
(def ^:private continue-only-bound-card (bound-card #{:wt-continueonly}))

(def ^:private defer-caller
  (workflow/workflow
   "Caller"
   {:entrypoints #{:start}}
   (workflow/call :sub :wt-card {})))

(defn- defer-sandwich
  "step a -> defer -> step c, the shape the whole feature exists for."
  [targets]
  (workflow/bind-defers
   (workflow/workflow
    "Sandwich"
    (workflow/step :a "Step a" :self)
    (workflow/defer :perform-work "Choose work" :depends-on [:a])
    (workflow/step :c "Step c" :self :depends-on [:perform-work]))
   {:perform-work targets}))

(defn- final-defer-workflow
  "A defer as the last declared step, beside parallel sibling work."
  [targets]
  (workflow/bind-defers
   (workflow/workflow
    "Final defer"
    (workflow/step :sibling "Sibling work" :self)
    (workflow/defer :perform-work "Choose work"))
   {:perform-work targets}))

(defn- register-defer-targets! []
  (workflow/register-workflow! :wt-devflow 'skein.spools.workflow-test/defer-devflow)
  (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-spike))

(defn- definition-symbol [v]
  (let [{ns-sym :ns name-sym :name} (meta v)]
    (symbol (str (ns-name ns-sym)) (str name-sym))))

(defn- start-at-defer!
  "Start `run-id` on the bound card and complete its first step, leaving the
  defer as the whole ready frontier."
  [run-id]
  (workflow/start! run-id tracked-card {})
  (workflow/complete! run-id)
  (workflow/ready-step run-id))

(defn- ready-titles [run-id]
  (mapv :title (workflow/ready run-id)))

(defn- complete-ready! [run-id title]
  (let [step (first (filter #(= title (:title %)) (workflow/ready run-id)))]
    (workflow/complete! run-id {:step (:id step)})))

(deftest defer-declares-a-runtime-selected-returning-point
  ;; PROP-Dfr-001.S1: a defer is ordinary returning composition, so ordinary
  ;; topology may depend on it. What it may not be is conditional or multiplied.
  (let [definition (workflow/workflow
                    "Defer"
                    (workflow/defer :perform-work "Choose work")
                    (workflow/step :record "Record outcome" :self
                                   :depends-on [:perform-work]))]
    (is (= {:id :perform-work
            :title "Choose work"
            :attributes {"workflow/role" "defer"
                         "workflow/defer" "perform-work"}}
           (first (:steps definition))))
    (is (s/valid? ::workflow/defer-declaration (first (:steps definition))))
    (is (not (s/valid? ::workflow/defer-declaration {:id :bad :title "Bad"}))))
  (testing "every way of continuing past a defer now builds"
    (doseq [[label successor]
            [[:successor (workflow/step :after "After" :self :depends-on [:perform-work])]
             [:condition (workflow/step :maybe "Maybe" :self
                                        :depends-on [:perform-work] :condition :flag)]
             [:loop (workflow/step :each "Each" :self
                                   :depends-on [:perform-work] :loop {:count 2})]
             [:call (workflow/call :sub :wt-spike {} :depends-on [:perform-work])]
             [:checkpoint (workflow/checkpoint :pick "Pick" :depends-on [:perform-work]
                                               :choices [:a])]]]
      (testing (name label)
        (is (= [:perform-work (:id successor)]
               (mapv :id (:steps (workflow/workflow
                                  "Fine"
                                  (workflow/defer :perform-work "Choose")
                                  successor))))))))
  (testing "the point itself is unconditional: the builder rejects the opts outright"
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
        (is (= key (:key (ex-data thrown))))))))

(deftest bind-defers-owns-the-user-authority-boundary
  (testing "targets materialize in registered-name order whatever the author wrote"
    (is (= ["wt-devflow" "wt-spike"]
           (get-in (second (:steps (bound-card #{:wt-spike :wt-devflow})))
                   [:attributes "workflow/defer-workflows"]))))
  (testing "binding a name the definition never declared is a defect, not a new point"
    (let [thrown (try (workflow/bind-defers card-template {:nope #{:wt-spike}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-unknown (:reason (ex-data thrown))))
      (is (= [:perform-work] (:declared (ex-data thrown))))))
  (testing "an empty target set is a defer no worker can fill"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow defer bindings"
                          (workflow/bind-defers card-template {:perform-work #{}})))
    (is (s/valid? ::workflow/defer-bindings {:perform-work #{:wt-devflow}}))
    (is (not (s/valid? ::workflow/defer-bindings {:perform-work #{}}))))
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

(deftest defer-targets-require-call-and-are-validated-against-the-complete-candidate
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (testing "a target that is not registered at all"
        (let [thrown (try (workflow/register-workflow!
                           :wt-card 'skein.spools.workflow-test/tracked-card)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-unregistered (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown)))
              "filling a defer runs its target as an inline procedure")))
      (register-defer-targets!)
      (is (= :wt-card (workflow/register-workflow!
                       :wt-card 'skein.spools.workflow-test/tracked-card)))
      (testing "a target that declares only :continue is not selectable"
        (workflow/register-workflow! :wt-continueonly
                                     'skein.spools.workflow-test/defer-continue-only)
        (let [thrown (try (workflow/register-workflow!
                           :wt-bad-card
                           (definition-symbol #'continue-only-bound-card))
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-entrypoint-unsupported (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown))))
          (is (= :defer (:declaring-kind (ex-data thrown))))))
      (testing "a target that is not a definition map at all"
        (let [thrown (try (workflow/register-workflow!
                           :wt-legacy 'skein.spools.workflow-test/exploding-constructor)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/definition-invalid (:reason (ex-data thrown))))
          (is (= 'skein.spools.workflow-test/exploding-constructor
                 (:definition (ex-data thrown)))))))))

(deftest a-call-procedure-may-declare-a-defer
  ;; PROP-Dfr-001.S1 removes the old restriction: a procedure join returns, and
  ;; so does a defer, so the two compose instead of contradicting.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (workflow/register-workflow! :wt-card 'skein.spools.workflow-test/tracked-card)
      (is (= :wt-caller (workflow/register-workflow!
                         :wt-caller 'skein.spools.workflow-test/defer-caller)))
      (workflow/start! "nested-call" #'defer-caller {})
      (workflow/complete! "nested-call")
      (let [defer (workflow/ready-step "nested-call")]
        (is (= "defer" (:role defer)))
        (workflow/defer! "nested-call" :wt-spike))
      (is (= ["Probe the unknown"] (ready-titles "nested-call")))
      (workflow/complete! "nested-call")
      (is (= ["Record the result"] (ready-titles "nested-call"))
          "the enclosing procedure resumes past the defer it contained")
      (is (true? (:done (workflow/complete! "nested-call")))))))

(deftest defer-returns-to-the-declaring-workflow
  ;; PROP-Dfr-001.G1/S3. This is the feature: a routine chosen at run time, run
  ;; inside the caller's own molecule, with the caller's next step waiting on it.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (let [root-id (do (workflow/start! "sandwich" (defer-sandwich #{:wt-two-step}) {})
                        (:id (workflow/current-root "sandwich")))]
        (is (= ["Step a"] (ready-titles "sandwich")))
        (let [after-a (workflow/complete! "sandwich")
              pending (first (:ready after-a))]
          (is (= "defer" (:role pending)))
          (is (= ["wt-two-step"] (:workflows pending)))
          (let [filled (workflow/defer! "sandwich" :wt-two-step
                                        {:defer-scope "the thing"} {:by "worker-1"})
                join (repl/strand (:id pending))]
            (is (= ["Plan the thing"] (mapv :title (:ready filled)))
                "the expansion is ready; step c is not")
            (is (= "procedure" (get-in join [:attributes :workflow/role]))
                "CC3: the defer became an ordinary procedure join in the same batch")
            (is (= "active" (:state join)))
            (testing "CC4: the expansion hangs under the caller's own root, not a new one"
              (is (= root-id (:id (workflow/current-root "sandwich"))))
              (is (some #(= "Plan the thing" (:title %))
                        (:strands (graph/subgraph rt [root-id])))))
            (testing "CC6: the fill record"
              (let [attrs (:attributes join)]
                (is (= "wt-two-step" (:workflow/deferred-workflow attrs)))
                (is (= "skein.spools.workflow-test/defer-two-step-target"
                       (:workflow/deferred-definition attrs)))
                (is (re-matches #"[0-9a-f]{16}" (:workflow/deferred-fingerprint attrs)))
                (is (= {:defer-scope "the thing"} (:workflow/deferred-params attrs)))
                (is (= "worker-1" (:workflow/deferred-by attrs)))
                (is (nil? (:workflow/outcome attrs))
                    "a filled defer is procedure bookkeeping, not an outcome")))
            (workflow/complete! "sandwich")
            (let [after-ship (workflow/complete! "sandwich")]
              (is (= ["Step c"] (mapv :title (:ready after-ship)))
                  "step c becomes ready only once the expansion's exits close")
              (is (= "closed" (:state (repl/strand (:id pending))))
                  "the join auto-closed through the existing cascade"))
            (is (true? (:done (workflow/complete! "sandwich"))))))))))

(deftest a-final-defer-returns-without-abandoning-parallel-siblings
  ;; PROP-Dfr-001.S4/G5: tail position is not an ownership transfer. The root
  ;; stays, its context stays, and the run finishes only when the siblings do.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/start! "final" (final-defer-workflow #{:wt-two-step}) {:label "caller"})
      (let [root-id (:id (workflow/current-root "final"))
            pending (first (filter #(= "defer" (:role %)) (workflow/ready "final")))
            ;; a sibling step is ready beside the defer, so trusted Clojure names
            ;; the strand it means rather than inferring the sole ready item
            filled (workflow/defer! "final" :wt-two-step {} {:step (:id pending)})]
        (is (= #{"Sibling work" "Plan default"} (set (mapv :title (:ready filled)))))
        (is (false? (:done filled)))
        (complete-ready! "final" "Plan default")
        (let [after-ship (complete-ready! "final" "Ship it")]
          (is (= "closed" (:state (repl/strand (:id pending))))
              "the join closes normally after its selected routine exits")
          (is (false? (:done after-ship))
              "the parallel sibling was never abandoned by filling a final defer")
          (is (= ["Sibling work"] (mapv :title (:ready after-ship)))))
        (let [after-sibling (complete-ready! "final" "Sibling work")
              molecules (workflow/run-history "final")]
          (is (true? (:done after-sibling)))
          (is (= [root-id] (mapv (comp :id :root) molecules))
              "one molecule throughout: no root transfer"))
        (is (= {:label "caller"}
               (get-in (repl/strand root-id) [:attributes :workflow/context]))
            "the declaring root's context is never replaced by the target's")))))

(deftest defer-isolates-the-target-from-caller-params
  ;; DELTA-Dfr-001.CC3: the publishing spool never saw the filling spool, so its
  ;; context must not reach the target.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/start! "isolated" (defer-sandwich #{:wt-two-step})
                       {:defer-scope "caller value"})
      (workflow/complete! "isolated")
      (is (= ["Plan default"] (mapv :title (:ready (workflow/defer! "isolated" :wt-two-step))))
          "an omitted param falls to the target's own default, never the caller's key")
      (testing "omitted params and an explicit empty map are the same request"
        (workflow/start! "isolated-2" (defer-sandwich #{:wt-two-step}) {})
        (workflow/complete! "isolated-2")
        (workflow/defer! "isolated-2" :wt-two-step)
        (is (= (ready-titles "isolated") (ready-titles "isolated-2")))))))

(deftest defer-resolves-its-target-live-and-fails-with-the-defer-still-ready
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (testing "a name outside the poured allowlist is refused before resolution"
        (let [defer-id (:id (start-at-defer! "live-1"))
              thrown (try (workflow/defer! "live-1" :wt-elsewhere {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-target-not-allowed (:reason (ex-data thrown))))
          (is (= ["wt-devflow" "wt-spike"] (:allowed (ex-data thrown))))
          (is (= "active" (:state (repl/strand defer-id))))))
      (testing "a compatible repoint runs the replacement"
        (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-devflow)
        (workflow/defer! "live-1" :wt-spike {:feature "repointed"})
        (is (= ["Inspect repointed for agent"] (ready-titles "live-1")))
        (workflow/register-workflow! :wt-spike 'skein.spools.workflow-test/defer-spike))
      (testing "removal, a lost :call, and rejected params all leave the defer ready"
        (let [defer-id (:id (start-at-defer! "live-2"))
              check (fn [thrown reason]
                      (is (= reason (:reason (ex-data thrown))))
                      (is (= "active" (:state (repl/strand defer-id)))
                          "nothing closed, so the worker can retry")
                      (is (= "defer" (get-in (repl/strand defer-id)
                                             [:attributes :workflow/role]))
                          "and nothing was rewritten into a join"))]
          (check (try (workflow/defer! "live-2" :wt-devflow {:feature 42})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/params-invalid)
          (workflow/register-workflow! :wt-devflow
                                       'skein.spools.workflow-test/defer-continue-only)
          (check (try (workflow/defer! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/entrypoint-unsupported)
          (workflow/unregister-workflow! :wt-devflow)
          (check (try (workflow/defer! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/definition-unregistered))))))

(deftest defer-validates-its-request-shape-before-touching-the-run
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (let [defer-id (:id (start-at-defer! "req-1"))]
        (doseq [[label call] [[:blank-run-id #(workflow/defer! "" :wt-spike {})]
                              [:string-workflow #(workflow/defer! "req-1" "wt-spike" {})]
                              [:non-map-params #(workflow/defer! "req-1" :wt-spike [1 2])]
                              [:blank-by #(workflow/defer! "req-1" :wt-spike {} {:by ""})]]]
          (testing (name label)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Invalid workflow defer request"
                                  (call)))))
        (is (= "active" (:state (repl/strand defer-id))))))))

(deftest a-ready-defer-is-neither-completed-nor-advanced
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (let [defer-id (:id (start-at-defer! "role-1"))]
        (let [complete-error (try (workflow/complete! "role-1")
                                  (catch clojure.lang.ExceptionInfo e e))
              advance-error (try (workflow/advance! "role-1")
                                 (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/step-is-defer (:reason (ex-data complete-error))))
          (is (re-find #"defer!" (ex-message complete-error)))
          (is (= :workflow/ready-next-absent (:reason (ex-data advance-error))))
          (is (re-find #"workflow defer" (:guidance (ex-data advance-error))))
          (is (= [defer-id] (mapv :id (:ready (ex-data advance-error)))))
          (is (= "active" (:state (repl/strand defer-id)))))
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

(deftest a-pending-defer-blocks-done-and-never-cascades-shut
  ;; PROP-Dfr-001.S11: cascade-join-ids closes only procedure joins. If an
  ;; unfilled defer used that role, completing its sibling would close it over
  ;; an empty dependency set.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (let [result (workflow/start! "pending" (final-defer-workflow #{:wt-two-step}) {})
            defer (first (filter #(= "defer" (:role %)) (:ready result)))]
        (is (= "perform-work" (:defer defer)))
        (is (= ["wt-two-step"] (:workflows defer)))
        (is (false? (:done result)))
        (let [after-sibling (complete-ready! "pending" "Sibling work")]
          (is (= "active" (:state (repl/strand (:id defer)))))
          (is (false? (:done after-sibling))
              "an unfilled defer keeps the run unfinished"))
        (is (not-any? #(= (:id defer) (:id %))
                      (mapcat :events (workflow/run-history "pending")))
            "an unfilled defer emits no history event")))))

(deftest compile-stamps-each-defer-with-its-lexical-path
  (let [callee (workflow/bind-defers
                (workflow/workflow "C" (workflow/defer :pick "Pick"))
                {:pick #{:wt-two-step}})
        outer (workflow/workflow "Outer" (workflow/call :c callee {}))
        payload (workflow/compile outer {} {:definition 'skein.spools.workflow-test/outer})
        path (get-in (first (filter #(= "defer" (get-in % [:attributes "workflow/role"]))
                                    (:strands payload)))
                     [:attributes "workflow/defer-path"])]
    (is (= ["skein.spools.workflow-test/outer" nil] (mapv #(get % "definition") path))
        "the enclosing definition, then the procedure it fixed-called into")
    (is (every? #(re-matches #"[0-9a-f]{16}" (get % "fingerprint")) path))
    (is (apply not= (map #(get % "fingerprint") path))
        "each ancestor is digested as itself, not as the root over again")
    (let [anonymous (workflow/compile
                     (workflow/bind-defers
                      (workflow/workflow "Anonymous" (workflow/defer :pick "Pick"))
                      {:pick #{:wt-two-step}}))
          anonymous-path (get-in (second (:strands anonymous))
                                 [:attributes "workflow/defer-path"])]
      (is (nil? (get-in anonymous-path [0 "definition"])))
      (is (re-matches #"[0-9a-f]{16}" (get-in anonymous-path [0 "fingerprint"]))))))

(deftest sibling-defers-carry-independent-paths
  ;; The path is per defer strand, never a shared root record, which is what lets
  ;; two siblings later select the same target.
  (let [definition (workflow/bind-defers
                    (workflow/workflow
                     "Two points"
                     (workflow/defer :first-pick "First")
                     (workflow/defer :second-pick "Second"))
                    {:first-pick #{:wt-two-step}
                     :second-pick #{:wt-two-step}})
        payload (workflow/compile definition {})
        paths (mapv #(get-in % [:attributes "workflow/defer-path"])
                    (filter #(= "defer" (get-in % [:attributes "workflow/role"]))
                            (:strands payload)))]
    (is (= 2 (count paths)))
    (is (every? some? paths) "each sibling carries its own path, not a shared one")
    (is (apply = paths) "siblings at the same lexical depth share a path value")))

(deftest an-authored-defer-path-cannot-forge-lineage
  ;; PROP-Dfr-001.S5: compile owns the path. An author who supplies one must not
  ;; be able to blank it and walk past the cycle check.
  (testing "the builder refuses the key outright"
    (let [thrown (try (workflow/defer :again "Choose again"
                                      :attributes {"workflow/defer-path" []})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-path-reserved (:reason (ex-data thrown))))))
  (testing "and definition validation catches a raw map that skipped it"
    (let [forged {:id :again :title "Choose again"
                  :attributes {"workflow/role" "defer"
                               "workflow/defer" "again"
                               "workflow/defer-workflows" ["wt-two-step"]
                               "workflow/defer-path" []}}
          thrown (try (workflow/workflow "Forged" forged)
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-path-reserved (:reason (ex-data thrown))))
      (is (= :again (:defer (ex-data thrown))))
      (testing "and compiling the raw map anyway overwrites the authored value"
        (let [payload (workflow/compile {:name "Forged" :steps [forged]} {}
                                        {:definition 'skein.spools.workflow-test/forged})
              path (get-in (second (:strands payload))
                           [:attributes "workflow/defer-path"])]
          (is (= ["skein.spools.workflow-test/forged"] (mapv #(get % "definition") path))
              "the authored empty ancestry was replaced, not merged into")
          (is (re-matches #"[0-9a-f]{16}" (get-in path [0 "fingerprint"]))))))))

(deftest defer-refuses-a-malformed-persisted-path
  ;; Persisted attributes are an I/O boundary. Missing lineage must fail before
  ;; mutation instead of becoming an empty ancestry that lets a cycle through.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (doseq [[run-id bad-path] [["bad-path-shape" {}]
                                 ["bad-path-entry" [{"definition" nil}]]]]
        (workflow/start! run-id (defer-sandwich #{:wt-two-step}) {})
        (workflow/complete! run-id)
        (let [pending (workflow/ready-step run-id)]
          (repl/update! (:id pending) {:attributes {"workflow/defer-path" bad-path}})
          (let [thrown (try (workflow/defer! run-id :wt-two-step)
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/defer-path-invalid (:reason (ex-data thrown))))
            (is (contains? (ex-data thrown) :path))
            (is (= "active" (:state (repl/strand (:id pending))))
                "a malformed path fails before the fill mutates the defer")))))))

(workflow/defworkflow defer-self-target
  "A routine whose own defer may select it, which must be refused."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Self" (workflow/defer :again "Choose again"))
   {:again #{:wt-self}}))

(workflow/defworkflow defer-nested-inner
  "A routine whose own defer must not be able to select it again."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Inner" (workflow/defer :again "Choose again"))
   {:again #{:wt-nested-inner}}))

(workflow/defworkflow defer-nested-outer
  "A defer target that fixed-calls a routine declaring its own defer."
  {:entrypoints #{:call}}
  (workflow/workflow "Outer" (workflow/call :inner :wt-nested-inner {})))

(workflow/defworkflow defer-cycle-a
  "A routine whose defer selects the routine that may select it back."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Cycle A" (workflow/defer :pick "Pick B"))
   {:pick #{:wt-cycle-b}}))

(workflow/defworkflow defer-cycle-b
  "The routine A selects, whose own defer may select A again or something new."
  {:entrypoints #{:call}}
  (workflow/bind-defers
   (workflow/workflow "Cycle B" (workflow/defer :pick-back "Pick again"))
   {:pick-back #{:wt-cycle-a :wt-two-step}}))

(deftest defer-refuses-a-direct-cycle-and-permits-siblings
  ;; DELTA-Dfr-001.CC5: the path is the lexical ancestry of one defer, so a
  ;; self-selection is refused while two siblings may both pick the same target.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-self 'skein.spools.workflow-test/defer-self-target)
      (testing "a defer may not select the definition it is declared in"
        (workflow/start! "cyclic" #'defer-self-target {})
        (let [pending (workflow/ready-step "cyclic")
              thrown (try (workflow/defer! "cyclic" :wt-self)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown))))
          (is (= "active" (:state (repl/strand (:id pending))))
              "nothing mutated: the point is still fillable with something else")))
      (testing "two sibling defers may both select the same target"
        (let [definition (workflow/bind-defers
                          (workflow/workflow
                           "Two points"
                           (workflow/defer :first-pick "First")
                           (workflow/defer :second-pick "Second"))
                          {:first-pick #{:wt-two-step} :second-pick #{:wt-two-step}})
              result (workflow/start! "siblings" definition {})
              ids (mapv :id (filter #(= "defer" (:role %)) (:ready result)))]
          (workflow/defer! "siblings" :wt-two-step {} {:step (first ids)})
          (workflow/defer! "siblings" :wt-two-step {} {:step (second ids)})
          (is (every? #(= "procedure" (get-in (repl/strand %) [:attributes :workflow/role])) ids)
              "neither sibling is in the other's ancestry, so neither is a cycle"))))))

(deftest a-poured-expansion-keeps-its-fixed-call-ancestry
  ;; DELTA-Dfr-001.CC5. A defers to B; B fixed-calls C; C declares a defer. C
  ;; must be in that defer's path, or it can select itself.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-nested-inner
                                   'skein.spools.workflow-test/defer-nested-inner)
      (workflow/register-workflow! :wt-nested-outer
                                   'skein.spools.workflow-test/defer-nested-outer)
      (workflow/start! "nested" (defer-sandwich #{:wt-nested-outer}) {})
      (workflow/complete! "nested")
      (workflow/defer! "nested" :wt-nested-outer)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "nested")))
            thrown (try (workflow/defer! "nested" :wt-nested-inner {} {:step (:id inner)})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
            "the poured path kept the fixed-call ancestor, so selecting it is a cycle")))))

(deftest defer-refuses-a-nested-cycle-and-permits-acyclic-nesting
  ;; PROP-Dfr-001.S5: A -> B -> A fails at the second fill, because filling a
  ;; defer extends the path with the selected target's fingerprint.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      ;; mutual references cannot both be staged at once, so B is registered as a
      ;; plain callable first and repointed to its real definition afterwards
      (workflow/register-workflow! :wt-cycle-b
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-a 'skein.spools.workflow-test/defer-cycle-a)
      (workflow/register-workflow! :wt-cycle-b 'skein.spools.workflow-test/defer-cycle-b)
      (workflow/start! "cycles" #'defer-cycle-a {})
      (workflow/defer! "cycles" :wt-cycle-b)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "cycles")))]
        (is (= "pick-back" (:defer inner)))
        (let [thrown (try (workflow/defer! "cycles" :wt-cycle-a {} {:step (:id inner)})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
              "A is already in this defer's ancestry")
          (is (= 2 (count (:path (ex-data thrown))))))
        (testing "an acyclic nested selection still fills"
          (workflow/defer! "cycles" :wt-two-step {} {:step (:id inner)})
          (is (= ["Plan default"] (ready-titles "cycles"))))))))

(deftest defer-cycles-survive-a-fingerprint-change-across-weaver-generations
  ;; A definition holding render fns prints with their JVM identity hashes, so
  ;; the same registered routine fingerprints differently after a restart. The
  ;; ancestry check must still refuse the cycle, or A -> B -> A slips through
  ;; whenever the second fill lands in a later generation.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-b
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-a 'skein.spools.workflow-test/defer-cycle-a)
      (workflow/register-workflow! :wt-cycle-b 'skein.spools.workflow-test/defer-cycle-b)
      (workflow/start! "regen" #'defer-cycle-a {})
      (workflow/defer! "regen" :wt-cycle-b)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "regen")))
            path (get-in (repl/strand (:id inner)) [:attributes :workflow/defer-path])
            restarted (mapv #(assoc % :fingerprint (str "0000000000000000" (:fingerprint %)))
                            path)]
        (is (= 2 (count path)))
        (is (every? :definition path)
            "every ancestry entry of a registered routine records its symbol")
        ;; exactly what a later generation persists: same symbols, fresh digests
        (repl/update! (:id inner) {:attributes {"workflow/defer-path" restarted}})
        (let [thrown (try (workflow/defer! "regen" :wt-cycle-a {} {:step (:id inner)})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
              "the definition symbol carries the ancestry when the digest cannot"))
        (testing "and a genuinely different routine still fills"
          (workflow/defer! "regen" :wt-two-step {} {:step (:id inner)})
          (is (= ["Plan default"] (ready-titles "regen"))))))))

(deftest defer-materializes-a-multi-step-expansion-with-colliding-ids
  ;; Prefixing is what keeps a target whose step ids are :a and :c disjoint from
  ;; a caller that already uses :a and :c, and the expansion's entry must inherit
  ;; the defer's own depends-on.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-fanout 'skein.spools.workflow-test/defer-fanout-target)
      (workflow/start! "fanout" (defer-sandwich #{:wt-fanout}) {})
      (workflow/complete! "fanout")
      (let [pending (workflow/ready-step "fanout")
            filled (workflow/defer! "fanout" :wt-fanout)
            root-id (:id (workflow/current-root "fanout"))
            strands (:strands (graph/subgraph rt [root-id]))
            titles (set (map :title strands))]
        (is (= ["Target a"] (mapv :title (:ready filled)))
            "only the expansion's entry is ready; the caller's own :a and :c are untouched")
        (is (every? titles ["Step a" "Step c" "Target a" "Target left" "Target right" "Target c"])
            "colliding ids materialize as distinct prefixed strands")
        (testing "the whole fan-out runs and returns to the caller"
          (workflow/complete! "fanout")
          (is (= #{"Target left" "Target right"} (set (ready-titles "fanout"))))
          (workflow/complete! "fanout" {:step (:id (first (workflow/ready "fanout")))})
          (workflow/complete! "fanout" {:step (:id (first (workflow/ready "fanout")))})
          (is (= ["Target c"] (ready-titles "fanout")))
          (let [after-c (workflow/complete! "fanout")]
            (is (= ["Step c"] (mapv :title (:ready after-c)))
                "step c waits for the whole expansion, then becomes ready")
            (is (= "closed" (:state (repl/strand (:id pending)))))))))))

(deftest defer-into-an-empty-target-does-not-stall-the-run
  ;; An empty or fully-conditioned-out target yields no exits, so the join must
  ;; still close rather than leaving an invisible active procedure forever.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-empty 'skein.spools.workflow-test/defer-empty-target)
      (workflow/start! "empty-target" (defer-sandwich #{:wt-empty}) {})
      (workflow/complete! "empty-target")
      (let [pending (workflow/ready-step "empty-target")
            filled (workflow/defer! "empty-target" :wt-empty)]
        (is (= "closed" (:state (repl/strand (:id pending))))
            "a join with no expansion to wait for closes immediately")
        (is (= ["Step c"] (mapv :title (:ready filled)))
            "the declaring workflow continues instead of stalling"))
      (testing "and a final empty defer finishes the run in the fill batch"
        (workflow/start! "empty-final"
                         (workflow/bind-defers
                          (workflow/workflow "Only a defer"
                                             (workflow/defer :perform-work "Choose"))
                          {:perform-work #{:wt-empty}})
                         {})
        (is (true? (:done (workflow/defer! "empty-final" :wt-empty))))))))

(deftest a-filled-defer-cannot-be-filled-again
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/start! "double" (defer-sandwich #{:wt-two-step}) {})
      (workflow/complete! "double")
      (let [pending-id (:id (workflow/ready-step "double"))]
        (workflow/defer! "double" :wt-two-step)
        (testing "a filled defer is no longer ready, so naming it is not-ready"
          (let [thrown (try (workflow/defer! "double" :wt-two-step {} {:step pending-id})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (re-find #"not ready" (ex-message thrown)))
            (is (= pending-id (:step (ex-data thrown))))))
        (testing "naming a ready strand of another role is the step-not-defer failure"
          (let [ready-step-id (:id (workflow/ready-step "double"))
                thrown (try (workflow/defer! "double" :wt-two-step {} {:step ready-step-id})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/step-not-defer (:reason (ex-data thrown))))))))))

(defn reject-defer-batch-hook [ctx]
  (throw (ex-info "defer batch rejected" {:code "policy/rejected" :ctx ctx})))

(deftest a-failing-defer-apply-commits-nothing
  ;; The fill is one batch. A rejected apply must leave the defer ready and pour
  ;; no part of the expansion, not a half-materialized run to unpick by hand.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/start! "atomic" (defer-sandwich #{:wt-two-step}) {})
      (workflow/complete! "atomic")
      (let [pending (workflow/ready-step "atomic")
            root-id (:id (workflow/current-root "atomic"))
            before (count (:strands (graph/subgraph rt [root-id])))]
        (hooks/register-hook! rt :reject-defer #{:batch/apply-before-commit}
                              'skein.spools.workflow-test/reject-defer-batch-hook {})
        (let [thrown (try (workflow/defer! "atomic" :wt-two-step)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? (ex-data thrown)) "the rejected apply surfaces as a failure"))
        (is (= before (count (:strands (graph/subgraph rt [root-id]))))
            "no expansion strand was poured")
        (let [still (repl/strand (:id pending))]
          (is (= "active" (:state still)))
          (is (= "defer" (get-in still [:attributes :workflow/role]))
              "the point was not converted to a join by the failed batch"))))))

(deftest a-checkpoint-cutover-closes-an-unfilled-sibling-defer
  ;; closeable-roles is every strand the engine poured under an abandoned root.
  ;; Omitting defer would leave a pending selection point active beneath a root
  ;; that has already been replaced — and it must not become a history event.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/register-workflow! :wt-second
                                   'skein.spools.workflow-test/registry-second-stage)
      (let [definition (workflow/bind-defers
                        (workflow/workflow
                         "Router with a pending defer"
                         (workflow/checkpoint :go "Go"
                                              :kind :agent
                                              :choices [{:key :advance :label "Advance"
                                                         :next :wt-second}])
                         (workflow/defer :perform-work "Choose work"))
                        {:perform-work #{:wt-two-step}})
            result (workflow/start! "defer-cutover" definition {})
            defer-id (:id (first (filter #(= "defer" (:role %)) (:ready result))))
            go-id (:id (first (filter #(= "checkpoint" (:role %)) (:ready result))))]
        (is (= "active" (:state (repl/strand defer-id))))
        ;; the selector is required because a pending defer is ready beside the
        ;; checkpoint, and trusted choose! resolves the sole ready step by id
        ;; rather than filtering by role — the CLI is where roles partition.
        (workflow/choose! "defer-cutover" :advance {} {:step go-id})
        (is (= "closed" (:state (repl/strand defer-id)))
            "the route's cutover force-closes the pending defer with the old root")
        (is (not-any? #(= defer-id (:id %))
                      (mapcat :events (workflow/run-history "defer-cutover")))
            "a force-closed defer was never acted on, so history omits it")))))

(deftest run-history-omits-filled-defer-joins
  ;; PROP-Dfr-001.S6: a filled defer is procedure bookkeeping. Which routine a
  ;; worker selected is read from the strand, not replayed as a history molecule.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'skein.spools.workflow-test/defer-two-step-target)
      (workflow/start! "history" (defer-sandwich #{:wt-two-step}) {})
      (workflow/complete! "history")
      (let [defer-id (:id (workflow/ready-step "history"))]
        (workflow/defer! "history" :wt-two-step {} {:by "worker-1"})
        (dotimes [_ 3] (workflow/complete! "history"))
        (let [molecules (workflow/run-history "history")
              events (mapcat :events molecules)]
          (is (= 1 (count molecules)) "returning composition stays in one molecule")
          (is (= #{:step-closed} (set (map :type events)))
              "no :continuation type survives the cutover")
          (is (not-any? #(= defer-id (:id %)) events))
          (is (= #{"Step a" "Plan default" "Ship it" "Step c"} (set (map :title events)))
              "the expansion's own steps are ordinary closes; the join is not one")
          (is (= "wt-two-step"
                 (get-in (repl/strand defer-id) [:attributes :workflow/deferred-workflow]))
              "the selection is still readable on the strand itself"))))))

(deftest the-removed-dispatch-and-transfer-surface-is-gone
  ;; PROP-Dfr-001.NG1: a pre-v1 clean break, so nothing survives as an alias.
  (doseq [removed '[dispatch dispatch! run-dispatch! continue! run-continue!
                    bind-handoffs]]
    (is (nil? (ns-resolve 'skein.spools.workflow removed))
        (str removed " must not survive as a public var")))
  (doseq [removed [:skein.spools.workflow/dispatch-declaration
                   :skein.spools.workflow/dispatch-request
                   :skein.spools.workflow/continue-request
                   :skein.spools.workflow/handoff-bindings]]
    (is (nil? (s/get-spec removed)) (str removed " must not survive as a spec")))
  (is (= #{"step" "checkpoint" "defer"}
         (set (s/form :skein.spools.workflow.view/role)))
      "no ready item ever reports a dispatch role again"))

(deftest concurrent-choose-and-defer-serialize-under-the-run-guard
  ;; Both verbs resolve their frontier inside the guard, so one wins and the
  ;; other re-resolves against the frontier it left rather than filling twice.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
      (register-defer-targets!)
      (start-at-defer! "race-1")
      (let [attempts (mapv (fn [target]
                             (future
                               (try {:ok (workflow/defer! "race-1" target
                                                          {:feature "raced"})}
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
            "both fills returned; a timeout here means the guard deadlocked")
        (is (= 1 (count winners)) "exactly one fill pours")
        (is (= 1 (count losers)))
        (is (contains? #{:workflow/step-not-defer :workflow/params-invalid}
                       (:reason (ex-data (:err (first losers)))))
            "the loser re-resolved and found the winner's frontier, not its own point")
        (is (some? (workflow/current-root "race-1"))
            "one active root, never two under one run id")))))
