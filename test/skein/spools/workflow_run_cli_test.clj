(ns skein.spools.workflow-run-cli-test
  "Tests for the generic worker run surface of the `workflow` op: start, ready,
  complete, choose, continue, and await over the engine's published lifecycle."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.cli.alpha :as cli-alpha]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.test-support :as test-support :refer [with-runtime]]
            [skein.spools.workflow :as workflow]
            [skein.spools.workflow.cli :as cli]
            [skein.spools.workflow.internal.runs :as runs]
            [skein.test.alpha :as test-alpha]))

;; --- fixtures ---------------------------------------------------------------

(s/def ::scope string?)
(s/def ::build-params (s/keys :req-un [::scope]))
(s/def ::verdict #{"pass" "fail"})
(s/def ::sign-off-input (s/keys :req-un [::verdict]))

(workflow/defworkflow solo
  "One ordinary step and nothing else."
  {:entrypoints #{:start :continue :call}}
  (workflow/workflow "Solo" (workflow/step :work "Do the work" :self)))

(workflow/defworkflow twin
  "Two ordinary steps ready at once."
  {:entrypoints #{:start}}
  (workflow/workflow "Twin"
                     (workflow/step :left "Left" :self)
                     (workflow/step :right "Right" :self)))

(workflow/defworkflow mixed
  "One item of every role a worker verb can act on, all ready together."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Mixed"
   (workflow/step :work "Do the work" :self)
   (workflow/gate :ci "Wait for CI" :subagent)
   (workflow/checkpoint :sign-off "Sign the work off"
                        :choices [{:key :ship
                                   :label "Ship it"
                                   :input {:spec ::sign-off-input
                                           :doc "Record the reviewer's verdict."}}
                                  {:key :rework :label "Send it back" :next :solo}])))

(workflow/defworkflow composed
  "A definition whose entry frontier spans a spliced call and a loop."
  {:entrypoints #{:start}
   :defaults {:rounds ["one" "two"]}}
  (workflow/workflow
   "Composed"
   (workflow/step :brief "Brief the work" :self)
   (workflow/call :sub :solo {})
   (workflow/step :round "Run a round" :self :loop {:each :rounds})))

(workflow/defworkflow gated
  "A single external gate: ready, and never inferable."
  {:entrypoints #{:start}}
  (workflow/workflow "Gated" (workflow/gate :ci "Wait for CI" :subagent)))

(workflow/defworkflow scoped
  "A definition whose params its own spec judges."
  {:entrypoints #{:start}
   :param-spec ::build-params
   :defaults {:owner "nobody"}}
  (workflow/workflow (fn [{:keys [scope]}] (str "Build " scope))
                     (workflow/step :work "Do the work" :self)))

(workflow/defworkflow follow-on
  "A routine a run can be continued or routed into."
  {:entrypoints #{:start :continue}
   :param-spec ::build-params}
  (workflow/workflow "Follow on" (workflow/step :record "Record the outcome" :self)))

(workflow/defworkflow handoff
  "Hand a finished run to whichever routine the worker picks."
  {:entrypoints #{:start}}
  (workflow/bind-handoffs
   (workflow/workflow
    "Hand off"
    (workflow/step :summarize "Summarize what happened" :self)
    (workflow/defer :next-routine "Choose the next routine" :depends-on [:summarize]))
   {:next-routine #{:follow-on}}))

(workflow/defworkflow dispatched
  "Select a returning procedure, then finish the caller."
  {:entrypoints #{:start}}
  (workflow/bind-handoffs
   (workflow/workflow
    "Dispatched"
    (workflow/step :prepare "Prepare the work" :self)
    (workflow/dispatch :perform "Choose the procedure" :depends-on [:prepare])
    (workflow/step :finish "Finish the work" :self :depends-on [:perform]))
   {:perform #{:solo}}))

(workflow/defworkflow two-dispatches
  "Two independent returning hand-offs."
  {:entrypoints #{:start}}
  (workflow/bind-handoffs
   (workflow/workflow
    "Two dispatches"
    (workflow/dispatch :left "Choose left")
    (workflow/dispatch :right "Choose right"))
   {:left #{:solo} :right #{:solo}}))

(defn- activate-cli!
  "Activate the engine and then the separately declared CLI module."
  [rt]
  (test-support/activate-spool! rt :skein/spools-workflow 'skein.spools.workflow)
  (test-support/activate-spool! rt :skein/spools-workflow-cli 'skein.spools.workflow.cli
                                :after [:skein/spools-workflow]))

(defn- register!
  [& names]
  (doseq [name names]
    (workflow/register-workflow!
     name (symbol "skein.spools.workflow-run-cli-test" (clojure.core/name name)))))

(defn- invoke
  "Call the op handler the way the weaver does, with parsed args and the argv they
  were parsed from (which `--attr` duplicate detection is the only reader of)."
  ([args] (invoke args []))
  ([args argv] (cli/workflow-op {:op/args args :op/argv argv})))

(defn- from-argv
  "Parse real argv against the registered arg-spec and invoke the handler with
  both, the way the weaver reaches the op."
  [rt argv & [payloads]]
  (let [arg-spec (:arg-spec (weaver/resolve-op rt 'workflow))]
    (invoke (cli-alpha/parse arg-spec argv (or payloads {})) argv)))

(defn- verb
  [name run-id & {:as args}]
  (invoke (merge {:subcommand [name] :run-id run-id} args)))

(defn- started
  "Start `workflow` as run `run-id` through the CLI and return the result."
  [run-id workflow & {:as args}]
  (invoke (merge {:subcommand ["start"] :run-id run-id :workflow (clojure.core/name workflow)}
                 args)))

(defn- ready-ids
  [result]
  (mapv :id (:ready result)))

(defn- item-id
  "Return the id of the ready item titled `title` in `result`."
  [result title]
  (or (some #(when (= title (:title %)) (:id %)) (:ready result))
      (throw (ex-info "No ready item with that title" {:title title :ready (:ready result)}))))

(defn- failure
  [f]
  (ex-data (try (f) (catch clojure.lang.ExceptionInfo e e))))

(defn- reason-of
  [f]
  (:reason (failure f)))

(defn- wire-value
  [value]
  (json/read-str (json/write-str value) :key-fn keyword))

;; --- the shared envelope ----------------------------------------------------

(deftest every-run-verb-answers-with-the-same-envelope
  ;; PROP-Wcd-001.S4: one result shape across the surface, so a worker never has
  ;; to read a run to learn what it may do next.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (let [start (started "run-envelope" :solo)]
        (is (= "workflow start" (:operation start)))
        (is (= "run-envelope" (:run-id start)))
        (is (= "Solo" (get-in start [:root :title])))
        (is (= "active" (get-in start [:root :state])))
        (is (false? (:done start)))
        (is (= ["Do the work"] (mapv :title (:ready start))))
        (is (= #{:operation :run-id :root :ready :done} (set (keys start)))))
      (let [ready (verb "ready" "run-envelope")]
        (is (= "workflow ready" (:operation ready)))
        (is (= (ready-ids (verb "ready" "run-envelope")) (ready-ids ready))))
      (let [done (verb "complete" "run-envelope")]
        (is (= "workflow complete" (:operation done)))
        (is (true? (:done done)))
        (is (= [] (:ready done)))
        (testing "a finished run still names the root it poured"
          (is (= "Solo" (get-in done [:root :title])))
          (is (= "closed" (get-in done [:root :state]))))))))

(deftest ready-reports-every-role-in-the-frontier
  ;; A worker filtering for its own role can do that; one that never saw the
  ;; sibling item cannot.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (started "run-frontier" :mixed)
      (let [ready (verb "ready" "run-frontier")]
        (is (= [["Do the work" "step"] ["Wait for CI" "step"] ["Sign the work off" "checkpoint"]]
               (mapv (juxt :title :role) (:ready ready))))
        (is (= "subagent" (:gate (second (:ready ready)))))
        (is (= ["ship" "rework"] (:choices (nth (:ready ready) 2))))
        (is (every? #(= "run-frontier" (:run-id %)) (:ready ready)))))))

(deftest reads-and-mutations-fail-loudly-for-an-unknown-run
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (is (= :workflow/run-unknown (reason-of #(verb "ready" "never-poured"))))
      (is (= :workflow/run-unknown (reason-of #(verb "complete" "never-poured"))))
      (is (= :workflow/run-unknown
             (reason-of #(invoke {:subcommand ["choose"] :run-id "never-poured"
                                  :choice "ship"})))
          "a wrong run id and an empty frontier need different repairs"))))

(deftest the-frontier-orders-spliced-calls-and-loop-rounds-by-definition-position
  ;; A call's steps arrive carrying the position they held inside their own
  ;; compile; what orders the frontier is the position they hold in this run.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :composed)
      (let [start (started "run-composed" :composed)]
        (is (= ["Brief the work" "Do the work" "Run a round" "Run a round"]
               (mapv :title (:ready start))))
        (is (= [0 1 3 4]
               (mapv #(get-in (weaver/show rt (:id %)) [:attributes :workflow/position])
                     (:ready start)))
            "the join step at index 2 is engine bookkeeping and never appears")))))

;; --- start ------------------------------------------------------------------

(deftest start-pours-a-registered-definition-with-its-own-params
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :scoped)
      (let [result (started "run-params" :scoped :params {"scope" "queue"})
            root (weaver/show rt (get-in result [:root :id]))]
        (is (= "Build queue" (get-in result [:root :title]))
            "a rendered name is resolved against the params the worker supplied")
        (is (= {:scope "queue" :owner "nobody"} (get-in root [:attributes :workflow/context]))
            "the definition's defaults merge underneath")
        (is (= "scoped" (get-in root [:attributes :workflow/definition-name])))))))

(deftest start-refuses-params-its-definition-rejects
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :scoped)
      (is (= :workflow/params-invalid
             (reason-of #(started "run-bad-params" :scoped :params {"owner" "agent"})))
          "the definition's own :param-spec judges the merged map")
      (is (nil? (workflow/current-root "run-bad-params"))
          "a refused start pours nothing"))))

(deftest start-only-accepts-a-registered-startable-name
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :follow-on)
      (is (= :workflow/definition-unregistered (reason-of #(started "run-absent" :nope))))
      (started "run-taken" :solo)
      (is (thrown? clojure.lang.ExceptionInfo (started "run-taken" :solo))
          "one active run per run id"))))

;; --- complete: inference, roles, and gates ----------------------------------

(deftest complete-infers-the-sole-ordinary-step-across-a-mixed-frontier
  ;; A ready checkpoint and a ready gate are not ambiguity for `complete`:
  ;; neither is a step it could close.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (let [start (started "run-mixed" :mixed)
            work (item-id start "Do the work")
            result (verb "complete" "run-mixed")]
        (is (not (contains? (set (ready-ids result)) work)))
        (is (= ["Wait for CI" "Sign the work off"] (mapv :title (:ready result))))
        (is (false? (:done result)))))))

(deftest complete-merges-the-attribute-pair-onto-the-closed-step
  ;; PROP-Wcd-001.S2: `strand add`'s attribute pair, so a worker records its own
  ;; outcome vocabulary in the same mutation that closes the step.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (let [work (item-id (started "run-attrs" :solo) "Do the work")
            result (from-argv rt ["complete" "run-attrs"
                                  "--attributes" "{\"acme/verdict\":\"stale\",\"acme/exit\":7}"
                                  "--attr" "acme/verdict=pass"])
            closed (weaver/show rt work)]
        (is (true? (:done result)))
        (is (= "pass" (get-in closed [:attributes :acme/verdict]))
            "--attr wins key by key, as it does on strand add")
        (is (= 7 (get-in closed [:attributes :acme/exit]))
            "--attributes carries typed values through untouched")))))

(deftest complete-refuses-a-duplicate-attr-key-before-mutating
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (let [work (item-id (started "run-dup-attr" :solo) "Do the work")
            data (failure #(from-argv rt ["complete" "run-dup-attr"
                                          "--attr" "acme/verdict=pass"
                                          "--attr" "acme/verdict=fail"]))]
        (is (= :workflow/attr-key-duplicate (:reason data)))
        (is (= "acme/verdict" (:key data)))
        (is (= "active" (:state (weaver/show rt work))) "nothing was closed")))))

(deftest complete-refuses-attributes-that-are-not-a-json-object
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (started "run-bad-attrs" :solo)
      (doseq [[label value] [["an array" [1 2]] ["a JSON null" nil] ["a blank key" {"" "x"}]]]
        (testing label
          (is (= :workflow/attributes-invalid
                 (reason-of #(invoke {:subcommand ["complete"] :run-id "run-bad-attrs"
                                      :attributes value})))))))))

(deftest complete-refuses-an-ambiguous-step-frontier-before-mutating
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :twin)
      (let [start (started "run-twin" :twin)
            data (failure #(verb "complete" "run-twin"))]
        (is (= :workflow/ready-step-ambiguous (:reason data)))
        (is (= (ready-ids start) (mapv :id (:compatible data)))
            "the failure carries the complete compatible set")
        (is (re-find #"--step" (:guidance data)))
        (is (= (ready-ids start) (ready-ids (verb "ready" "run-twin")))
            "nothing was closed")
        (testing "--step resolves it"
          (is (= [(second (ready-ids start))]
                 (ready-ids (verb "complete" "run-twin" :step (first (ready-ids start)))))))))))

(deftest complete-refuses-a-wrong-role-step-selector
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (let [start (started "run-wrong-role" :mixed)
            checkpoint (item-id start "Sign the work off")
            data (failure #(verb "complete" "run-wrong-role" :step checkpoint))]
        (is (= :workflow/ready-step-incompatible (:reason data)))
        (is (= "checkpoint" (:role data)))
        (is (= ["Do the work" "Wait for CI"] (mapv :title (:compatible data)))
            "the failure names what this verb could have acted on")))))

(deftest a-step-selector-must-name-a-ready-item
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (started "run-unknown-step" :solo)
      (let [data (failure #(verb "complete" "run-unknown-step" :step "no-such-strand"))]
        (is (= :workflow/step-not-ready (:reason data)))
        (is (= ["Do the work"] (mapv :title (:ready data))))))))

(deftest a-gate-is-never-inferred-and-never-closed-anonymously
  ;; PROP-Wcd-001.S4: closing a gate asserts something outside the run happened,
  ;; so it takes an explicit step and an actor.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :gated)
      (let [start (started "run-gate" :gated)
            gate (item-id start "Wait for CI")]
        (is (= :workflow/ready-step-absent (reason-of #(verb "complete" "run-gate")))
            "the only ready item is a gate, so nothing is inferable")
        (let [data (failure #(verb "complete" "run-gate" :step gate))]
          (is (= :workflow/gate-actor-required (:reason data)))
          (is (= "subagent" (:gate data))))
        (let [result (verb "complete" "run-gate" :step gate :by "ci-bot")]
          (is (true? (:done result)))
          (is (= "ci-bot"
                 (get-in (weaver/show rt gate) [:attributes :workflow/outcome-by]))))))))

;; --- choose -----------------------------------------------------------------

(deftest choose-infers-the-sole-checkpoint-and-carries-input-and-actor
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (let [start (started "run-choose" :mixed)
            checkpoint (item-id start "Sign the work off")
            result (invoke {:subcommand ["choose"] :run-id "run-choose" :choice "ship"
                            :input {"verdict" "pass"} :by "reviewer"})
            closed (:attributes (weaver/show rt checkpoint))]
        (is (= "workflow choose" (:operation result)))
        (is (= "ship" (:workflow/outcome closed)))
        (is (= "reviewer" (:workflow/outcome-by closed)))
        (is (= {:verdict "pass"} (:workflow/outcome-input closed)))
        (is (= ["Do the work" "Wait for CI"] (mapv :title (:ready result)))
            "the sibling frontier is untouched")))))

(deftest choose-refuses-input-the-choice-contract-rejects
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (started "run-choose-input" :mixed)
      (is (= :workflow/input-invalid
             (reason-of #(invoke {:subcommand ["choose"] :run-id "run-choose-input"
                                  :choice "ship" :input {"verdict" "maybe"}})))
          "the choice's own whole-map spec judges the input")
      (is (some #(= "checkpoint" (:role %)) (:ready (verb "ready" "run-choose-input")))
          "the checkpoint stays ready"))))

(deftest choose-routes-into-a-continuation-and-returns-its-frontier
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (started "run-routed" :mixed)
      (let [result (invoke {:subcommand ["choose"] :run-id "run-routed" :choice "rework"})]
        (is (= "Solo" (get-in result [:root :title])))
        (is (= ["Do the work"] (mapv :title (:ready result)))
            "the routed continuation replaced the whole frontier")))))

(deftest choose-refuses-a-frontier-without-a-checkpoint
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (let [start (started "run-no-checkpoint" :solo)]
        (is (= :workflow/ready-checkpoint-absent
               (reason-of #(invoke {:subcommand ["choose"] :run-id "run-no-checkpoint"
                                    :choice "ship"}))))
        (is (= :workflow/ready-checkpoint-incompatible
               (reason-of #(invoke {:subcommand ["choose"] :run-id "run-no-checkpoint"
                                    :choice "ship" :step (first (ready-ids start))}))))))))

