(ns millstrand.api.runtime.glossary.alpha-test
  "Tests for the reload-cleared glossary registry and the op-registration
  glossary-ref existence check (DELTA-Dtf-002.CC5/CC7, DELTA-Dtf-003.CC2)."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.runtime.glossary.alpha :as glossary]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :refer [with-runtime]]))

(defn- outcome [name owner]
  {:name name :definition (str name " definition") :owner owner})

(defn demo-handler
  "No-op handler symbol target for registration tests (never invoked here)."
  [ctx]
  ctx)

;; --- registry surface -------------------------------------------------------

(deftest register-and-introspect-round-trip
  (with-runtime
    (fn [rt _]
      (let [decl (outcome "lifecycle/timeout" 'my.spool/install)]
        (is (= decl (glossary/register-glossary-outcome! rt decl))
            "register returns the recorded outcome")
        (is (glossary/outcome-registered? rt "lifecycle/timeout"))
        (is (not (glossary/outcome-registered? rt "lifecycle/missing")))
        (is (= [decl] (glossary/glossary-outcomes rt))))
      (testing "introspection is sorted by name"
        (glossary/register-glossary-outcome! rt (outcome "lifecycle/abort" 'my.spool/install))
        (is (= ["lifecycle/abort" "lifecycle/timeout"]
               (mapv :name (glossary/glossary-outcomes rt))))))))

(deftest register-rejects-invalid-shapes
  (with-runtime
    (fn [rt _]
      (testing "unqualified name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (glossary/register-glossary-outcome! rt (outcome "timeout" 'o)))))
      (testing "blank definition fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (glossary/register-glossary-outcome!
                               rt {:name "a/b" :definition "  " :owner 'o}))))
      (testing "unknown keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (glossary/register-glossary-outcome!
                               rt (assoc (outcome "a/b" 'o) :extra true)))))
      (testing "non-map fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                              (glossary/register-glossary-outcome! rt "nope")))))))

(deftest register-is-loud-on-collision-naming-both
  (with-runtime
    (fn [rt _]
      (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" :owner-a))
      (let [ex (try (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" :owner-b))
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= {:outcome "lifecycle/timeout"
                :existing-owner :owner-a
                :attempted-owner :owner-b}
               (ex-data ex))
            "collision names both registrants")))))

(deftest replace-requires-existing-name
  (with-runtime
    (fn [rt _]
      (testing "replace of an absent name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot replace"
                              (glossary/replace-glossary-outcome!
                               rt (outcome "lifecycle/timeout" :owner-a)))))
      (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" :owner-a))
      (testing "replace of a present name re-seats the definition"
        (let [revised {:name "lifecycle/timeout" :definition "revised" :owner :owner-a}]
          (is (= revised (glossary/replace-glossary-outcome! rt revised)))
          (is (= "revised" (:definition (first (glossary/glossary-outcomes rt))))))))))

(deftest refresh-preserves-direct-glossary-entries
  (with-runtime
    (fn [rt _]
      (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" 'my.spool/install))
      (is (seq (glossary/glossary-outcomes rt)))
      (runtime/refresh! rt)
      (is (seq (glossary/glossary-outcomes rt))
          "refresh does not erase direct runtime-owned service state"))))

;; --- op-registration glossary-ref existence check ---------------------------

(defn- demo-spec [failure-modes]
  {:op "demo"
   :hook-class :read
   :deadline-class :standard
   :flags {:x {:type :string :doc "x"}}
   :annotations {:failure-modes failure-modes}})

(deftest register-op-requires-registered-failure-modes
  (with-runtime
    (fn [rt _]
      (testing "a failure-mode with no registered outcome fails registration loudly"
        (let [ex (try (weaver/register-op! rt 'demo
                                           {:arg-spec (demo-spec ["lifecycle/timeout"])}
                                           `demo-handler)
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= "lifecycle/timeout" (:failure-mode (ex-data ex))))))
      (testing "registration succeeds once the outcome is registered first (load order)"
        (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" 'my.spool/install))
        (is (= "demo" (:name (weaver/register-op! rt 'demo
                                                  {:arg-spec (demo-spec ["lifecycle/timeout"])}
                                                  `demo-handler))))))))

(deftest register-op-checks-subcommand-failure-modes
  (with-runtime
    (fn [rt _]
      (let [spec {:op "demo2"
                  :subcommands {"go" {:doc "go"
                                      :hook-class :read
                                      :deadline-class :standard
                                      :annotations {:failure-modes ["lifecycle/timeout"]}}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered glossary outcome"
                              (weaver/register-op! rt 'demo2 {:arg-spec spec} `demo-handler)))
        (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" 'my.spool/install))
        (is (= "demo2" (:name (weaver/register-op! rt 'demo2 {:arg-spec spec} `demo-handler))))))))

(deftest replace-op-also-checks-failure-modes
  (with-runtime
    (fn [rt _]
      (glossary/register-glossary-outcome! rt (outcome "lifecycle/timeout" 'my.spool/install))
      (weaver/register-op! rt 'demo3 {:arg-spec (demo-spec ["lifecycle/timeout"])} `demo-handler)
      (testing "replace-op! re-checks refs against the runtime glossary"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered glossary outcome"
                              (weaver/replace-op! rt 'demo3
                                                  {:arg-spec (demo-spec ["lifecycle/other"])}
                                                  `demo-handler)))))))
