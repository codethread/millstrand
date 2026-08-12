(ns millstrand.api.registry.alpha-test
  "External-style coverage for the blessed owner-registry primitive.

  Exercises the API the way a spool domain would: declare a kind, publish and
  delete complete owner partitions, read immutable snapshots, and explain the
  shadow/override state — without reaching into the storage kernel. Two
  independent registries live in two unpublished runtimes to prove owner
  partitions and versioned spool-state handles never cross-talk."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]))

(s/def ::name string?)
(s/def ::payload int?)
(s/def ::entry (s/keys :req-un [::name ::payload]))
(s/def ::enabled? boolean?)
(s/def ::other-entry (s/keys :req-un [::enabled?]))

;; A kind declared without an explicit layer policy exercises the default fill.
(def ^:private items-kind
  {:id :test/items
   :entry-spec ::entry
   :binding-moment {:unit :dispatch :resolution :snapshot}})

;; A second kind states its layer policy explicitly through the re-export.
(def ^:private other-kind
  {:id :test/other
   :entry-spec ::other-entry
   :binding-moment {:unit :lookup :resolution :live}
   :layer-policy registry/layer-precedence})

(defn- entry [name payload]
  {:name name :payload payload})

(defn- owner-partition
  ([layer entries]
   (owner-partition layer entries #{}))
  ([layer entries overrides]
   {:layer layer :entries entries :overrides overrides}))

(defn- declared-registry []
  (doto (registry/registry)
    (registry/declare-kind! items-kind)
    (registry/declare-kind! other-kind)))

(defn- test-runtime []
  {:spool-state (atom {}) :generation-id (str (gensym "gen"))})

(defn- runtime-registry
  "Fetch the versioned owner-registry handle owned by `rt`'s spool-state."
  [rt]
  (runtime/spool-state rt ::registry {:version 1} #(declared-registry)))

(deftest independent-registries-in-two-runtimes-have-no-cross-talk
  (let [rt-a (test-runtime)
        rt-b (test-runtime)
        reg-a (runtime-registry rt-a)
        reg-b (runtime-registry rt-b)]
    (testing "a versioned spool-state handle is stable within one runtime"
      (is (identical? reg-a (runtime-registry rt-a)))
      (is (not (identical? reg-a reg-b))))
    (registry/replace-owner! reg-a :test/items :workspace/catalog
                             (owner-partition :workspace {:alpha (entry "a" 1)}))
    (registry/replace-owner! reg-b :test/items :workspace/catalog
                             (owner-partition :workspace {:beta (entry "b" 2)}))
    (testing "each registry only sees its own owner partitions"
      (is (= {:alpha (entry "a" 1)} (registry/effective reg-a :test/items)))
      (is (= {:beta (entry "b" 2)} (registry/effective reg-b :test/items))))
    (testing "removing an owner in one registry leaves the other intact"
      (registry/remove-owner! reg-a :test/items :workspace/catalog)
      (is (empty? (registry/effective reg-a :test/items)))
      (is (= {:beta (entry "b" 2)} (registry/effective reg-b :test/items))))))

(deftest declared-kind-is-a-contribution-key-undeclared-is-refused
  (let [reg (registry/registry)
        declaration (registry/declare-kind! reg items-kind)]
    (testing "declaring a kind returns the published declaration result"
      (is (= :declared (:status declaration)))
      (is (= :test/items (:kind declaration)))
      (is (= registry/layer-precedence (:layer-policy (:declaration declaration))))
      (is (s/valid? :millstrand.api.registry.alpha/declaration-result declaration)))
    (testing "re-declaring the identical kind is unchanged"
      (is (= :unchanged (:status (registry/declare-kind! reg items-kind)))))
    (testing "a declared kind accepts published owner partitions"
      (let [result (registry/replace-owner!
                    reg :test/items :workspace/catalog
                    (owner-partition :workspace {:alpha (entry "a" 1)}))]
        (is (= :replaced (:status result)))
        (is (= {:alpha (entry "a" 1)} (registry/effective reg :test/items)))))
    (testing "an undeclared kind is refused loudly with the snapshot unchanged"
      (let [before (registry/snapshot reg)
            thrown (try
                     (registry/replace-owner!
                      reg :test/undeclared :workspace/catalog
                      (owner-partition :workspace {:x (entry "x" 1)}))
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= {:error :undeclared-kind :kind :test/undeclared} (ex-data thrown)))
        (is (identical? before (registry/snapshot reg)))))))

(deftest override-and-deletion-transitions
  (let [reg (declared-registry)]
    (registry/replace-owner! reg :test/items :millstrand/defaults
                             (owner-partition :defaults {:shared (entry "default" 1)}))
    (testing "a higher layer requires explicit override intent"
      (let [before (registry/snapshot reg)
            thrown (try
                     (registry/replace-owner!
                      reg :test/items :workspace/custom
                      (owner-partition :workspace {:shared (entry "override" 2)}))
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :override-required (:error (ex-data thrown))))
        (is (identical? before (registry/snapshot reg)))))
    (registry/replace-owner! reg :test/items :workspace/custom
                             (owner-partition :workspace {:shared (entry "override" 2)}
                                              #{:shared}))
    (testing "the override wins while the shadowed entry is retained"
      (is (= (entry "override" 2) (get (registry/effective reg :test/items) :shared))))
    (testing "refreshing the shadowed owner updates storage only"
      (registry/replace-owner! reg :test/items :millstrand/defaults
                               (owner-partition :defaults {:shared (entry "refreshed" 3)}))
      (is (= (entry "override" 2) (get (registry/effective reg :test/items) :shared))))
    (testing "removing the override restores the refreshed lower entry"
      (registry/remove-owner! reg :test/items :workspace/custom)
      (is (= (entry "refreshed" 3) (get (registry/effective reg :test/items) :shared))))
    (testing "deletion by omission drops keys the owner no longer declares"
      (registry/replace-owner! reg :test/items :millstrand/defaults
                               (owner-partition :defaults {}))
      (is (empty? (registry/effective reg :test/items))))))

(deftest explain-reports-effective-shadowed-and-override
  (let [reg (declared-registry)]
    (registry/replace-owner! reg :test/items :millstrand/defaults
                             (owner-partition :defaults {:shared (entry "default" 1)}))
    (registry/replace-owner! reg :test/items :workspace/custom
                             (owner-partition :workspace {:shared (entry "override" 2)}
                                              #{:shared}))
    (let [explanation (registry/explain reg :test/items)
          shared (get explanation :shared)]
      (is (s/valid? :millstrand.api.registry.alpha/explanation explanation))
      (testing "the effective contender is the overriding workspace owner"
        (is (= :workspace/custom (:owner (:effective shared))))
        (is (true? (:override? (:effective shared)))))
      (testing "the shadowed contender is the retained defaults owner"
        (is (= [:millstrand/defaults] (mapv :owner (:shadowed shared)))))
      (testing "contenders are ordered low-to-high by layer"
        (is (= [:defaults :workspace] (mapv :layer (:contenders shared))))))
    (testing "an undeclared or unpopulated kind explains to an empty map"
      (is (= {} (registry/explain reg :test/missing))))))

(deftest malformed-partitions-refuse-before-publication
  (let [reg (declared-registry)
        malformed [(owner-partition :workspace {:x {:name "missing payload"}})
                   {:layer :unknown :entries {}}
                   {:layer :workspace :entries {} :overrides #{:absent}}
                   {:layer :workspace :entries {} :unknown true}]]
    (doseq [candidate malformed]
      (let [before (registry/snapshot reg)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (registry/replace-owner! reg :test/items :workspace/bad candidate)))
        (is (identical? before (registry/snapshot reg)))))
    (testing "a malformed kind declaration also refuses before publication"
      (let [before (registry/snapshot reg)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (registry/declare-kind!
                      reg (assoc items-kind :entry-spec :missing/spec))))
        (is (identical? before (registry/snapshot reg)))))
    (testing "a foreign value is refused as a registry handle"
      (is (thrown? clojure.lang.ExceptionInfo
                   (registry/snapshot {:not :a-handle}))))))

(deftest snapshot-readers-retain-values-across-concurrent-publication
  (let [reg (declared-registry)
        _ (registry/replace-owner! reg :test/items :workspace/module
                                   (owner-partition :workspace {:value (entry "old" 1)}))
        captured (promise)
        release (promise)
        reader (future
                 (let [snap (registry/snapshot reg)]
                   (deliver captured true)
                   @release
                   (get-in snap [:effective :test/items :value :value])))]
    @captured
    (registry/replace-owner! reg :test/items :workspace/module
                             (owner-partition :workspace {:value (entry "new" 2)}))
    (deliver release true)
    (is (= (entry "old" 1) @reader))
    (is (= {:value (entry "new" 2)} (registry/effective reg :test/items)))))