;; --- continue ---------------------------------------------------------------

(deftest continue-infers-the-sole-defer-exit-and-pours-the-target
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :follow-on :handoff)
      (started "run-handoff" :handoff)
      (verb "complete" "run-handoff")
      (let [result (invoke {:subcommand ["continue"] :run-id "run-handoff"
                            :workflow "follow-on" :params {"scope" "queue"}
                            :by "worker"})]
        (is (= "workflow continue" (:operation result)))
        (is (= "Follow on" (get-in result [:root :title])))
        (is (= ["Record the outcome"] (mapv :title (:ready result))))))))

(deftest continue-refuses-a-target-outside-the-defers-allowlist
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :follow-on :handoff)
      (started "run-handoff-denied" :handoff)
      (verb "complete" "run-handoff-denied")
      (is (= :workflow/defer-target-not-allowed
             (reason-of #(invoke {:subcommand ["continue"] :run-id "run-handoff-denied"
                                  :workflow "solo"}))))
      (is (= ["Choose the next routine"]
             (mapv :title (:ready (verb "ready" "run-handoff-denied"))))
          "the defer stays ready to retry"))))

(deftest continue-refuses-a-frontier-without-a-defer-exit
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :follow-on :handoff)
      (let [start (started "run-handoff-early" :handoff)]
        (is (= :workflow/ready-defer-absent
               (reason-of #(invoke {:subcommand ["continue"] :run-id "run-handoff-early"
                                    :workflow "follow-on"}))))
        (is (= :workflow/ready-defer-incompatible
               (reason-of #(invoke {:subcommand ["continue"] :run-id "run-handoff-early"
                                    :workflow "follow-on"
                                    :step (first (ready-ids start))}))))))))

