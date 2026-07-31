(ns skein.core.weaver.core-registry-test
  "Owner-partition semantics shared by the six core weaver registries.

  Each core kind (ops, queries, patterns, hooks, events, bins) is backed by the same
  owner-registry kernel, so the deletion-completeness, same-layer collision,
  cross-layer override/restoration, and owner-isolation guarantees are proven
  once per kind against the backing store (TASK-Olr-002.DW2)."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.core.weaver.core-registry :as cr]))

(def ^:private kinds [:ops :queries :patterns :hooks :events :bins])

(defn- value
  "A representative entry value valid for `kind`'s declared entry spec. Named
  queries store a where-vector; every other core kind stores an entry map."
  [kind tag]
  (if (= kind :queries)
    [:= [:attr :tag] (name tag)]
    {:tag tag}))

(defn- effective [store] (cr/effective store))

(deftest owner-removal-is-complete-and-isolated
  (doseq [kind kinds]
    (testing kind
      (let [store (cr/backed-registry kind)]
        (cr/replace-owner! store :owner-a
                           {:layer :workspace :entries {:a (value kind :a)} :overrides #{}})
        (cr/replace-owner! store :owner-b
                           {:layer :workspace :entries {:b (value kind :b)} :overrides #{}})
        (is (= {:a (value kind :a) :b (value kind :b)} (effective store))
            "both owners contribute their keys")
        (cr/remove-owner! store :owner-a)
        (is (= {:b (value kind :b)} (effective store))
            "removing owner-a drops only its keys and leaves owner-b intact")))))

(deftest same-layer-collision-fails-before-publication
  (doseq [kind kinds]
    (testing kind
      (let [store (cr/backed-registry kind)]
        (cr/replace-owner! store :owner-a
                           {:layer :workspace :entries {:k (value kind :a)} :overrides #{}})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same layer"
                              (cr/replace-owner! store :owner-b
                                                 {:layer :workspace
                                                  :entries {:k (value kind :b)}
                                                  :overrides #{}})))
        (is (= {:k (value kind :a)} (effective store))
            "the refused publication leaves the prior effective view unchanged")))))

(deftest cross-layer-shadowing-requires-explicit-override
  (doseq [kind kinds]
    (testing kind
      (let [store (cr/backed-registry kind)]
        (cr/replace-owner! store :base
                           {:layer :spools :entries {:k (value kind :low)} :overrides #{}})
        (testing "a higher layer without override intent is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"override intent"
                                (cr/replace-owner! store :top
                                                   {:layer :workspace
                                                    :entries {:k (value kind :high)}
                                                    :overrides #{}})))
          (is (= {:k (value kind :low)} (effective store))))
        (testing "an authorized override wins and restores the base when removed"
          (cr/replace-owner! store :top
                             {:layer :workspace
                              :entries {:k (value kind :high)}
                              :overrides #{:k}})
          (is (= {:k (value kind :high)} (effective store)))
          (cr/remove-owner! store :top)
          (is (= {:k (value kind :low)} (effective store))
              "removing the overriding owner restores the shadowed base entry"))))))

(deftest direct-per-entry-writes-require-an-explicit-owner
  (doseq [kind kinds]
    (testing kind
      (let [store (cr/backed-registry kind)]
        (cr/put-entry! store :repl :k (value kind :a))
        (is (= {:k (value kind :a)} (effective store))
            "a direct write lands under its stated owner")
        (cr/put-entry! store :repl :k2 (value kind :b))
        (cr/remove-entry! store :repl :k)
        (is (= {:k2 (value kind :b)} (effective store))
            "removing one key leaves the owner's other keys")
        (cr/remove-entry! store :repl :k2)
        (is (= {} (effective store))
            "removing the last key empties the projection")
        (testing "a system-owned entry is shadowed loudly by a direct write"
          (cr/put-entry! store cr/system-owner :sys (value kind :low))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"override intent"
                                (cr/put-entry! store :repl :sys (value kind :high))))
          (is (= {:sys (value kind :low)} (effective store))))))))

(deftest explain-reports-effective-owner-provenance-and-shadowing
  (doseq [kind kinds]
    (testing kind
      (let [store (cr/backed-registry kind)]
        (testing "an unpopulated kind explains as an empty map"
          (is (= {} (cr/explain store))))
        (cr/replace-owner! store :base
                           {:layer :spools :entries {:k (value kind :low)} :overrides #{}})
        (cr/replace-owner! store :top
                           {:layer :workspace :entries {:k (value kind :high)} :overrides #{:k}})
        (let [{:keys [effective shadowed contenders]} (get (cr/explain store) :k)]
          (testing "the winning contender names the overriding owner and layer"
            (is (= {:owner :top :layer :workspace :value (value kind :high)
                    :override? true :effective? true}
                   effective)))
          (testing "the shadowed lower-layer owner remains visible as data"
            (is (= [{:owner :base :layer :spools :value (value kind :low)
                     :override? false :effective? false}]
                   shadowed)))
          (testing "contenders are ordered low-to-high"
            (is (= [:base :top] (mapv :owner contenders)))))))))

(deftest explain-applies-the-value-sanitizer-to-every-contender
  ;; The hooks/events introspection reads pass a sanitizer that strips the
  ;; resolved :fn-value from each contender's stored entry (SPEC-004.C66).
  (let [store (cr/backed-registry :hooks)
        entry (fn [tag] {:key :h :types #{:x} :fn 'ns/f :fn-value (fn [_] tag)
                         :order 0 :metadata {:tag tag}})]
    (cr/replace-owner! store :base
                       {:layer :spools :entries {:h (entry :low)} :overrides #{}})
    (cr/replace-owner! store :top
                       {:layer :workspace :entries {:h (entry :high)} :overrides #{:h}})
    (let [{:keys [contenders]} (get (cr/explain store #(dissoc % :fn-value)) :h)]
      (is (not-any? #(contains? (:value %) :fn-value) contenders)
          "no contender leaks a resolved function value")
      (is (every? #(= 'ns/f (get-in % [:value :fn])) contenders)
          "the handler symbol stays as data"))))

(deftest effective-returns-an-immutable-derived-snapshot
  (let [store (cr/backed-registry :ops)]
    (cr/replace-owner! store :owner-a
                       {:layer :workspace :entries {:a (value :ops :a)} :overrides #{}})
    (let [snapshot (effective store)]
      (cr/put-entry! store :owner-b :b (value :ops :b))
      (is (= {:a (value :ops :a)} snapshot))
      (is (= {:a (value :ops :a) :b (value :ops :b)} (effective store))))))
