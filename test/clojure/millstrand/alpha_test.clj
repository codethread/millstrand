(ns millstrand.alpha-test
  "Tests for the blessed millstrand.api.*.alpha surfaces (batch, graph, hooks)."
  (:require [millstrand.api.batch.alpha :as batch]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.patterns.alpha :as patterns]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [millstrand.repl]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.core-registry :as cr]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.test-support :as test-support]))

(defn reset-repl-state! []
  (reset! (var-get (ns-resolve 'millstrand.repl 'active-config-dir))
          (var-get (ns-resolve 'millstrand.repl 'no-connection))))

(defn with-runtime [f]
  (test-support/with-runtime
    (fn [rt _config-dir]
      (try
        (f rt)
        (finally
          (reset-repl-state!))))))

;; Namespace-level on purpose: hooks are registered by symbol and resolved
;; to top-level vars, so capture state cannot be a per-test local. Reset by
;; the :each fixture below; the runner never splits a namespace across threads.
(def alpha-hook-contexts (atom []))

(use-fixtures :each (fn [f] (reset! alpha-hook-contexts []) (f)))

(defn test-hook [_ctx]
  :ok)

(defn capture-alpha-hook [ctx]
  (swap! alpha-hook-contexts conj ctx)
  :ok)

(defn normalize-alpha-hook [ctx]
  (swap! alpha-hook-contexts conj ctx)
  {:hook/value (:hook/value ctx)})