;; --- dispatch ---------------------------------------------------------------

(deftest dispatch-drives-a-returning-procedure-through-the-cli
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :dispatched)
      (started "run-dispatch" :dispatched)
      (verb "complete" "run-dispatch")
      ;; through real argv, so the declared arg-spec — subcommand, positional, and
      ;; every flag — is what the verb is reached by, not a hand-built arg map.
      (let [filled (from-argv rt ["dispatch" "run-dispatch"
                                  "--workflow" "solo" "--by" "worker"])]
        (is (= "workflow dispatch" (:operation filled)))
        (is (= ["Do the work"] (mapv :title (:ready filled))))
        (verb "complete" "run-dispatch")
        (is (= ["Finish the work"] (mapv :title (:ready (verb "ready" "run-dispatch")))))
        (is (true? (:done (verb "complete" "run-dispatch"))))))))

(deftest dispatch-is-role-scoped-and-leaves-the-point-ready-on-refusal
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :follow-on :handoff :dispatched :two-dispatches)
      (let [two (started "run-two-dispatches" :two-dispatches)]
        (is (= :workflow/ready-dispatch-ambiguous
               (reason-of #(invoke {:subcommand ["dispatch"] :run-id "run-two-dispatches"
                                    :workflow "solo"}))))
        (is (= (ready-ids two)
               (mapv :id (:compatible (failure #(invoke {:subcommand ["dispatch"]
                                                         :run-id "run-two-dispatches"
                                                         :workflow "solo"})))))))
      (started "run-dispatch-roles" :dispatched)
      (verb "complete" "run-dispatch-roles")
      (is (= :workflow/step-not-dispatch
             (reason-of #(invoke {:subcommand ["continue"] :run-id "run-dispatch-roles"
                                  :workflow "solo"}))))
      (is (= :workflow/step-not-completable
             (reason-of #(verb "complete" "run-dispatch-roles"))))
      (is (= :workflow/dispatch-target-not-allowed
             (reason-of #(invoke {:subcommand ["dispatch"] :run-id "run-dispatch-roles"
                                  :workflow "handoff"}))))
      (is (= ["Choose the procedure"]
             (mapv :title (:ready (verb "ready" "run-dispatch-roles"))))))))

