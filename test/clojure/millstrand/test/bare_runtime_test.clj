(ns millstrand.test.bare-runtime-test
  "Exercise the classpath fixture's real storage, activation, and owned cleanup."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(deftest bare-runtimes-isolate-state-and-leave-ambient-selection-alone
  (let [ambient (current/runtime-or-nil)
        contexts (atom [])
        closed (atom 0)
        result
        (t/run-with-bare-runtime
         {}
         (fn [{outer :runtime :as ctx}]
           (swap! contexts conj ctx)
           (is (= :sqlite-file (:storage ctx)))
           (is (.isFile (io/file (:db-path ctx))))
           (is (identical? ambient (current/runtime-or-nil)))
           (is (not (.exists (io/file (:config-dir ctx) "deps.edn"))))
           (let [strand (weaver/add! outer {:title "Outer"})
                 state (runtime/spool-state outer ::state #(atom :outer))]
             (runtime/spool-state outer ::resource
                                  #(hash-map :close-fn (fn [] (swap! closed inc))))
             (t/activate-module! outer ::fixture
                                 'millstrand.test.hyphen-source-fixture)
             (is (contains? (graph/queries outer) "loaded-query"))
             (current/with-runtime outer
               (t/run-with-bare-runtime
                {:storage :sqlite-memory}
                (fn [{inner :runtime :as inner-ctx}]
                  (swap! contexts conj inner-ctx)
                  (is (identical? outer (current/runtime)))
                  (is (nil? (:db-path inner-ctx)))
                  (is (nil? (weaver/show inner (:id strand))))
                  (is (not (contains? (graph/queries inner) "loaded-query")))
                  (is (empty? (:modules (runtime/status inner))))
                  (is (= :inner @(runtime/spool-state inner ::state #(atom :inner))))
                  (is (= :outer @state)))))
             (is (= "Outer" (:title (weaver/show outer (:id strand))))))
           :result))]
    (is (= :result result))
    (is (= 1 @closed))
    (is (= 2 (count (distinct (map :config-dir @contexts)))))
    (doseq [ctx @contexts]
      (is (not (.exists (io/file (:config-dir ctx)))))))
  (is (nil? (t/run-with-bare-runtime {} (constantly nil)))))

(deftest bare-runtime-preserves-body-error-and-still-closes-everything
  (let [captured (atom nil)
        closed (atom 0)
        body-error (ex-info "Body failed" {})
        close-error (ex-info "Resource close failed" {})
        error (try
                (t/run-with-bare-runtime
                 {:storage :sqlite-memory}
                 (fn [{:keys [runtime] :as ctx}]
                   (reset! captured ctx)
                   (runtime/spool-state
                    runtime ::resource
                    #(hash-map :close-fn (fn []
                                           (swap! closed inc)
                                           (throw close-error))))
                   (throw body-error)))
                (catch Throwable t t))]
    (is (identical? body-error error))
    (is (= 1 @closed) "A failed close is not retried")
    (is (= 1 (count (.getSuppressed ^Throwable error))))
    (is (not (.exists (io/file (:config-dir @captured)))))
    (testing "the memory store was closed despite the resource error"
      (is (thrown? Exception (weaver/list (:runtime @captured)))))))

(deftest bare-runtime-reports-cleanup-error-after-successful-body
  (let [captured (atom nil)
        closed (atom 0)
        error (try
                (t/run-with-bare-runtime
                 {}
                 (fn [{:keys [runtime] :as ctx}]
                   (reset! captured ctx)
                   (runtime/spool-state
                    runtime ::resource
                    #(hash-map :close-fn (fn []
                                           (swap! closed inc)
                                           (throw (ex-info "Close failed" {})))))
                   :success))
                (catch Throwable t t))]
    (is (= "Weaver teardown step failed" (ex-message error)))
    (is (= 1 @closed))
    (is (not (.exists (io/file (:config-dir @captured)))))))

(deftest bare-runtime-cleans-up-after-module-activation-fails
  (let [captured (atom nil)]
    (is (thrown? Exception
                 (t/run-with-bare-runtime
                  {}
                  (fn [{:keys [runtime] :as ctx}]
                    (reset! captured ctx)
                    (t/activate-module! runtime ::missing
                                        'millstrand.test.no-such-module)))))
    (is (not (.exists (io/file (:config-dir @captured)))))))

(deftest bare-runtime-rejects-world-options-and-invalid-storage
  (doseq [opts [{:storage :unknown}
                {:root "/not-a-fixture"}
                {:publish? true}
                {:deps-edn "{:deps {}}"}
                {:init-clj "(throw (Exception.))"}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"bare runtime options"
         (t/run-with-bare-runtime
          opts (fn [_] (throw (AssertionError. "Body must not run"))))))))
