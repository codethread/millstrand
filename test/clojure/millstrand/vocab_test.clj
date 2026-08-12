(ns millstrand.vocab-test
  "Tests for the millstrand.api.vocab.alpha vocabulary registry: the core seed lives
  in the `new-state` init-fn (present on a fresh runtime before any
  spool module activates),
  `declare!` is a single-owner hard edge with same-owner idempotency, and the
  reads are runtime-first, sorted, and narrowable."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.relations.alpha :as relations]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.vocab.alpha :as vocab]
            [millstrand.spools.test-support :refer [with-runtime]]))

(defn- attr-decl [name owner]
  {:kind :attr-namespace :name name :owner owner :doc (str name " attributes")})

(defn- find-declaration [runtime kind name]
  (some #(when (= [kind name] [(:kind %) (:name %)]) %)
        (vocab/declarations runtime)))

(deftest fresh-runtime-carries-core-seed
  (testing "the reflected edge catalog and core note/* are present before any spool activation"
    (with-runtime
      (fn [rt _]
        (let [edges (vocab/declarations rt {:kind :edge})]
          (is (= (set (map :relation relations/catalog))
                 (set (map :name edges)))
              "one owned :edge declaration per catalog entry")
          (is (every? #(= :millstrand/core (:owner %)) edges))
          (let [depends (find-declaration rt :edge "depends-on")]
            (is (= {:kind :edge :name "depends-on" :owner :millstrand/core
                    :family :operational
                    :direction "blocked --depends-on--> blocker"
                    :declared-acyclic? true}
                   (dissoc depends :doc))
                "edge declaration reflects catalog family/direction/acyclicity")))
        (let [note (find-declaration rt :attr-namespace "note")]
          (is (= 'millstrand.api.notes.alpha (:owner note)))
          (is (= :attr-namespace (:kind note)))
          ;; note/kind is declared as an open, guidance-only advisory key — a
          ;; :keys entry, never a registry-enforced enum of its value set.
          (is (contains? (set (:keys note)) "note/kind")))))))

(deftest declare-and-read-round-trip
  (with-runtime
    (fn [rt _]
      (let [decl (attr-decl "widget" :my.spool/init)]
        (is (= decl (vocab/declare! rt decl)) "declare! returns the recorded map")
        (is (= decl (find-declaration rt :attr-namespace "widget")))
        (is (nil? (find-declaration rt :attr-namespace "undeclared")))
        (is (contains? (set (vocab/declarations rt)) decl))
        (testing "declarations are sorted by [:kind :name]"
          (let [all (vocab/declarations rt)]
            (is (= all (sort-by (juxt :kind :name) all)))))
        (testing "narrowing by kind"
          (is (every? #(= :attr-namespace (:kind %))
                      (vocab/declarations rt {:kind :attr-namespace}))))))))

(deftest declare-rejects-invalid-shapes
  (with-runtime
    (fn [rt _]
      (testing "unknown kind"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                              (vocab/declare! rt {:kind :bogus :name "x" :owner :o :doc "d"}))))
      (testing "unknown keys"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (vocab/declare! rt (assoc (attr-decl "x" :o) :extra true)))))
      (testing "missing required keys"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (vocab/declare! rt {:kind :attr-namespace :name "x"}))))
      (testing "unknown read opt key"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (vocab/declarations rt {:bogus true}))))
      (testing "unknown read :kind fails loudly instead of matching nothing"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                              (vocab/declarations rt {:kind :bogus})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                              (vocab/declarations rt {:kind nil})))))))

(deftest declare-is-single-owner-hard-edge
  (with-runtime
    (fn [rt _]
      (vocab/declare! rt (attr-decl "widget" :owner-a))
      (testing "different owner throws with attribution"
        (let [ex (try (vocab/declare! rt (attr-decl "widget" :owner-b))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= {:name "widget" :kind :attr-namespace
                  :existing-owner :owner-a :declaring-owner :owner-b}
                 (ex-data ex)))))
      (testing "same owner is an idempotent replace (the reload invariant)"
        (let [replaced (assoc (attr-decl "widget" :owner-a) :doc "revised")]
          (is (= replaced (vocab/declare! rt replaced)))
          (is (= "revised" (:doc (find-declaration rt :attr-namespace "widget")))))))))

(deftest core-vocab-defaults-restore-after-owner-removal
  (with-runtime
    (fn [rt _]
      (let [handle (#'vocab/registry rt)
            kind :vocab
            owner :workspace/test
            note-key [:attr-namespace "note"]
            overridden (assoc (get (registry/effective handle kind) note-key)
                              :doc "Workspace note vocabulary")]
        (registry/replace-owner! handle kind owner
                                 {:layer :workspace
                                  :entries {note-key overridden}
                                  :overrides #{note-key}})
        (is (= "Workspace note vocabulary"
               (:doc (get (registry/effective handle kind) note-key))))
        (registry/remove-owner! handle kind owner)
        (is (= 'millstrand.api.notes.alpha
               (:owner (get (registry/effective handle kind) note-key)))
            "removing the workspace owner restores the core defaults entry")))))