;; --- concurrency ------------------------------------------------------------

(deftest a-mutation-whose-frontier-moved-fails-as-retryable-rather-than-applying
  ;; The guard's second half, at its own seam: given the frontier the caller
  ;; resolved against and the one the guard found, a difference is another
  ;; worker's write and the request must not apply to it. Testing the seam
  ;; directly keeps the interleaving deterministic without redefining a var the
  ;; rest of the parallel suite shares.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :twin)
      (let [before (:ready (started "run-race" :twin))
            [left right] (mapv :id before)
            after (filterv #(= right (:id %)) before)
            data (failure #(runs/require-fresh-frontier! "workflow complete" :step
                                                         "run-race" right before after))]
        (is (= :workflow/frontier-stale (:reason data)))
        (is (= "workflow complete" (:operation data)))
        (is (= right (:step data)))
        (is (= [right] (mapv :id (:ready data))) "the frontier as it is now")
        (is (re-find #"workflow ready" (:guidance data)))
        (testing "an unchanged compatible frontier passes straight through"
          (is (= before (runs/require-fresh-frontier! "workflow complete" :step
                                                      "run-race" nil before before))))
        (testing "a sibling of another role changing is not this verb's race"
          (is (= [left right] (ready-ids (verb "ready" "run-race")))))))))

