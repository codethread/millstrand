(ns millstrand.api.runtime.help-transform.alpha-test
  "Tests for the reload-cleared at-most-one default-help-transform slot
  (DELTA-Dtf-002.CC1/D1)."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.runtime.help-transform.alpha :as transform]
            [millstrand.spools.batteries :as batteries]
            [millstrand.spools.test-support :refer [with-runtime]]))

(defn- registration [owner]
  {:transform (fn [envelope] (str owner ":" (:schema-version envelope)))
   :owner owner})

(deftest register-and-introspect-round-trip
  (with-runtime
    (fn [rt _]
      (is (not (transform/default-help-transform-registered? rt)))
      (is (nil? (transform/default-help-transform rt)))
      (let [decl (registration 'my.spool/render)]
        (is (= decl (transform/register-default-help-transform! rt decl))
            "register returns the recorded registration")
        (is (transform/default-help-transform-registered? rt))
        (is (= 'my.spool/render (:owner (transform/default-help-transform rt)))
            "introspection reports the provenance")
        (is (fn? (:transform (transform/default-help-transform rt))))))))

(deftest register-builtin-resolves-batteries-transform
  (with-runtime
    (fn [rt _]
      (let [registered (transform/register-builtin! rt)]
        (is (= 'millstrand.spools.batteries (:owner registered)))
        (is (identical? batteries/default-help-transform
                        (:transform registered)))))))

(deftest register-rejects-invalid-shapes
  (with-runtime
    (fn [rt _]
      (testing "a missing transform fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (transform/register-default-help-transform! rt {:owner 'o}))))
      (testing "a non-callable transform fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (transform/register-default-help-transform!
                               rt {:transform 42 :owner 'o}))))
      (testing "unknown keys fail loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (transform/register-default-help-transform!
                               rt (assoc (registration 'o) :extra true)))))
      (testing "a non-map fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                              (transform/register-default-help-transform! rt "nope")))))))

(deftest register-is-loud-on-occupied-slot-naming-both
  (with-runtime
    (fn [rt _]
      (transform/register-default-help-transform! rt (registration :owner-a))
      (let [ex (try (transform/register-default-help-transform! rt (registration :owner-b))
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= {:existing-owner :owner-a :attempted-owner :owner-b}
               (ex-data ex))
            "an occupied slot names both registrants")))))

(deftest replace-requires-an-occupied-slot
  (with-runtime
    (fn [rt _]
      (testing "replace of an empty slot fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot replace"
                              (transform/replace-default-help-transform! rt (registration :owner-a)))))
      (transform/register-default-help-transform! rt (registration :owner-a))
      (testing "replace of an occupied slot re-seats the transform"
        (let [revised (registration :owner-b)]
          (is (= revised (transform/replace-default-help-transform! rt revised)))
          (is (= :owner-b (:owner (transform/default-help-transform rt)))))))))

(deftest unregister-is-owner-checked
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"cannot unregister"
           (transform/unregister-default-help-transform! rt :owner-a)))
      (let [decl (registration :owner-a)]
        (transform/register-default-help-transform! rt decl)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"another owner"
             (transform/unregister-default-help-transform! rt :owner-b)))
        (is (= decl
               (transform/unregister-default-help-transform! rt :owner-a)))
        (is (nil? (transform/default-help-transform rt)))))))

(deftest refresh-preserves-the-direct-slot
  (with-runtime
    (fn [rt _]
      (transform/register-default-help-transform! rt (registration 'my.spool/render))
      (is (transform/default-help-transform-registered? rt))
      (runtime/refresh! rt)
      (is (transform/default-help-transform-registered? rt)
          "refresh does not erase direct runtime-owned service state"))))
