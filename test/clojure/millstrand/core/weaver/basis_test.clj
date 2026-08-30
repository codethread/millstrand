(ns millstrand.core.weaver.basis-test
  "Tests for generation basis construction and semantic fingerprints."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.core.weaver.basis :as basis]))

(defn- inspect-basis!
  [workspace & arguments]
  (let [command (into ["clojure" "-Srepro" "-X:deps" "basis"
                       ":user" "nil" ":project" "\"deps.edn\""]
                      arguments)
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.directory workspace)
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (is (zero? exit) (str "basis inspection failed: " output))
    (edn/read-string output)))

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

(deftest dns-s9-01-and-02-compose-workspace-sources-without-user-deps
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
    (is (nil? (:user @captured)))
    (is (= (.getCanonicalPath workspace) (:dir @captured)))
    (is (= {'io.millstrand/millstrand runtime-coordinate}
           (get-in @captured [:args :extra-deps])))
    (is (s/valid? :millstrand.core.specs/generation-basis generation))))

(deftest dns-s9-03-absent-local-overlays-are-optional
  (let [workspace (workspace! {:paths ["shared-src"]} nil)
        captured (atom nil)
        generation
        (binding [basis/*create-basis*
                  (fn [options]
                    (reset! captured options)
                    (resolved-basis options))]
          (basis/create-generation-basis
           (.getPath workspace)
           {:local/root "/millstrand"}))]
    (is (= [:project] (mapv :kind (:sources generation))))
    (is (= [] (:aliases generation)))
    (is (nil? (:extra @captured)))
    (is (not (.exists (io/file workspace "deps.local.edn"))))))

(deftest dns-s9-05-documented-basis-inspection
  (let [shared-root (workspace! {:paths ["shared-src"]} nil)
        local-root (workspace! {:paths ["local-src"]} nil)
        workspace
        (workspace!
         {:deps {'example/shared {:local/root (.getCanonicalPath shared-root)}}
          :aliases {:millstrand/weaver {:jvm-opts ["-Dshared=true"]}}}
         {:deps {'example/local {:local/root (.getCanonicalPath local-root)}}
          :aliases {:millstrand/local {:jvm-opts ["-Dlocal=true"]}}})
        shared (inspect-basis! workspace
                               ":aliases" "[:millstrand/weaver]")
        overlaid (inspect-basis! workspace
                                 ":extra" "\"deps.local.edn\""
                                 ":aliases"
                                 "[:millstrand/weaver :millstrand/local]")]
    (is (contains? (:libs shared) 'example/shared))
    (is (not (contains? (:libs shared) 'example/local)))
    (is (every? #(contains? (:libs overlaid) %)
                ['example/shared 'example/local]))
    (is (= ["-Dshared=true"] (get-in shared [:argmap :jvm-opts])))
    (is (= ["-Dshared=true" "-Dlocal=true"]
           (get-in overlaid [:argmap :jvm-opts])))))

(deftest fingerprint-is-canonical-and-semantic
  (let [project {:deps {'a/x {:mvn/version "1"}}
                 :aliases {:millstrand/weaver {:jvm-opts ["-Dmode=weaver"]}}}
        create-basis (fn [root version]
                       {:libs {'a/x {:mvn/version version
                                     :deps/manifest :mvn
                                     :deps/root (str root "/cache")
                                     :paths [(str root "/cache/a-x.jar")]}
                               'io.millstrand/millstrand
                               {:local/root (str root "/millstrand")
                                :deps/root (str root "/millstrand")
                                :paths [(str root "/millstrand/src")]}}
                        :classpath-roots [(str root "/workspace/src")]
                        :argmap {:jvm-opts ["-Dmode=weaver"]
                                 :extra-paths [(str root "/workspace/dev")]}})
        fingerprint (fn [workspace root version]
                      (binding [basis/*create-basis*
                                (fn [_] (create-basis root version))]
                        (:fingerprint
                         (basis/create-generation-basis
                          (.getPath workspace)
                          {:local/root (str root "/millstrand")}))))
        left (workspace! project nil)
        relocated (workspace! project nil)
        coordinate-edit (workspace! (assoc-in project [:deps 'a/x :mvn/version]
                                              "2") nil)
        alias-edit (workspace! (assoc-in project
                                         [:aliases :millstrand/weaver :jvm-opts]
                                         ["-Dmode=changed"]) nil)
        source-edit (workspace! project {:deps {'extra/x {:mvn/version "1"}}})
        left-fingerprint (fingerprint left "/first" "1")]
    (is (= left-fingerprint (fingerprint relocated "/copied" "1")))
    (is (not= left-fingerprint (fingerprint coordinate-edit "/copied" "2")))
    (is (not= left-fingerprint (fingerprint alias-edit "/copied" "1")))
    (is (not= left-fingerprint (fingerprint source-edit "/copied" "1")))
    (is (re-matches #"sha256:[0-9a-f]{64}" left-fingerprint))))

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
