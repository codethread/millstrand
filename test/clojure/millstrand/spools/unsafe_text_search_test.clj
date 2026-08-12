(ns millstrand.spools.unsafe-text-search-test
  "Tests for the UNSAFE substring-search spool: title and attribute-value hits,
  archived-row visibility, literal metacharacter matching, and the loud
  blank-substring and overflow failures."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]
            [millstrand.spools.unsafe-text-search :as unsafe-text-search]
            [millstrand.test.alpha :as t]))

(deftest authoring-form-publishes-the-complete-search-operation
  (with-runtime
    (fn [rt _]
      (let [source-result
            (test-support/activate-spool!
             rt :millstrand/spools-unsafe-text-search 'millstrand.spools.unsafe-text-search)
            source-entry (first (filter #(= "search" (:name %)) (weaver/ops rt)))
            image-result
            (test-support/activate-spool!
             rt :millstrand/spools-unsafe-text-search 'millstrand.spools.unsafe-text-search
             :load :image)
            image-entry (first (filter #(= "search" (:name %)) (weaver/ops rt)))]
        (is (= :loaded (get-in source-result
                               [:modules :millstrand/spools-unsafe-text-search :source/status])))
        (is (= :image (get-in image-result
                              [:modules :millstrand/spools-unsafe-text-search :source/status])))
        (is (= source-entry image-entry)
            "image replay publishes the source-collected normalized entry")
        (is (= 'millstrand.spools.unsafe-text-search/search-op (:fn source-entry)))
        (is (= 'millstrand.spools.unsafe-text-search (:provenance source-entry)))
        (is (= "search" (get-in source-entry [:arg-spec :op])))
        (is (= :read (get-in source-entry [:arg-spec :hook-class])))
        (is (= :standard (get-in source-entry [:arg-spec :deadline-class])))
        (is (= :collection (get-in source-entry [:returns :type])))
        (is (nil? (ns-resolve 'millstrand.spools.unsafe-text-search 'spool))
            "the forms-only module exposes no legacy entry point"))
      (weaver/add! rt {:title "Search return coverage" :attributes {"topic" "returns"}})
      (let [entries (filterv #(= 'millstrand.spools.unsafe-text-search (:provenance %))
                             (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (set (map (juxt :name (constantly {})) entries))
            value (weaver/op! rt 'search ["returns"])
            _ (t/check-op-return! rt 'search value)
            checked #{["search" {}]}]
        (is (= [] missing))
        (is (= #{} (set/difference required checked)))))))

(deftest search-hits-titles-and-attribute-values
  (with-runtime
    (fn [rt _]
      (let [design (weaver/add! rt {:title "Design the payments flow" :attributes {"topic" "billing"}})]
        (weaver/add! rt {:title "Unrelated work" :attributes {"topic" "shipping"}})
        (testing "a title substring hit carries no attribute key"
          (let [rows (unsafe-text-search/search rt {:substring "payments"})]
            (is (= [{:id (:id design) :attr-key nil}]
                   (mapv #(select-keys % [:id :attr-key]) rows)))))
        (testing "an attribute-value hit carries the matching key"
          (let [rows (unsafe-text-search/search rt {:substring "billing"})]
            (is (= 1 (count rows)))
            (is (= (:id design) (:id (first rows))))
            (is (= "topic" (:attr-key (first rows))))
            (is (str/includes? (:snippet (first rows)) "billing"))))))))

(deftest attr-key-scope-searches-only-that-attribute-and-skips-titles
  (with-runtime
    (fn [rt _]
      (let [a (weaver/add! rt {:title "shared token here" :attributes {"owner" "shared token"}})]
        (weaver/add! rt {:title "other" :attributes {"note" "shared token"}})
        (testing "--attr-key restricts to one attribute key and drops the title branch"
          (let [rows (unsafe-text-search/search rt {:substring "shared token" :attr-key "owner"})]
            (is (= [{:id (:id a) :attr-key "owner"}]
                   (mapv #(select-keys % [:id :attr-key]) rows)))))))))

(deftest archived-rows-are-invisible-by-default-and-visible-with-archived
  (with-runtime
    (fn [rt _]
      (let [strand (weaver/add! rt {:title "Old session" :attributes {"transcript" "secretword"}})]
        (weaver/archive-attributes! rt (:id strand) ["transcript"])
        (testing "an archived attribute value is invisible to the query language and to a default search"
          (is (empty? (unsafe-text-search/search rt {:substring "secretword"}))))
        (testing "--archived opts the cold row back in"
          (let [rows (unsafe-text-search/search rt {:substring "secretword" :archived? true})]
            (is (= [{:id (:id strand) :attr-key "transcript"}]
                   (mapv #(select-keys % [:id :attr-key]) rows)))))))))

(deftest substrings-match-literally
  (with-runtime
    (fn [rt _]
      (let [pct (weaver/add! rt {:title "Rollout 50% done"})]
        (weaver/add! rt {:title "Rollout 50X done"})
        (testing "a LIKE metacharacter in the substring matches literally, not as a wildcard"
          (is (= [(:id pct)]
                 (mapv :id (unsafe-text-search/search rt {:substring "50%"})))))))))

(deftest blank-substring-fails-loudly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                            (unsafe-text-search/search rt {:substring "   "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                            (unsafe-text-search/search rt {:substring nil}))))))

(deftest malformed-options-fail-loudly-before-changing-search-mode
  (with-runtime
    (fn [rt _]
      (testing ":archived? must be a boolean, not a truthy string"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (unsafe-text-search/search rt {:substring "needle" :archived? "false"}))))
      (testing ":attr-key must be a non-blank string"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (unsafe-text-search/search rt {:substring "needle" :attr-key ""})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (unsafe-text-search/search rt {:substring "needle" :attr-key :owner})))))))

(deftest overflow-fails-loudly-and-caps-rather-than-truncates
  (with-runtime
    (fn [rt _]
      (dotimes [n 3]
        (weaver/add! rt {:title (str "widget number " n)}))
      (testing "matches within the limit return"
        (is (= 3 (count (unsafe-text-search/search rt {:substring "widget" :limit 3})))))
      (testing "more matches than the limit fail loudly naming --limit"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than the --limit"
                              (unsafe-text-search/search rt {:substring "widget" :limit 2}))))
      (testing "a non-positive limit fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                              (unsafe-text-search/search rt {:substring "widget" :limit 0})))))))

(deftest op-handler-threads-args-and-passes-the-archived-flag-through
  (with-runtime
    (fn [rt _]
      (let [strand (weaver/add! rt {:title "Session log" :attributes {"transcript" "coldvalue"}})]
        (weaver/archive-attributes! rt (:id strand) ["transcript"])
        (testing "absent --archived defaults to hot rows only"
          (is (empty? (unsafe-text-search/search-op {:op/runtime rt :op/args {:substring "coldvalue"}}))))
        (testing "present --archived reads cold rows"
          (is (= [(:id strand)]
                 (mapv :id (unsafe-text-search/search-op {:op/runtime rt :op/args {:substring "coldvalue" :archived true}})))))
        (testing "a non-boolean --archived fails loudly rather than coercing to a truthy read"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts are invalid"
                                (unsafe-text-search/search-op {:op/runtime rt :op/args {:substring "coldvalue" :archived "false"}}))))))))
