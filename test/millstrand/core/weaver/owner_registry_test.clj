(ns millstrand.core.weaver.owner-registry-test
  "Tests for the atomic owner-partitioned registry kernel."
  (:refer-clojure :exclude [partition])
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [millstrand.core.weaver.owner-registry :as owner-registry]))

(s/def ::entry (s/keys :req-un [::name ::payload]))
(s/def ::name string?)
(s/def ::payload int?)
(s/def ::other-entry (s/keys :req-un [::enabled?]))
(s/def ::enabled? boolean?)

(def ^:private kind
  {:id :test/items
   :entry-spec ::entry
   :binding-moment {:unit :dispatch :resolution :snapshot}
   :layer-policy owner-registry/layer-precedence})

(def ^:private other-kind
  {:id :test/other
   :entry-spec ::other-entry
   :binding-moment {:unit :lookup :resolution :live}
   :layer-policy owner-registry/layer-precedence})

(defn- entry [name payload]
  {:name name :payload payload})

(defn- partition
  ([layer entries]
   (partition layer entries #{}))
  ([layer entries overrides]
   {:layer layer :entries entries :overrides overrides}))

(defn- declared-registry []
  (doto (owner-registry/registry)
    (owner-registry/declare-kind! kind)
    (owner-registry/declare-kind! other-kind)))

(defn- effective-values [registry-atom kind-id]
  (owner-registry/effective-values (owner-registry/snapshot registry-atom)
                                   kind-id))

(deftest complete-owner-replacement-and-deletion
  (let [registry-atom (declared-registry)]
    (is (= :unchanged
           (:status (owner-registry/remove-owner!
                     registry-atom :test/items :workspace/missing))))
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/catalog
     (partition :workspace
                {:alpha (entry "alpha" 1)
                 :deleted (entry "deleted" 2)}))
    (let [result (owner-registry/replace-owner!
                  registry-atom :test/items :workspace/catalog
                  (partition :workspace {:alpha (entry "alpha" 3)}))]
      (is (= :replaced (:status result)))
      (is (s/valid? :millstrand.core.weaver.owner-registry/mutation-result result))
      (is (= {:alpha (entry "alpha" 3)}
             (effective-values registry-atom :test/items)))
      (is (= #{:alpha}
             (set (keys (get-in result [:current :entries]))))))
    (is (= :removed
           (:status (owner-registry/remove-owner!
                     registry-atom :test/items :workspace/catalog))))
    (is (empty? (effective-values registry-atom :test/items)))
    (is (= :unchanged
           (:status (owner-registry/remove-owner!
                     registry-atom :test/items :workspace/catalog))))))

(deftest stable-owner-is-independent-of-source-renames
  (let [registry-atom (declared-registry)]
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/stable-module
     (partition :workspace {:old-name (entry "old" 1)}))
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/stable-module
     (partition :workspace {:new-name (entry "new" 2)}))
    (is (= #{:workspace/stable-module}
           (set (keys (get-in (owner-registry/snapshot registry-atom)
                              [:partitions :test/items])))))
    (is (= {:new-name (entry "new" 2)}
           (effective-values registry-atom :test/items)))))