(deftest concurrent-workers-cannot-both-close-one-ready-step
  (with-runtime
    {:publish? true}
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (let [start (started "run-contended" :solo)
            step (first (ready-ids start))
            attempts (mapv (fn [_]
                             (future (try {:ok (verb "complete" "run-contended" :step step)}
                                          (catch clojure.lang.ExceptionInfo e
                                            {:reason (:reason (ex-data e))}))))
                           (range 4))
            results (mapv deref attempts)]
        (is (= 1 (count (filter :ok results))) "exactly one close wins")
        (is (every? #{:workflow/frontier-stale :workflow/step-not-ready}
                    (keep :reason results))
            "every loser fails in the stable retryable vocabulary")))))

;; --- await ------------------------------------------------------------------

(deftest await-answers-with-the-attention-reason-that-stopped-it
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed)
      (started "run-await" :mixed)
      (let [result (verb "await" "run-await" :timeout-secs 5)]
        (is (= "workflow await" (:operation result)))
        (is (= "run-await" (:run-id result)))
        (is (= :checkpoint (:reason result)) "a ready checkpoint outranks the ready step")
        (is (false? (:done result)))
        (is (= "Sign the work off" (:title (:detail result))))
        (is (= 3 (count (:ready result)))))
      (invoke {:subcommand ["choose"] :run-id "run-await" :choice "ship"
               :input {"verdict" "pass"}})
      (verb "complete" "run-await")
      (verb "complete" "run-await" :step (item-id (verb "ready" "run-await") "Wait for CI")
            :by "ci-bot")
      (let [result (verb "await" "run-await" :timeout-secs 5)]
        (is (= :done (:reason result)))
        (is (true? (:done result)))))))

