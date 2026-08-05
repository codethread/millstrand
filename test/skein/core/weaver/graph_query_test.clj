(ns skein.core.weaver.graph-query-test
  "Tests for the weaver runtime: transport, op dispatch, and lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.graph.alpha :as graph]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db :as db]
            [skein.core.db-test :as db-test]
            [skein.spools.test-support :as test-support]))

(def delete-tree! test-support/delete-tree!)

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(deftest weaver-api-delegates-to-db-and-normalizes-results
  (with-runtime
    (fn [rt _]
      (is (= {:database "initialized"} (weaver/init rt)))
      (let [design (weaver/add! rt {:title "Design" :state "closed" :attributes {:priority "high"}})
            docs (weaver/add! rt {:title "Docs" :attributes {:owner "agent"}})]
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:relation "blocks" :acyclic true} (weaver/declare-acyclic-relation! rt "blocks")))
        (is (= ["blocks" "depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (= {:priority "high"} (:attributes design)))
        (weaver/update! rt (:id docs) {:attributes {:phase "write"}
                                       :edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent" :phase "write"} (:attributes (weaver/show rt (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (weaver/list rt)))))
        (is (= [(:id docs)] (mapv :id (weaver/ready rt))))))))

(deftest weaver-query-registry-add-list-and-resolve
  (with-runtime
    (fn [rt _]
      (let [owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}]
        (is (= {"mine" owner-query} (graph/register-query! rt 'mine owner-query)))
        (is (= owner-query (graph/resolve-query rt :mine)))
        (is (= {"mine" owner-query} (graph/queries rt)))
        (is (= {"mine" owner-query}
               (graph/queries rt)))))))

(deftest weaver-query-registry-accepts-parameterized-in-queries
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [agent (weaver/add! rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add! rt {:title "Human" :attributes {:owner "human"}})
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}]
        (is (= {"owners" owners-query} (graph/register-query! rt 'owners owners-query)))
        (is (= [(:id agent)] (mapv :id (weaver/list-query rt :owners {:owners ["agent"]}))))
        (is (= #{(:id agent) (:id human)}
               (set (map :id (weaver/list-query rt :owners {:owners ["agent" "human"]})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners "agent"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":in values must be a non-empty collection"
                              (weaver/list-query rt :owners {:owners []})))))))

(deftest weaver-query-registry-accepts-edge-predicates
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [blocker (weaver/add! rt {:title "Blocker"})
            blocked (weaver/add! rt {:title "Blocked" :attributes {:owner "agent"}})
            edge-query {:params [:relation]
                        :where [:edge/out [:param :relation] [:= :state "active"]]}]
        (weaver/update! rt (:id blocked) {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {"blocked" edge-query} (graph/register-query! rt 'blocked edge-query)))
        (is (= [(:id blocked)] (mapv :id (weaver/list-query rt :blocked {:relation "depends-on"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested edge predicates"
                              (graph/register-query! rt 'bad-edge
                                                     [:edge/out "depends-on"
                                                      [:edge/in "depends-on" [:= :state "active"]]])))
        (is (= {"blocked" edge-query} (graph/queries rt)))))))

(deftest weaver-query-introspection-api-describes-registered-definitions
  (with-runtime
    (fn [rt _]
      (let [open-query [:= :state "active"]
            owner-query {:params [:owner]
                         :where [:= [:attr :owner] [:param :owner]]}
            declared-unused-query {:params [:owner :unused]
                                   :where [:= [:attr :owner] [:param :owner]]}
            owners-query {:params [:owners]
                          :where [:in [:attr :owner] [:param :owners]]}
            literal-query [:= [:attr :payload] [[:param :literal-value]]]
            relation-query {:params [:relation :owner]
                            :where [:edge/out [:param :relation]
                                    [:and
                                     [:= [:attr :owner] [:param :owner]]
                                     [:= :state "active"]]]}]
        (doseq [[query-name query-def] {:open open-query
                                        :mine owner-query
                                        :declared-unused declared-unused-query
                                        :owners owners-query
                                        :literal literal-query
                                        :blocked relation-query}]
          (graph/register-query! rt query-name query-def))

        (is (= {:name "mine"
                :params [:owner]
                :referenced-params [:owner]
                :where (:where owner-query)
                :definition owner-query
                :where-form (pr-str (:where owner-query))
                :definition-form (pr-str owner-query)
                :summary (str "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` "
                              "and pass runtime values with repeated `--param key=value` arguments.")}
               (graph/query-explain rt :mine)))

        (try
          (graph/query-explain rt :missing)
          (is false "expected query explain missing query failure")
          (catch clojure.lang.ExceptionInfo e
            (is (= "Query not found" (ex-message e)))
            (is (= {:query :missing
                    :canonical-query "missing"
                    :available ["blocked" "declared-unused" "literal" "mine" "open" "owners"]}
                   (ex-data e)))))))))

(deftest weaver-runtime-transformation-primitives
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [agent (weaver/add! rt {:title "Agent" :attributes {:owner "agent"}})
            human (weaver/add! rt {:title "Human" :attributes {:owner "human"}})
            feature (weaver/add! rt {:title "Feature" :attributes {:kind "feature"}})]
        (weaver/update! rt (:id feature) {:edges [{:type "parent-of" :to (:id agent)}
                                                  {:type "parent-of" :to (:id human)}]})
        (graph/register-query! rt 'agent-owned {:params [:owner]
                                                :where [:= [:attr :owner] [:param :owner]]})
        (is (= [(:id agent)] (graph/query-ids rt 'agent-owned {:owner "agent"})))
        (is (= [(:id human)] (graph/query-ids rt [:= [:attr :owner] "human"] {})))
        (is (= [(:id human) (:id agent)]
               (mapv :id (graph/strands-by-ids rt [(:id human) (:id agent) (:id human)]))))
        (is (= [(:id feature)] (graph/ancestor-root-ids rt [(:id agent)])))
        (is (= #{(:id feature) (:id agent) (:id human)}
               (set (map :id (:strands (graph/subgraph rt [(:id feature)]))))))))))

(deftest weaver-query-registry-fails-clearly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Query not found"
                            (graph/resolve-query rt 'missing)))
      (try
        (graph/resolve-query rt 'missing)
        (is false "expected missing query error")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'missing (:query (ex-data e))))
          (is (= "missing" (:canonical-query (ex-data e))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"simple symbols or keywords"
                            (graph/register-query! rt 'user/mine [:= :state "active"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown query operator"
                            (graph/register-query! rt :broken [:unknown :state "active"])))
      (graph/register-query! rt :ok [:= :state "active"])
      (is (= {"ok" [:= :state "active"]} (graph/queries rt))))))

(deftest weaver-api-update-preserves-domain-errors-and-rolls-back
  (with-runtime
    (fn [rt _]
      (weaver/init rt)
      (let [source (weaver/add! rt {:title "Source"})
            target (weaver/add! rt {:title "Target"})
            other-target (weaver/add! rt {:title "Other target"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Strand not found"
                              (weaver/update! rt "missing" {:edges [{:type "depends-on" :to (:id target)}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"non-blank"
                              (weaver/update! rt (:id source) {:title ""
                                                               :edges [{:type "depends-on" :to (:id target)}]})))
        (is (empty? (db/execute! (:datasource rt) ["SELECT 1 FROM strand_edges WHERE from_strand_id = ?" (:id source)])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              (re-pattern (:id target))
                              (weaver/add! rt {:title "Malformed run"
                                               :edges [{:type "serves" :to (:id target)}
                                                       {:type "serves" :to (:id other-target)}]})))
        (is (nil? (some #(when (= "Malformed run" (:title %)) %) (weaver/list rt))))
        (weaver/update! rt (:id source) {:edges [{:type "serves" :to (:id target)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              (re-pattern (:id target))
                              (weaver/update! rt (:id source)
                                              {:title "Must roll back"
                                               :edges [{:type "serves" :to (:id other-target)}]})))
        (is (= "Source" (:title (weaver/show rt (:id source)))))
        (is (= [(:id target)]
               (mapv :to_strand_id
                     (db/execute! (:datasource rt)
                                  ["SELECT to_strand_id
                                    FROM strand_edges
                                    WHERE from_strand_id = ? AND edge_type = 'serves'"
                                   (:id source)]))))))))