(deftest same-layer-collisions-refuse-before-publication
  (let [registry-atom (declared-registry)
        base (entry "base" 1)]
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/one
     (partition :workspace {:shared base}))
    (let [before (owner-registry/snapshot registry-atom)
          thrown (try
                   (owner-registry/replace-owner!
                    registry-atom :test/items :workspace/two
                    (partition :workspace {:shared (entry "other" 2)} #{:shared}))
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= {:error :same-layer-duplicate
              :kind :test/items
              :key :shared
              :layer :workspace
              :owners [:workspace/one :workspace/two]}
             (ex-data thrown)))
      (is (identical? before (owner-registry/snapshot registry-atom))))))

(deftest fixed-layer-precedence-requires-explicit-override
  (let [registry-atom (declared-registry)]
    (owner-registry/replace-owner!
     registry-atom :test/items :millstrand/defaults
     (partition :defaults {:shared (entry "default" 1)}))
    (let [before (owner-registry/snapshot registry-atom)
          thrown (try
                   (owner-registry/replace-owner!
                    registry-atom :test/items :spool/custom
                    (partition :spools {:shared (entry "spool" 2)}))
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :override-required (:error (ex-data thrown))))
      (is (= :millstrand/defaults (:shadowed-owner (ex-data thrown))))
      (is (identical? before (owner-registry/snapshot registry-atom))))
    (owner-registry/replace-owner!
     registry-atom :test/items :spool/custom
     (partition :spools {:shared (entry "spool" 2)} #{:shared}))
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/custom
     (partition :workspace {:shared (entry "workspace" 3)} #{:shared}))
    (owner-registry/replace-owner!
     registry-atom :test/items :repl/session
     (partition :direct {:shared (entry "direct" 4)} #{:shared}))
    (is (= {:shared (entry "direct" 4)}
           (effective-values registry-atom :test/items)))
    (is (= [:defaults :spools :workspace :direct]
           (mapv :layer
                 (get-in (owner-registry/snapshot registry-atom)
                         [:provenance :test/items :shared]))))))

(deftest shadowed-partitions-survive-every-override-transition
  (let [registry-atom (declared-registry)]
    (owner-registry/replace-owner!
     registry-atom :test/items :millstrand/defaults
     (partition :defaults {:shared (entry "default" 1)}))
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/custom
     (partition :workspace {:shared (entry "override" 2)} #{:shared}))
    (testing "refreshing the shadowed owner updates stored state only"
      (owner-registry/replace-owner!
       registry-atom :test/items :millstrand/defaults
       (partition :defaults {:shared (entry "refreshed" 3)}))
      (is (= (entry "override" 2)
             (get (effective-values registry-atom :test/items) :shared)))
      (is (= (entry "refreshed" 3)
             (get-in (owner-registry/snapshot registry-atom)
                     [:partitions :test/items :millstrand/defaults :entries :shared]))))
    (testing "removing the override restores the refreshed lower entry"
      (owner-registry/remove-owner! registry-atom :test/items :workspace/custom)
      (is (= (entry "refreshed" 3)
             (get (effective-values registry-atom :test/items) :shared))))
    (testing "removing the shadowed entry leaves the override effective"
      (owner-registry/replace-owner!
       registry-atom :test/items :workspace/custom
       (partition :workspace {:shared (entry "override" 4)} #{:shared}))
      (owner-registry/replace-owner!
       registry-atom :test/items :millstrand/defaults
       (partition :defaults {}))
      (is (= (entry "override" 4)
             (get (effective-values registry-atom :test/items) :shared)))
      (is (= [:workspace/custom]
             (mapv :owner
                   (get-in (owner-registry/snapshot registry-atom)
                           [:provenance :test/items :shared])))))
    (testing "an override must be restated on every complete replacement"
      (owner-registry/replace-owner!
       registry-atom :test/items :millstrand/defaults
       (partition :defaults {:shared (entry "returned" 5)}))
      (let [before (owner-registry/snapshot registry-atom)
            thrown (try
                     (owner-registry/replace-owner!
                      registry-atom :test/items :workspace/custom
                      (partition :workspace {:shared (entry "missing intent" 6)}))
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :override-required (:error (ex-data thrown))))
        (is (identical? before (owner-registry/snapshot registry-atom)))
        (is (= (entry "override" 4)
               (get (effective-values registry-atom :test/items) :shared)))))))

(deftest kinds-are-isolated-and-must-be-declared
  (let [registry-atom (declared-registry)]
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/module
     (partition :workspace {:same-key (entry "item" 1)}))
    (owner-registry/replace-owner!
     registry-atom :test/other :workspace/module
     (partition :workspace {:same-key {:enabled? true}}))
    (is (= {:same-key (entry "item" 1)}
           (effective-values registry-atom :test/items)))
    (is (= {:same-key {:enabled? true}}
           (effective-values registry-atom :test/other)))
    (let [before (owner-registry/snapshot registry-atom)
          thrown (try
                   (owner-registry/replace-owner!
                    registry-atom :test/missing :workspace/module
                    (partition :workspace {:x (entry "x" 1)}))
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= {:error :undeclared-kind :kind :test/missing}
             (ex-data thrown)))
      (is (identical? before (owner-registry/snapshot registry-atom))))))

(deftest projections-have-deterministic-ordering
  (let [candidate {:kinds {:test/other other-kind :test/items kind}
                   :partitions
                   {:test/items
                    {:workspace/z (partition :workspace
                                             {:z (entry "z" 3)
                                              :a (entry "a" 1)})
                     :spool/a (partition :spools
                                         {:middle (entry "middle" 2)})}}}
        normalized (owner-registry/normalize candidate)]
    (is (= [:test/items :test/other] (vec (keys (:kinds normalized)))))
    (is (= [:spool/a :workspace/z]
           (vec (keys (get-in normalized [:partitions :test/items])))))
    (is (= [:a :middle :z]
           (vec (keys (get-in normalized [:effective :test/items])))))
    (is (= [:spool/a :workspace/z] (vec (keys (:owners normalized)))))))

(deftest malformed-candidates-refuse-before-publication
  (let [registry-atom (declared-registry)
        malformed [(partition :workspace {:x {:name "missing payload"}})
                   {:layer :unknown :entries {}}
                   {:layer :workspace :entries {} :overrides #{:absent}}
                   {:layer :workspace :entries {} :unknown true}]]
    (doseq [candidate malformed]
      (let [before (owner-registry/snapshot registry-atom)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (owner-registry/replace-owner!
                      registry-atom :test/items :workspace/bad candidate)))
        (is (identical? before (owner-registry/snapshot registry-atom)))))))

(deftest kind-declarations-are-validated-before-publication
  (let [registry-atom (owner-registry/registry)
        before (owner-registry/snapshot registry-atom)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (owner-registry/declare-kind!
                  registry-atom
                  (assoc kind :entry-spec :missing/spec))))
    (is (identical? before (owner-registry/snapshot registry-atom)))))

(deftest readers-retain-old-snapshots-across-atomic-publication
  (let [registry-atom (declared-registry)
        _ (owner-registry/replace-owner!
           registry-atom :test/items :workspace/module
           (partition :workspace {:value (entry "old" 1)}))
        captured (promise)
        release (promise)
        reader (future
                 (let [read-snapshot (owner-registry/snapshot registry-atom)]
                   (deliver captured true)
                   @release
                   (get-in read-snapshot
                           [:effective :test/items :value :value])))]
    @captured
    (owner-registry/replace-owner!
     registry-atom :test/items :workspace/module
     (partition :workspace {:value (entry "new" 2)}))
    (deliver release true)
    (is (= (entry "old" 1) @reader))
    (is (= (entry "new" 2)
           (get (effective-values registry-atom :test/items) :value)))))

(def ^:private entry-map-gen
  (gen/map (gen/fmap keyword (gen/not-empty gen/string-alphanumeric))
           (gen/let [name gen/string-alphanumeric
                     payload (gen/large-integer* {:min Integer/MIN_VALUE
                                                  :max Integer/MAX_VALUE})]
             (entry name payload))
           {:max-elements 12}))

(defn- prefix-keys [prefix entries]
  (into {} (map (fn [[entry-key value]] [[prefix entry-key] value]) entries)))

(defspec normalization-is-deterministic-and-idempotent 100
  (prop/for-all [entries entry-map-gen]
                (let [candidate {:kinds {:test/items kind}
                                 :partitions {:test/items
                                              {:workspace/module
                                               (partition :workspace entries)}}}
                      first-pass (owner-registry/normalize candidate)
                      second-pass (owner-registry/normalize candidate)]
                  (and (= first-pass second-pass)
                       (= first-pass (owner-registry/normalize first-pass))))))

(defspec replacement-changes-only-the-selected-owner-partition 100
  (prop/for-all [selected-before entry-map-gen
                 selected-after entry-map-gen
                 unrelated entry-map-gen]
                (let [registry-atom (declared-registry)
                      _ (owner-registry/replace-owner!
                         registry-atom :test/items :workspace/selected
                         (partition :workspace (prefix-keys :selected selected-before)))
                      _ (owner-registry/replace-owner!
                         registry-atom :test/items :spool/unrelated
                         (partition :spools (prefix-keys :unrelated unrelated)))
                      before (owner-registry/snapshot registry-atom)
                      _ (owner-registry/replace-owner!
                         registry-atom :test/items :workspace/selected
                         (partition :workspace (prefix-keys :selected selected-after)))
                      after (owner-registry/snapshot registry-atom)]
                  (and (= (partition :spools (prefix-keys :unrelated unrelated))
                          (get-in after [:partitions :test/items :spool/unrelated]))
                       (= (get-in before [:partitions :test/other])
                          (get-in after [:partitions :test/other]))
                       (= (partition :workspace (prefix-keys :selected selected-after))
                          (get-in after [:partitions :test/items :workspace/selected]))))))