(deftest await-times-out-rather-than-blocking-on-an-executor-owned-frontier
  ;; Also the regression guard for `--timeout-secs 0`: a healthy executor-owned
  ;; frontier is what `await` waits through, so a zero dropped on the way to the
  ;; engine would block here for the 1800-second default instead of answering.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :gated)
      (workflow/register-executor! :subagent (fn [_step] nil))
      (started "run-await-timeout" :gated)
      (let [result (verb "await" "run-await-timeout" :timeout-secs 0)]
        (is (= :timeout (:reason result)))
        (is (= ["Wait for CI"] (mapv :title (:ready result))))))))

(deftest a-supplied-flag-is-carried-even-when-its-value-is-empty-or-zero
  ;; Presence, not truthiness: an empty selector and a zero timeout are things
  ;; the worker said, and the request spec is what judges them. (Clojure counts
  ;; "" and 0 as true, so a reader coming from another language should not have
  ;; to take that on trust.)
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (started "run-empty-flags" :solo)
      (is (thrown? clojure.lang.ExceptionInfo (verb "complete" "run-empty-flags" :step ""))
          "an empty --step is a stated selector the request spec refuses, not inference")
      (is (thrown? clojure.lang.ExceptionInfo (verb "complete" "run-empty-flags" :by ""))
          "an empty --by is refused rather than dropped")
      (is (= ["Do the work"] (mapv :title (:ready (verb "ready" "run-empty-flags"))))
          "neither refused request mutated the run"))))

(deftest a-run-result-refuses-a-role-outside-the-published-vocabulary
  ;; A definition may write its own workflow/role (TEN-002); a worker surface
  ;; that presented one as an ordinary step would be inventing a contract.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo)
      (let [start (started "run-odd-role" :solo)
            step (first (ready-ids start))]
        (weaver/update! rt step {:attributes {"workflow/role" "improvised"}})
        (is (= :workflow/run-result-invalid (reason-of #(verb "ready" "run-odd-role"))))))))

;; --- op wiring --------------------------------------------------------------

(deftest the-run-verbs-declare-their-classes-and-return-cases
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (let [entry (weaver/resolve-op rt 'workflow)
            leaf (fn [verb] (get-in entry [:arg-spec :subcommands verb]))]
        (doseq [verb ["start" "complete" "choose" "continue" "dispatch"]]
          (is (= :mutating (:hook-class (leaf verb))) verb)
          (is (= :standard (:deadline-class (leaf verb))) verb))
        (is (= [:read :standard] ((juxt :hook-class :deadline-class) (leaf "ready"))))
        (is (= [:read :unbounded] ((juxt :hook-class :deadline-class) (leaf "await")))
            "await blocks by design and writes nothing")
        (is (= #{"list" "show" "start" "ready" "complete" "choose" "continue" "dispatch" "await"}
               (set (keys (:subcommands (:arg-spec entry))))))
        (is (= (set (keys (:subcommands (:arg-spec entry))))
               (set (keys (:subcommands (:returns entry))))))))))

(deftest run-verb-returns-match-their-declaration
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed :follow-on :handoff :dispatched)
      (let [check (fn [verb value]
                    (test-alpha/check-op-return! rt 'workflow {:subcommand [verb]}
                                                 (wire-value value)))]
        (check "start" (started "run-returns" :mixed))
        (check "ready" (verb "ready" "run-returns"))
        (check "complete" (verb "complete" "run-returns"))
        (check "choose" (invoke {:subcommand ["choose"] :run-id "run-returns"
                                 :choice "ship" :input {"verdict" "pass"}}))
        (check "await" (verb "await" "run-returns" :timeout-secs 0))
        (started "run-returns-defer" :handoff)
        (verb "complete" "run-returns-defer")
        (check "continue" (invoke {:subcommand ["continue"] :run-id "run-returns-defer"
                                   :workflow "follow-on" :params {"scope" "queue"}}))
        (started "run-returns-dispatch" :dispatched)
        (verb "complete" "run-returns-dispatch")
        (check "dispatch" (invoke {:subcommand ["dispatch"] :run-id "run-returns-dispatch"
                                   :workflow "solo"}))))))

