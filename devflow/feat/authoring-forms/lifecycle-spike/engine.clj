(ns skein.lifecycle-spike.engine
  "Bounded lifecycle-engine prototype for the authoring-forms feasibility spike."
  (:require [clojure.set :as set]))

(def ^:private kinds #{:seed :resource :reconcile})
(def ^:private scopes #{:module :runtime})
(def ^:private common-keys #{:kind :after})
(def ^:private kind-keys
  {:seed #{:apply}
   :resource #{:open :close :scope}
   :reconcile #{:read-desired :read-actual :apply :on-removed :trigger-kinds}})

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- callable-symbols
  [declaration]
  (select-keys declaration
               (case (:kind declaration)
                 :seed [:apply]
                 :resource [:open :close]
                 :reconcile [:read-desired :read-actual :apply :on-removed])))

(defn- validate-declaration!
  [effect-id declaration]
  (when-not (keyword? effect-id)
    (fail! "Lifecycle effect id must be a keyword"
           {:effect/id effect-id :effect/phase :validate}))
  (when-not (map? declaration)
    (fail! "Lifecycle declaration must be a map"
           {:effect/id effect-id :effect/phase :validate
            :offending-value declaration}))
  (let [kind (:kind declaration)
        allowed (set/union common-keys (get kind-keys kind #{}))
        unknown (set/difference (set (keys declaration)) allowed)]
    (when-not (contains? kinds kind)
      (fail! "Unknown lifecycle effect kind"
             {:effect/id effect-id :effect/kind kind
              :effect/phase :validate :allowed kinds}))
    (when (seq unknown)
      (fail! "Unknown lifecycle declaration keys"
             {:effect/id effect-id :effect/kind kind
              :effect/phase :validate :offending-value unknown
              :allowed allowed}))
    (when-not (set? (get declaration :after #{}))
      (fail! "Lifecycle dependencies must be a set"
             {:effect/id effect-id :effect/kind kind
              :effect/phase :validate :offending-value (:after declaration)}))
    (doseq [[slot callable] (callable-symbols declaration)]
      (when-not (qualified-symbol? callable)
        (fail! "Lifecycle callable must be a qualified symbol"
               {:effect/id effect-id :effect/kind kind :effect/callable callable
                :effect/callable-slot slot :effect/phase :validate})))
    (case kind
      :seed
      (when-not (= #{:apply} (set (keys (callable-symbols declaration))))
        (fail! "Seed requires one apply callable"
               {:effect/id effect-id :effect/kind kind :effect/phase :validate}))

      :resource
      (do
        (when-not (= #{:open :close}
                     (set (keys (callable-symbols declaration))))
          (fail! "Resource requires open and close callables"
                 {:effect/id effect-id :effect/kind kind :effect/phase :validate}))
        (when-not (contains? scopes (get declaration :scope :module))
          (fail! "Unknown resource scope"
                 {:effect/id effect-id :effect/kind kind :effect/phase :validate
                  :offending-value (:scope declaration) :allowed scopes})))

      :reconcile
      (when-not (= #{:read-desired :read-actual :apply :on-removed}
                   (set (keys (callable-symbols declaration))))
        (fail! "Reconcile effect requires readers, apply, and removal callables"
               {:effect/id effect-id :effect/kind kind :effect/phase :validate}))
      nil)))

(defn- visit
  [declarations temporary permanent order effect-id]
  (cond
    (contains? @permanent effect-id) nil
    (contains? @temporary effect-id)
    (fail! "Lifecycle dependency cycle"
           {:effect/id effect-id :effect/phase :validate})
    :else
    (do
      (swap! temporary conj effect-id)
      (doseq [dependency (sort (get-in declarations [effect-id :after] #{}))]
        (when-not (contains? declarations dependency)
          (fail! "Lifecycle dependency is missing"
                 {:effect/id effect-id :effect/dependency dependency
                  :effect/phase :validate}))
        (visit declarations temporary permanent order dependency))
      (swap! temporary disj effect-id)
      (swap! permanent conj effect-id)
      (swap! order conj effect-id))))

(defn validate!
  "Validate declarations and return their deterministic dependency order."
  [declarations]
  (when-not (map? declarations)
    (fail! "Lifecycle declarations must be a map"
           {:effect/phase :validate :offending-value declarations}))
  (doseq [[effect-id declaration] declarations]
    (validate-declaration! effect-id declaration))
  (let [temporary (atom #{})
        permanent (atom #{})
        order (atom [])]
    (doseq [effect-id (sort (keys declarations))]
      (visit declarations temporary permanent order effect-id))
    @order))

(defn plan
  "Return a side-effect-free transition plan for retained state and declarations."
  [state declarations changed-kinds]
  (let [order (validate! declarations)
        retained (:effects state {})
        previous-ids (set (keys retained))
        next-ids (set (keys declarations))
        removed (set/difference previous-ids next-ids)]
    {:apply (filterv #(not (contains? previous-ids %)) order)
     :preserve (filterv #(and (= (get-in retained [% :declaration])
                                 (get declarations %))
                              (= :healthy (get-in retained [% :health]))
                              (not (and (= :reconcile (get-in declarations [% :kind]))
                                        (seq (set/intersection
                                              (get-in declarations [% :trigger-kinds] #{})
                                              changed-kinds)))))
                        order)
     :retry (filterv #(and (= (get-in retained [% :declaration])
                              (get declarations %))
                           (= :degraded (get-in retained [% :health])))
                     order)
     :replace (filterv #(and (contains? previous-ids %)
                             (not= (get-in retained [% :declaration])
                                   (get declarations %)))
                       order)
     :reconcile (filterv #(and (= :reconcile (get-in declarations [% :kind]))
                               (= (get-in retained [% :declaration])
                                  (get declarations %))
                               (= :healthy (get-in retained [% :health]))
                               (seq (set/intersection
                                     (get-in declarations [% :trigger-kinds] #{})
                                     changed-kinds)))
                         order)
     :remove (filterv removed
                      (reverse (validate! (into {}
                                                (map (fn [effect-id]
                                                       [effect-id
                                                        (get-in retained
                                                                [effect-id :declaration])]))
                                                previous-ids))))}))

(defn- resolve-callables!
  [resolver effect-id declaration]
  (into {}
        (map (fn [[slot callable]]
               (let [resolved (get resolver callable)]
                 (when-not (ifn? resolved)
                   (fail! "Lifecycle callable does not resolve to a function"
                          {:effect/id effect-id :effect/kind (:kind declaration)
                           :effect/callable callable :effect/callable-slot slot
                           :effect/phase :resolve}))
                 [slot resolved])))
        (callable-symbols declaration)))

(defn- resolve-declarations!
  [resolver declarations]
  (into {}
        (map (fn [[effect-id declaration]]
               [effect-id (resolve-callables! resolver effect-id declaration)]))
        declarations))

(defn- data-result!
  [effect-id phase value]
  (when-not (or (nil? value)
                (string? value) (boolean? value) (number? value)
                (keyword? value) (symbol? value)
                (and (coll? value)
                     (every? #(or (nil? %) (string? %) (boolean? %) (number? %)
                                  (keyword? %) (symbol? %) (coll? %))
                             (tree-seq coll? seq value))))
    (fail! "Lifecycle result must be data-first"
           {:effect/id effect-id :effect/phase phase :offending-value value}))
  value)

(defn- base-context
  [runtime module-key effect-id declaration phase refresh-result]
  {:runtime runtime
   :module/key module-key
   :effect/id effect-id
   :effect/kind (:kind declaration)
   :effect/declaration declaration
   :effect/phase phase
   :refresh/result refresh-result})

(defn- exception-data
  [throwable]
  (cond-> {:class (.getName (class throwable))
           :message (ex-message throwable)}
    (ex-data throwable) (assoc :data (ex-data throwable))
    (ex-cause throwable) (assoc :cause {:class (.getName (class (ex-cause throwable)))
                                        :message (ex-message (ex-cause throwable))})))

(defn- apply-effect
  [runtime module-key refresh-result effect-id declaration callables]
  (let [context #(base-context runtime module-key effect-id declaration % refresh-result)]
    (try
      (case (:kind declaration)
        :seed
        (let [result (data-result! effect-id :apply
                                   ((:apply callables) (context :apply)))]
          {:declaration declaration :callables callables :health :healthy
           :status :applied :result result})

        :resource
        (let [handle ((:open callables) (context :open))]
          {:declaration declaration :callables callables :health :healthy
           :status :applied :handle handle})

        :reconcile
        (let [desired ((:read-desired callables) (context :read-desired))
              actual ((:read-actual callables) (context :read-actual))
              result ((:apply callables)
                      (assoc (context :apply) :desired desired :actual actual))]
          {:declaration declaration :callables callables :health :healthy
           :status :applied :result (data-result! effect-id :apply result)}))
      (catch Throwable throwable
        {:declaration declaration :callables callables :health :degraded
         :status :failed :phase (case (:kind declaration)
                                  :resource :open
                                  :apply)
         :error (exception-data throwable)}))))

(defn- remove-effect
  [runtime module-key refresh-result effect-id retained runtime-stop?]
  (let [{:keys [declaration callables handle]} retained
        scope (get declaration :scope :module)]
    (if (and (= :runtime scope) (not runtime-stop?))
      (assoc retained :status :retained)
      (try
        (let [context (base-context runtime module-key effect-id declaration
                                    :remove refresh-result)
              result
              (case (:kind declaration)
                :seed nil
                :resource ((:close callables) (assoc context :resource handle))
                :reconcile ((:on-removed callables) context))]
          {:status :removed
           :result (data-result! effect-id :remove result)})
        (catch Throwable throwable
          (assoc retained :health :degraded :status :failed :phase :remove
                 :error (exception-data throwable)))))))

(defn- run-removals
  [runtime module-key state refresh-result removal-order runtime-stop?]
  (reduce
   (fn [{:keys [effects blocked] :as acc} effect-id]
     (let [dependents (set (for [[candidate {:keys [declaration]}] effects
                                 :when (contains? (:after declaration #{}) effect-id)]
                             candidate))]
       (if (seq (set/intersection blocked dependents))
         (-> acc
             (update :blocked conj effect-id)
             (assoc-in [:outcomes effect-id]
                       {:status :blocked :phase :remove}))
         (let [result (remove-effect runtime module-key refresh-result effect-id
                                     (get effects effect-id) runtime-stop?)]
           (cond
             (= :failed (:status result))
             (-> acc
                 (update :blocked conj effect-id)
                 (assoc-in [:effects effect-id] result)
                 (assoc-in [:outcomes effect-id]
                           (dissoc result :handle :callables :declaration)))

             (= :retained (:status result))
             (-> acc
                 (assoc-in [:effects effect-id] result)
                 (assoc-in [:outcomes effect-id]
                           (dissoc result :handle :callables :declaration)))

             :else
             (-> acc
                 (update :effects dissoc effect-id)
                 (assoc-in [:outcomes effect-id] result)))))))
   {:effects (:effects state {}) :outcomes {} :blocked #{}}
   removal-order))

(defn- run-applications
  [runtime module-key resolved-callables declarations refresh-result
   action-order removal-result]
  (reduce
   (fn [{:keys [halted?] :as acc} effect-id]
     (if halted?
       (assoc-in acc [:outcomes effect-id]
                 {:status :not-attempted :phase :apply})
       (let [declaration (get declarations effect-id)
             result (apply-effect runtime module-key refresh-result effect-id declaration
                                  (get resolved-callables effect-id))]
         (-> acc
             (assoc :halted? (= :failed (:status result)))
             (assoc-in [:effects effect-id] result)
             (assoc-in [:outcomes effect-id]
                       (dissoc result :handle :callables :declaration))))))
   removal-result
   action-order))

(defn- preserve-effects
  [effects previous-effects preserved]
  (reduce (fn [current effect-id]
            (if (contains? current effect-id)
              current
              (assoc current effect-id (get previous-effects effect-id))))
          effects
          preserved))

(defn refresh
  "Execute one prototype lifecycle refresh after an asserted publication point."
  [{:keys [runtime module-key resolver state declarations changed-kinds
           published? runtime-stop?]
    :or {state {} declarations {} changed-kinds #{} published? true}}]
  (let [transition-plan (plan state declarations changed-kinds)
        resolved-callables (resolve-declarations! resolver declarations)
        _ (when-not published?
            (fail! "Lifecycle execution requires completed contribution publication"
                   {:module/key module-key :effect/phase :publication}))
        refresh-result {:publication/status :published}
        close-ids (set (concat (:remove transition-plan)
                               (:replace transition-plan)))
        removal-order (filterv close-ids
                               (reverse
                                (validate!
                                 (into {}
                                       (map (fn [[effect-id retained]]
                                              [effect-id (:declaration retained)]))
                                       (:effects state {})))))
        removed (run-removals runtime module-key state refresh-result removal-order
                              runtime-stop?)
        action-ids (set (concat (:apply transition-plan)
                                (:replace transition-plan)
                                (:retry transition-plan)
                                (:reconcile transition-plan)))
        actions (filterv action-ids (validate! declarations))
        applied (run-applications runtime module-key resolved-callables declarations
                                  refresh-result actions removed)
        effects (preserve-effects (:effects applied) (:effects state {})
                                  (:preserve transition-plan))]
    {:state {:effects effects}
     :plan transition-plan
     :outcomes (:outcomes applied)
     :status (if (some #(= :failed (:status %)) (vals (:outcomes applied)))
               :degraded
               :applied)}))
