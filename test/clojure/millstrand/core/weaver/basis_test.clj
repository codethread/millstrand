(ns millstrand.core.weaver.basis-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.core.weaver.basis :as basis]))

(defn- workspace!
  [project extra]
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "millstrand-basis-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (io/file directory "deps.edn") (pr-str project))
    (when extra
      (spit (io/file directory "deps.local.edn") (pr-str extra)))
    directory))

(defn- resolved-basis
  [options]
  {:libs (merge (:deps (:project options))
                (:deps (:extra options))
                (get-in options [:args :extra-deps]))
   :classpath-roots []
   :argmap {:aliases (:aliases options)}})

(deftest generation-basis-selects-sources-aliases-and-reserved-coordinate
  (let [workspace (workspace!
                   {:deps {'example/shared {:local/root "shared"}}
                    :aliases {:millstrand/weaver {:extra-paths ["shared-src"]}}}
                   {:deps {'example/shared {:local/root "local"}}
                    :aliases {:millstrand/local {:extra-paths ["local-src"]}}})
        runtime-coordinate {:local/root "/millstrand"}
        captured (atom nil)
        generation
        (binding [basis/*create-basis*
                  (fn [options]
                    (reset! captured options)
                    (resolved-basis options))]
          (basis/create-generation-basis (.getPath workspace)
                                         runtime-coordinate))]
    (is (= [:millstrand/weaver :millstrand/local] (:aliases generation)))
    (is (= nil (:user @captured)))
    (is (= (.getCanonicalPath workspace) (:dir @captured)))
    (is (= {'io.millstrand/millstrand runtime-coordinate}
           (get-in @captured [:args :extra-deps])))
    (is (s/valid? :millstrand.core.specs/generation-basis generation))))

(deftest fingerprint-is-canonical-and-path-independent
  (let [left {:sources [{:kind :project :deps {:deps {'a/x {:mvn/version "1"}
                                                       'z/x {:mvn/version "2"}}}}]
              :aliases []
              :reserved-deps {'io.millstrand/millstrand {:local/root "/m"}}
              :basis {:libs {} :classpath-roots [] :argmap {}}}
        right (into (array-map) (reverse left))]
    (is (= (basis/basis-fingerprint left) (basis/basis-fingerprint right)))
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (basis/basis-fingerprint left)))))

(deftest dependency-files-fail-with-closed-diagnostics
  (testing "missing canonical file"
    (let [workspace (workspace! {} nil)]
      (.delete (io/file workspace "deps.edn"))
      (try
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"})
        (is false "expected missing deps.edn to fail")
        (catch clojure.lang.ExceptionInfo failure
          (is (= :deps-read (:stage (ex-data failure))))
          (is (s/valid? :millstrand.core.specs/dependency-diagnostic
                        (basis/dependency-diagnostic failure)))))))
  (testing "reserved runtime coordinate"
    (let [workspace (workspace!
                     {:deps {'io.millstrand/millstrand
                             {:local/root "elsewhere"}}}
                     nil)]
      (try
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"})
        (is false "expected reserved coordinate to fail")
        (catch clojure.lang.ExceptionInfo failure
          (is (= "reserved dependency io.millstrand/millstrand is supplied by Mill"
                 (:message (ex-data failure)))))))))

(deftest resolver-failure-preserves-cause
  (let [workspace (workspace! {:deps {'broken/lib {:mvn/version "nope"}}}
                              nil)]
    (try
      (binding [basis/*create-basis*
                (fn [_]
                  (throw (ex-info "artifact missing"
                                  {:lib 'broken/lib
                                   :coord {:mvn/version "nope"}})))]
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"}))
      (is false "expected resolution to fail")
      (catch clojure.lang.ExceptionInfo failure
        (is (= :deps-resolve (:stage (ex-data failure))))
        (is (= "artifact missing" (:cause (ex-data failure))))
        (is (= 'broken/lib (get-in (ex-data failure) [:coordinate :lib])))))))

(deftest canonical-encoder-rejects-tagged-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"outside the canonical EDN domain"
                        (basis/canonical-edn (java.util.UUID/randomUUID)))))