(deftest alpha-helpers-route-directly-inside-daemon-runtime
  (with-runtime
    (fn [rt]
      (weaver/init rt)
      (let [feature (weaver/add! rt {:title "Feature" :attributes {:kind "feature"}})
            task (weaver/add! rt {:title "Task" :attributes {:owner "agent"}})]
        (weaver/update! rt (:id feature) {:edges [{:type "parent-of" :to (:id task)}]})
        (graph/register-query! rt 'agent-owned [:= [:attr :owner] "agent"])
        (is (= [(:id task)] (graph/query-ids rt 'agent-owned {})))
        (is (= [(:id task)] (mapv :id (graph/strands-by-ids rt [(:id task) (:id task)]))))
        (is (= {:strand-id (:id task)
                :keys ["owner"]
                :archived? true
                :changed 1}
               (weaver/archive-attributes! rt (:id task) [:owner])))
        (is (= [] (weaver/list rt [:= [:attr :owner] "agent"] {})))
        (is (= {:owner "agent"} (:attributes (weaver/show rt (:id task)))))
        (is (= {:strand-id (:id task)
                :keys ["owner"]
                :archived? false
                :changed 1}
               (weaver/unarchive-attributes! rt (:id task) [:owner])))
        (weaver/update! rt (:id task) {:attributes {:payload (str/join (repeat 1100 "x"))}})
        (let [lean-task (first (filter #(= (:id task) (:id %))
                                       (weaver/ready-lean rt 1024)))
              payload (get-in lean-task [:attributes :payload])]
          (is (true? (:millstrand/omitted payload)))
          (is (pos-int? (:bytes payload))))
        (let [lean-task (first (filter #(= (:id task) (:id %))
                                       (weaver/list-lean rt 1024)))
              payload (get-in lean-task [:attributes :payload])]
          (is (true? (:millstrand/omitted payload)))
          (is (pos-int? (:bytes payload))))
        (weaver/update! rt (:id task) {:attributes {:payload nil}})
        (is (= [(:id feature)] (graph/ancestor-root-ids rt [(:id task)] {})))
        (is (= #{(:id feature) (:id task)}
               (set (map :id (:strands (graph/subgraph rt [(:id feature)]))))))
        (is (= {:key :policy
                :types #{:payload/received}
                :fn 'millstrand.alpha-test/test-hook
                :order 5
                :metadata {:doc "policy"}}
               (hooks/register-hook! rt :policy #{:payload/received} 'millstrand.alpha-test/test-hook {:order 5 :doc "policy"})))
        (is (= [{:key :policy
                 :types #{:payload/received}
                 :fn 'millstrand.alpha-test/test-hook
                 :order 5
                 :metadata {:doc "policy"}}]
               (hooks/hooks rt)))
        (is (= {:unregistered :policy} (hooks/unregister-hook! rt :policy)))
        (let [batch-result (batch/apply! rt {:refs {:feature (:id feature)
                                                    :task (:id task)}
                                             :strands [{:ref :task
                                                        :state "closed"
                                                        :attributes {:owner "agent" :phase "batched"}}
                                                       {:ref :batch-task
                                                        :title "Batch task"
                                                        :attributes {:owner "agent"}}]
                                             :edges [{:op :upsert
                                                      :from :batch-task
                                                      :to :feature
                                                      :type "depends-on"}]})
              created (first (:created batch-result))
              updated (first (:updated batch-result))]
          (is (= (:id created) (get-in batch-result [:refs :batch-task])))
          (is (= {:title "Batch task" :state "active" :attributes {:owner "agent"}}
                 (select-keys created [:title :state :attributes])))
          (is (= {:id (:id task)
                  :state "closed"
                  :attributes {:owner "agent" :phase "batched"}}
                 (select-keys (:after updated) [:id :state :attributes]))))))))

(s/def ::name-forms-pattern-input map?)

(defn name-forms-pattern
  "Fixture pattern fn for the lookup-name-forms test; ignores its input."
  [_]
  [{:ref 'only :title "name-forms"}])

(deftest pattern-lookups-accept-cli-string-forms
  (with-runtime
    (fn [rt]
      (patterns/register-pattern! rt 'name-forms 'millstrand.alpha-test/name-forms-pattern
                                  ::name-forms-pattern-input)
      (let [entry (patterns/resolve-pattern rt 'name-forms)]
        (is (not (contains? entry :doc)))
        (is (s/valid? ::specs/pattern-entry entry))
        (is (= entry (patterns/resolve-pattern rt "name-forms"))
            "a raw CLI string resolves the same registered entry")
        (is (= entry (patterns/resolve-pattern rt " :name-forms "))
            "trimming and the leading-colon keyword form match query lookup rules")
        (is (= entry (patterns/resolve-pattern rt :name-forms))
            "a keyword resolves the same entry"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be blank"
                            (patterns/resolve-pattern rt "  "))
          "a blank string fails loudly instead of resolving")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern not found"
                            (patterns/resolve-pattern rt "absent"))
          "an unknown name still fails with the pattern-not-found contract"))))

(deftest public-pattern-readers-validate-planted-entries
  (with-runtime
    (fn [rt]
      (cr/replace-owner! (access/pattern-store rt) :malformed
                         {:layer :workspace
                          :entries {"bad" {:name "bad"}}
                          :overrides #{}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern registry entry is invalid"
                            (patterns/patterns rt)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pattern registry entry is invalid"
                            (patterns/resolve-pattern rt :bad))))))

(deftest readiness-where-is-public-data
  (is (vector? graph/readiness-where))
  (is (= [:and [:= :state "active"]
          [:not [:edge/out "depends-on" [:= :state "active"]]]]
         graph/readiness-where)))

(deftest readiness-where-matches-ready-path
  (with-runtime
    (fn [rt]
      (weaver/init rt)
      (let [ready (weaver/add! rt {:title "Ready"})
            active-target (weaver/add! rt {:title "Active dependency"})
            blocked (weaver/add! rt {:title "Blocked"})
            inactive (weaver/add! rt {:title "Inactive" :state "closed"})]
        (weaver/update! rt (:id blocked)
                        {:edges [{:type "depends-on" :to (:id active-target)}]})
        (let [composed-where (graph/conjoin-where [:= :state "active"]
                                                  graph/readiness-where)
              ready-ids (set (map :id (weaver/ready rt)))
              where-ids (set (map :id (weaver/list rt composed-where {})))]
          (is (= #{(:id ready) (:id active-target)} ready-ids))
          (is (not (contains? ready-ids (:id blocked))))
          (is (not (contains? ready-ids (:id inactive))))
          (is (= ready-ids where-ids)))))))

(deftest conjoin-where-overlays-an-extra-clause
  (let [bare [:= [:attr :owner] "agent"]
        detailed {:where [:= [:attr :owner] [:param :owner]] :params [:owner]}]
    (is (= [:and bare [:= :state "closed"]]
           (graph/conjoin-where bare [:= :state "closed"]))
        "a bare-vector definition conjoins into the canonical [:and ...] shape")
    (is (= [:and [:= [:attr :owner] [:param :owner]] [:= :state "closed"]]
           (graph/conjoin-where detailed [:= :state "closed"] {:owner "agent"}))
        "a parameterized definition substitutes its where-expression, params intact")
    (is (= bare (graph/conjoin-where bare nil))
        "a nil overlay returns the definition unchanged")
    (is (= detailed (graph/conjoin-where detailed nil {:owner "agent"}))
        "a nil overlay preserves a map definition and its declared :params")
    (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query parameters"
                                   (graph/conjoin-where detailed nil {:nope "x"})))]
      (is (= {:params [:nope] :declared [:owner]} (ex-data ex))
          "a nil overlay validates params against the declared set"))))

(deftest coerce-declared-params-restricts-and-rejects-unknown
  (let [detailed {:where [:= [:attr :owner] [:param :owner]] :params [:owner]}
        bare [:= [:attr :owner] "agent"]]
    (is (= {:owner "agent"} (graph/coerce-declared-params detailed {"owner" "agent"}))
        "a declared string param round-trips to its keyword name")
    (is (= {} (graph/coerce-declared-params detailed {}))
        "no supplied params yields an empty map")
    (is (= {} (graph/coerce-declared-params detailed nil))
        "nil supplied params normalizes to an empty map")
    (is (= {} (graph/coerce-declared-params bare {}))
        "a definition with no declared :params accepts an empty map")
    (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                   #"Unknown query parameters"
                                   (graph/coerce-declared-params detailed {"nope" "x"})))]
      (is (= {:params ["nope"] :declared [:owner]} (ex-data ex))
          "the failure carries the offending name and the full declared set"))))

(deftest referenced-params-reads-param-references
  (is (= [:owner]
         (graph/referenced-params {:where [:= [:attr :owner] [:param :owner]]
                                   :params [:owner]}))
      "a map definition reports its where-expression's references")
  (is (= [:owner :phase]
         (graph/referenced-params [:and [:= [:attr :owner] [:param :owner]]
                                   [:= [:attr :phase] [:param :phase]]]))
      "a bare vector reports references in first-seen order")
  (is (= [] (graph/referenced-params [:= [:attr :owner] "agent"]))
      "a definition with no references reports none"))

(deftest query-helpers-reject-malformed-definition-seams
  (doseq [[helper invoke]
          [["conjoin-where" #(graph/conjoin-where % nil)]
           ["coerce-declared-params" #(graph/coerce-declared-params % {})]
           ["referenced-params" graph/referenced-params]]
          query-def [{:where [:= [:attr :owner] [:param :owner]] :params ["owner"]}
                     {:where '(:= [:attr :owner] "agent") :params [:owner]}]]
    (let [ex (try
               (invoke query-def)
               nil
               (catch clojure.lang.ExceptionInfo error error))]
      (is ex (str helper " rejects malformed definitions at its seam"))
      (is (= query-def (:query-def (ex-data ex)))
          (str helper " reports the offending definition"))
      (is (= "a vector or map with vector :where and optional sequential keyword :params"
             (:contract (ex-data ex)))
          (str helper " reports its documented definition contract")))))

(deftest current-runtime-fails-loudly-without-ambient-runtime
  ;; This namespace runs in the parallel batch, whose tests start unpublished
  ;; runtimes only; with the thread-local binding cleared, the public entry point
  ;; must see no ambient runtime and fail loudly.
  (binding [weaver-runtime/*runtime* nil]
    (is (nil? (current/runtime-or-nil)))
    (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                   #"No active Millstrand weaver runtime"
                                   (current/runtime)))]
      (is (= :absent (:millstrand/runtime (ex-data ex)))))))

(deftest current-with-runtime-scopes-the-ambient-runtime
  ;; The nil outer binding isolates the assertion from any published runtime, so
  ;; the reads below can only see what the scoping forms bound.
  (binding [weaver-runtime/*runtime* nil]
    (is (= [::rt ::rt]
           (current/with-runtime ::rt
             [(current/runtime-or-nil) (current/runtime)])))
    (is (= ::value (current/with-runtime* ::rt (constantly ::value))))
    (is (nil? (current/runtime-or-nil))
        "the scoped binding unwinds with the dynamic extent")))

(deftest current-with-runtime*-rejects-nil-runtime
  (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                 #"Cannot scope a nil Millstrand runtime"
                                 (current/with-runtime* nil (constantly :never))))]
    (is (= :nil (:millstrand/runtime (ex-data ex))))))
