(ns millstrand.core.query-compile-test
  "Tests for structural query compilation invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.query :as query]))

(defn compiled [expr]
  (query/compile-query expr {}))

(defn attr-exists-sql [predicate]
  (str "EXISTS (SELECT 1 FROM attributes AS a"
       " WHERE a.strand_id = t.id"
       " AND a.archived = 0"
       " AND a.key = ?"
       " AND " predicate
       ")"))

(deftest attr-keys-compile-to-row-backed-exists-predicates
  (testing "single predicate forms bind value path and top-level key"
    (doseq [[expr sql params]
            [[[:= [:attr :owner] "agent"] (attr-exists-sql "json_extract(a.value, ?) = ?") ["owner" "$" "agent"]]
             [[:!= [:attr :owner] "agent"] (attr-exists-sql "json_extract(a.value, ?) <> ?") ["owner" "$" "agent"]]
             [[:< [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) < ?") ["rank" "$" 3]]
             [[:<= [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) <= ?") ["rank" "$" 3]]
             [[:> [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) > ?") ["rank" "$" 3]]
             [[:>= [:attr :rank] 3] (attr-exists-sql "json_extract(a.value, ?) >= ?") ["rank" "$" 3]]
             [[:in [:attr :owner] ["agent" "human"]] (attr-exists-sql "json_extract(a.value, ?) IN (?, ?)") ["owner" "$" "agent" "human"]]
             [[:exists [:attr :owner]] (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL") ["owner" "$"]]
             [[:missing [:attr :owner]] (str "NOT " (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")) ["owner" "$"]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr))))
  (testing "nested attributes use the remaining JSON path inside the stored value"
    (is (= {:sql (attr-exists-sql "json_extract(a.value, ?) = ?")
            :params ["owner" "$.\"name\"" "agent"]}
           (compiled [:= [:attr :owner :name] "agent"]))))
  (testing "logical composition preserves row-backed self-join predicates"
    (is (= {:sql (str "(" (attr-exists-sql "json_extract(a.value, ?) = ?")
                      " AND (NOT "
                      (attr-exists-sql "json_extract(a.value, ?) IS NOT NULL")
                      "))")
            :params ["owner" "$" "agent" "blocked" "$"]}
           (compiled [:and [:= [:attr :owner] "agent"] [:not [:exists [:attr :blocked]]]])))))

(deftest negated-attr-comparisons-compile-through-present-rows
  (testing "negated attr predicates keep missing and archived keys out of the result"
    (doseq [[expr sql params]
            [[[:not [:= [:attr :owner] "agent"]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) = ?)")
              ["owner" "$" "agent"]]
             [[:not [:< [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) < ?)")
              ["rank" "$" 3]]
             [[:not [:!= [:attr :owner] "agent"]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) <> ?)")
              ["owner" "$" "agent"]]
             [[:not [:> [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) > ?)")
              ["rank" "$" 3]]
             [[:not [:>= [:attr :rank] 3]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) >= ?)")
              ["rank" "$" 3]]
             [[:not [:in [:attr :owner] ["agent" "human"]]]
              (attr-exists-sql "NOT (json_extract(a.value, ?) IN (?, ?))")
              ["owner" "$" "agent" "human"]]]]
      (is (= {:sql sql :params params} (compiled expr)) (pr-str expr)))))

(defn- query-titles [ds expr]
  (set (map :title (db/query-strands ds expr))))

(deftest composed-negation-preserves-attr-null-semantics
  (db-test/with-db
    (fn [ds]
      (let [a-matching-b-absent
            (:id (db/add-strand! ds {:title "a matching, b absent"
                                     :attributes {:a 1}}))
            a-nonmatching-b-absent
            (:id (db/add-strand! ds {:title "a nonmatching, b absent"
                                     :attributes {:a 0}}))
            both-matching
            (:id (db/add-strand! ds {:title "both matching"
                                     :attributes {:a 1 :b 2}}))
            both-nonmatching
            (:id (db/add-strand! ds {:title "both nonmatching"
                                     :attributes {:a 0 :b 3}}))
            b-present-nonmatching
            (:id (db/add-strand! ds {:title "b present nonmatching"
                                     :attributes {:a 1 :b 3}}))
            b-archived
            (:id (db/add-strand! ds {:title "b archived"
                                     :attributes {:a 1 :b 3}}))
            ids [a-matching-b-absent
                 a-nonmatching-b-absent
                 both-matching
                 both-nonmatching
                 b-present-nonmatching
                 b-archived]
            attr-and [:and [:= [:attr :a] 1] [:= [:attr :b] 2]]
            attr-or [:or [:= [:attr :a] 1] [:= [:attr :b] 2]]]
        (db/archive-attributes! ds b-archived [:b])

        (testing ":not over :and excludes missing and archived-only keys"
          (is (= #{"a nonmatching, b absent"
                   "both nonmatching"
                   "b present nonmatching"}
                 (query-titles ds [:and [:in :id ids] [:not attr-and]]))))

        (testing ":not over :or requires every negated attribute leaf to be present"
          (is (= #{"both nonmatching"}
                 (query-titles ds [:and [:in :id ids] [:not attr-or]]))))

        (testing "nested :not cancels compositionally"
          (is (= #{"both matching"}
                 (query-titles ds [:and [:in :id ids] [:not [:not attr-and]]]))))))))

(deftest json-null-and-missing-nested-paths-follow-document-semantics
  (db-test/with-db
    (fn [ds]
      (let [json-null-matching-other
            (:id (db/add-strand! ds {:title "json null, other matching"
                                     :attributes {:json-value nil :other 2}}))
            json-null-nonmatching-other
            (:id (db/add-strand! ds {:title "json null, other nonmatching"
                                     :attributes {:json-value nil :other 3}}))
            json-matching
            (:id (db/add-strand! ds {:title "json matching"
                                     :attributes {:json-value 1 :other 2}}))
            json-nonmatching
            (:id (db/add-strand! ds {:title "json nonmatching"
                                     :attributes {:json-value 0 :other 3}}))
            nested-missing-matching-other
            (:id (db/add-strand! ds {:title "nested missing, other matching"
                                     :attributes {:nested {} :other 2}}))
            nested-missing-nonmatching-other
            (:id (db/add-strand! ds {:title "nested missing, other nonmatching"
                                     :attributes {:nested {} :other 3}}))
            nested-matching
            (:id (db/add-strand! ds {:title "nested matching"
                                     :attributes {:nested {:value 1} :other 2}}))
            nested-nonmatching
            (:id (db/add-strand! ds {:title "nested nonmatching"
                                     :attributes {:nested {:value 0} :other 3}}))
            json-ids [json-null-matching-other
                      json-null-nonmatching-other
                      json-matching
                      json-nonmatching]
            nested-ids [nested-missing-matching-other
                        nested-missing-nonmatching-other
                        nested-matching
                        nested-nonmatching]
            json-field [:attr :json-value]
            nested-field [:attr :nested :value]
            matching-other [:= [:attr :other] 2]]
        (testing "stored JSON null is missing, not existing, and not comparable"
          (is (= #{"json matching"}
                 (query-titles ds [:and [:in :id json-ids] [:= json-field 1]])))
          (is (= #{"json matching" "json nonmatching"}
                 (query-titles ds [:and [:in :id json-ids] [:exists json-field]])))
          (is (= #{"json null, other matching" "json null, other nonmatching"}
                 (query-titles ds [:and [:in :id json-ids] [:missing json-field]]))))

        (testing "a missing nested path follows the same semantics"
          (is (= #{"nested matching"}
                 (query-titles ds [:and [:in :id nested-ids] [:= nested-field 1]])))
          (is (= #{"nested matching" "nested nonmatching"}
                 (query-titles ds [:and [:in :id nested-ids] [:exists nested-field]])))
          (is (= #{"nested missing, other matching" "nested missing, other nonmatching"}
                 (query-titles ds [:and [:in :id nested-ids] [:missing nested-field]]))))

        (testing "composed negation retains unknown leaves unless another disjunct is true"
          (doseq [[ids field expected-and expected-or]
                  [[json-ids json-field
                    #{"json null, other nonmatching" "json nonmatching"}
                    #{"json nonmatching"}]
                   [nested-ids nested-field
                    #{"nested missing, other nonmatching" "nested nonmatching"}
                    #{"nested nonmatching"}]]]
            (is (= expected-and
                   (query-titles ds [:and [:in :id ids]
                                     [:not [:and [:= field 1] matching-other]]])))
            (is (= expected-or
                   (query-titles ds [:and [:in :id ids]
                                     [:not [:or [:= field 1] matching-other]]])))))))))

(deftest negated-attr-predicates-require-present-hot-keys
  (db-test/with-db
    (fn [ds]
      (let [eq-nonmatching (:id (db/add-strand! ds {:title "eq nonmatching"
                                                    :attributes {:eq-owner "human"}}))
            eq-matching (:id (db/add-strand! ds {:title "eq matching"
                                                 :attributes {:eq-owner "agent"}}))
            eq-absent (:id (db/add-strand! ds {:title "eq absent"}))
            eq-archived (:id (db/add-strand! ds {:title "eq archived"
                                                 :attributes {:eq-owner "human"}}))
            lt-nonmatching (:id (db/add-strand! ds {:title "lt nonmatching"
                                                    :attributes {:lt-rank 5}}))
            lt-matching (:id (db/add-strand! ds {:title "lt matching"
                                                 :attributes {:lt-rank 2}}))
            lt-absent (:id (db/add-strand! ds {:title "lt absent"}))
            lt-archived (:id (db/add-strand! ds {:title "lt archived"
                                                 :attributes {:lt-rank 5}}))
            in-nonmatching (:id (db/add-strand! ds {:title "in nonmatching"
                                                    :attributes {:in-owner "human"}}))
            in-matching (:id (db/add-strand! ds {:title "in matching"
                                                 :attributes {:in-owner "agent"}}))
            in-absent (:id (db/add-strand! ds {:title "in absent"}))
            in-archived (:id (db/add-strand! ds {:title "in archived"
                                                 :attributes {:in-owner "human"}}))
            neq-nonmatching (:id (db/add-strand! ds {:title "neq nonmatching"
                                                     :attributes {:neq-owner "agent"}}))
            neq-matching (:id (db/add-strand! ds {:title "neq matching"
                                                  :attributes {:neq-owner "human"}}))
            neq-absent (:id (db/add-strand! ds {:title "neq absent"}))
            neq-archived (:id (db/add-strand! ds {:title "neq archived"
                                                  :attributes {:neq-owner "agent"}}))
            gt-nonmatching (:id (db/add-strand! ds {:title "gt nonmatching"
                                                    :attributes {:gt-rank 2}}))
            gt-matching (:id (db/add-strand! ds {:title "gt matching"
                                                 :attributes {:gt-rank 5}}))
            gt-absent (:id (db/add-strand! ds {:title "gt absent"}))
            gt-archived (:id (db/add-strand! ds {:title "gt archived"
                                                 :attributes {:gt-rank 2}}))
            gte-nonmatching (:id (db/add-strand! ds {:title "gte nonmatching"
                                                     :attributes {:gte-rank 2}}))
            gte-matching (:id (db/add-strand! ds {:title "gte matching"
                                                  :attributes {:gte-rank 3}}))
            gte-absent (:id (db/add-strand! ds {:title "gte absent"}))
            gte-archived (:id (db/add-strand! ds {:title "gte archived"
                                                  :attributes {:gte-rank 2}}))]
        (db/archive-attributes! ds eq-archived [:eq-owner])
        (db/archive-attributes! ds lt-archived [:lt-rank])
        (db/archive-attributes! ds in-archived [:in-owner])
        (db/archive-attributes! ds neq-archived [:neq-owner])
        (db/archive-attributes! ds gt-archived [:gt-rank])
        (db/archive-attributes! ds gte-archived [:gte-rank])

        (testing ":not over :="
          (is (= #{"eq nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [eq-nonmatching eq-matching eq-absent eq-archived]]
                                   [:not [:= [:attr :eq-owner] "agent"]]]))))

        (testing ":not over :<"
          (is (= #{"lt nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [lt-nonmatching lt-matching lt-absent lt-archived]]
                                   [:not [:< [:attr :lt-rank] 3]]]))))

        (testing ":not over :in"
          (is (= #{"in nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [in-nonmatching in-matching in-absent in-archived]]
                                   [:not [:in [:attr :in-owner] ["agent"]]]]))))

        (testing ":not over :!="
          (is (= #{"neq nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [neq-nonmatching neq-matching neq-absent neq-archived]]
                                   [:not [:!= [:attr :neq-owner] "agent"]]]))))

        (testing ":not over :>"
          (is (= #{"gt nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [gt-nonmatching gt-matching gt-absent gt-archived]]
                                   [:not [:> [:attr :gt-rank] 3]]]))))

        (testing ":not over :>="
          (is (= #{"gte nonmatching"}
                 (query-titles ds [:and
                                   [:in :id [gte-nonmatching gte-matching gte-absent gte-archived]]
                                   [:not [:>= [:attr :gte-rank] 3]]]))))))))

(deftest attr-keys-are-never-spliced-into-sql
  (let [{:keys [sql params]} (compiled [:= [:attr "owner\") OR 1 = 1 --"] "agent"])]
    (is (not (re-find #"owner|1 = 1" sql)))
    (is (= ["owner\") OR 1 = 1 --" "$" "agent"] params))))
