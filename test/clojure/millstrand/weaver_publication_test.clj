(ns millstrand.weaver-publication-test
  "Serialized tests for published weaver runtime singleton semantics."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.source-file :as source-file]
            [millstrand.spools.test-support :as test-support]))

(deftest stop-unpublishes-published-runtime
  (let [world (test-support/temp-world)
        rt (weaver-runtime/start! nil {:world world})]
    (try
      (is (= rt @weaver-runtime/current-runtime))
      (weaver-runtime/stop! rt)
      (is (nil? @weaver-runtime/current-runtime))
      (finally
        (test-support/delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-init-failures-do-not-publish-metadata
  (let [world (test-support/temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (source-file/spit-forms! init ['(throw (ex-info "init failed" {}))])
      (is (thrown? Exception (weaver-runtime/start! nil {:world world})))
      (is (nil? @weaver-runtime/current-runtime))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (finally
        (some-> @weaver-runtime/current-runtime weaver-runtime/stop!)
        (metadata/delete! world)
        (test-support/delete-tree! (io/file (:config-dir world)))))))

(deftest unpublished-start-does-not-publish
  (let [world (test-support/temp-world)
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (is (nil? @weaver-runtime/current-runtime))
      (finally
        (weaver-runtime/stop! rt)
        (test-support/delete-tree! (io/file (:config-dir world) ".."))))))

(deftest publishing-second-runtime-still-fails-loudly
  (let [world-a (test-support/temp-world)
        world-b (test-support/temp-world)
        rt-a (weaver-runtime/start! nil {:world world-a})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"runtime is already active"
                            (weaver-runtime/start! nil {:world world-b})))
      (finally
        (weaver-runtime/stop! rt-a)
        (test-support/delete-tree! (io/file (:config-dir world-a) ".."))
        (test-support/delete-tree! (io/file (:config-dir world-b) ".."))))))
