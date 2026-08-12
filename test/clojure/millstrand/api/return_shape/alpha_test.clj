(ns millstrand.api.return-shape.alpha-test
  "Tests for finite return declarations and captured-value checks."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [millstrand.api.return-shape.alpha :as return-shape]))

(def scalar-cases
  [[:string "text"]
   [:integer 42]
   [:number 3.5]
   [:boolean false]
   [:null nil]
   [:json {:nested ["value" 1 true nil]}]])

(deftest scalar-and-nullable-shapes
  (doseq [[shape value] scalar-cases]
    (is (= shape (return-shape/validate! shape)))
    (is (= value (return-shape/check! shape value))))
  (doseq [scalar [:string :integer :number :boolean]]
    (is (nil? (return-shape/check! [:nullable scalar] nil))))
  (is (= "present" (return-shape/check! [:nullable :string] "present")))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! :integer 1.5)))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! :json {:bad-key 1 'symbol-key 2}))))

(deftest map-and-collection-shapes
  (let [shape {:type :map
               :required {:id :string}
               :optional {:score [:nullable :number]}
               :extra {:type :collection :items :boolean}}]
    (is (= {:id "a" :score nil :flags [true false]}
           (return-shape/check! shape {:id "a" :score nil :flags [true false]})))
    (is (= {:id "a"} (return-shape/check! shape {:id "a"})))
    (let [missing (is (thrown? clojure.lang.ExceptionInfo
                               (return-shape/check! shape {})))
          nested (is (thrown? clojure.lang.ExceptionInfo
                              (return-shape/check! shape {:id "a" :flags [true "no"]})))]
      (is (= [:id] (:path (ex-data missing))))
      (is (= :string (:expected (ex-data missing))))
      (is (= [:flags 1] (:path (ex-data nested))))
      (is (= :boolean (:expected (ex-data nested))))
      (is (= "no" (:actual (ex-data nested))))))
  (let [closed {:type :map :optional {:name :string}}
        mismatch (is (thrown? clojure.lang.ExceptionInfo
                              (return-shape/check! closed {:other 1})))]
    (is (= [:other] (:path (ex-data mismatch))))
    (is (= :undeclared-key (:reason (ex-data mismatch)))))
  (is (= '(1 2 3)
         (return-shape/check! {:type :collection :items :integer} '(1 2 3))))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/check! {:type :collection :items :integer} #{1 2}))))

(deftest malformed-declarations-fail-loudly
  (doseq [declaration [:keyword
                       'named-reference
                       string?
                       [:nullable :json]
                       [:nullable :null]
                       [:nullable [:nullable :string]]
                       [:or :string :integer]
                       {:type :map :required {:id :string} :optional {:id :string}}
                       {:type :map :required {"id" :string}}
                       {:type :map :coerce true}
                       {:type :map :default {}}
                       {:type :collection}
                       {:type :collection :items :string :recursive :self}
                       {:stream {:emits :string}}
                       {:subcommands {:run :string}}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (return-shape/validate! declaration))
        (pr-str declaration))))

(deftest routed-declarations-and-json-safe-explanation
  (let [declaration
        {:subcommands
         {"list" {:type :collection :items :string}
          "watch" {:stream {:emits {:type :map :required {:id :integer}}
                            :result [:nullable :boolean]}}}}
        explained (return-shape/explain declaration)]
    (is (= declaration (return-shape/validate! declaration)))
    (is (= {:subcommands
            {"list" {:type "collection" :items "string"}
             "watch" {:stream
                      {:emits {:type "map"
                               :required {"id" "integer"}
                               :optional {}}
                       :result ["nullable" "boolean"]}}}}
           explained))
    (is (string? (json/write-str explained)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"selected concrete return shape"
                          (return-shape/check! declaration {})))))

;; --- mirror-recursion over nested subcommand trees (DELTA-Lhc-001.CC4) ------

(def deep-returns
  {:subcommands
   {"admin" {:subcommands
             {"caps" {:subcommands
                      {"show" {:type :map :required {:id :string}}
                       "grant" {:stream {:emits :string :result :boolean}}}}
              "audit" {:type :collection :items :string}}}
    "list" {:type :collection :items :string}}})

(deftest nested-subcommand-trees-validate-explain-and-round-trip
  (is (= deep-returns (return-shape/validate! deep-returns)))
  (let [explained (return-shape/explain deep-returns)]
    (is (= {:type "map" :required {"id" "string"} :optional {}}
           (get-in explained [:subcommands "admin" :subcommands "caps"
                              :subcommands "show"])))
    (is (= {:stream {:emits "string" :result "boolean"}}
           (get-in explained [:subcommands "admin" :subcommands "caps"
                              :subcommands "grant"])))
    (is (string? (json/write-str explained)))))

(deftest nested-subcommand-tree-failures-carry-depth-paths
  (let [bad (is (thrown? clojure.lang.ExceptionInfo
                         (return-shape/validate!
                          {:subcommands
                           {"a" {:subcommands {"b" [:or :bad]}}}})))]
    (is (= [:subcommands "a" :subcommands "b"] (:path (ex-data bad)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (return-shape/validate!
                {:subcommands {"a" {:subcommands {:kw :string}}}})))
  (doseq [[declaration expected-path]
          [[{:subcommands {}} []]
           [{:subcommands {"a" {:subcommands {}}}} [:subcommands "a"]]]]
    (let [error (is (thrown? clojure.lang.ExceptionInfo
                             (return-shape/validate! declaration)))]
      (is (= :empty-subcommands (:reason (ex-data error))))
      (is (= expected-path (:path (ex-data error))))
      (is (nil? (:token (ex-data error))))
      (is (= [] (:available (ex-data error)))))))

(deftest select-case-walks-the-full-path
  (is (= {:type :map :required {:id :string}}
         (return-shape/select-case deep-returns ["admin" "caps" "show"])))
  (is (= {:type :collection :items :string}
         (return-shape/select-case deep-returns ["list"])))
  (is (= :string (return-shape/select-case :string [])))
  (let [short-path (is (thrown? clojure.lang.ExceptionInfo
                                (return-shape/select-case deep-returns ["admin"])))
        unknown (is (thrown? clojure.lang.ExceptionInfo
                             (return-shape/select-case deep-returns
                                                       ["admin" "caps" "bogus"])))
        long-path (is (thrown? clojure.lang.ExceptionInfo
                               (return-shape/select-case deep-returns
                                                         ["list" "extra"])))]
    (is (= :missing-return-subcommand (:reason (ex-data short-path))))
    (is (= ["admin"] (:path (ex-data short-path))))
    (is (= ["audit" "caps"] (:available (ex-data short-path))))
    (is (= :unknown-return-subcommand (:reason (ex-data unknown))))
    (is (= ["admin" "caps"] (:path (ex-data unknown))))
    (is (= "bogus" (:token (ex-data unknown))))
    (is (= ["grant" "show"] (:available (ex-data unknown))))
    (is (= :unrouted-return-path (:reason (ex-data long-path))))
    (is (= ["list"] (:path (ex-data long-path))))
    (is (= "extra" (:token (ex-data long-path))))))

(deftest select-case-validates-before-walking
  (doseq [declaration [{:subcommands {"x" nil}}
                       {:subcommands {"x" {:subcommands {}}}}]]
    (let [error (is (thrown? clojure.lang.ExceptionInfo
                             (return-shape/select-case declaration ["x"])))]
      (is (::return-shape/error (ex-data error)))
      (is (some? (:reason (ex-data error)))))))