(deftest declared-args-carry-argv-to-the-run-verbs
  ;; The op is reached as argv, so the declared arg-spec is the real entrance.
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :solo :mixed :follow-on :handoff)
      (let [arg-spec (:arg-spec (weaver/resolve-op rt 'workflow))
            parse (fn [argv] (cli-alpha/parse arg-spec argv))]
        (is (= {:subcommand ["start"] :run-id "r1" :workflow "solo"}
               (parse ["start" "r1" "--workflow" "solo"])))
        (is (= {:subcommand ["start"] :run-id "r1" :workflow "scoped"
                :params {"scope" "queue"}}
               (parse ["start" "r1" "--workflow" "scoped" "--params" "{\"scope\":\"queue\"}"])))
        (is (= {:subcommand ["ready"] :run-id "r1"} (parse ["ready" "r1"])))
        (is (= {:subcommand ["complete"] :run-id "r1" :step "s-1" :by "agent"}
               (parse ["complete" "r1" "--step" "s-1" "--by" "agent"])))
        (is (= {:subcommand ["complete"] :run-id "r1"
                :attr {"acme/verdict" "pass"} :attributes {"acme/exit" 0}}
               (parse ["complete" "r1" "--attr" "acme/verdict=pass"
                       "--attributes" "{\"acme/exit\":0}"])))
        (is (= {:subcommand ["choose"] :run-id "r1" :choice "ship" :input {"verdict" "pass"}}
               (parse ["choose" "r1" "ship" "--input" "{\"verdict\":\"pass\"}"])))
        (is (= {:subcommand ["continue"] :run-id "r1" :workflow "follow-on"}
               (parse ["continue" "r1" "--workflow" "follow-on"])))
        (is (= {:subcommand ["dispatch"] :run-id "r1" :workflow "solo"}
               (parse ["dispatch" "r1" "--workflow" "solo"])))
        (is (= {:subcommand ["await"] :run-id "r1" :timeout-secs 30}
               (parse ["await" "r1" "--timeout-secs" "30"])))
        (testing "the parser refuses what the surface does not declare"
          (is (thrown? clojure.lang.ExceptionInfo (parse ["start" "r1"])))
          (is (thrown? clojure.lang.ExceptionInfo (parse ["choose" "r1"])))
          (is (thrown? clojure.lang.ExceptionInfo (parse ["continue" "r1"])))
          (is (thrown? clojure.lang.ExceptionInfo (parse ["ready" "r1" "--limit" "5"])))
          (is (thrown? clojure.lang.ExceptionInfo (parse ["complete" "r1" "--notes" "done"]))))
        (testing "argv reaches the engine through the handler"
          (is (= ["Do the work"]
                 (mapv :title (:ready (invoke (parse ["start" "run-argv" "--workflow" "solo"]))))))
          (is (true? (:done (invoke (parse ["complete" "run-argv"]))))))))))

(deftest json-flags-take-stdin-and-payload-references
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :scoped)
      (let [arg-spec (:arg-spec (weaver/resolve-op rt 'workflow))]
        (is (= {"scope" "queue"}
               (:params (cli-alpha/parse arg-spec
                                         ["start" "r1" "--workflow" "scoped" "--params" ":stdin"]
                                         {"stdin" "{\"scope\":\"queue\"}"})))
            "a whole-value reference resolves before the JSON parse")
        (is (= {"scope" "queue"}
               (:params (cli-alpha/parse arg-spec
                                         ["start" "r1" "--workflow" "scoped"
                                          "--params" ":payload/params"]
                                         {"params" "{\"scope\":\"queue\"}"}))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli-alpha/parse arg-spec ["start" "r1" "--workflow" "scoped"
                                                "--params" "not-json"]))
            "a malformed payload fails at the parser, before the engine")
        (testing "complete's attribute pair takes the same references"
          (is (= {"acme/exit" 0}
                 (:attributes (cli-alpha/parse arg-spec ["complete" "r1" "--attributes" ":stdin"]
                                               {"stdin" "{\"acme/exit\":0}"}))))
          (is (= {"acme/log" "tail"}
                 (:attr (cli-alpha/parse arg-spec ["complete" "r1" "--attr" "acme/log=:payload/log"]
                                         {"log" "tail"})))))))))

(deftest a-json-flag-that-is-not-an-object-fails-loudly
  (with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :scoped :solo :mixed)
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke {:subcommand ["start"] :run-id "run-json" :workflow "scoped"
                            :params [1 2]}))
          "params must be a JSON object, not an array")
      (started "run-json-input" :mixed)
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke {:subcommand ["choose"] :run-id "run-json-input" :choice "ship"
                            :input "pass"}))))))

;; --- the cross-spool composition fixture ------------------------------------
;;
;; PROP-Wcd-001.S7/EX7 end to end, through module publication rather than
;; imperative registration: three owners that never name each other's code. A
;; kanban-style spool publishes a template with a named exit, a devflow-style
;; spool registers a routine that advertises `:continue`, and the workspace —
;; the only place that can see both — binds one to the other and registers the
;; result. A worker then drives the composite through the shipped verbs alone.

