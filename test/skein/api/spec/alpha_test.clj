(ns skein.api.spec.alpha-test
  "Tests for the spec-over-wire documentation projection."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.api.spec.alpha :as spec-alpha]))

;; --- fixture specs -----------------------------------------------------------

(def invocations
  "Counts predicate invocations; the projection must never move it."
  (atom 0))

(defn tracked-pred?
  "Non-blank string predicate whose calls are counted for the invocation proof."
  [value]
  (swap! invocations inc)
  (and (string? value) (not (str/blank? value))))

(defn documented-pred?
  "First line of documentation.
  Second line that must not leak into the projection."
  [value]
  (string? value))

(defn- private-pred?
  [value]
  (int? value))

;; Deliberately doc-less predicates: their placeholder hint must fall back to
;; the printed symbol, keeping these assertions independent of clojure.core
;; docstring wording.
(defn- plain-str?
  [value]
  (string? value))

(defn- plain-int?
  [value]
  (int? value))

(defn- plain-map?
  [value]
  (map? value))

(s/def ::tracked tracked-pred?)
(s/def ::documented documented-pred?)
(s/def ::hidden private-pred?)
(s/def ::qualified-key ::documented)
(s/def ::title string?)
(s/def ::count pos-int?)
(s/def ::tags (s/coll-of plain-str? :kind vector? :min-count 1))
(s/def ::status #{"open" "closed"})
(s/def ::nested (s/keys :req-un [::title] :opt-un [::count]))
(s/def ::outer (s/keys :req [::qualified-key]
                       :req-un [::nested ::tracked]
                       :opt-un [::tags ::status ::hidden]))
(s/def ::either (s/or :name plain-str? :id plain-int?))
(s/def ::maybe (s/nilable plain-str?))
(s/def ::pair (s/tuple plain-str? plain-int?))
(s/def ::lookup (s/map-of plain-str? plain-int?))
(s/def ::guarded (s/and plain-map? #(contains? % :x)))
(s/def ::value string?)
(s/def ::children (s/coll-of ::tree :kind vector?))
(s/def ::tree (s/keys :req-un [::value] :opt-un [::children]))
(s/def ::odd-op (s/fspec :args (s/cat :x int?) :ret int?))

;; A dotted var name sends `ns-resolve` down its class-lookup branch, which
;; answers nil rather than the interned var: the projection must read that as
;; ordinary non-resolution, never as an error.
(declare dotted.pred)
(s/def ::dotted skein.api.spec.alpha-test/dotted.pred)

(defn- entry-for
  [entries json-key]
  (some #(when (= json-key (get % "key")) %) entries))

;; --- contract nodes ----------------------------------------------------------

(deftest keys-required-vs-optional-and-qualification
  (let [node (spec-alpha/contract ::outer)
        required (get node "required")
        optional (get node "optional")]
    (is (= "map" (get node "kind")))
    (is (= "skein.api.spec.alpha-test/outer" (get node "spec")))
    (let [qualified (entry-for required "skein.api.spec.alpha-test/qualified-key")
          unqualified (entry-for required "nested")]
      (is (true? (get qualified "qualified")))
      (is (false? (get unqualified "qualified")))
      (is (= "skein.api.spec.alpha-test/nested" (get unqualified "spec")))
      (is (= "map" (get-in unqualified ["contract" "kind"]))))
    (is (= #{"tags" "status" "hidden"} (set (map #(get % "key") optional))))))

(deftest nested-keys-collections-and-scalar-operators
  (let [node (spec-alpha/contract ::outer)
        tags (get (entry-for (get node "optional") "tags") "contract")
        status (get (entry-for (get node "optional") "status") "contract")]
    (is (= "coll" (get tags "kind")))
    (is (= "skein.api.spec.alpha-test/plain-str?" (get-in tags ["item" "form"])))
    (is (= {"kind" "clojure.core/vector?" "min-count" 1} (get tags "constraints")))
    (is (= "opaque" (get status "kind")))
    (is (= (pr-str #{"open" "closed"}) (get status "form")))))

(deftest and-or-nilable-map-of-tuple
  (let [and-node (spec-alpha/contract ::guarded)
        or-node (spec-alpha/contract ::either)
        nil-node (spec-alpha/contract ::maybe)
        map-node (spec-alpha/contract ::lookup)
        tuple-node (spec-alpha/contract ::pair)]
    (is (= "and" (get and-node "kind")))
    (is (= "skein.api.spec.alpha-test/plain-map?" (get-in and-node ["shape" "form"])))
    (is (= 1 (count (get and-node "constraints"))))
    (is (str/includes? (first (get and-node "constraints")) "contains?"))
    (is (= ["name" "id"] (mapv #(get % "tag") (get or-node "branches"))))
    (is (= "nilable" (get nil-node "kind")))
    (is (= "skein.api.spec.alpha-test/plain-str?" (get-in nil-node ["of" "form"])))
    (is (= "map-of" (get map-node "kind")))
    (is (= "skein.api.spec.alpha-test/plain-str?" (get-in map-node ["key" "form"])))
    (is (= "skein.api.spec.alpha-test/plain-int?" (get-in map-node ["value" "form"])))
    (is (= ["skein.api.spec.alpha-test/plain-str?" "skein.api.spec.alpha-test/plain-int?"]
           (mapv #(get % "form") (get tuple-node "items"))))))

(deftest recursive-reference-emits-ref-node
  (let [node (spec-alpha/contract ::tree)
        children (get (entry-for (get node "optional") "children") "contract")]
    (is (= "coll" (get children "kind")))
    (is (= {"kind" "ref" "spec" "skein.api.spec.alpha-test/tree"}
           (select-keys (get children "item") ["kind" "spec"])))))

(deftest unrecognized-operator-falls-back-verbatim
  (let [node (spec-alpha/contract ::odd-op)]
    (is (= "opaque" (get node "kind")))
    (is (str/includes? (get node "form") "fspec"))))

(deftest var-doc-enrichment-reads-metadata-only
  (let [node (spec-alpha/contract ::documented)
        hidden (spec-alpha/contract ::hidden)]
    (is (= "pred" (get node "kind")))
    (is (= "First line of documentation." (get node "doc")))
    (is (nil? (get node "private")))
    (is (true? (get hidden "private")))))

(deftest dotted-name-class-lookup-miss-is-plain-non-resolution
  (let [node (spec-alpha/contract ::dotted)]
    (is (= "pred" (get node "kind")))
    (is (str/includes? (get node "form") "dotted.pred"))
    (is (nil? (get node "doc")))))

(deftest no-predicate-is-ever-invoked
  (reset! invocations 0)
  (spec-alpha/contract ::outer)
  (spec-alpha/template ::outer)
  (spec-alpha/spec-forms ::outer)
  (spec-alpha/projection ::outer)
  (is (zero? @invocations)))

;; --- spec-forms graph --------------------------------------------------------

(deftest spec-forms-keeps-v1-shape-and-accretes-doc
  (let [graph (spec-alpha/spec-forms ::outer)
        root (first graph)
        by-spec (into {} (map (juxt #(get % "spec") identity)) graph)]
    (is (= "root" (get root "relation")))
    (is (= "skein.api.spec.alpha-test/outer" (get root "spec")))
    (is (every? #(= "keyword-reference" (get % "relation")) (rest graph)))
    ;; alias chains collapse in s/form: ::qualified-key records the predicate
    ;; symbol directly, so the doc lands on its own graph entry
    (is (= "First line of documentation."
           (get (get by-spec "skein.api.spec.alpha-test/qualified-key") "doc")))
    (is (true? (get (get by-spec "skein.api.spec.alpha-test/hidden") "private")))
    (is (contains? by-spec "skein.api.spec.alpha-test/tracked"))))

;; --- template ----------------------------------------------------------------

(deftest template-renders-copyable-skeleton
  (let [skeleton (spec-alpha/template ::outer)]
    (is (map? skeleton))
    (is (contains? skeleton "skein.api.spec.alpha-test/qualified-key"))
    (is (map? (get skeleton "nested")))
    (is (= ["<skein.api.spec.alpha-test/plain-str?>"] (get skeleton "tags")))
    (is (str/starts-with? (get-in skeleton ["nested" "title"]) "<Return true"))
    (is (str/starts-with? (get skeleton "hidden") "<optional "))
    (is (= (pr-str #{"open" "closed"})
           (subs (get skeleton "status")
                 (count "<optional ")
                 (dec (count (get skeleton "status"))))))))

(deftest template-structural-nodes
  (is (= "<skein.api.spec.alpha-test/plain-str?>" (spec-alpha/template ::either)))
  (is (= "<skein.api.spec.alpha-test/plain-str?>" (spec-alpha/template ::maybe)))
  (is (= ["<skein.api.spec.alpha-test/plain-str?>" "<skein.api.spec.alpha-test/plain-int?>"]
         (spec-alpha/template ::pair)))
  (is (= {"<skein.api.spec.alpha-test/plain-str?>" "<skein.api.spec.alpha-test/plain-int?>"}
         (spec-alpha/template ::lookup)))
  (is (= "<skein.api.spec.alpha-test/plain-map?>" (spec-alpha/template ::guarded)))
  (let [tree (spec-alpha/template ::tree)]
    (is (= "<recursive: skein.api.spec.alpha-test/tree>"
           (first (get tree "children"))))))

;; --- explain and problems ----------------------------------------------------

(deftest problems-detect-missing-keys-structurally
  (let [missing (spec-alpha/problems ::nested {})
        qualified-missing (spec-alpha/problems ::outer {:nested {:title "t"}
                                                        :tracked "x"})
        bad-value (spec-alpha/problems ::nested {:title 42})]
    (is (= "title" (get (first missing) "missing-key")))
    (is (= "skein.api.spec.alpha-test/qualified-key"
           (get (first qualified-missing) "missing-key")))
    (is (= ["title"] (get (first bad-value) "in")))
    (is (nil? (get (first bad-value) "missing-key")))
    (is (= [] (spec-alpha/problems ::nested {:title "ok"})))))

(deftest explain-text-is-plain-text
  (is (string? (spec-alpha/explain-text ::nested {})))
  (is (str/includes? (spec-alpha/explain-text ::nested {}) "title")))

;; --- bundle, JSON safety, and failure ----------------------------------------

(deftest projection-bundle-and-json-safety
  (let [bundle (spec-alpha/projection ::outer)]
    (is (= #{"spec" "spec-forms" "contract" "template"} (set (keys bundle))))
    (is (= "skein.api.spec.alpha-test/outer" (get bundle "spec")))
    (is (string? (json/write-str bundle)))
    (is (string? (json/write-str (spec-alpha/problems ::nested {}))))))

(deftest unregistered-spec-fails-loudly
  (let [failure (is (thrown? clojure.lang.ExceptionInfo
                             (spec-alpha/contract ::not-a-spec)))]
    (is (= :spec/unregistered (:reason (ex-data failure))))
    (is (contains? (ex-data failure) :skein.api.spec.alpha/error)))
  (is (thrown? clojure.lang.ExceptionInfo (spec-alpha/template ::not-a-spec)))
  (is (thrown? clojure.lang.ExceptionInfo (spec-alpha/spec-forms ::not-a-spec)))
  (is (thrown? clojure.lang.ExceptionInfo (spec-alpha/problems ::not-a-spec {}))))
