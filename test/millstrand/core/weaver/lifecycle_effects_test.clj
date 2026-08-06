(ns millstrand.core.weaver.lifecycle-effects-test
  "Executable evidence for the bounded lifecycle authoring feasibility spike."
  (:require [clojure.test :refer [deftest is testing]]))

(load-file "devflow/feat/authoring-forms/lifecycle-spike/engine.clj")
(require '[millstrand.core.weaver.lifecycle-effects :as engine])

(defn- recording-resolver
  [calls failures]
  {'fixture/open
   (fn [{:keys [effect/id]}]
     (swap! calls conj [:open id])
     (when (contains? @failures [:open id])
       (throw (ex-info "open failed" {:at id})))
     {:handle id})
   'fixture/open-v2
   (fn [{:keys [effect/id]}]
     (swap! calls conj [:open-v2 id])
     {:handle id})
   'fixture/plain-failure
   (fn [_]
     (throw (RuntimeException. "plain open failed")))
   'fixture/close
   (fn [{:keys [effect/id resource]}]
     (swap! calls conj [:close id (:handle resource)])
     (when (contains? @failures [:close id])
       (throw (ex-info "close failed" {:at id})))
     {:closed id})
   'fixture/seed
   (fn [{:keys [effect/id]}]
     (swap! calls conj [:seed id])
     {:seeded id})
   'fixture/remove
   (fn [{:keys [effect/id]}]
     (swap! calls conj [:remove id])
     {:removed id})
   'fixture/desired
   (fn [_] {:job {:schedule "daily"}})
   'fixture/actual
   (fn [_] {})
   'fixture/converge
   (fn [{:keys [effect/id desired actual]}]
     (swap! calls conj [:converge id desired actual])
     {:converged id})})

(defn- resource
  [& {:as opts}]
  (merge {:kind :resource
          :open 'fixture/open
          :close 'fixture/close}
         opts))

(defn- reconcile-effect
  [& {:as opts}]
  (merge {:kind :reconcile
          :read-desired 'fixture/desired
          :read-actual 'fixture/actual
          :apply 'fixture/converge
          :on-removed 'fixture/remove}
         opts))

(defn- run-refresh
  [resolver state declarations changed-kinds]
  (engine/refresh {:runtime ::runtime
                   :module-key :fixture/module
                   :resolver resolver
                   :state state
                   :declarations declarations
                   :changed-kinds changed-kinds}))

(deftest declarations-fail-before-execution
  (testing "closed keys, qualified callables, dependencies, and cycles"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown lifecycle declaration keys"
         (engine/validate! {:bad (assoc (resource) :surprise true)})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"qualified symbol"
         (engine/validate! {:bad (assoc (resource) :open 'open)})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"missing"
         (engine/validate! {:bad (resource :after #{:absent})})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"cycle"
         (engine/validate! {:a (resource :after #{:b})
                            :b (resource :after #{:a})})))))

(deftest publication-precedes-effects-and-plan-is-pure
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        declarations {:pool (resource)}]
    (is (= [:pool] (:apply (engine/plan {} declarations #{}))))
    (is (empty? @calls))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"completed contribution publication"
         (engine/refresh {:module-key :fixture/module
                          :resolver resolver
                          :declarations declarations
                          :published? false})))
    (is (empty? @calls))))

(deftest callable-resolution-precedes-publication
  (let [declarations {:pool (resource)}]
    (try
      (engine/refresh {:module-key :fixture/module
                       :resolver {}
                       :declarations declarations
                       :published? false})
      (is false "unresolved callable should fail")
      (catch clojure.lang.ExceptionInfo error
        (is (= :resolve (:effect/phase (ex-data error))))
        (is (= 'fixture/open (:effect/callable (ex-data error))))))))

(deftest apply-preserve-replace-and-removal-follow-dependency-order
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        initial {:pool (resource)
                 :handler (resource :after #{:pool})}
        first-refresh (run-refresh resolver {} initial #{})]
    (is (= [[:open :pool] [:open :handler]] @calls))
    (reset! calls [])
    (let [preserved (run-refresh resolver (:state first-refresh) initial #{})]
      (is (empty? @calls))
      (is (= #{:pool :handler} (set (:preserve (:plan preserved)))))
      (let [changed (assoc-in initial [:pool :generation] 2)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Unknown lifecycle declaration keys"
             (engine/plan (:state preserved) changed #{}))))
      (let [replacement (assoc-in initial [:handler :open] 'fixture/open-v2)
            replaced (run-refresh resolver (:state preserved) replacement #{})]
        (is (= [[:close :handler :handler] [:open-v2 :handler]] @calls))
        (reset! calls [])
        (let [removed (run-refresh resolver (:state replaced) {} #{})]
          (is (= [[:close :handler :handler] [:close :pool :pool]] @calls))
          (is (empty? (get-in removed [:state :effects]))))))))

(deftest mixed-removal-and-replacement-share-reverse-teardown-order
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        previous {:pool (resource)
                  :handler (resource :after #{:pool})}
        applied (run-refresh resolver {} previous #{})
        next {:handler (resource :open 'fixture/open-v2)}]
    (reset! calls [])
    (run-refresh resolver (:state applied) next #{})
    (is (= [[:close :handler :handler]
            [:close :pool :pool]
            [:open-v2 :handler]]
           @calls))))

(deftest failed-open-retries-whole-boundary-and-preserves-siblings
  (let [calls (atom [])
        failures (atom #{[:open :monitor]})
        resolver (recording-resolver calls failures)
        declarations {:pool (resource)
                      :monitor (resource :after #{:pool})
                      :scan {:kind :seed
                             :after #{:monitor}
                             :apply 'fixture/seed}}
        failed (run-refresh resolver {} declarations #{})]
    (is (= [[:open :pool] [:open :monitor]] @calls))
    (is (= :degraded (:status failed)))
    (is (= :not-attempted (get-in failed [:outcomes :scan :status])))
    (swap! failures disj [:open :monitor])
    (reset! calls [])
    (let [retried (run-refresh resolver (:state failed) declarations #{})]
      (is (= [[:open :monitor] [:seed :scan]] @calls))
      (is (= :healthy (get-in retried [:state :effects :pool :health])))
      (is (= :applied (:status retried))))))

(deftest failed-close-retains-handle-and-blocks-only-its-dependencies
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        declarations {:pool (resource)
                      :handler (resource :after #{:pool})
                      :independent (resource)}
        applied (run-refresh resolver {} declarations #{})]
    (reset! calls [])
    (reset! failures #{[:close :handler]})
    (let [failed (run-refresh resolver (:state applied) {} #{})]
      (is (= :degraded (get-in failed [:outcomes :handler :status])))
      (is (= :blocked (get-in failed [:outcomes :pool :status])))
      (is (= :removed (get-in failed [:outcomes :independent :status])))
      (is (= {:handle :handler}
             (get-in failed [:state :effects :handler :handle])))
      (reset! failures #{})
      (reset! calls [])
      (let [retried (run-refresh resolver (:state failed) {} #{})]
        (is (= [[:close :handler :handler] [:close :pool :pool]] @calls))
        (is (empty? (get-in retried [:state :effects])))))))

(deftest ordinary-failures-retain-class-and-message
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        failed (run-refresh resolver {}
                            {:pool (resource :open 'fixture/plain-failure)}
                            #{})]
    (is (= "java.lang.RuntimeException"
           (get-in failed [:outcomes :pool :error :class])))
    (is (= "plain open failed"
           (get-in failed [:outcomes :pool :error :message])))))

(deftest cross-owner-publication-triggers-reconcile-and-omission-cleans-up
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        declarations {:jobs (reconcile-effect :trigger-kinds #{:cron/jobs})}
        applied (run-refresh resolver {} declarations #{})]
    (reset! calls [])
    (let [unchanged (run-refresh resolver (:state applied) declarations #{})]
      (is (empty? @calls))
      (run-refresh resolver (:state unchanged) declarations #{:cron/jobs})
      (is (= :converge (ffirst @calls)))
      (reset! calls [])
      (let [removed (run-refresh resolver (:state unchanged) {} #{})]
        (is (= [[:remove :jobs]] @calls))
        (is (empty? (get-in removed [:state :effects])))))))

(deftest runtime-scoped-resource-survives-module-removal-and-closes-at-stop
  (let [calls (atom [])
        failures (atom #{})
        resolver (recording-resolver calls failures)
        declarations {:workers (resource :scope :runtime)}
        applied (run-refresh resolver {} declarations #{})
        removed (run-refresh resolver (:state applied) {} #{})]
    (is (= :retained (get-in removed [:outcomes :workers :status])))
    (is (contains? (get-in removed [:state :effects]) :workers))
    (reset! calls [])
    (let [stopped (engine/refresh
                   {:runtime ::runtime
                    :module-key :fixture/module
                    :resolver resolver
                    :state (:state removed)
                    :declarations {}
                    :runtime-stop? true})]
      (is (= [[:close :workers :workers]] @calls))
      (is (empty? (get-in stopped [:state :effects]))))))