(defn- module-source!
  "Write module source `forms` under `config-dir` and return its workspace path."
  [config-dir label requires forms]
  (let [source (str "modules/" label ".clj")
        file (io/file config-dir source)]
    (io/make-parents file)
    (spit file (str "(ns test.compose." label "\n"
                    "  \"Cross-spool composition fixture module.\"\n"
                    "  (:require [skein.spools.workflow :as workflow]" requires "))\n"
                    forms))
    source))

(def ^:private kanban-template-form
  ;; The template names where a worker chooses, and nothing about who they may
  ;; choose. It is a plain `def`, so this owner registers nothing at all.
  (str "(def general\n"
       "  (workflow/workflow\n"
       "    \"Track a card\"\n"
       "    (workflow/step :prepare \"Prepare the card\" :self)\n"
       "    (workflow/defer :perform-work \"Choose how this work will be performed\"\n"
       "                    :depends-on [:prepare])))\n"))

(def ^:private devflow-routine-form
  (str "(clojure.spec.alpha/def ::feature clojure.core/string?)\n"
       "(clojure.spec.alpha/def ::devflow-params\n"
       "  (clojure.spec.alpha/keys :req-un [::feature]))\n"
       "(workflow/defworkflow devflow\n"
       "  \"Plan and build a feature.\"\n"
       "  {:entrypoints #{:start :continue}\n"
       "   :param-spec ::devflow-params}\n"
       "  (workflow/workflow\n"
       "    (fn [{:keys [feature]}] (str \"Plan and build \" feature))\n"
       "    (workflow/step :inspect \"Inspect feature context\" :self)))\n"))

(def ^:private workspace-binding-form
  ;; The workspace is the authority boundary: it can see both spools, so it says
  ;; which registered routines the kanban exit allows.
  (str "(workflow/defworkflow tracked-card\n"
       "  \"Track a card and select its delivery routine.\"\n"
       "  {:entrypoints #{:start}}\n"
       "  (workflow/bind-handoffs template/general {:perform-work #{:devflow}}))\n"))

(deftest a-workspace-binds-one-spools-defer-exit-to-anothers-registered-routine
  (with-runtime
    (fn [rt config-dir]
      (activate-cli! rt)
      (let [template (module-source! config-dir "template" "" kanban-template-form)
            routine (module-source! config-dir "routine" "" devflow-routine-form)
            workspace (module-source! config-dir "binding"
                                      "\n            [test.compose.template :as template]"
                                      workspace-binding-form)]
        (is (= :applied (:status (runtime/module! rt :compose/template {:file template}))))
        (is (= :applied (:status (runtime/module! rt :compose/routine {:file routine}))))
        (is (= :applied (:status (runtime/module!
                                  rt :compose/binding
                                  {:file workspace :after [:compose/template :compose/routine]}))))
        (testing "the template owner registered nothing; the workspace registered the composite"
          (is (= #{:devflow :tracked-card} (set (keys (workflow/workflows)))))
          (is (= 'test.compose.binding/tracked-card
                 (workflow/workflow-definition :tracked-card))))
        (testing "both startable routines are catalogued, with the exit's allowlist on show"
          (is (= ["devflow" "tracked-card"]
                 (mapv :name (:definitions (invoke {:subcommand ["list"]})))))
          (is (= [{:defer "perform-work" :workflows ["devflow"]}]
                 (mapv #(select-keys % [:defer :workflows])
                       (:defers (:declared (invoke {:subcommand ["show"]
                                                    :workflow "tracked-card"})))))))
        (let [started (started "card-123" :tracked-card)
              _ (verb "complete" "card-123")
              at-exit (verb "ready" "card-123")
              exit (first (:ready at-exit))]
          (is (= ["Prepare the card"] (mapv :title (:ready started))))
          (testing "the poured exit carries the allowed names, in registered order"
            (is (= "defer" (:role exit)))
            (is (= "perform-work" (:defer exit)))
            (is (= ["devflow"] (:workflows exit))))
          (testing "continue transfers the root and carries only the target's params"
            (let [continued (invoke {:subcommand ["continue"] :run-id "card-123"
                                     :workflow "devflow"
                                     :params {"feature" "kanban-web-ui"}})]
              (is (= "workflow continue" (:operation continued)))
              (is (= "Plan and build kanban-web-ui" (:title (:root continued))))
              (is (not= (:id (:root started)) (:id (:root continued))))
              (is (= ["Inspect feature context"] (mapv :title (:ready continued))))
              (is (false? (:done continued)))))
          (testing "the transferred run finishes under the same run id"
            (is (true? (:done (verb "complete" "card-123"))))))))))
